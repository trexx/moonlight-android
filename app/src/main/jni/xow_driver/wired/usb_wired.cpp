/*
 * Android port addition - not part of upstream xow.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

#include "usb_wired.h"
#include "../utils/log.h"

/*
 * A read timeout has to be long enough not to spin and short enough that the loop notices it has
 * been asked to stop. Input arrives every 4 ms when anything is happening and not at all when
 * nothing is, so this is a stop-responsiveness figure rather than a data one.
 */
#define USB_TIMEOUT_READ 500
#define USB_TIMEOUT_WRITE 1000

// Interface 0 carries GIP. Interface 1 is audio and is claimed separately, only if audio is wanted.
#define GIP_INTERFACE 0

UsbWiredDevice::UsbWiredDevice(int fd)
{
    /*
     * Android owns enumeration, so libusb must not go looking for devices itself - it has no
     * permission to, and on Android it would find nothing. The fd from UsbDeviceConnection is the
     * only way in, which is the same handoff dongle/usb.cpp uses.
     */
    int error = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr);

    if (error != LIBUSB_SUCCESS)
    {
        Log::error("Wired: libusb_set_option failed: %s", libusb_error_name(error));

        return;
    }

    error = libusb_init(&ctx);

    if (error < 0)
    {
        Log::error("Wired: libusb_init failed: %s", libusb_error_name(error));

        return;
    }

    error = libusb_wrap_sys_device(ctx, (intptr_t)fd, &handle);

    if (error < 0 || handle == nullptr)
    {
        Log::error("Wired: libusb_wrap_sys_device failed: %s", libusb_error_name(error));

        handle = nullptr;

        return;
    }

    /*
     * No reset, no set_configuration and no claim, unlike the dongle path. Android has already
     * configured the device and the Java layer has already claimed interface 0 off the kernel
     * driver on this same fd; resetting or re-claiming here would either fail or drop the device
     * out from under the claim that just succeeded.
     */
    Log::info("Wired: device opened");
}

UsbWiredDevice::~UsbWiredDevice()
{
    if (handle == nullptr)
    {
        if (ctx != nullptr)
        {
            libusb_exit(ctx);
        }

        return;
    }

    // Interface 0 is the Java layer's to release, for the same reason it is Java's to claim.
    // Interface 1 is ours.
    disableAudioInterface();

    libusb_close(handle);

    if (ctx != nullptr)
    {
        libusb_exit(ctx);
    }
}

int UsbWiredDevice::interruptRead(uint8_t *buffer, size_t length)
{
    int transferred = 0;

    int error = libusb_interrupt_transfer(handle, ENDPOINT_IN, buffer,
                                          static_cast<int>(length), &transferred,
                                          USB_TIMEOUT_READ);

    // Silence is the normal state of an idle pad, so a timeout returns "nothing" rather than an
    // error the caller would have to special-case.
    if (error == LIBUSB_ERROR_TIMEOUT)
    {
        return 0;
    }

    if (error)
    {
        Log::error("Wired: interrupt read failed: %s", libusb_error_name(error));

        return -1;
    }

    return transferred;
}

bool UsbWiredDevice::interruptWrite(const uint8_t *data, size_t length)
{
    int transferred = 0;

    int error = libusb_interrupt_transfer(handle, ENDPOINT_OUT, const_cast<uint8_t *>(data),
                                          static_cast<int>(length), &transferred,
                                          USB_TIMEOUT_WRITE);

    if (error)
    {
        Log::error("Wired: interrupt write failed: %s", libusb_error_name(error));

        return false;
    }

    return transferred == static_cast<int>(length);
}

bool UsbWiredDevice::enableAudioInterface()
{
    if (handle == nullptr)
    {
        return false;
    }

    if (audioClaimed)
    {
        return true;
    }

    int error = libusb_claim_interface(handle, AUDIO_INTERFACE);

    if (error)
    {
        Log::error("Wired: could not claim the audio interface: %s", libusb_error_name(error));

        return false;
    }

    /*
     * Down to the idle setting first, so a session starts from a known state rather than whatever
     * the last one left. A clean toggle-off does this on the way out, but a killed process does
     * not - and then the pad is still on the streaming setting with its audio device started, and
     * the next session builds on top of that. Re-selecting resets the endpoint, which is otherwise
     * only fixed by unplugging the pad.
     *
     * Failure here is ignored: it means the interface was already idle, which is the state wanted.
     */
    libusb_set_interface_alt_setting(handle, AUDIO_INTERFACE, 0);

    /*
     * The endpoints only exist on alt setting 1; alt 0 is the idle setting and declares none. Until
     * this succeeds there is nothing to submit to, so a failure here is the end of audio rather
     * than something to carry on past.
     */
    error = libusb_set_interface_alt_setting(handle, AUDIO_INTERFACE, AUDIO_ALT_SETTING);

    if (error)
    {
        Log::error("Wired: could not select the audio alt setting: %s", libusb_error_name(error));

        libusb_release_interface(handle, AUDIO_INTERFACE);

        return false;
    }

    audioClaimed = true;

    Log::info("Wired: audio interface claimed, alt setting %d selected", AUDIO_ALT_SETTING);

    return true;
}

void UsbWiredDevice::disableAudioInterface()
{
    if (handle == nullptr || !audioClaimed)
    {
        return;
    }

    audioClaimed = false;

    // Back to the setting with no endpoints, so the device stops expecting a stream
    int error = libusb_set_interface_alt_setting(handle, AUDIO_INTERFACE, 0);

    if (error)
    {
        Log::error("Wired: could not release the audio alt setting: %s", libusb_error_name(error));
    }

    error = libusb_release_interface(handle, AUDIO_INTERFACE);

    if (error)
    {
        Log::error("Wired: could not release the audio interface: %s", libusb_error_name(error));
    }
}
