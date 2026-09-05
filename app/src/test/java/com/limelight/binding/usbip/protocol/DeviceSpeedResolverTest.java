package com.limelight.binding.usbip.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.limelight.binding.usbip.protocol.DeviceSpeedResolver.SPEED_FULL;
import static com.limelight.binding.usbip.protocol.DeviceSpeedResolver.SPEED_HIGH;
import static com.limelight.binding.usbip.protocol.DeviceSpeedResolver.SPEED_LOW;
import static com.limelight.binding.usbip.protocol.DeviceSpeedResolver.SPEED_SUPER;
import static com.limelight.binding.usbip.protocol.DeviceSpeedResolver.XFER_BULK;
import static com.limelight.binding.usbip.protocol.DeviceSpeedResolver.XFER_INTERRUPT;
import static com.limelight.binding.usbip.protocol.DeviceSpeedResolver.XFER_ISOCHRONOUS;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for bus-speed inference.
 *
 * <p>Android never reports USB speed, so this is a heuristic over descriptors, and a wrong answer
 * does not fail locally at all — it fails on the Windows host as "This device cannot start.
 * (Code 10)", after the device has already enumerated fine on this box. Nothing about that message
 * points back here, which is why the descriptor shapes of the devices this fork actually forwards
 * are pinned individually below.
 */
class DeviceSpeedResolverTest {

    private static DeviceSpeedResolver.Endpoint endpoint(int type, int maxPacketSize) {
        return new DeviceSpeedResolver.Endpoint(type, maxPacketSize);
    }

    @Nested
    @DisplayName("devices this fork forwards")
    class RealDevices {

        @Test
        @DisplayName("a HID gamepad with 64-byte interrupt endpoints is full speed")
        void gamepadIsFullSpeed() {
            // The shape of a cabled Xbox pad's GIP data interface and of a Switch Pro Controller.
            assertEquals(SPEED_FULL, DeviceSpeedResolver.resolve(64, new DeviceSpeedResolver.Endpoint[]{
                    endpoint(XFER_INTERRUPT, 64),
                    endpoint(XFER_INTERRUPT, 64),
            }));
        }

        @Test
        @DisplayName("a force-feedback wheel with small interrupt endpoints is full speed")
        void wheelIsFullSpeed() {
            assertEquals(SPEED_FULL, DeviceSpeedResolver.resolve(64, new DeviceSpeedResolver.Endpoint[]{
                    endpoint(XFER_INTERRUPT, 16),
                    endpoint(XFER_INTERRUPT, 16),
            }));
        }

        @Test
        @DisplayName("a pad with isochronous audio interfaces is still full speed")
        void padWithAudioIsFullSpeed() {
            // A DualSense or cabled Xbox pad: HID plus the audio interfaces Windows enumerates
            // whether or not anyone wants the audio. The ISO endpoints must not push this to high
            // speed, but they do rule out low speed.
            assertEquals(SPEED_FULL, DeviceSpeedResolver.resolve(64, new DeviceSpeedResolver.Endpoint[]{
                    endpoint(XFER_INTERRUPT, 64),
                    endpoint(XFER_ISOCHRONOUS, 228),
                    endpoint(XFER_ISOCHRONOUS, 64),
            }));
        }

        @Test
        @DisplayName("a low-speed keyboard or mouse stays low speed")
        void keyboardIsLowSpeed() {
            assertEquals(SPEED_LOW, DeviceSpeedResolver.resolve(8, new DeviceSpeedResolver.Endpoint[]{
                    endpoint(XFER_INTERRUPT, 8),
            }));
        }
    }

    @Nested
    @DisplayName("elimination rules")
    class Elimination {

        @Test
        @DisplayName("a bulk endpoint rules out low speed, which has none")
        void bulkRulesOutLowSpeed() {
            assertEquals(SPEED_FULL, DeviceSpeedResolver.resolve(8, new DeviceSpeedResolver.Endpoint[]{
                    endpoint(XFER_BULK, 64),
            }));
        }

        @Test
        @DisplayName("a 512-byte bulk endpoint means high speed")
        void largeBulkMeansHighSpeed() {
            // Full speed caps bulk at 64 bytes, so 512 leaves high speed as the slowest survivor.
            assertEquals(SPEED_HIGH, DeviceSpeedResolver.resolve(64, new DeviceSpeedResolver.Endpoint[]{
                    endpoint(XFER_BULK, 512),
                    endpoint(XFER_BULK, 512),
            }));
        }

        @Test
        @DisplayName("an interrupt endpoint over 64 bytes rules out full speed")
        void largeInterruptRulesOutFullSpeed() {
            assertEquals(SPEED_HIGH, DeviceSpeedResolver.resolve(64, new DeviceSpeedResolver.Endpoint[]{
                    endpoint(XFER_INTERRUPT, 1024),
            }));
        }

        @Test
        @DisplayName("a 512-byte control endpoint means super speed")
        void controlSizeFiveTwelveMeansSuperSpeed() {
            assertEquals(SPEED_SUPER, DeviceSpeedResolver.resolve(512, new DeviceSpeedResolver.Endpoint[]{
                    endpoint(XFER_BULK, 1024),
            }));
        }

        @Test
        @DisplayName("an interrupt endpoint over 8 bytes rules out low speed even at 8-byte control")
        void largeInterruptRulesOutLowSpeed() {
            assertEquals(SPEED_FULL, DeviceSpeedResolver.resolve(8, new DeviceSpeedResolver.Endpoint[]{
                    endpoint(XFER_INTERRUPT, 64),
            }));
        }

        @Test
        @DisplayName("prefers the slowest surviving speed")
        void prefersSlowestSurvivor() {
            // A 64-byte control endpoint permits full and high speed, and nothing else narrows it.
            // Claiming a speed the device cannot sustain is what breaks enumeration; under-claiming
            // only changes how the virtual host controller schedules.
            assertEquals(SPEED_FULL, DeviceSpeedResolver.resolve(64,
                    new DeviceSpeedResolver.Endpoint[0]));
        }

        @Test
        @DisplayName("falls back to the endpoints when bMaxPacketSize0 is not a legal value")
        void toleratesIllegalControlPacketSize() {
            // Rejecting the device over a field used only as a hint would be worse than guessing.
            assertEquals(SPEED_FULL, DeviceSpeedResolver.resolve(0, new DeviceSpeedResolver.Endpoint[]{
                    endpoint(XFER_BULK, 64),
            }));
        }
    }
}
