/*
 * Copyright (C) 2020 Medusalix
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

#include <cstddef>
#include <cstdint>
#include <functional>
#include <vector>

struct Frame;
class Bytes;

/*
 * Base class for GIP (Game Input Protocol) devices.
 *
 * The handshake, and the state it drives the device through (MS-GIPUSB 3.1.1):
 *
 *   <- Announce             (from controller)          device is in Arrival
 *   -> Identify             (metadata request)         Arrival -> Idle
 *   <- Identify             (metadata response)
 *   -> Set device state     (start)                    Idle -> Active
 *   -> LED mode: dim
 *   -> Serial number: 0x04
 *   <- Serial number        (from controller)
 *
 * Start comes after the metadata response rather than alongside the request: a device leaves the
 * Hello stage only on receipt of a state message, and an audio sub-device announces itself only
 * once the primary has initialised (2.2.11). A device that never answers the metadata request is
 * started anyway after a timeout - see Controller::waitForMetadata().
 *
 * The security exchange (command 6) is not implemented. MS-GIPUSB 5 says the host succeeds it by
 * default, and a device may opt out of it entirely, so nothing here depends on it.
 */
class GipDevice
{
public:
    using SendPacket = std::function<bool(const Bytes &data)>;

    bool handlePacket(const Bytes &packet);

protected:
    enum BatteryType
    {
        BATT_TYPE_CHARGING = 0x00,
        BATT_TYPE_ALKALINE = 0x01,
        BATT_TYPE_NIMH = 0x02,
    };

    enum BatteryLevel
    {
        BATT_LEVEL_EMPTY = 0x00,
        BATT_LEVEL_LOW = 0x01,
        BATT_LEVEL_MED = 0x02,
        BATT_LEVEL_FULL = 0x03,
    };

    /*
     * States for the Set Device State command (MS-GIPUSB 3.1.5.5.5, Table 39). xow called this
     * "power mode" and named 0x01 SLEEP, which is wrong twice over: the message drives the device
     * state machine in 3.1.1 rather than a power rail, and 0x01 is Stop - the state the spec has
     * the host send to an *audio* device where a non-audio device gets Start.
     *
     * The device leaves the Hello stage only on receipt of one of these, so when it is sent is as
     * load-bearing as what it says. 0x03 (Full power) and 0x06 (Reserved) are omitted; nothing
     * here sends them.
     */
    enum DeviceState
    {
        STATE_START = 0x00,
        STATE_STOP = 0x01,
        STATE_OFF = 0x04,
        // Full teardown and reinitialise. The device must reply with a "powering off" status first
        STATE_RESET = 0x07,
    };

    enum LedMode
    {
        LED_OFF = 0x00,
        LED_ON = 0x01,
        LED_BLINK_FAST = 0x02,
        LED_BLINK_MED = 0x03,
        LED_BLINK_SLOW = 0x04,
        LED_FADE_SLOW = 0x08,
        LED_FADE_FAST = 0x09,
    };

    struct AnnounceData
    {
        uint8_t macAddress[6];
        uint16_t unknown;
        uint16_t vendorId;
        uint16_t productId;

        struct
        {
            uint16_t major;
            uint16_t minor;
            uint16_t build;
            uint16_t revision;
        } __attribute__((packed)) firmwareVersion;

        /*
         * Four separate versions, one byte per part (MS-GIPUSB Table 27, offsets 24 to 31). xow
         * read all eight bytes as a second four-part 16-bit version and called the lot
         * "hardwareVersion", which is why a pad reporting hardware 2.1 logged as "258.1.1.1":
         * 0x0102 is the major and minor bytes read as one little-endian word.
         *
         * The last three are protocol versions the specification pins: RF and GIP MUST both be
         * 1.0, and so MUST security. The security one is the useful one - it says which handshake
         * a pad expects before we have to infer it from what it answers.
         */
        struct
        {
            uint8_t major;
            uint8_t minor;
        } __attribute__((packed)) hardwareVersion, rfVersion, securityVersion, gipVersion;
    } __attribute__((packed));

