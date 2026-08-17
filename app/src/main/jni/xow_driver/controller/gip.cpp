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

#include "gip.h"
#include "../utils/log.h"
#include "../utils/bytes.h"

#include <algorithm>
#include <random>

enum FrameCommand
{
    CMD_ACKNOWLEDGE = 0x01,
    CMD_ANNOUNCE = 0x02,
    CMD_STATUS = 0x03,
    CMD_IDENTIFY = 0x04,
    CMD_SET_DEVICE_STATE = 0x05,
    CMD_AUTHENTICATE = 0x06,
    CMD_GUIDE_BTN = 0x07,
    CMD_AUDIO_CONFIG = 0x08,
    CMD_RUMBLE = 0x09,
    CMD_LED_MODE = 0x0a,
    CMD_SERIAL_NUM = 0x1e,
    CMD_INPUT = 0x20,
    CMD_AUDIO_SAMPLES = 0x60,
};

// Different frame types
// Command: controller doesn't respond
// Request: controller responds with data
// Request (ACK): controller responds with ack + data
//
// These are the high nibble of the GIP header's flags byte (MS-GIPUSB 2.2.10.2), so the values
// here are the spec's bits shifted down by four: ACME is bit 4, System bit 5, InitFrag bit 6 and
// Fragment bit 7. The two original names predate the specification being published and are kept
// because they are what the rest of this driver says.
enum FrameType
{
    TYPE_COMMAND = 0x00,
    TYPE_ACK = 0x01,          // spec: ACME, "acknowledge me"
    TYPE_REQUEST = 0x02,      // spec: System
    TYPE_CHUNK_START = 0x04,  // spec: InitFrag, first fragment of a fragmented message
    TYPE_CHUNK = 0x08,        // spec: Fragment
};

// The largest metadata or security transfer we will reassemble. The spec caps a message at what
// its data class MTU and the length encoding allow; this is a sanity bound so a corrupt length
// cannot make us allocate wildly. xone uses the same value.
#define CHUNK_BUFFER_MAX_LENGTH 0xffff

struct Frame
{
    uint8_t command;
    uint8_t deviceId : 4;
    uint8_t type : 4;
    uint8_t sequence;
    uint8_t length;
} __attribute__((packed));

/*
 * Payload of a Protocol Control acknowledgement (MS-GIPUSB 3.1.5.1). 'received' is how much of the
 * message has arrived contiguously and 'remaining' how much is still expected, which is what lets
 * the sender decide whether to retransmit.
 *
 * acknowledgePacket() below builds the same nine bytes by reusing Frame, which happens to lay out
 * identically; this spells the fields out because the chunked path has real values to put in them.
 */
struct AcknowledgeData
{
    uint8_t unknown;
    uint8_t command;
    uint8_t options;
    uint16_t received;
    uint8_t padding[2];
    uint16_t remaining;
} __attribute__((packed));

/*
 * Decodes the variable-length integer the header uses for payload length and chunk offset
 * (MS-GIPUSB 3.1.5.2): seven bits per byte, low order first, bit 7 meaning another byte follows.
 * At most four bytes.
 *
 * Returns the number of bytes consumed, or zero if there were none to read.
 */
static size_t decodeVarint(const uint8_t *data, size_t available, uint32_t &value)
{
    size_t i;

    value = 0;

    for (i = 0; i < sizeof(value) && i < available; i++)
    {
        value |= static_cast<uint32_t>(data[i] & 0x7f) << (i * 7);

        if (!(data[i] & 0x80))
        {
            return i + 1;
        }
    }

    // Ran out of input, or the length claimed more continuation bytes than the encoding allows
    return 0;
}

GipDevice::GipDevice(SendPacket sendPacket) : sendPacket(sendPacket) {}

