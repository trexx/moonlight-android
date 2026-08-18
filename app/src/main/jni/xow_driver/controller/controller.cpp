/*
 * Copyright (C) 2019 Medusalix
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

#include "controller.h"
#include "../utils/log.h"
#include "../utils/jni.h"
#include "../utils/crypto.h"
#include "gip.h"

#include <cstdlib>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <algorithm>
#include <string>
#include <utility>
#include <linux/input.h>

// Configuration for the compatibility mode
#define COMPATIBILITY_ENV "XOW_COMPATIBILITY"
#define COMPATIBILITY_NAME "Microsoft X-Box 360 pad"
#define COMPATIBILITY_PID 0x028e
#define COMPATIBILITY_VERSION 0x0104

// Accessories use IDs greater than zero
#define DEVICE_ID_CONTROLLER 0
#define DEVICE_NAME "Xbox One Wireless Controller"

#define INPUT_STICK_FUZZ 255
#define INPUT_STICK_FLAT 4095
#define INPUT_TRIGGER_FUZZ 3
#define INPUT_TRIGGER_FLAT 63

// Motor levels are a percentage, not a raw byte: MS-GIPUSB v20240916 section 3.1.5.6.1
// (Direct Motor Command) specifies every level field as "Percentage, 0 - 100% (0x00 to 0x64),
// of PWM for motor". Anything above 0x64 is out of spec.
// Metadata element item widths. These are the sizes of the structures each element is an array
// of, per MS-GIPUSB 2.2.2.4 and xone's reading of it: a command descriptor is 23 bytes, a firmware
// version is two 16-bit halves, an audio format is a 2-byte pair and an interface is a GUID.
#define METADATA_COMMAND_LENGTH 23
#define METADATA_VERSION_LENGTH 4
#define METADATA_AUDIO_FORMAT_LENGTH 2
#define METADATA_GUID_LENGTH 16

#define RUMBLE_MAX_POWER 100
#define RUMBLE_DELAY std::chrono::milliseconds(10)

// Power level from the status byte's top two bits (MS-GIPUSB Table 30). Only "powering off or
// resetting" is acted on; 01 is unused, 10 is full power and 11 is reserved.
#define POWER_LEVEL_OFF 0x00

// Bytes per second of 48 kHz 16-bit stereo, which is what the ring holds whatever the transport
// packetises it into: 48000 Hz * 2 channels * 2 bytes.
#define AUDIO_BYTES_PER_SECOND (48000 * 2 * 2)

// Four packets, so roughly four cadences may queue before samples start being dropped. Enough to
// ride out a scheduling hiccup on either thread, short enough that a real backlog is discarded
// rather than turning into permanent lag.
#define AUDIO_BUFFER_MAX_BYTES (audioPacketBytes * 4)

// How far the pad's requested flow rate may sit from our packet size before it is worth saying so.
//
// MS-GIPUSB 3.2.5.1.5 has the device modulate this by "+/- one sample per channel per 1 ms" to
// absorb clock drift, which over an 8 ms packet is 8 samples per channel: 8 * 2 channels * 2 bytes.
// Anything inside that band is the protocol working, not a fault, and logging it would be noise.
#define AUDIO_FLOW_RATE_TOLERANCE (8 * 2 * 2)

/*
 * Half again the cadence, derived rather than fixed: at the wireless adapter's 1536-byte packet
 * that is the 12 ms this was originally written as, and a transport with a shorter packet gets a
 * proportionally shorter one. Past this the sender has missed its slot and the audio has a hole in
 * it, where anything shorter is the jitter of two threads meeting.
 *
 * Floored at 2 ms so a very short packet does not make the counter fire on ordinary scheduling.
 */
#define AUDIO_STARVE_TIMEOUT std::chrono::microseconds( \
    std::max<int64_t>(2000, (audioPacketBytes * 1500000LL) / AUDIO_BYTES_PER_SECOND))

// Scales a 16-bit magnitude, which is what moonlight-common-c and the Android input APIs both
// deal in, onto the protocol's 0 - 100 range.
#define RUMBLE_SCALE(magnitude) \
    static_cast<uint8_t>((static_cast<uint32_t>(magnitude) * RUMBLE_MAX_POWER) / UINT16_MAX)

Controller::Controller(
    SendPacket sendPacket
) : GipDevice(std::move(sendPacket)),
    stopRumbleThread(false), jvm(nullptr), jthis(nullptr) {}

Controller::~Controller()
{
    {
        std::lock_guard<std::mutex> lock(startMutex);

        stopStartThread = true;
    }

    startCondition.notify_one();

    if (startThread.joinable())
    {
        startThread.join();
    }

    stopRumbleThread = true;
    rumbleCondition.notify_one();

    if (rumbleThread.joinable())
    {
        rumbleThread.join();
    }

    // Stops and joins the audio thread if it is running. Must happen before the JNI teardown
    // below, since the thread touches nothing Java but does use this object's sendPacket.
    setAudioEnabled(false);

    /*
     * Only where the device is ours to switch off. A pad on the adapter is: when this driver stops,
     * nothing else is driving it, and leaving it powered would drain a battery.
     *
     * A cabled pad is not. Android's own driver takes it back the moment we let go, and telling it
     * to power off leaves it unresponsive to whoever picks it up next - including us on the next
     * connect, which showed up as a pad that would not answer a metadata request until it had been
     * physically unplugged and replugged.
     */
    if (powerOffOnTeardown && !setDeviceState(DEVICE_ID_CONTROLLER, STATE_OFF))
    {
        Log::error("Failed to turn off controller");
    }
    // Both destruction paths run on an already-attached thread - the read thread via
    // handleControllerDisconnect(), the app thread via Dongle::stop() - so this no longer attaches.
    // It never detached either, so attaching here used to leave the caller's thread attached.
    JNIEnv *env = getAttachedEnv(jvm);
    if (env == nullptr) {
        return;
    }

    if (jthis != nullptr) {
        env->DeleteGlobalRef(jthis);
        jthis = nullptr;
    }

    if (jclazz != nullptr) {
        env->DeleteGlobalRef(jclazz);
        jclazz = nullptr;
    }
}

