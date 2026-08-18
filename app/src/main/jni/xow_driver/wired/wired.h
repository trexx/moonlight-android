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
#include <memory>
#include <thread>

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
class WiredController
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

private:
#ifdef _DEBUG
    /*
     * Disposable: finds out whether Android will carry an isochronous transfer at all.
     *
     * No part of this app has ever submitted one - every other USB transfer here is bulk or
     * interrupt - and the whole cabled audio design rests on it working. usbfs supports it and the
     * wrapped-descriptor handoff is proven, but "should work" has already been wrong twice on this
     * transport, so this answers it in a few lines rather than after the full audio path is
     * written.
     *
     * Sends silence to an endpoint the device is not yet configured to play, so nothing should be
     * audible; the result is entirely in the logged per-packet status.
     */
    void spikeIsochronous();
#endif

    void readPackets();

    /* Sends a GIP message out interface 0, which is what GipDevice::sendPacket resolves to here. */
    bool sendPacket(const Bytes &data);

    std::unique_ptr<UsbWiredDevice> device;
    std::unique_ptr<Controller> gipController;

    std::atomic<bool> stopThread{false};
    std::thread readThread;

    JavaVM *jvm;
};