bool GipDevice::handlePacket(const Bytes &packet)
{
    // Ignore invalid packets
    if (packet.size() < sizeof(Frame))
    {
        return true;
    }

    const Frame *frame = packet.toStruct<Frame>();

    // Fragmented messages carry a longer header - a multi-byte length and a total-length-or-offset
    // field after it - so they cannot be read through the fixed Frame struct. Only metadata and
    // security arrive this way; input, status and the rest never do, so the common path below is
    // left exactly as it was rather than routed through the reassembly code.
    if (frame->type & (TYPE_CHUNK | TYPE_CHUNK_START))
    {
        const uint8_t *raw = packet.raw();
        // Command, flags and sequence are fixed width; the length field starts after them
        size_t offset = 3;
        uint32_t length = 0;
        uint32_t chunkOffset = 0;

        size_t consumed = decodeVarint(raw + offset, packet.size() - offset, length);
        if (consumed == 0)
        {
            Log::debug("Malformed chunk length");

            return true;
        }
        offset += consumed;

        if (frame->type & TYPE_CHUNK)
        {
            consumed = decodeVarint(raw + offset, packet.size() - offset, chunkOffset);
            if (consumed == 0)
            {
                Log::debug("Malformed chunk offset");

                return true;
            }
            offset += consumed;
        }

        if (packet.size() < offset + length)
        {
            Log::debug("Truncated chunk payload");

            return true;
        }

        return handleChunk(*frame, length, chunkOffset, Bytes(packet, offset));
    }

    if (frame->type & TYPE_ACK && !acknowledgePacket(*frame))
    {
        Log::error("Failed to acknowledge packet");

        return false;
    }

    // Ignore packets from accessories
    if (frame->deviceId > 0)
    {
        return true;
    }

    // An unfragmented message may still carry the multi-byte length encoding: the single byte in
    // Frame tops out at 127, and MS-GIPUSB 2.2.10.4 has anything longer use the varint form with
    // the continuation bit set. Nothing this driver handles today is that long - input, status,
    // announce, guide and serial are all well under it - so this exists for correctness rather
    // than for a message we have seen. Reading such a header through Frame alone would take the
    // first length byte literally and dispatch against a wrong length.
    uint32_t payloadLength = frame->length;
    size_t headerLength = sizeof(Frame);

    if (frame->length & 0x80)
    {
        size_t consumed = decodeVarint(packet.raw() + 3, packet.size() - 3, payloadLength);

        if (consumed == 0)
        {
            Log::debug("Malformed payload length");

            return true;
        }

        headerLength = 3 + consumed;

        if (packet.size() < headerLength)
        {
            return true;
        }
    }

    const Bytes data(packet, headerLength);

    // Data is 32-bit aligned, check for minimum size
    if (
        frame->command == CMD_ANNOUNCE &&
        payloadLength == sizeof(AnnounceData) &&
        data.size() >= sizeof(AnnounceData)
    ) {
        deviceAnnounced(
            frame->deviceId,
            data.toStruct<AnnounceData>()
        );
    }

    // Newer controllers send a larger status packet
    // MS-GIPUSB Table 26 allows three status payload sizes: 0x01 (legacy, deprecated), 0x04, and
    // 0x23-0x37 (extended). Section 3.1.5.5.2.2 says all new GIP devices MUST use the extended
    // form, so testing for exactly 0x04 dropped their status messages whole - no battery, no fault
    // events. The leading four bytes are the same in both, so parse those and ignore the tail.
    else if (
        frame->command == CMD_STATUS &&
        payloadLength >= sizeof(StatusData) &&
        data.size() >= sizeof(StatusData)
    ) {
        statusReceived(
            frame->deviceId,
            data.toStruct<StatusData>()
        );
    }

    else if (
        frame->command == CMD_GUIDE_BTN &&
        payloadLength == sizeof(GuideButtonData) &&
        data.size() >= sizeof(GuideButtonData)
    ) {
        guideButtonPressed(data.toStruct<GuideButtonData>());
    }

    else if (
        frame->command == CMD_AUTHENTICATE &&
        data.size() >= payloadLength
    ) {
        authReceived(data.raw(), payloadLength);
    }

    else if (
        frame->command == CMD_SERIAL_NUM &&
        payloadLength == sizeof(SerialData) &&
        data.size() >= sizeof(SerialData)
    ) {
        serialNumberReceived(data.toStruct<SerialData>());
    }

    // Elite controllers send a larger input packet
    // The button remapping is done in hardware
    // The "non-remapped" input is appended to the packet
    else if (
        frame->command == CMD_INPUT &&
        payloadLength >= sizeof(InputData) &&
        data.size() >= sizeof(InputData)
    ) {
        inputReceived(data.toStruct<InputData>());
    }

    // Ignore any unknown packets
    return true;
}

bool GipDevice::setDeviceState(uint8_t id, DeviceState state)
{
    Frame frame = {};

    frame.command = CMD_SET_DEVICE_STATE;
    frame.deviceId = id;
    frame.type = TYPE_REQUEST;
    frame.sequence = getSequence();
    frame.length = sizeof(uint8_t);

    Bytes out;

    out.append(frame);
    out.append(static_cast<uint8_t>(state));

    return sendPacket(out);
}

bool GipDevice::performRumble(RumbleData rumble)
{
    Frame frame = {};

    frame.command = CMD_RUMBLE;
    frame.type = TYPE_COMMAND;
    frame.sequence = getSequence();
    frame.length = sizeof(rumble);

    Bytes out;

    out.append(frame);
    out.append(rumble);

    return sendPacket(out);
}