void Controller::registerJavaContext(JavaVM *vm, JNIEnv *env, jobject thiz) {
    this->jvm = vm;
    this->jthis = thiz;

    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == nullptr) {
        Log::error("Failed to resolve controller class");

        return;
    }

    // Promote to a global reference: the local one dies when this JNI call returns, and the
    // method IDs below are only valid for as long as the class stays loaded.
    this->jclazz = static_cast<jclass>(env->NewGlobalRef(clazz));
    env->DeleteLocalRef(clazz);

    if (this->jclazz == nullptr) {
        Log::error("Failed to retain controller class");

        return;
    }

    this->updateInputMethod = env->GetMethodID(this->jclazz, "updateInput", "(ISSSSSS)V");
    this->updateBatteryMethod = env->GetMethodID(this->jclazz, "updateBattery", "(BBB)V");

    if (this->updateInputMethod == nullptr || this->updateBatteryMethod == nullptr) {
        Log::error("Failed to resolve controller callbacks");
    }
}

void Controller::deviceAnnounced(uint8_t id, const AnnounceData *announce)
{
    Log::info("Device %u announced, vendor %04x product %04x",
              id, announce->vendorId, announce->productId);

    /*
     * A sub-device, which for this pad means the 3.5 mm audio one: MS-GIPUSB 2.2.1.4 has these
     * enumerate only once the primary has completed the security handshake, and Table 1 shows them
     * carrying their own device ID, VID and PID.
     *
     * It must not go through initInput(). That is the primary device's setup - it ends by assigning
     * to rumbleThread, and assigning over a thread that is already running calls std::terminate.
     * A sub-device has no rumble, no input reports and no LED; what it has is its own metadata,
     * which is where its audio formats are stated.
     */
    if (id != DEVICE_ID_CONTROLLER)
    {
        if (!requestIdentify(id))
        {
            Log::error("Failed to request metadata from device %u", id);
        }

        return;
    }

    Log::debug(
        "Firmware version: %d.%d.%d.%d",
        announce->firmwareVersion.major,
        announce->firmwareVersion.minor,
        announce->firmwareVersion.build,
        announce->firmwareVersion.revision
    );
    Log::debug(
        "Hardware version: %d.%d",
        announce->hardwareVersion.major,
        announce->hardwareVersion.minor
    );

    // Logged because the security version decides which handshake a pad expects, and because the
    // specification requires all three to be 1.0 - anything else is a device worth knowing about.
    Log::debug(
        "Protocol versions: RF %d.%d, security %d.%d, GIP %d.%d",
        announce->rfVersion.major,
        announce->rfVersion.minor,
        announce->securityVersion.major,
        announce->securityVersion.minor,
        announce->gipVersion.major,
        announce->gipVersion.minor
    );

    /*
     * A device in Arrival re-sends Hello every 500 ms until the host answers (MS-GIPUSB 2.2.1), so
     * a second one is routine rather than an error - and on a cable nothing above this dedupes
     * them, where the dongle drops duplicate associations before they reach here.
     *
     * Answering again is right; rebuilding the input state is not. initInput() assigns to
     * rumbleThread, and assigning over a thread that is already running calls std::terminate.
     */
    if (inputInitialised.exchange(true))
    {
        Log::debug("Device re-announced, answering without reinitialising");

        if (!requestIdentify())
        {
            Log::error("Failed to answer a repeated announce");
        }

        return;
    }

    initInput();
}

void Controller::statusReceived(uint8_t id, const StatusData *status)
{
    const std::string levels[] = { "critically low", "low", "medium", "full" };
    const std::string charges[] = { "not charging", "charging", "charge error", "reserved" };

    uint8_t type = status->batteryType;
    uint8_t level = status->batteryLevel;

    // MS-GIPUSB Table 30 packs four fields into this byte: battery level in 1:0, battery type in
    // 3:2, charge state in 5:4 and power level in 7:6. xow's struct names only the low two and
    // lumps the top nibble into connectionInfo, which is why the charge state looked absent - the
    // protocol does report it, this driver was simply throwing it away.
    uint8_t charge = status->connectionInfo & 0x03;
    uint8_t power = (status->connectionInfo >> 2) & 0x03;

    // Nothing has moved since the last report
    if (type == batteryType && level == batteryLevel &&
        charge == batteryCharge && power == powerLevel)
    {
        return;
    }

    batteryType = type;
    batteryLevel = level;
    batteryCharge = charge;
    powerLevel = power;

    // Type 0 is "battery absent", not "charging" - BATT_TYPE_CHARGING is a misnomer inherited
    // from xow, whose name is left alone so gip.h stays refreshable (see UPSTREAM.md). Table 30
    // is explicit: 00 absent or bus powered, 01 standard/alkaline, 10 rechargeable. A pad with no
    // battery has no meaningful level, which is why this case used to return early - and why
    // battery never reached the host at all, since the state most worth reporting was dropped.
    if (type == BATT_TYPE_CHARGING)
    {
        Log::info("Battery: absent, running on external power");
    }
    else
    {
        Log::info("Battery: %s, %s", levels[level].c_str(), charges[charge].c_str());
    }

    // 00 means the pad is powering off or resetting, which is worth seeing in a log next to a
    // disconnect that would otherwise look unexplained.
    if (power == POWER_LEVEL_OFF)
    {
        Log::info("Controller is powering off or resetting");
    }

    notifyJavaBattery(type, level, charge);
}

