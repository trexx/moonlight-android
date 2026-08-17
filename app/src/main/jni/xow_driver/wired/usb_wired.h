/*
 * Android port addition - not part of upstream xow.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

#pragma once

#include "../utils/bytes.h"

#include <libusb/libusb.h>
#include <cstdint>

/*
 * A cabled GIP device, reached over libusb.
 *
 * Separate from dongle/usb.cpp's UsbDevice rather than sharing it, because that class is shaped
 * for the adapter: it resets the device, sets the configuration, claims interface 0 unconditionally
 * and offers only bulk transfers. A pad wants none of the first three - Android has already
 * configured it, and resetting a controller mid-session drops the input Android is still using -
 * and none of its transfers are bulk.
 *
 * MS-GIPUSB 2.2.12 gives the layout, confirmed by a descriptor scan of a real pad:
 *
 *   interface 0 alt 0   class ff sub 47 proto d0   ep 0x01 intr 64 @4ms, ep 0x81 intr 64 @4ms
 *   interface 1 alt 0   class ff sub 47 proto d0   no endpoints (idle)
 *   interface 1 alt 1   class ff sub 47 proto d0   ep 0x02 isoc 228 @1ms, ep 0x82 isoc 228 @1ms
 *
 * So GIP messages - input, rumble, metadata, the security handshake, audio control - all travel on
 * interface 0's interrupt endpoints, and only the audio samples themselves are isochronous. That
 * split is why the whole GIP stack has to be reachable here before audio is: the handshake that
 * gates the audio sub-device runs entirely on interface 0.
 */
class UsbWiredDevice
{
public:
    // Interface 0's interrupt endpoints carry every GIP message
    static const uint8_t ENDPOINT_IN = 0x81;
    static const uint8_t ENDPOINT_OUT = 0x01;

    // The Command data class MTU is 64 bytes, and a fragment never exceeds one interrupt packet
    static const size_t MAX_TRANSFER_SIZE = 64;

    /*
     * The caller must already have claimed interface 0 on this fd, and must keep it claimed for
     * the life of this object.
     *
     * Claiming is left to Java deliberately. Android's own driver holds a cabled pad's interface,
     * and the only thing that detaches it is UsbDeviceConnection.claimInterface(iface, true) -
     * which is what every other cabled driver here does. libusb_claim_interface on the wrapped fd
     * would just fail with EBUSY against the kernel driver, so this does not attempt it: one
     * owner, and it is the one with the API that can win.
     */
    explicit UsbWiredDevice(int fd);
    ~UsbWiredDevice();

    /* @return whether the device opened */
    bool isOpen() const { return handle != nullptr; }

    /*
     * @return bytes read, 0 on timeout, negative on error. A timeout is not a failure: a pad with
     *         nothing to say is silent, and the read loop simply goes round again.
     */
    int interruptRead(uint8_t *buffer, size_t length);

    bool interruptWrite(const uint8_t *data, size_t length);

private:
    libusb_context *ctx = nullptr;
    libusb_device_handle *handle = nullptr;
};