bool GipDevice::setLedMode(LedModeData mode)
{
    Frame frame = {};

    frame.command = CMD_LED_MODE;
    frame.type = TYPE_REQUEST;
    frame.sequence = getSequence();
    frame.length = sizeof(mode);

    Bytes out;

    out.append(frame);
    out.append(mode);

    return sendPacket(out);
}

bool GipDevice::requestSerialNumber()
{
    Frame frame = {};

    frame.command = CMD_SERIAL_NUM;
    frame.type = TYPE_REQUEST | TYPE_ACK;
    frame.sequence = getSequence();
    frame.length = sizeof(uint8_t);

    Bytes out;

    // The purpose of other values is still to be discovered
    out.append(frame);
    out.append(static_cast<uint8_t>(0x04));

    return sendPacket(out);
}

/*
 * Accumulates one fragment of a fragmented message (MS-GIPUSB 3.1.5.2).
 *
 * The first fragment carries InitFrag and puts the *total* message length in the field where later
 * fragments put their offset - the spec calls it TLO for that reason. The transfer ends with an
 * empty fragment, at which point the reassembled message is dispatched.
 */
bool GipDevice::handleChunk(const Frame &frame, uint32_t length, uint32_t offset,
                            const Bytes &data)
{
    if (frame.type & TYPE_CHUNK_START)
    {
        // On the first fragment the offset field is the total length of what is coming
        if (offset > CHUNK_BUFFER_MAX_LENGTH)
        {
            Log::error("Chunked message too large: %u bytes", offset);

            return true;
        }

        if (chunkActive)
        {
            Log::debug("Discarding incomplete chunked message");
        }

        chunkBuffer.assign(offset, 0);
        chunkLength = offset;
        chunkCommand = frame.command;
        chunkActive = true;

        // The first fragment's own payload starts at zero
        offset = 0;
    }

    if (!chunkActive)
    {
        // A stray completion for a transfer we never saw the start of. Older controllers send
        // these spontaneously, so it is not worth logging as an error.
        return true;
    }

    if (frame.command != chunkCommand)
    {
        Log::error("Conflicting chunked message");

        chunkActive = false;
        chunkBuffer.clear();

        return true;
    }

    uint32_t received = offset + length;

    if (received > chunkLength || received < offset)
    {
        Log::error("Chunk overruns its message");

        chunkActive = false;
        chunkBuffer.clear();

        return true;
    }

    if (length > 0)
    {
        // Acknowledge when asked, and always on the final fragment: the sender waits for that one
        // before considering the transfer done, whether or not it set the flag.
        bool last = (received == chunkLength);

        if ((frame.type & TYPE_ACK) || last)
        {
            if (!acknowledgeChunk(frame, received, chunkLength - received))
            {
                Log::error("Failed to acknowledge chunk");

                return false;
            }
        }

        if (data.size() < length)
        {
            return true;
        }

        std::copy(data.begin(), data.begin() + length, chunkBuffer.begin() + offset);

        return true;
    }

    // An empty fragment ends the transfer
    dispatchChunked(chunkCommand, chunkBuffer.data(), chunkLength);

    chunkActive = false;
    chunkBuffer.clear();

    return true;
}

/*
 * Handles a message that arrived fragmented. Only metadata is acted on; anything else is noted
 * and dropped, which is the same as before this reassembly existed, only now visibly.
 */
void GipDevice::dispatchChunked(uint8_t command, const uint8_t *data, size_t length)
{
    if (command == CMD_IDENTIFY && length >= sizeof(IdentifyData))
    {
        const IdentifyData *identify = reinterpret_cast<const IdentifyData *>(data);

        // Offsets are relative to the end of the opening blob, not the start of the message
        identifyReceived(identify, data + sizeof(identify->unknown),
                         length - sizeof(identify->unknown));

        return;
    }

    if (command == CMD_AUTHENTICATE)
    {
        authReceived(data, length);

        return;
    }

    Log::debug("Ignoring chunked message: command 0x%02x, %zu bytes", command, length);
}

/*
 * Acknowledges a fragment, reporting how much of the message has arrived contiguously and how much
 * is still outstanding, so the sender knows whether to retransmit (MS-GIPUSB 3.1.5.1).
 *
 * Distinct from acknowledgePacket() below, which answers a single unfragmented message and has no
 * progress to report.
 */
bool GipDevice::acknowledgeChunk(const Frame &frame, uint32_t received, uint32_t remaining)
{
    Frame header = {};

    header.command = CMD_ACKNOWLEDGE;
    header.deviceId = frame.deviceId;
    header.type = TYPE_REQUEST;
    header.sequence = frame.sequence;
    header.length = sizeof(AcknowledgeData);

    AcknowledgeData ack = {};

    ack.command = frame.command;
    ack.options = frame.deviceId | (TYPE_REQUEST << 4);
    ack.received = static_cast<uint16_t>(received);
    ack.remaining = static_cast<uint16_t>(remaining);

    Bytes out;

    out.append(header);
    out.append(ack);

    return sendPacket(out);
}