void Controller::serialNumberReceived(const SerialData *serial)
{
    const std::string number(
        serial->serialNumber,
        sizeof(serial->serialNumber)
    );

    Log::info("Serial number: %s", number.c_str());
}

#define SET_BUTTON_STATUS(flag, ok) do{ \
    if(ok) {                            \
        buttonStatus |= flag;           \
    } else {                            \
        buttonStatus &= ~flag;          \
    }                                   \
} while(0);

void Controller::guideButtonPressed(const GuideButtonData *button)
{
    SET_BUTTON_STATUS(SPECIAL_BUTTON_FLAG, button->pressed);
    inputReceived(nullptr);
}

void Controller::updateButtonStatus(const GipDevice::InputData *input) {
    SET_BUTTON_STATUS(PLAY_FLAG, input->buttons.start);
    SET_BUTTON_STATUS(BACK_FLAG, input->buttons.select);
    SET_BUTTON_STATUS(A_FLAG, input->buttons.a);
    SET_BUTTON_STATUS(B_FLAG, input->buttons.b);
    SET_BUTTON_STATUS(X_FLAG, input->buttons.x);
    SET_BUTTON_STATUS(Y_FLAG, input->buttons.y);
    SET_BUTTON_STATUS(UP_FLAG, input->buttons.dpadUp);
    SET_BUTTON_STATUS(DOWN_FLAG, input->buttons.dpadDown);
    SET_BUTTON_STATUS(LEFT_FLAG, input->buttons.dpadLeft);
    SET_BUTTON_STATUS(RIGHT_FLAG, input->buttons.dpadRight);
    SET_BUTTON_STATUS(LB_FLAG, input->buttons.bumperLeft);
    SET_BUTTON_STATUS(RB_FLAG, input->buttons.bumperRight);
    SET_BUTTON_STATUS(LS_CLK_FLAG, input->buttons.stickLeft);
    SET_BUTTON_STATUS(RS_CLK_FLAG, input->buttons.stickRight);
    this->triggerLeft = input->triggerLeft;
    this->triggerRight = input->triggerRight;
    this->stickLeftX = input->stickLeftX;
    this->stickLeftY = input->stickLeftY;
    this->stickRightX = input->stickRightX;
    this->stickRightY = input->stickRightY;
}
#undef SET_BUTTON_STATUS

void Controller::inputReceived(const InputData *input)
{
    if(input) {
        updateButtonStatus(input);
    }

    if(jthis == nullptr || updateInputMethod == nullptr) {
        return;
    }

    // The read thread is attached for its whole life, so this is a thread-local lookup rather
    // than the attach/detach pair this used to do on every report. Nothing here may detach.
    JNIEnv *env = getAttachedEnv(jvm);
    if (env == nullptr) {
        return;
    }

    env->CallVoidMethod(jthis, updateInputMethod, buttonStatus, triggerLeft, triggerRight,
                        stickLeftX, stickLeftY, stickRightX, stickRightY);
}

void Controller::notifyJavaBattery(uint8_t type, uint8_t level, uint8_t charge)
{
    if (jthis == nullptr || updateBatteryMethod == nullptr) {
        return;
    }

    JNIEnv *env = getAttachedEnv(jvm);
    if (env == nullptr) {
        return;
    }

    // Raw GIP values: the mapping onto Moonlight's battery constants lives on the Java side,
    // where those constants are defined.
    env->CallVoidMethod(jthis, updateBatteryMethod, static_cast<jbyte>(type),
                        static_cast<jbyte>(level), static_cast<jbyte>(charge));
}

void Controller::initInput()
{
    // Ask for metadata and wait for the answer before starting the device. MS-GIPUSB 3.1.1 has the
    // device go Arrival -> Idle on the metadata request and Idle -> Active on Set Device State, and
    // 2.2.11 has an audio sub-device send its own Hello 500-1000 ms after "the primary device
    // initializes". Starting the device while it is still transmitting metadata gets that order
    // wrong, which is the one part of this handshake we can see is not what the spec describes.
    //
    // What comes back is also worth having on its own: the audio formats a pad declares are the
    // only statement of what it can be sent, and this is where they arrive.
    if (!requestIdentify())
    {
        Log::error("Failed to request metadata");

        // Nothing will answer, so do not wait for it
        startDevice();
    }
    else
    {
        startThread = std::thread(&Controller::waitForMetadata, this);
    }

    rumbleThread = std::thread(&Controller::processRumble, this);
}

/*
 * Logs what the controller reports it can do. Each offset points at a count byte followed by that
 * many fixed-size items, so an element is only read once its count has been bounds-checked against
 * the payload actually received.
 */
