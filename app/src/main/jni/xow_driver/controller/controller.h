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

#pragma once

#include <jni.h>

#include "gip.h"
#include "../utils/buffer.h"

#include <atomic>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <vector>

/*
 * Forwards gamepad events to virtual input device
 * Passes force feedback effects to gamepad
 */
class Controller : public GipDevice
{
public:
    Controller(SendPacket sendPacket);
    ~Controller();

    /*
     * Takes a global reference to thiz and resolves the callback methods once. Called from the
     * Java thread constructing XboxWirelessController, so env is that thread's - jmethodIDs are
     * not references and stay valid on any thread, provided the class is kept alive, which the
     * global reference below does.
     */
    void registerJavaContext(JavaVM *vm, JNIEnv *env, jobject thiz);
    void inputRumble(short lowFreqMotor, short highFreqMotor);
    void inputRumbleTrigger(short leftTrigger, short rightTrigger);

private:
    /* GIP events */
    void deviceAnnounced(uint8_t id, const AnnounceData *announce) override;
    void statusReceived(uint8_t id, const StatusData *status) override;
    void guideButtonPressed(const GuideButtonData *button) override;
    void serialNumberReceived(const SerialData *serial) override;
    void inputReceived(const InputData *input) override;
    void identifyReceived(const IdentifyData *identify,
                          const uint8_t *payload, size_t length) override;

    void updateButtonStatus(const InputData *input);

    /*
     * Security exchange. Phase one: drive the handshake far enough to see what the pad answers,
     * without any crypto. Reaching the certificate proves the framing, the acknowledgement-driven
     * flow and the chunk reassembly it arrives over; the crypto that follows is the part with a
     * known shape. See AUDIO.md for why this is being attempted.
     */
    void authReceived(const uint8_t *data, size_t length) override;

    // Handshake command last sent, so a reply can be matched to its request
    uint8_t authLastSent = 0;

    /* Lifts the RSA public key out of the controller's certificate; see the definition. */
    bool extractPublicKey(const uint8_t *data, size_t length);

    // DER RSAPublicKey taken from the certificate, empty until one arrives
    std::vector<uint8_t> authPublicKey;

    // Audio formats the pad declared in its metadata, two bytes per entry, empty if it declared
    // none. Written once when metadata arrives and read afterwards; see identifyReceived().
    std::vector<uint8_t> audioFormats;

    /* Device initialization */
    void initInput(const AnnounceData *announce);

    /*
     * Moves the device from Idle to Active and finishes setup. Idempotent: whichever of the
     * metadata response or the timeout below gets there first wins, and the other does nothing.
     */
    void startDevice();

    // Set once the device has been told to start, so it is told exactly once
    std::atomic<bool> deviceStarted{false};

    /*
     * Waits out the metadata response before starting the device anyway.
     *
     * MS-GIPUSB 3.1.1 has a device leave the Hello stage only on a Set Device State, and 2.2.11
     * has an audio sub-device announce itself only once the primary has initialised - so start
     * belongs after the metadata exchange, not racing it. A pad that never answers still has to
     * be started or it would never report input at all, which is what this is for.
     */
    void waitForMetadata();

    std::thread startThread;
    std::mutex startMutex;
    std::condition_variable startCondition;
    // Guarded by startMutex, so teardown can cut the wait short rather than sleeping it out
    bool stopStartThread = false;

    /* Rumble buffer consumer */
    void processRumble();
    void sendRumble();

    std::atomic<bool> stopRumbleThread;
    std::thread rumbleThread;
    std::mutex rumbleMutex;
    std::condition_variable rumbleCondition;
    Buffer<RumbleData> rumbleBuffer;

    void notifyJavaBattery(uint8_t type, uint8_t level, uint8_t charge);

    // Last reported status fields, so only changes are forwarded. 0xff is "nothing seen yet",
    // which no GIP value collides with - each of these is two bits wide.
    uint8_t batteryType = 0xff;
    uint8_t batteryLevel = 0xff;
    uint8_t batteryCharge = 0xff;
    uint8_t powerLevel = 0xff;
    uint16_t rumbleLeft, rumbleRight, rumbleTriggerLeft, rumbleTriggerRight;

    uint32_t buttonStatus = 0;
    uint16_t triggerLeft = 0;
    uint16_t triggerRight = 0;
    int16_t stickLeftX = 0;
    int16_t stickLeftY = 0;
    int16_t stickRightX = 0;
    int16_t stickRightY = 0;

    JavaVM *jvm;
    jobject jthis;
    // Global reference, so the class stays loaded and the method IDs below stay valid. Resolved
    // once in registerJavaContext() rather than per callback, which is what GetObjectClass() and
    // GetMethodID() used to cost on every input report.
    jclass jclazz = nullptr;
    jmethodID updateInputMethod = nullptr;
    jmethodID updateBatteryMethod = nullptr;
};

constexpr uint16_t A_FLAG = 0x1000;
constexpr uint16_t B_FLAG = 0x2000;
constexpr uint16_t X_FLAG = 0x4000;
constexpr uint16_t Y_FLAG = 0x8000;
constexpr uint16_t UP_FLAG = 0x0001;
constexpr uint16_t DOWN_FLAG = 0x0002;
constexpr uint16_t LEFT_FLAG = 0x0004;
constexpr uint16_t RIGHT_FLAG = 0x0008;
constexpr uint16_t LB_FLAG = 0x0100;
constexpr uint16_t RB_FLAG = 0x0200;
constexpr uint16_t PLAY_FLAG = 0x0010;
constexpr uint16_t BACK_FLAG = 0x0020;
constexpr uint16_t LS_CLK_FLAG = 0x0040;
constexpr uint16_t RS_CLK_FLAG = 0x0080;
constexpr uint16_t SPECIAL_BUTTON_FLAG = 0x0400;