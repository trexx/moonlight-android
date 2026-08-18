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

    /*
     * The ring drains one transfer's worth at a time, so its packet size is the transfer's payload
     * rather than the adapter's 8 ms buffer. Since the size is also the cadence - the sender paces
     * itself by waiting for one packet's worth of samples - this makes it wait 4 ms rather than 8.
     */
    gipController->setAudioTransport(this);

    // Android takes this pad back the moment we release it, so switching it off on the way out
    // would leave it dead for whoever picks it up next - us included, on the following connect.
    gipController->setPowerOffOnTeardown(false);

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


void LIBUSB_CALL WiredController::audioCallback(libusb_transfer *transfer)
{
    static_cast<WiredController *>(transfer->user_data)->audioTransferComplete(transfer);
}

void WiredController::audioTransferComplete(libusb_transfer *transfer)
{
    /*
     * Isochronous reports a status per packet, not just per transfer, and that is the only place a
     * dropped millisecond is visible - the transfer as a whole still reports COMPLETED. Counting
     * them here is what makes the queue depth above a measured choice rather than a guess.
     */
    for (int i = 0; i < transfer->num_iso_packets; i++)
    {
        if (transfer->iso_packet_desc[i].status != LIBUSB_TRANSFER_COMPLETED)
        {
            audioUnderruns.fetch_add(1, std::memory_order_relaxed);
        }
    }

    // Cancelled transfers are teardown, not failures, and must not go back in the free list
    if (transfer->status == LIBUSB_TRANSFER_CANCELLED || !audioRunning.load())
    {
        return;
    }

    // Straight back out, filled with whatever the ring has. The bus is the clock here: this
    // transfer's slots are going to be transmitted regardless, so the only question is whether
    // they carry audio or silence, and stopping to wait for samples would guarantee silence.
    submitAudioTransfer(transfer);
}

bool WiredController::enableAudio()
{
    if (audioRunning.load())
    {
        return true;
    }

    if (!device->enableAudioInterface())
    {
        return false;
    }

    audioUnderruns.store(0, std::memory_order_relaxed);
    audioIdle.store(0, std::memory_order_relaxed);
    audioPrimed.store(false, std::memory_order_relaxed);
    audioTransfers.clear();
    audioBuffers.clear();
    audioFree.clear();

    for (int i = 0; i < AUDIO_TRANSFERS; i++)
    {
        libusb_transfer *transfer = libusb_alloc_transfer(AUDIO_PACKETS_PER_TRANSFER);

        if (transfer == nullptr)
        {
            Log::error("Wired: could not allocate an audio transfer");

            disableAudio();

            return false;
        }

        audioTransfers.push_back(transfer);
        audioBuffers.emplace_back(AUDIO_PACKETS_PER_TRANSFER * AUDIO_PACKET_BYTES, 0);
        audioFree.push_back(i);
    }

    audioRunning.store(true);
    audioEventThread = std::thread(&WiredController::handleAudioEvents, this);

    // Primed with silence and all submitted, so the endpoint is fed from the first frame and every
    // completion has a transfer to hand straight back.
    for (libusb_transfer *transfer : audioTransfers)
    {
        submitAudioTransfer(transfer);
    }

    Log::info("Wired: audio ready, %d transfers of %d packets (%d ms queued)",
              AUDIO_TRANSFERS, AUDIO_PACKETS_PER_TRANSFER,
              AUDIO_TRANSFERS * AUDIO_PACKETS_PER_TRANSFER);

    return true;
}

void WiredController::disableAudio()
{
    if (!audioRunning.exchange(false))
    {
        // Still tear down a half-built pool from a failed enableAudio()
        for (libusb_transfer *transfer : audioTransfers)
        {
            libusb_free_transfer(transfer);
        }

        audioTransfers.clear();
        audioBuffers.clear();
        audioFree.clear();
        device->disableAudioInterface();

        return;
    }

    // Wake anything waiting for a free transfer that will now never come
    audioCondition.notify_all();

    for (libusb_transfer *transfer : audioTransfers)
    {
        libusb_cancel_transfer(transfer);
    }

    if (audioEventThread.joinable())
    {
        audioEventThread.join();
    }

    for (libusb_transfer *transfer : audioTransfers)
    {
        libusb_free_transfer(transfer);
    }

    audioTransfers.clear();
    audioBuffers.clear();
    audioFree.clear();

    device->disableAudioInterface();

    Log::info("Wired: audio stopped");
}