/*
 * Locates one metadata element. Each offset points at a count byte followed by that many
 * fixed-size items; a zero offset means the device has none. Returns the item block, or nullptr
 * if the element is absent or would run past what actually arrived.
 */
static const uint8_t *findInfoElement(const uint8_t *payload, size_t length, uint16_t offset,
                                      size_t itemLength, uint8_t &count)
{
    count = 0;

    if (offset == 0 || offset >= length)
    {
        return nullptr;
    }

    count = payload[offset];

    if (count == 0 || offset + 1 + static_cast<size_t>(count) * itemLength > length)
    {
        count = 0;

        return nullptr;
    }

    return payload + offset + 1;
}

/*
 * Logs an element as raw bytes, for the ones whose contents this driver does not interpret.
 */
static void logInfoElement(const char *name, const uint8_t *payload, size_t length,
                           uint16_t offset, size_t itemLength)
{
    uint8_t count;
    const uint8_t *items = findInfoElement(payload, length, offset, itemLength, count);

    if (items == nullptr)
    {
        return;
    }

    std::string hex;

    for (size_t i = 0; i < static_cast<size_t>(count) * itemLength; i++)
    {
        char byte[4];

        snprintf(byte, sizeof(byte), "%02x ", items[i]);
        hex += byte;
    }

    Log::info("Metadata %s: %u item(s): %s", name, count, hex.c_str());
}

/*
 * Logs the client command descriptors, which say which data-class messages the device actually
 * speaks. Worth decoding rather than dumping: this is the element that answers "can this pad do
 * audio at all", and a pad that lists only 0x20 and 0x09 has input and rumble and nothing else.
 */
static void logCommandDescriptors(const uint8_t *payload, size_t length, uint16_t offset)
{
    uint8_t count;
    const uint8_t *items = findInfoElement(payload, length, offset,
                                           METADATA_COMMAND_LENGTH, count);

    if (items == nullptr)
    {
        return;
    }

    std::string list;

    for (uint8_t i = 0; i < count; i++)
    {
        const uint8_t *item = items + static_cast<size_t>(i) * METADATA_COMMAND_LENGTH;
        char entry[32];

        // Layout per xone's gip_command_descriptor: marker, unknown, command, length
        snprintf(entry, sizeof(entry), "%02x(len %u) ", item[2], item[3]);
        list += entry;
    }

    Log::info("Metadata commands: %u item(s): %s", count, list.c_str());
}

void Controller::authCompleted(const uint8_t *sessionKey, size_t length)
{
    // The handshake is what gates sub-device enumeration (2.2.1.4), so this is the first moment
    // asking could succeed. The session key is for link encryption, which this driver does not do.
    probeSubDevices();
}

void Controller::probeSubDevices()
{
    /*
     * The expansion index is three bits (2.2.10), so 1 to 7 is every sub-device a primary can
     * have; zero is the primary itself. Seven small requests once per connect is nothing next to
     * being unable to find the audio device at all.
     *
     * The observed audio sub-device answers on 3 rather than the 1 that Table 1's example uses,
     * which is why this asks all of them rather than the one the specification happens to
     * illustrate.
     */
    for (uint8_t id = 1; id <= 7; id++)
    {
        if (!requestIdentify(id))
        {
            Log::error("Failed to ask device %u for metadata", id);

            return;
        }
    }
}

void Controller::identifyReceived(uint8_t id, const IdentifyData *identify,
                                  const uint8_t *payload, size_t length)
{
    Log::info("Metadata from device %u: %zu bytes", id, length);

    // Item widths are xone's, which are the sizes of the structs it maps each element onto:
    // gip_command_descriptor is 23 bytes and gip_firmware_version is 4. The 1 and 8 used here
    // before were guesses, and the firmware element overran into the ones logged after it.
    logCommandDescriptors(payload, length, identify->clientCommandsOffset);
    logInfoElement("firmware versions", payload, length,
                   identify->firmwareVersionsOffset, METADATA_VERSION_LENGTH);
    logInfoElement("audio formats", payload, length,
                   identify->audioFormatsOffset, METADATA_AUDIO_FORMAT_LENGTH);
    logInfoElement("capabilities out", payload, length, identify->capabilitiesOutOffset, 1);
    logInfoElement("capabilities in", payload, length, identify->capabilitiesInOffset, 1);
    logInfoElement("interfaces", payload, length, identify->interfacesOffset, METADATA_GUID_LENGTH);

    // Retained rather than only logged: whether a device has a usable audio format is the one
    // thing a caller needs before offering to send it audio, and metadata is the only place that
    // says so. An empty list means the device declared none.
    uint8_t formats;
    const uint8_t *items = findInfoElement(payload, length, identify->audioFormatsOffset,
                                           METADATA_AUDIO_FORMAT_LENGTH, formats);

    std::vector<uint8_t> parsed;

    if (items != nullptr)
    {
        parsed.assign(items,
                      items + static_cast<size_t>(formats) * METADATA_AUDIO_FORMAT_LENGTH);
    }

    if (id == DEVICE_ID_CONTROLLER)
    {
        audioFormats = parsed;
    }
    else if (!parsed.empty())
    {
        // The audio sub-device, which is where audio actually lives. Its formats are pairs of
        // capture and render, and 2.2.11 has the host take the first rather than choose.
        audioDeviceId = id;
        audioDeviceFormats = parsed;

        Log::info("Audio device %u offers %zu format pair(s), first %02x/%02x",
                  id, parsed.size() / METADATA_AUDIO_FORMAT_LENGTH, parsed[0], parsed[1]);
    }

    // The metadata exchange is done, so the device is in Idle and can be started. Wakes the
    // fallback waiter too, so it stops waiting rather than sitting out its full timeout.
    startDevice();
    startCondition.notify_one();
}

