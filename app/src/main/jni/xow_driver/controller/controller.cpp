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

// One packet of 48 kHz 16-bit stereo at the protocol's 8 ms cadence:
// 48000 Hz * 2 channels * 2 bytes * 8 ms / 1000 = 1536. See gip_make_audio_config() in xone.
#define AUDIO_PACKET_BYTES 1536

// Four packets, so roughly 32 ms may queue before samples start being dropped. Enough to ride out
// a scheduling hiccup on either thread, short enough that a real backlog is discarded rather than
// turning into permanent lag.
#define AUDIO_BUFFER_MAX_BYTES (AUDIO_PACKET_BYTES * 4)

// How far the pad's requested flow rate may sit from our packet size before it is worth saying so.
//
// MS-GIPUSB 3.2.5.1.5 has the device modulate this by "+/- one sample per channel per 1 ms" to
// absorb clock drift, which over an 8 ms packet is 8 samples per channel: 8 * 2 channels * 2 bytes.
// Anything inside that band is the protocol working, not a fault, and logging it would be noise.
#define AUDIO_FLOW_RATE_TOLERANCE (8 * 2 * 2)

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

    if (!setDeviceState(DEVICE_ID_CONTROLLER, STATE_OFF))
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
    Log::info("Device announced, product id: %04x", announce->productId);
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

    initInput(announce);
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

void Controller::initInput(const AnnounceData *announce)
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

void Controller::identifyReceived(const IdentifyData *identify,
                                  const uint8_t *payload, size_t length)
{
    Log::info("Metadata received: %zu bytes", length);

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

    // Retained rather than only logged: whether a pad has a usable audio format is the one thing
    // a caller needs to know before offering to send it audio, and metadata is the only place
    // that says so. An empty list means the device declared none.
    uint8_t formats;
    const uint8_t *items = findInfoElement(payload, length, identify->audioFormatsOffset,
                                           METADATA_AUDIO_FORMAT_LENGTH, formats);

    audioFormats.clear();

    if (items != nullptr)
    {
        audioFormats.assign(items,
                            items + static_cast<size_t>(formats) * METADATA_AUDIO_FORMAT_LENGTH);
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
    std::unique_lock<std::mutex> lock(startMutex);

    // The same 500 ms the spec gives a device for assuming a lost state message (MS-GIPUSB 3.1.1).
    // Metadata has arrived within about 60 ms on every pad measured here, so this is a fallback
    // for one that never answers rather than a delay anything normally waits out.
    startCondition.wait_for(lock, std::chrono::milliseconds(500),
                            [this] { return stopStartThread || deviceStarted.load(); });

    if (stopStartThread)
    {
        return;
    }

    if (!deviceStarted.load())
    {
        Log::info("No metadata after 500 ms; starting the controller anyway");
    }

    startDevice();
}

void Controller::audioSamplesReceived(const AudioSamplesData *samples)
{
    // How many bytes of render data the pad wants in each message (MS-GIPUSB Table 69). It nudges
    // this up and down to absorb the difference between its clock and ours, and per 3.2.5.1.5 that
    // is "the mechanism GIP devices use to eliminate pops and clicks in audio".
    //
    // We send a fixed AUDIO_PACKET_BYTES regardless, as xone does, so we are declining that
    // mechanism rather than implementing it - see AUDIO.md. Small movement is therefore expected
    // and says nothing; only a request that sits well outside the band the spec describes is worth
    // reporting, and it would mean the pad is asking for a rate we never give it.
    if (samples->flowRate == audioFlowRate)
    {
        return;
    }

    audioFlowRate = samples->flowRate;

    int delta = static_cast<int>(audioFlowRate) - AUDIO_PACKET_BYTES;

    if (delta > AUDIO_FLOW_RATE_TOLERANCE || delta < -AUDIO_FLOW_RATE_TOLERANCE)
    {
        Log::debug("Audio flow rate %u is outside the expected band around %u",
                   audioFlowRate, AUDIO_PACKET_BYTES);
    }
}

/*
 * Whether the pad declared an audio format we can render to.
 *
 * Metadata lists these as (capture, render) pairs, which is how xone reads the same element -
 * it hands data[0] and data[1] straight to its format request. A pad that declared none has no
 * audio endpoint on this device id, and asking it to take audio is answered with silence.
 */
bool Controller::supportsAudioOut() const
{
    for (size_t i = 1; i < audioFormats.size(); i += METADATA_AUDIO_FORMAT_LENGTH)
    {
        if (audioFormats[i] == AUDIO_FORMAT_48KHZ_STEREO)
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
        // Refuse rather than half-succeed. Enabling routes stream audio away from the TV, so a
        // pad that cannot play it leaves the user with silence everywhere and no explanation.
        // The metadata is the only thing that says, and we already ask for it.
        if (!supportsAudioOut())
        {
            Log::info("Controller declared no 48 kHz stereo output; refusing audio");

            return false;
        }

        // 48 kHz stereo both ways. The capture direction is negotiated because the protocol pairs
        // them in one message and the pad needs a valid value, not because anything reads a
        // microphone here.
        if (!setAudioFormat(AUDIO_FORMAT_48KHZ_STEREO, AUDIO_FORMAT_48KHZ_STEREO))
        {
            Log::error("Failed to set audio format");

            return false;
        }

        {
            std::lock_guard<std::mutex> lock(audioMutex);

            audioBuffer.clear();
            audioBuffer.reserve(AUDIO_BUFFER_MAX_BYTES);
        }

        audioFlowRate = 0;
        stopAudioThread = false;
        audioEnabled = true;
        audioThread = std::thread(&Controller::processAudio, this);

        Log::info("Audio enabled for controller");

        return true;
    }

    audioEnabled = false;
    stopAudioThread = true;
    audioCondition.notify_one();

    if (audioThread.joinable())
    {
        audioThread.join();
    }

    {
        std::lock_guard<std::mutex> lock(audioMutex);

        audioBuffer.clear();
        audioBuffer.shrink_to_fit();
    }

    Log::info("Audio disabled for controller");

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
    uint8_t packet[AUDIO_PACKET_BYTES];

    while (!stopAudioThread)
    {
        {
            std::unique_lock<std::mutex> lock(audioMutex);

            audioCondition.wait(lock, [this] {
                return stopAudioThread || audioBuffer.size() >= AUDIO_PACKET_BYTES;
            });

            if (stopAudioThread)
            {
                return;
            }

            std::copy(audioBuffer.begin(), audioBuffer.begin() + AUDIO_PACKET_BYTES, packet);
            audioBuffer.erase(audioBuffer.begin(), audioBuffer.begin() + AUDIO_PACKET_BYTES);
        }

        // Outside the lock: this is a blocking USB transfer, and the decode thread must never
        // wait behind it.
        if (!sendAudioSamples(packet, sizeof(packet)))
        {
            Log::error("Failed to send audio samples");

            return;
        }
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