    struct StatusData
    {
        uint32_t batteryLevel : 2;
        uint32_t batteryType : 2;
        uint32_t connectionInfo : 4;
        uint8_t unknown1;
        uint16_t unknown2;
    } __attribute__((packed));

    struct GuideButtonData
    {
        uint8_t pressed;
        uint8_t unknown;
    } __attribute__((packed));

    struct RumbleData
    {
        uint8_t unknown;
        uint8_t setRight : 1;
        uint8_t setLeft : 1;
        uint8_t setRightTrigger : 1;
        uint8_t setLeftTrigger : 1;
        uint8_t padding : 4;
        uint8_t leftTrigger;
        uint8_t rightTrigger;
        uint8_t left;
        uint8_t right;
        uint8_t duration;
        uint8_t delay;
        uint8_t repeat;
    } __attribute__((packed));

    struct LedModeData
    {
        uint8_t unknown;
        uint8_t mode;
        uint8_t brightness;
    } __attribute__((packed));

    struct SerialData
    {
        uint16_t unknown;
        char serialNumber[14];
    } __attribute__((packed));

    /*
     * Metadata response header (MS-GIPUSB 2.2.2.4, "Device Metadata Object"), the reply to a
     * metadata request. Every field after the opening blob is a byte offset into the payload
     * that follows it - zero meaning "this device has none" - and each of those points at a
     * count byte followed by that many fixed-size items.
     *
     * Offsets are relative to the end of 'unknown', not to the start of the message.
     */
    struct IdentifyData
    {
        uint8_t unknown[16];
        uint16_t clientCommandsOffset;
        uint16_t firmwareVersionsOffset;
        uint16_t audioFormatsOffset;
        uint16_t capabilitiesOutOffset;
        uint16_t capabilitiesInOffset;
        uint16_t classesOffset;
        uint16_t interfacesOffset;
        uint16_t hidDescriptorOffset;
    } __attribute__((packed));

    struct InputData
    {
        struct
        {
            uint32_t unknown : 2;
            uint32_t start : 1;
            uint32_t select : 1;
            uint32_t a : 1;
            uint32_t b : 1;
            uint32_t x : 1;
            uint32_t y : 1;
            uint32_t dpadUp : 1;
            uint32_t dpadDown : 1;
            uint32_t dpadLeft : 1;
            uint32_t dpadRight : 1;
            uint32_t bumperLeft : 1;
            uint32_t bumperRight : 1;
            uint32_t stickLeft : 1;
            uint32_t stickRight : 1;
        } __attribute__((packed)) buttons;

        uint16_t triggerLeft;
        uint16_t triggerRight;
        int16_t stickLeftX;
        int16_t stickLeftY;
        int16_t stickRightX;
        int16_t stickRightY;
    } __attribute__((packed));

    GipDevice(SendPacket sendPacket);
    virtual ~GipDevice() = default;

    virtual void deviceAnnounced(uint8_t id, const AnnounceData *announce) = 0;
    virtual void statusReceived(uint8_t id, const StatusData *status) = 0;
    virtual void guideButtonPressed(const GuideButtonData *button) = 0;
    virtual void serialNumberReceived(const SerialData *serial) = 0;
    virtual void inputReceived(const InputData *input) = 0;

    /*
     * The reassembled metadata response. 'payload' points at the bytes the offsets in 'identify'
     * are relative to, and is valid only for the duration of the call.
     *
     * Not pure virtual: a device that never asks for metadata has no reason to implement it.
     */
    virtual void identifyReceived(const IdentifyData *identify,
                                  const uint8_t *payload, size_t length) {}