void Controller::startDevice()
{
    if (deviceStarted.exchange(true))
    {
        return;
    }

    if (!setDeviceState(DEVICE_ID_CONTROLLER, STATE_START))
    {
        Log::error("Failed to start controller");

        return;
    }

    LedModeData ledMode = {};

    // Dim the LED a little bit, like the original driver
    // Brightness ranges from 0x00 to 0x20
    ledMode.mode = LED_ON;
    ledMode.brightness = 0x14;

    if (!setLedMode(ledMode))
    {
        Log::error("Failed to set initial LED mode");

        return;
    }

    if (!requestSerialNumber())
    {
        Log::error("Failed to request serial number");
    }

    // The security exchange, once the device is Active. A capture of a Windows host has it here -
    // set state, LED, then the handshake, about 170 ms after the announce - and sending it any
    // earlier, while the pad is still in Arrival, got no answer at all. Its audio sub-device
    // appears seconds after this completes; this driver has never sent it and none has appeared.
    // Failing is not fatal, the driver has always run without it.
    if (!sendAuthHostHello())
    {
        Log::error("Failed to start the security exchange");
    }
}

void Controller::waitForMetadata()
{
    /*
     * Attached because startDevice() ends by opening the security exchange, and that reaches
     * GipCrypto - which is Java. Every other caller of startDevice() is the read thread, which
     * attaches itself, so this path was the one that could not: an unattached thread gets no
     * JNIEnv, every crypto call returns empty, and the handshake fails with "no random source".
     *
     * Latent until a pad failed to answer the metadata request, because nothing else reaches
     * startDevice() from here.
     */
    JNIEnv *env = nullptr;
    bool attached = jvm != nullptr && jvm->AttachCurrentThread(&env, nullptr) == JNI_OK;

    if (!attached)
    {
        Log::error("Failed to attach the start thread to the JVM");
    }

    std::unique_lock<std::mutex> lock(startMutex);

    // The same 500 ms the spec gives a device for assuming a lost state message (MS-GIPUSB 3.1.1).
    // Metadata has arrived within about 60 ms on every pad measured here, so this is a fallback
    // for one that never answers rather than a delay anything normally waits out.
    startCondition.wait_for(lock, std::chrono::milliseconds(500),
                            [this] { return stopStartThread || deviceStarted.load(); });

    if (stopStartThread)
    {
        if (attached)
        {
            jvm->DetachCurrentThread();
        }

        return;
    }

    if (!deviceStarted.load())
    {
        Log::info("No metadata after 500 ms; starting the controller anyway");
    }

    startDevice();

    if (attached)
    {
        jvm->DetachCurrentThread();
    }
}

void Controller::audioSamplesReceived(const AudioSamplesData *samples)
{
    // How many bytes of render data the pad wants in each message (MS-GIPUSB Table 69). It nudges
    // this up and down to absorb the difference between its clock and ours, and per 3.2.5.1.5 that
    // is "the mechanism GIP devices use to eliminate pops and clicks in audio".
    //
    // We send a fixed audioPacketBytes regardless, as xone does, so we are declining that
    // mechanism rather than implementing it - see AUDIO.md. Small movement is therefore expected
    // and says nothing; only a request that sits well outside the band the spec describes is worth
    // reporting, and it would mean the pad is asking for a rate we never give it.
    if (samples->flowRate == audioFlowRate)
    {
        return;
    }

    audioFlowRate = samples->flowRate;

    int delta = static_cast<int>(audioFlowRate) - static_cast<int>(audioPacketBytes);

    if (delta > AUDIO_FLOW_RATE_TOLERANCE || delta < -AUDIO_FLOW_RATE_TOLERANCE)
    {
        Log::debug("Audio flow rate %u is outside the expected band around %u",
                   audioFlowRate, (unsigned)audioPacketBytes);
    }
}

/*
 * Whether the pad declared an audio format we can render to.
 *
 * Metadata lists these as (capture, render) pairs, which is how xone reads the same element -
 * it hands data[0] and data[1] straight to its format request. A pad that declared none has no
 * audio endpoint on this device id, and asking it to take audio is answered with silence.
 */
void Controller::audioStats(uint32_t out[6]) const
{
    out[0] = audioPacketsSent.load(std::memory_order_relaxed);
    out[1] = audioBytesDropped.load(std::memory_order_relaxed);
    out[2] = audioStarved.load(std::memory_order_relaxed);
    out[3] = audioSendFailures.load(std::memory_order_relaxed);
    out[4] = audioFlowRate;
    // Only a transport can see these: an isochronous packet reports its own status, where a GIP
    // message on the main link simply succeeds or fails as a whole.
    out[5] = audioTransport != nullptr ? audioTransport->underruns() : 0;
}