/*
 * Big-endian, because the auth headers are - the rest of GIP is little-endian, so this is written
 * out rather than reusing anything.
 */
static void putBigEndian16(uint8_t *buf, uint16_t value)
{
    buf[0] = static_cast<uint8_t>(value >> 8);
    buf[1] = static_cast<uint8_t>(value);
}

bool GipDevice::sendAuthHostHello()
{
    // Handshake header, data header, 32 random bytes, two unknown 4-byte fields, trailer. The
    // captured Windows exchange sends exactly this, 58 bytes, so the shape is not inferred.
    const size_t dataLength = sizeof(AuthDataHeader) + AUTH_RANDOM_LENGTH + 4 + 4;
    const size_t total = sizeof(AuthHandshakeHeader) + dataLength + AUTH_TRAILER_LENGTH;

    Bytes payload(total);
    uint8_t *raw = payload.raw();

    std::fill(raw, raw + total, 0);

    raw[0] = 0x00;                                          // context: handshake
    raw[1] = AUTH_OPT_ACKNOWLEDGE | AUTH_OPT_FROM_HOST;
    raw[2] = 0x00;                                          // no error
    raw[3] = AUTH_HOST_HELLO;
    putBigEndian16(raw + 4, static_cast<uint16_t>(dataLength));

    raw[6] = AUTH_HOST_HELLO;
    raw[7] = 0x01;                                          // security protocol major version
    putBigEndian16(raw + 8, static_cast<uint16_t>(dataLength - sizeof(AuthDataHeader)));

    // The client mixes this into the master secret. Only unpredictability matters, so the default
    // engine seeded from the system source is enough - nothing here is verifying anyone.
    std::random_device source;

    for (size_t i = 0; i < AUTH_RANDOM_LENGTH; i++)
    {
        raw[sizeof(AuthHandshakeHeader) + sizeof(AuthDataHeader) + i] =
                static_cast<uint8_t>(source());
    }

    Frame frame = {};

    frame.command = CMD_AUTHENTICATE;
    frame.type = TYPE_REQUEST | TYPE_ACK;
    frame.sequence = securitySequence++;

    if (securitySequence == 0x00)
    {
        securitySequence = 0x01;
    }

    frame.length = static_cast<uint8_t>(total);

    Bytes out;

    out.append(frame);
    out.append(payload);

    return sendPacket(out);
}

bool GipDevice::requestAuthPacket(uint8_t command, uint16_t length)
{
    // A request carries the handshake header and the trailer, and no data header: the length field
    // says how much the device should send back.
    const size_t total = sizeof(AuthHandshakeHeader) + AUTH_TRAILER_LENGTH;

    Bytes payload(total);
    uint8_t *raw = payload.raw();

    std::fill(raw, raw + total, 0);

    raw[0] = 0x00;
    raw[1] = AUTH_OPT_REQUEST | AUTH_OPT_FROM_HOST;
    raw[2] = 0x00;
    raw[3] = command;
    putBigEndian16(raw + 4, length);

    Frame frame = {};

    frame.command = CMD_AUTHENTICATE;
    frame.type = TYPE_REQUEST | TYPE_ACK;
    frame.sequence = securitySequence++;

    if (securitySequence == 0x00)
    {
        securitySequence = 0x01;
    }

    frame.length = static_cast<uint8_t>(total);

    Bytes out;

    out.append(frame);
    out.append(payload);

    return sendPacket(out);
}

bool GipDevice::requestIdentify()
{
    Frame frame = {};

    frame.command = CMD_IDENTIFY;
    frame.type = TYPE_REQUEST;
    frame.sequence = getSequence();
    frame.length = 0;

    Bytes out;

    out.append(frame);

    return sendPacket(out);
}

bool GipDevice::acknowledgePacket(Frame frame)
{
    Frame header = {};

    header.command = CMD_ACKNOWLEDGE;
    header.deviceId = frame.deviceId;
    header.type = TYPE_REQUEST;
    header.sequence = frame.sequence;
    header.length = sizeof(header) + 5;

    frame.type = TYPE_REQUEST;
    frame.sequence = frame.length;
    frame.length = 0;

    Bytes out;

    out.append(header);
    out.pad(1);
    out.append(frame);
    out.pad(4);

    return sendPacket(out);
}

uint8_t GipDevice::getSequence(bool accessory)
{
    if (accessory)
    {
        // Zero is an invalid sequence number
        if (accessorySequence == 0x00)
        {
            accessorySequence = 0x01;
        }

        return accessorySequence++;
    }

    if (sequence == 0x00)
    {
        sequence = 0x01;
    }

    return sequence++;
}