void WiredController::handleAudioEvents()
{
    /*
     * libusb delivers completions only while someone is pumping it, and nothing else in this
     * driver does - the read loop is a synchronous transfer. The timeout is what lets this notice
     * teardown rather than blocking in the library.
     */
    while (audioRunning.load())
    {
        timeval timeout = { 0, 10000 };

        libusb_handle_events_timeout(device->context(), &timeout);
    }

    // Drain the cancellations issued during teardown, so no callback fires after the pool is freed
    for (int i = 0; i < 50; i++)
    {
        timeval timeout = { 0, 2000 };

        libusb_handle_events_timeout(device->context(), &timeout);
    }
}

bool WiredController::sendAudio(const uint8_t *samples, size_t length)
{
    /*
     * Never called: this transport pulls. Controller only pushes through here when it is the
     * clock, and it stands its sender thread down when a transport is set.
     */
    return false;
}

bool WiredController::submitAudioTransfer(libusb_transfer *transfer)
{
    if (!audioRunning.load())
    {
        return false;
    }

    // Which buffer belongs to this transfer; the pool is small, so a scan beats threading an index
    // through user_data alongside the object pointer.
    int index = -1;

    for (size_t i = 0; i < audioTransfers.size(); i++)
    {
        if (audioTransfers[i] == transfer)
        {
            index = static_cast<int>(i);

            break;
        }
    }

    if (index < 0)
    {
        return false;
    }

    uint8_t *buffer = audioBuffers[index].data();
    uint8_t fragment[AUDIO_FRAGMENT_BYTES];

    /*
     * One GIP message per millisecond, each with its own sequence number, at the packet stride the
     * endpoint expects - the device reads each isochronous packet as a whole message, so the header
     * cannot be written once for the buffer.
     */
    /*
     * Held off until there is a cushion. Consuming from an empty ring produces silence and keeps
     * the ring empty, so without this the stream never recovers from its own start.
     */
    if (!audioPrimed.load(std::memory_order_relaxed)
        && gipController->audioBuffered() >= AUDIO_PREFILL_BYTES)
    {
        audioPrimed.store(true, std::memory_order_relaxed);

        Log::info("Wired: audio buffer primed, streaming");
    }

    for (int i = 0; i < AUDIO_PACKETS_PER_TRANSFER; i++)
    {
        if (!audioPrimed.load(std::memory_order_relaxed))
        {
            // Deliberate silence while filling, not a shortfall, so it is not counted as one
            std::fill(fragment, fragment + sizeof(fragment), 0);
        }
        else if (gipController->drainAudio(fragment, sizeof(fragment)) == 0)
        {
            audioIdle.fetch_add(1, std::memory_order_relaxed);
        }

        gipController->encodeAudioFragment(fragment, sizeof(fragment),
                                           buffer + i * AUDIO_PACKET_BYTES);
    }

    libusb_fill_iso_transfer(transfer, device->deviceHandle(),
                             UsbWiredDevice::AUDIO_ENDPOINT_OUT,
                             buffer,
                             static_cast<int>(AUDIO_PACKETS_PER_TRANSFER * AUDIO_PACKET_BYTES),
                             AUDIO_PACKETS_PER_TRANSFER, audioCallback, this, 1000);

    libusb_set_iso_packet_lengths(transfer, AUDIO_PACKET_BYTES);

    int error = libusb_submit_transfer(transfer);

    if (error)
    {
        Log::error("Wired: audio submit failed: %s", libusb_error_name(error));

        return false;
    }

    return true;
}