bool Controller::supportsAudioOut() const
{
    /*
     * The audio sub-device's formats, not the pad's. A pad reports none of its own and is not
     * supposed to: audio is a separate GIP device with its own metadata, and it only announces
     * once the security handshake has completed (MS-GIPUSB 2.2.1.4). Asking device 0 was always
     * going to answer no, whatever was plugged into the jack.
     */
    if (audioDeviceId == 0)
    {
        return false;
    }

    // Render is the second of each capture/render pair
    for (size_t i = 1; i < audioDeviceFormats.size(); i += METADATA_AUDIO_FORMAT_LENGTH)
    {
        if (audioDeviceFormats[i] == AUDIO_FORMAT_48KHZ_STEREO)
        {
            return true;
        }
    }

    return false;
}

bool Controller::setAudioEnabled(bool enable)
{
    if (enable == audioEnabled)
    {
        return true;
    }

    if (enable)
    {
        /*
         * Refuse rather than half-succeed. Enabling routes the stream's audio away from the TV, so
         * a pad with nowhere to put it leaves the user with silence everywhere and no explanation.
         * The sub-device's metadata is what says, and it only exists once the security handshake
         * has completed (MS-GIPUSB 2.2.1.4).
         */
        if (audioDeviceId == 0 || audioDeviceFormats.size() < METADATA_AUDIO_FORMAT_LENGTH)
        {
            Log::info("No audio sub-device has announced; refusing audio");

            return false;
        }

        /*
         * Before the format is proposed, because on a transport that carries audio itself the
         * endpoints have to exist before the device is told to start streaming into them. Refusing
         * here is the same trade as above: better no audio with a reason than the stream's audio
         * moved off the TV into silence.
         */
        if (audioTransport != nullptr && !audioTransport->enableAudio())
        {
            Log::error("Audio transport would not start; refusing audio");

            return false;
        }

        /*
         * 2.2.11's sequence, and the order is the point: stop the device, propose a format, and
         * wait. The host does not choose a format - it takes the first pair the device advertised,
         * capture then render. Sending a format and streaming immediately, which is what this did
         * before, skips three steps the device is waiting on.
         */
        if (!setDeviceState(audioDeviceId, STATE_STOP))
        {
            Log::error("Failed to stop the audio device");

            return false;
        }

        auto in = static_cast<AudioFormat>(audioDeviceFormats[0]);
        auto out = static_cast<AudioFormat>(audioDeviceFormats[1]);

        if (!setAudioFormat(audioDeviceId, in, out))
        {
            Log::error("Failed to propose an audio format");

            return false;
        }

        {
            std::lock_guard<std::mutex> lock(audioMutex);

            audioBuffer.clear();
            audioBuffer.reserve(AUDIO_BUFFER_MAX_BYTES);
        }

        audioFlowRate = 0;
        audioPacketsSent = 0;
        audioBytesDropped = 0;
        audioSendFailures = 0;
        audioStarved = 0;
        audioState = AUDIO_AWAITING_FORMAT;
        audioEnabled = true;

        Log::info("Audio: proposed format %02x/%02x to device %u, awaiting its reply",
                  in, out, audioDeviceId);

        // Sending begins once the device has echoed the format and reported its volume
        return true;
    }

    audioEnabled = false;
    audioState = AUDIO_IDLE;
    stopAudioThread = true;
    audioCondition.notify_one();

    if (audioThread.joinable())
    {
        audioThread.join();
    }

    if (audioDeviceId != 0)
    {
        setDeviceState(audioDeviceId, STATE_STOP);
    }

    {
        std::lock_guard<std::mutex> lock(audioMutex);

        audioBuffer.clear();
        audioBuffer.shrink_to_fit();
    }

    /*
     * The session's totals, reported where they will actually be read. CLAUDE.md's note about the
     * end-of-stream summary applies: this is what reaches a bug report, whereas anything only on
     * screen is gone the moment the stream ends.
     *
     * Expect packets at 125 a second - one per 8 ms - so the count against the time audio was on
     * says whether the stream kept up. Dropped bytes mean the host outran the link and audio would
     * have drifted; starved counts mean the reverse, a ring emptied and a gap heard.
     */
    Log::info("Audio session: %u packets sent, %u bytes dropped, %u late, %u send failures, "
              "%u underruns, last flow rate %u",
              audioPacketsSent.load(std::memory_order_relaxed),
              audioBytesDropped.load(std::memory_order_relaxed),
              audioStarved.load(std::memory_order_relaxed),
              audioSendFailures.load(std::memory_order_relaxed),
              audioTransport != nullptr ? audioTransport->underruns() : 0,
              (unsigned)audioFlowRate);

    if (audioTransport != nullptr)
    {
        audioTransport->disableAudio();
    }

    Log::info("Audio disabled for controller");

    return true;
}

/*
 * Drives the rest of 2.2.11's sequence, which is answer-driven rather than timed.
 *
 * The device echoes the format it actually adopted; only then may it be started. After starting it
 * sends a volume message, and the specification is explicit that the host "might not start to play
 * audio data until a volume indication is received" - so that message, not the start, is what opens
 * the stream.
 */
