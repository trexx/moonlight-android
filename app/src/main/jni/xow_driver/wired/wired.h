/*
 * Android port addition - not part of upstream xow.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

#pragma once

#include "usb_wired.h"
#include "../controller/controller.h"

#include <jni.h>
#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

/*
 * A single cabled GIP controller.
 *
 * The wireless sibling of this is Dongle, which multiplexes up to sixteen clients over MT76 and
 * 802.11 framing. None of that applies here: one cable is one device, and GIP messages sit
 * directly on interface 0's interrupt endpoints with no transport framing around them at all. So
 * this class is the whole transport - claim, read loop, write callback - and everything above it
 * is the GipDevice stack already shared with the adapter.
 *
 * That sharing is the point. Moving cabled pads onto GipDevice is what gives them metadata, the
 * device-state machine, chunk reassembly and above all the security handshake, which MS-GIPUSB
 * 2.2.1.4 makes the gate on the audio sub-device. The existing XboxOneController implements none of
 * those - it sends canned init packets and parses two message types - so it could never reach
 * headphone audio however much isochronous plumbing were added beneath it.
 */
class WiredController : public GipAudioTransport
{
public:
    /*
     * Takes no jobject: nothing here calls back into Java. The GIP layer above does its own
     * callbacks through GipController.registerNative(), and holding a reference here as well would
     * mean the Java object had to exist before the native handle it is constructed from - which it
     * cannot, since the handle is a constructor argument.
     */
    WiredController(int fd, JavaVM *jvm);
    ~WiredController();

    /* Claims the device and starts the read thread. */
    bool start();
    void stop();

    /* @return the GIP device, for the Java layer to drive rumble and audio through */
    Controller *controller() { return gipController.get(); }

    /*
     * GipAudioTransport. A cabled pad's audio is isochronous on its own interface, so it cannot go
     * through the GIP link the way the adapter's does - see the interface's own comment.
     */
    bool enableAudio() override;
    void disableAudio() override;
    bool sendAudio(const uint8_t *samples, size_t length) override;
    uint32_t underruns() const override { return audioUnderruns.load(std::memory_order_relaxed); }

private:
    void readPackets();

    /* Reaps completed isochronous transfers; libusb has no callbacks without someone pumping it. */
    void handleAudioEvents();

    /* Called on the event thread when a transfer finishes, to count it and free it for reuse. */
    void audioTransferComplete(libusb_transfer *transfer);

    static void LIBUSB_CALL audioCallback(libusb_transfer *transfer);

    /* Sends a GIP message out interface 0, which is what GipDevice::sendPacket resolves to here. */
    bool sendPacket(const Bytes &data);

    std::unique_ptr<UsbWiredDevice> device;
    std::unique_ptr<Controller> gipController;

    std::atomic<bool> stopThread{false};
    std::thread readThread;

    /*
     * The isochronous ring.
     *
     * Each transfer carries AUDIO_PACKETS_PER_TRANSFER packets of one millisecond each, because the
     * bus consumes exactly one packet per USB frame. Their product is queued audio and therefore
     * latency: four transfers of four packets is 16 ms, against xone's 12 x 8 = 96 ms. The floor is
     * two transfers - with one in flight nothing is queued behind the one draining, so any delay in
     * the callback is a gap - and above that the queue only has to cover our own refill, because
     * the 32 ms sample ring already absorbs the host's jitter.
     */
    static const int AUDIO_TRANSFERS = 4;
    static const int AUDIO_PACKETS_PER_TRANSFER = 4;

    // One millisecond of 48 kHz 16-bit stereo, and the GIP message that carries it
    static const size_t AUDIO_FRAGMENT_BYTES = 192;
    static const size_t AUDIO_PACKET_BYTES = 198;

    std::vector<libusb_transfer *> audioTransfers;
    std::vector<std::vector<uint8_t>> audioBuffers;

    // Which transfers are free to fill. Guarded by audioMutex.
    std::vector<int> audioFree;
    std::mutex audioMutex;
    std::condition_variable audioCondition;

    std::atomic<bool> audioRunning{false};
    std::atomic<uint32_t> audioUnderruns{0};
    std::thread audioEventThread;

    JavaVM *jvm;
};
