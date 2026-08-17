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

    /*
     * Starts or stops rendering stream audio to this pad's headphone jack. Negotiates the format
     * and spins up the sender thread on enable; stops and releases both on disable, so a pad
     * nobody is listening to costs nothing.
     *
     * Safe to call repeatedly with the same value.
     */
    bool setAudioEnabled(bool enable);
    bool isAudioEnabled() const { return audioEnabled; }

    /* Whether this pad declared an audio format we can render to; see supportsAudioOut(). */
    bool supportsAudioOut() const;

    /*
     * Snapshot of the audio session's counters, for the performance overlay: packets sent, bytes
     * dropped, packets late by more than the cadence, send failures, and the pad's last requested
     * flow rate. Reads relaxed atomics, so it costs nothing on the sending path and needs no lock.
     */
    void audioStats(uint32_t out[5]) const;

    /*
     * Queues interleaved 16-bit stereo PCM for this pad. Called from Moonlight's audio decode
     * thread, so it only copies into the ring and returns - the blocking USB write happens on the
     * sender thread. Samples are dropped rather than queued without limit when the ring is full.
     */
    void queueAudio(const int16_t *samples, size_t count);

private:
    /* GIP events */
    void deviceAnnounced(uint8_t id, const AnnounceData *announce) override;
    void statusReceived(uint8_t id, const StatusData *status) override;
    void guideButtonPressed(const GuideButtonData *button) override;
    void serialNumberReceived(const SerialData *serial) override;
    void inputReceived(const InputData *input) override;
    void identifyReceived(uint8_t id, const IdentifyData *identify,
                          const uint8_t *payload, size_t length) override;

    void updateButtonStatus(const InputData *input);

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

    /* Audio producer/consumer, built on the same shape as the rumble pair above */
    void processAudio();
    void audioSamplesReceived(const AudioSamplesData *samples) override;

    void audioControlReceived(uint8_t id, const uint8_t *data, size_t length) override;

    /*
     * The audio sub-device: its GIP device id, and the capture/render format pairs it advertised.
     * Zero id means none has announced, which is the state until the security handshake completes -
     * MS-GIPUSB 2.2.1.4 gates sub-device enumeration on it.
     */
    uint8_t audioDeviceId = 0;
    std::vector<uint8_t> audioDeviceFormats;

    /*
     * Where 2.2.11's initialisation sequence has got to. The host cannot simply send a format and
     * start playing: it stops the device, proposes a format, waits for the device to echo the one
     * it adopted, starts it, and waits for the device's volume message before any audio counts as
     * playable.
     */
    enum AudioState
    {
        AUDIO_IDLE,
        AUDIO_AWAITING_FORMAT,
        AUDIO_AWAITING_VOLUME,
        AUDIO_STREAMING,
    };

    std::atomic<AudioState> audioState{AUDIO_IDLE};

    std::atomic<bool> audioEnabled{false};
    std::atomic<bool> stopAudioThread{false};
    std::thread audioThread;
    std::mutex audioMutex;
    std::condition_variable audioCondition;
    // Bytes awaiting transmission, drained one 8 ms packet at a time. Guarded by audioMutex.
    std::vector<uint8_t> audioBuffer;
    // Last flow rate the pad reported, tracked only to notice it drifting from the packet size
    uint16_t audioFlowRate = 0;

    /*
     * Audio stream health, following the VideoStats pattern: exact counts accumulated on the
     * sending path, with all formatting done off it. Relaxed atomics because they are written on
     * the sender and decode threads and read from neither - a few cycles at 125 packets a second,
     * next to a blocking USB transfer.
     *
     * Deliberately counts rather than averages. An averaged figure is what let "Average decoding
     * time" read 0.00 ms through a total decoder hang; a count of things that happened cannot be
     * quietly wrong in the same way.
     */
    std::atomic<uint32_t> audioPacketsSent{0};
    std::atomic<uint32_t> audioBytesDropped{0};
    std::atomic<uint32_t> audioSendFailures{0};

    /*
     * Times the sender found the ring completely empty. Waiting as such is normal - the ring is
     * how this paces itself, so the sender waits on every packet by design - but a ring drained to
     * nothing means the host stopped supplying in time, which is what a gap in the audio sounds
     * like.
     */
    std::atomic<uint32_t> audioStarved{0};

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