/*
 * Android port addition - not part of upstream xow.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

#include "wired.h"
#include "../utils/log.h"
#include "../utils/jni.h"

#include <functional>
#include <utility>

WiredController::WiredController(int fd, JavaVM *jvm) : jvm(jvm)
{
    device = std::make_unique<UsbWiredDevice>(fd);
}

WiredController::~WiredController()
{
    stop();

    // The controller's destructor talks to the device - it sends Set Device State: OFF - so it has
    // to go before the device does. Explicit rather than relying on member declaration order,
    // which would put device first and destroy it last.
    gipController.reset();
    device.reset();
}

bool WiredController::start()
{
    if (device == nullptr || !device->isOpen())
    {
        Log::error("Wired: device did not open");

        return false;
    }

    gipController = std::make_unique<Controller>(
        std::bind(&WiredController::sendPacket, this, std::placeholders::_1)
    );

    stopThread = false;
    readThread = std::thread(&WiredController::readPackets, this);

    /*
     * Started by hand, after the reader is up so nothing that comes back is missed.
     *
     * A cabled pad has already been through Arrival by the time we claim it - Android's own driver
     * took it there, which is why it works without us - and MS-GIPUSB 2.2.1 has a device Hello only
     * while in Arrival. So it never announces to us, and everything downstream of the announce
     * would never run. A capture showed exactly that: the pad sent battery status happily and never
     * said hello.
     */
    gipController->begin();

    Log::info("Wired: controller started");

    return true;
}

void WiredController::stop()
{
    if (stopThread)
    {
        return;
    }

    stopThread = true;

    if (readThread.joinable())
    {
        readThread.join();
    }
}

bool WiredController::sendPacket(const Bytes &data)
{
    if (device == nullptr || !device->isOpen())
    {
        return false;
    }

    return device->interruptWrite(data.raw(), data.size());
}

void WiredController::readPackets()
{
    uint8_t buffer[UsbWiredDevice::MAX_TRANSFER_SIZE];

    /*
     * Attached once for the life of the thread, not around each callback. Every JNI call this
     * driver makes happens below this point - input reports at up to 250 Hz on a cable - and the
     * attach/detach pair was the expensive part of each one on the wireless path. Nothing beneath
     * this may detach; see utils/jni.h.
     */
    JNIEnv *env = nullptr;

    if (jvm == nullptr || jvm->AttachCurrentThread(&env, nullptr) != JNI_OK)
    {
        Log::error("Wired: failed to attach read thread to the JVM");

        return;
    }

    while (!stopThread)
    {
        int transferred = device->interruptRead(buffer, sizeof(buffer));

        // Negative is a real failure - the cable is gone, or the interface was taken away
        if (transferred < 0)
        {
            break;
        }

        // Zero is a read timeout, which is just an idle pad
        if (transferred == 0)
        {
            continue;
        }

        Bytes packet(buffer, buffer + transferred);

        if (!gipController->handlePacket(packet))
        {
            Log::error("Wired: error handling packet");
        }
    }

    jvm->DetachCurrentThread();
}
