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
     * No reset and no set_configuration, unlike the dongle path. Android has already configured
     * the device, and resetting it would drop it out from under whatever else is using it - which
     * during development is Android's own input stack, still driving the pad we are about to take
     * over.
     */
    error = libusb_claim_interface(handle, GIP_INTERFACE);

    if (error)
    {
        Log::error("Wired: error claiming interface 0: %s", libusb_error_name(error));

        return;
    }

    claimed = true;

    Log::info("Wired: claimed GIP interface");
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

    if (claimed)
    {
        int error = libusb_release_interface(handle, GIP_INTERFACE);

        if (error)
        {
            Log::error("Wired: error releasing interface: %s", libusb_error_name(error));
        }
    }

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
