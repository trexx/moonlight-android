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
#include "gip.h"

#include <cstdlib>
#include <cmath>
#include <cstdint>
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
#define RUMBLE_MAX_POWER 100
#define RUMBLE_DELAY std::chrono::milliseconds(10)

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
    stopRumbleThread = true;
    rumbleCondition.notify_one();

    if (rumbleThread.joinable())
    {
        rumbleThread.join();
    }

    if (!setPowerMode(DEVICE_ID_CONTROLLER, POWER_OFF))
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
    this->updateBatteryMethod = env->GetMethodID(this->jclazz, "updateBattery", "(BB)V");

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
        "Hardware version: %d.%d.%d.%d",
        announce->hardwareVersion.major,
        announce->hardwareVersion.minor,
        announce->hardwareVersion.build,
        announce->hardwareVersion.revision
    );

    initInput(announce);
}

void Controller::statusReceived(uint8_t id, const StatusData *status)
{
    const std::string levels[] = { "low", "normal", "high", "full" };

    uint8_t type = status->batteryType;
    uint8_t level = status->batteryLevel;

    // Nothing has moved since the last report
    if (type == batteryType && level == batteryLevel)
    {
        return;
    }

    batteryType = type;
    batteryLevel = level;

    // BATT_TYPE_CHARGING is a misnomer inherited from xow: type 0 means the pad has no battery
    // and is running off the cable, not that it is charging. GIP's status message says nothing
    // about charge direction at all. xone reads the same wire values as NONE/STANDARD/KIT and
    // maps type 0 to "not charging" with an unknown level, which is the coherent reading and the
    // one followed here. gip.h keeps upstream's names so it stays refreshable - see UPSTREAM.md.
    //
    // This case used to return early, which is why battery never reached the host even though the
    // level was already tracked: the state that most needs reporting was the one being dropped.
    if (type == BATT_TYPE_CHARGING)
    {
        Log::info("Battery: none (powered externally)");
    }
    else
    {
        Log::info("Battery level: %s", levels[level].c_str());
    }

    notifyJavaBattery(type, level);
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

void Controller::notifyJavaBattery(uint8_t type, uint8_t level)
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
                        static_cast<jbyte>(level));
}

void Controller::initInput(const AnnounceData *announce)
{
    LedModeData ledMode = {};

    // Dim the LED a little bit, like the original driver
    // Brightness ranges from 0x00 to 0x20
    ledMode.mode = LED_ON;
    ledMode.brightness = 0x14;

    if (!setPowerMode(DEVICE_ID_CONTROLLER, POWER_ON))
    {
        Log::error("Failed to set initial power mode");

        return;
    }

    if (!setLedMode(ledMode))
    {
        Log::error("Failed to set initial LED mode");

        return;
    }

    if (!requestSerialNumber())
    {
        Log::error("Failed to request serial number");

        return;
    }

    rumbleThread = std::thread(&Controller::processRumble, this);
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