    /*
     * Sets the device state (MS-GIPUSB 3.1.5.5.5). 'id' is the expansion index: 0 is the primary
     * device, and a secondary device such as an audio sub-device is addressed by its own index.
     */
    bool setDeviceState(uint8_t id, DeviceState state);
    /*
     * Security exchange (MS-GIPUSB 3.1.5.5, message type 0x06), the handshake a Windows host runs
     * against every pad. A capture of one shows it completing, and the pad's audio sub-device
     * announcing a few seconds later; this driver has never sent any of it and no sub-device has
     * ever appeared. See AUDIO.md.
     *
     * The framing follows xone: an outer handshake header, an inner data header, the payload, then
     * an eight-byte trailer. Lengths in both headers are **big-endian**, unlike everywhere else in
     * GIP, and each counts the bytes after the header it sits in.
     */
    struct AuthHandshakeHeader
    {
        uint8_t context;
        uint8_t options;
        uint8_t error;
        uint8_t command;
        uint16_t length;   // big-endian
    } __attribute__((packed));

    struct AuthDataHeader
    {
        uint8_t command;
        uint8_t version;
        uint16_t length;   // big-endian
    } __attribute__((packed));

    /*
     * Handshake commands, version 1. The v2 set at 0x21-0x27 negotiates ECDH instead of RSA and is
     * deliberately absent: the captured exchange is v1 throughout, so nothing needs it yet.
     */
    enum AuthCommand
    {
        AUTH_HOST_HELLO = 0x01,
        AUTH_CLIENT_HELLO = 0x02,
        AUTH_CLIENT_CERTIFICATE = 0x03,
        AUTH_HOST_SECRET = 0x05,
        AUTH_HOST_FINISH = 0x07,
        AUTH_CLIENT_FINISH = 0x08,
    };

    enum AuthOption
    {
        AUTH_OPT_ACKNOWLEDGE = 0x01,
        AUTH_OPT_REQUEST = 0x02,
        AUTH_OPT_FROM_HOST = 0x40,
    };

    // Random bytes in a hello, and the fixed trailer every handshake message ends with
    static const size_t AUTH_RANDOM_LENGTH = 32;
    static const size_t AUTH_TRAILER_LENGTH = 8;

    /*
     * A 2048-bit DER RSAPublicKey: four header bytes plus the 0x010a the SEQUENCE declares. The
     * pre-master secret is 48 bytes and encrypts to the modulus size, 256.
     */
    static const size_t AUTH_PUBKEY_LENGTH = 270;
    static const size_t AUTH_SECRET_LENGTH = 48;

    /* Opens the security exchange. */
    bool sendAuthHostHello();

    /*
     * Asks the device for a handshake message it owes us. The exchange is host-driven: a reply is
     * requested rather than merely awaited.
     */
    bool requestAuthPacket(uint8_t command, uint16_t length);

    /*
     * A security message from the device, payload only. Not pure virtual - a device that never
     * starts a handshake has no reason to implement it.
     */
    virtual void authReceived(const uint8_t *data, size_t length) {}

    bool performRumble(RumbleData rumble);
    bool setLedMode(LedModeData mode);
    bool requestSerialNumber();
    bool requestIdentify();

private:
    bool acknowledgePacket(Frame frame);
    bool acknowledgeChunk(const Frame &frame, uint32_t received, uint32_t remaining);
    bool handleChunk(const Frame &frame, uint32_t length, uint32_t offset, const Bytes &data);
    void dispatchChunked(uint8_t command, const uint8_t *data, size_t length);
    uint8_t getSequence(bool accessory = false);

    uint8_t sequence = 0x01;
    uint8_t accessorySequence = 0x01;
    /*
     * Security is a Unique pool in MS-GIPUSB 2.2.9, so it counts separately from the Command
     * class. A capture of a Windows host shows exactly that: metadata, set-state and LED go out as
     * 1, 2, 3 and the first security message is 1 again.
     */
    uint8_t securitySequence = 0x01;
    SendPacket sendPacket;

    /*
     * Reassembly state for the one fragmented transfer a device may have in flight. Fragmented
     * messages are rare - metadata and security only - so this is allocated when one starts and
     * released when it completes, rather than kept around.
     */
    std::vector<uint8_t> chunkBuffer;
    uint32_t chunkLength = 0;
    uint8_t chunkCommand = 0;
    bool chunkActive = false;
};