void Controller::audioControlReceived(uint8_t id, const uint8_t *data, size_t length)
{
    if (id != audioDeviceId || length < 1)
    {
        return;
    }

    uint8_t subcommand = data[0];

    if (subcommand == AUDIO_CTRL_FORMAT && length >= 3)
    {
        if (audioState != AUDIO_AWAITING_FORMAT)
        {
            return;
        }

        // A device that cannot manage what was proposed answers with one it can, from its own
        // list. Nothing here renegotiates yet; say so rather than starting it on a mismatch.
        if (data[1] != audioDeviceFormats[0] || data[2] != audioDeviceFormats[1])
        {
            Log::error("Audio: device adopted %02x/%02x, not the %02x/%02x proposed",
                       data[1], data[2], audioDeviceFormats[0], audioDeviceFormats[1]);

            return;
        }

        Log::info("Audio: device confirmed format %02x/%02x, starting it", data[1], data[2]);

        audioState = AUDIO_AWAITING_VOLUME;

        if (!setDeviceState(audioDeviceId, STATE_START))
        {
            Log::error("Failed to start the audio device");
        }

        return;
    }

    if (subcommand == AUDIO_CTRL_VOLUME)
    {
        /*
         * Record what the device says before anything else, and do it whenever it arrives rather
         * than only during startup - 3.2.5.1.1 has the device re-send this whenever a field
         * changes, which is how a volume control on the device itself would reach us.
         */
        if (length >= sizeof(AudioVolumeData))
        {
            memcpy(&audioVolumeReported, data, sizeof(AudioVolumeData));
            audioVolumeKnown = true;

            Log::info(
                "Audio: device volume, speaker %u%% (%s), balance %u, mic %u%%, flags 0x%02x",
                audioVolumeReported.speaker & AUDIO_VOLUME_LEVEL,
                (audioVolumeReported.speaker & AUDIO_VOLUME_WRITABLE) ? "writable" : "read-only",
                audioVolumeReported.balance & AUDIO_VOLUME_LEVEL,
                audioVolumeReported.microphone & AUDIO_VOLUME_LEVEL,
                audioVolumeReported.flags
            );
        }
        else
        {
            // The plain Volume message, which the specification names but never gives a layout
            // for. Nothing can be read from it, so volume falls back to software scaling.
            Log::info("Audio: device reported volume in %zu bytes, not the extended form", length);
        }

        /*
         * Adopt the device's own level unless the user has chosen one. A pad here comes up at 80%,
         * so assuming 100 both misreported it in the menu and made "select 100%" audibly raise the
         * volume from a value that claimed to already be 100.
         *
         * Re-applying the user's choice on every volume message is deliberate: 3.2.5.1.1 has the
         * device re-send this whenever a field changes, so this is also what puts the level back
         * if something else moved it.
         */
        if (audioVolumeChosen)
        {
            setAudioVolume(audioVolumePercent);
        }
        else if (audioVolumeKnown)
        {
            audioVolumePercent = audioVolumeReported.speaker & AUDIO_VOLUME_LEVEL;
        }

        if (audioState != AUDIO_AWAITING_VOLUME)
        {
            return;
        }

        Log::info("Audio: device reported volume, streaming");

        audioState = AUDIO_STREAMING;
        stopAudioThread = false;
        audioThread = std::thread(&Controller::processAudio, this);
    }
}

void Controller::begin()
{
    /*
     * For a transport whose device never announces. A cabled pad has already been through Arrival
     * by the time we claim it, and MS-GIPUSB 2.2.1 has a device Hello only while in Arrival, so
     * waiting for one waits forever.
     *
     * Set Device State: RESET was tried here first, on the reasoning that 3.1.1 makes it a
     * protocol-level return to Arrival. On this hardware it takes the device off the USB bus
     * entirely - the read fails with NO_DEVICE and Android re-attaches it, permission prompt and
     * all, round and round. So nothing is reset and nothing is restarted: the device is simply
     * asked for its metadata where it stands, which is the least it can be sent.
     */
    if (inputInitialised.exchange(true))
    {
        return;
    }

    initInput();
}

void Controller::setAudioTransport(GipAudioTransport *transport)
{
    audioTransport = transport;
}

size_t Controller::encodeAudioFragment(const uint8_t *samples, size_t length, uint8_t *out)
{
    return encodeAudioMessage(audioDeviceId, samples, length, out);
}

void Controller::setAudioPacketBytes(size_t bytes)
{
    if (bytes == 0)
    {
        return;
    }

    audioPacketBytes = bytes;
}

bool Controller::setAudioVolume(uint8_t percent)
{
    if (percent > 100)
    {
        percent = 100;
    }

    audioVolumePercent = percent;
    audioVolumeChosen = true;

    // The device does the attenuation itself where it will let us ask, which is both free and what
    // 3.2.5.1.1 intends. Only the speaker field is touched: chat balance and microphone are not
    // ours to move, and this client has no microphone at all.
    if (audioVolumeKnown && (audioVolumeReported.speaker & AUDIO_VOLUME_WRITABLE))
    {
        audioSoftwareScale.store(256, std::memory_order_relaxed);

        AudioVolumeData volume = audioVolumeReported;

        volume.subcommand = AUDIO_CTRL_VOLUME;
        volume.speaker = AUDIO_VOLUME_WRITABLE | percent;

        return GipDevice::setAudioVolume(audioDeviceId, volume);
    }

    /*
     * Nothing to ask, so attenuate the samples ourselves. 8.8 fixed point, so 100% lands exactly on
     * unity and skips the scaling loop rather than multiplying every sample by 1.
     */
    audioSoftwareScale.store(static_cast<uint16_t>((percent * 256) / 100),
                             std::memory_order_relaxed);

    if (audioVolumeKnown)
    {
        Log::info("Audio: device volume is read-only, scaling in software to %u%%", percent);
    }

    return true;
}

