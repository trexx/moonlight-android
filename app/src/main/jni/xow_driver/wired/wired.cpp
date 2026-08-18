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
#include <vector>

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

#ifdef _DEBUG
    // Debug builds only, and disposable - see spikeIsochronous(). Runs here rather than on audio
    // enable because it answers a question about the transport, not about a session.
    spikeIsochronous();
#endif

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

#ifdef _DEBUG

namespace
{
    /*
     * Results of the spike, filled from the libusb event thread and read once it has drained.
     * Isochronous reports a status per packet rather than per transfer, which is the whole reason
     * this is worth measuring rather than assuming - an underrun shows up here and nowhere else.
     */
    struct SpikeResult
    {
        std::atomic<int> transfersDone{0};
        std::atomic<int> packetsCompleted{0};
        std::atomic<int> packetsFailed{0};
        std::atomic<int> lastTransferStatus{-1};
        std::atomic<int> lastPacketStatus{-1};
    };

    void LIBUSB_CALL spikeCallback(libusb_transfer *transfer)
    {
        auto *result = static_cast<SpikeResult *>(transfer->user_data);

        result->lastTransferStatus.store(transfer->status, std::memory_order_relaxed);

        for (int i = 0; i < transfer->num_iso_packets; i++)
        {
            const libusb_iso_packet_descriptor &packet = transfer->iso_packet_desc[i];

            if (packet.status == LIBUSB_TRANSFER_COMPLETED)
            {
                result->packetsCompleted.fetch_add(1, std::memory_order_relaxed);
            }
            else
            {
                result->packetsFailed.fetch_add(1, std::memory_order_relaxed);
                result->lastPacketStatus.store(packet.status, std::memory_order_relaxed);
            }
        }

        result->transfersDone.fetch_add(1, std::memory_order_relaxed);
    }
}

void WiredController::spikeIsochronous()
{
    // The real path's shape: 4 packets of one millisecond each, 192 bytes of audio plus a 6-byte
    // GIP header, inside the endpoint's 228.
    const int packetsPerTransfer = 4;
    const int packetSize = 198;
    const int transferCount = 4;

    if (!device->enableAudioInterface())
    {
        Log::error("Spike: no audio interface, isochronous is unreachable");

        return;
    }

    SpikeResult result;
    std::vector<uint8_t> buffer(packetsPerTransfer * packetSize, 0);
    std::vector<libusb_transfer *> transfers;

    for (int i = 0; i < transferCount; i++)
    {
        libusb_transfer *transfer = libusb_alloc_transfer(packetsPerTransfer);

        if (transfer == nullptr)
        {
            Log::error("Spike: could not allocate a transfer");

            break;
        }

        libusb_fill_iso_transfer(transfer, device->deviceHandle(),
                                 UsbWiredDevice::AUDIO_ENDPOINT_OUT,
                                 buffer.data(), static_cast<int>(buffer.size()),
                                 packetsPerTransfer, spikeCallback, &result, 1000);

        libusb_set_iso_packet_lengths(transfer, packetSize);

        int error = libusb_submit_transfer(transfer);

        if (error)
        {
            Log::error("Spike: submit failed: %s", libusb_error_name(error));

            libusb_free_transfer(transfer);

            break;
        }

        transfers.push_back(transfer);
    }

    if (transfers.empty())
    {
        Log::error("Spike: nothing was submitted");

        device->disableAudioInterface();

        return;
    }

    /*
     * Pumped here rather than on a thread: the spike is short and synchronous, and the real path
     * gets a proper event thread. A transfer that never completes leaves the loop on the timeout
     * rather than hanging the caller.
     */
    for (int i = 0; i < 200 && result.transfersDone.load() < (int)transfers.size(); i++)
    {
        timeval timeout = { 0, 5000 };

        libusb_handle_events_timeout(device->context(), &timeout);
    }

    Log::info("Spike: %d/%zu transfers done, %d packets ok, %d failed, transfer status %d, packet status %d",
              result.transfersDone.load(), transfers.size(),
              result.packetsCompleted.load(), result.packetsFailed.load(),
              result.lastTransferStatus.load(), result.lastPacketStatus.load());

    // Cancel anything still outstanding, then drain its callbacks before the buffer goes away
    for (libusb_transfer *transfer : transfers)
    {
        libusb_cancel_transfer(transfer);
    }

    for (int i = 0; i < 200 && result.transfersDone.load() < (int)transfers.size(); i++)
    {
        timeval timeout = { 0, 5000 };

        libusb_handle_events_timeout(device->context(), &timeout);
    }

    for (libusb_transfer *transfer : transfers)
    {
        libusb_free_transfer(transfer);
    }

    device->disableAudioInterface();
}

#endif