void Controller::queueAudio(const int16_t *samples, size_t count)
{
    if (!audioEnabled)
    {
        return;
    }

    const uint8_t *bytes = reinterpret_cast<const uint8_t *>(samples);
    size_t length = count * sizeof(int16_t);

    {
        std::lock_guard<std::mutex> lock(audioMutex);

        // Drop the oldest rather than growing without bound. A backlog here is heard as audio
        // drifting permanently behind the video, which is worse than losing a few milliseconds -
        // the same trade AudioRenderer's own documentation describes for the AudioTrack path.
        if (audioBuffer.size() + length > AUDIO_BUFFER_MAX_BYTES)
        {
            size_t excess = audioBuffer.size() + length - AUDIO_BUFFER_MAX_BYTES;

            audioBytesDropped.fetch_add(static_cast<uint32_t>(excess), std::memory_order_relaxed);

            if (excess >= audioBuffer.size())
            {
                audioBuffer.clear();
            }
            else
            {
                audioBuffer.erase(audioBuffer.begin(), audioBuffer.begin() + excess);
            }
        }

        audioBuffer.insert(audioBuffer.end(), bytes, bytes + length);
    }

    audioCondition.notify_one();
}

/*
 * Drains the ring one packet at a time. There is no timer: the host delivers audio in real time,
 * so waiting for a packet's worth of samples paces this at the protocol's 8 ms on its own.
 */
void Controller::processAudio()
{
    // Sized once here rather than per packet: the loop below must not allocate, and the size is
    // fixed for the life of the sender because a transport does not change mid-session.
    std::vector<uint8_t> packet(audioPacketBytes);

    while (!stopAudioThread)
    {
        {
            std::unique_lock<std::mutex> lock(audioMutex);

            auto ready = [this] {
                return stopAudioThread || audioBuffer.size() >= audioPacketBytes;
            };

            /*
             * Waiting is normal and says nothing: the ring is how this paces itself, so the sender
             * waits on every packet by design, and at a perfectly matched rate it finds the ring
             * empty every time. Waiting *longer than the cadence* is the thing worth counting -
             * that is the host failing to supply in time, which is what a gap sounds like.
             *
             * An earlier version counted an empty ring instead and reported 20% starvation through
             * audio that was audibly fine.
             */
            if (!audioCondition.wait_for(lock, AUDIO_STARVE_TIMEOUT, ready))
            {
                audioStarved.fetch_add(1, std::memory_order_relaxed);

                audioCondition.wait(lock, ready);
            }

            if (stopAudioThread)
            {
                return;
            }

            std::copy(audioBuffer.begin(), audioBuffer.begin() + audioPacketBytes, packet.begin());
            audioBuffer.erase(audioBuffer.begin(), audioBuffer.begin() + audioPacketBytes);
        }

        /*
         * Only reached on a device that refuses host volume requests; setAudioVolume() leaves this
         * at unity otherwise, so the usual cost is one relaxed load and a comparison per 8 ms
         * packet rather than anything per sample.
         */
        uint16_t scale = audioSoftwareScale.load(std::memory_order_relaxed);

        if (scale != 256)
        {
            int16_t *samples = reinterpret_cast<int16_t *>(packet.data());

            for (size_t i = 0; i < packet.size() / sizeof(int16_t); i++)
            {
                samples[i] = static_cast<int16_t>((samples[i] * scale) >> 8);
            }
        }

        /*
         * Outside the lock either way: the adapter's send is a blocking USB transfer and the
         * decode thread must never wait behind it, and a transport's submit can block on a free
         * buffer for the same reason.
         */
        bool sent = audioTransport != nullptr
                        ? audioTransport->sendAudio(packet.data(), packet.size())
                        : sendAudioSamples(audioDeviceId, packet.data(), packet.size());

        if (!sent)
        {
            audioSendFailures.fetch_add(1, std::memory_order_relaxed);

            Log::error("Failed to send audio samples");

            return;
        }

        audioPacketsSent.fetch_add(1, std::memory_order_relaxed);
    }
}

void Controller::processRumble()
{
    RumbleData rumble = {};
    std::unique_lock<std::mutex> lock(rumbleMutex);

    while (!stopRumbleThread)
    {
        rumbleCondition.wait(lock);

        while (rumbleBuffer.get(rumble))
        {
            performRumble(rumble);

            // Delay rumble to work around firmware bug
            std::this_thread::sleep_for(RUMBLE_DELAY);
        }
    }
}

void Controller::sendRumble() {
    RumbleData rumble = {};

    rumble.setLeft = true;
    rumble.setRight = true;
    rumble.setLeftTrigger = true;
    rumble.setRightTrigger = true;
    rumble.left = RUMBLE_SCALE(this->rumbleLeft);
    rumble.right = RUMBLE_SCALE(this->rumbleRight);
    rumble.leftTrigger = RUMBLE_SCALE(this->rumbleTriggerLeft);
    rumble.rightTrigger = RUMBLE_SCALE(this->rumbleTriggerRight);
    rumble.duration = 10;
    rumble.repeat = 1;

    rumbleBuffer.put(rumble);
    rumbleCondition.notify_one();
}

void Controller::inputRumble(short lowFreqMotor, short highFreqMotor) {
    this->rumbleLeft = lowFreqMotor;
    this->rumbleRight = highFreqMotor;

    sendRumble();
}

void Controller::inputRumbleTrigger(short leftTrigger, short rightTrigger) {
    this->rumbleTriggerLeft = leftTrigger;
    this->rumbleTriggerRight = rightTrigger;

    sendRumble();
}
