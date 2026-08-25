package com.limelight.binding.input.driver;

import com.limelight.LimeLog;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Crypto primitives for the GIP security exchange, called from the native driver.
 *
 * <p>These live in Java because Android's providers already have every one of them, where doing it
 * natively would mean enabling bignum, RSA and elliptic curves in the mbedtls config shared with
 * {@code moonlight-core} — growing both libraries by a couple of hundred kilobytes for a handshake
 * that runs once when a controller connects. Nothing here is on a per-frame or per-report path, so
 * the JNI hop costs nothing worth measuring.
 *
 * <p>Every method is static and pure, so the native side needs no instance and this is usable from
 * any transport — the wireless adapter today, a cabled pad later.
 *
 * <p><b>The class reference must be cached from a Java thread.</b> The driver's read threads attach
 * to the JVM themselves, and a thread attached that way gets the system class loader, which cannot
 * find application classes — {@code FindClass} for this class fails there. The native side resolves
 * it once in {@code JNI_OnLoad}, which runs on whichever thread called
 * {@code System.loadLibrary("xow-driver")} and so has the application class loader.
 *
 * <p>Nothing in Java references this class, so it survives only because
 * {@code proguard-rules.pro} keeps the whole {@code driver} package. The native side names it as
 * a string in {@code xow_driver_jni.cpp}; renaming or moving it breaks the handshake at runtime,
 * not at compile time.
 */
final class GipCrypto {
    /** The v2 handshake's curve, and the raw X‖Y key length it carries. */
    private static final String P256_CURVE = "secp256r1";
    private static final int P256_KEY_LENGTH = 64;

    private GipCrypto() {
    }

    /** @return SHA-256 of {@code data}, or null if the digest is unavailable */
    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            LimeLog.warning("GipCrypto: SHA-256 failed: " + e);
            return null;
        }
    }

    /** @return HMAC-SHA256 of {@code data} under {@code key}, or null on failure */
    static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            LimeLog.warning("GipCrypto: HMAC-SHA256 failed: " + e);
            return null;
        }
    }

    /** @return {@code count} cryptographically random bytes */
    static byte[] randomBytes(int count) {
        byte[] out = new byte[count];
        new SecureRandom().nextBytes(out);
        return out;
    }

    /**
     * Encrypts under an RSA public key with PKCS#1 v1.5 padding, which is what the handshake's
     * pre-master secret uses.
     *
     * @param publicKey DER {@code RSAPublicKey}: a SEQUENCE of modulus and exponent, as lifted
     *                  from the controller's certificate. Not a SubjectPublicKeyInfo, so it cannot
     *                  go through {@code X509EncodedKeySpec} — see {@link #parsePublicKey}.
     * @return the ciphertext, 256 bytes for a 2048-bit key, or null if the key or input is refused
     */
    static byte[] rsaEncrypt(byte[] publicKey, byte[] plaintext) {
        try {
            PublicKey key = parsePublicKey(publicKey);
            if (key == null) {
                return null;
            }

            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            LimeLog.warning("GipCrypto: RSA encrypt failed: " + e);
            return null;
        }
    }

    /**
     * Performs the v2 handshake's ECDH exchange over NIST P-256.
     *
     * <p>Generates an ephemeral key pair, agrees a shared secret with the device's public key, and
     * returns both halves the caller needs. Done in one call because the private key has no life
     * beyond it — the device's public key is already in hand when the host sends its own, so there
     * is nothing to keep between the two steps and no key material to store.
     *
     * <p>Keys are raw affine coordinates, 32 bytes of X then 32 of Y, with no {@code 0x04}
     * uncompressed-point prefix and no DER — that is what the Linux ECDH implementation xone uses
     * emits, and what the protocol carries.
     *
     * @param peerPublicKey the device's 64-byte public key
     * @return 96 bytes: our 64-byte public key, then the SHA-256 of the agreed secret, which is
     *         what the PRF takes as its own secret. Null if the key is refused or the curve is
     *         unavailable.
     */
    static byte[] ecdhP256(byte[] peerPublicKey) {
        if (peerPublicKey == null || peerPublicKey.length != P256_KEY_LENGTH) {
            LimeLog.warning("GipCrypto: ECDH needs a 64-byte peer key");
            return null;
        }

        try {
            ECParameterSpec params = p256Parameters();

            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec(P256_CURVE));
            KeyPair pair = generator.generateKeyPair();

            // The agreed secret is the X coordinate alone, which the protocol then hashes rather
            // than using directly.
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(pair.getPrivate());
            agreement.doPhase(decodePoint(peerPublicKey, params), true);

            byte[] hashed = sha256(agreement.generateSecret());
            if (hashed == null) {
                return null;
            }

            byte[] out = new byte[P256_KEY_LENGTH + hashed.length];
            encodePoint(((ECPublicKey) pair.getPublic()).getW(), out);
            System.arraycopy(hashed, 0, out, P256_KEY_LENGTH, hashed.length);

            return out;
        } catch (Exception e) {
            LimeLog.warning("GipCrypto: ECDH failed: " + e);
            return null;
        }
    }

    /** @return the named curve's parameters, needed to rebuild a bare point into a key */
    private static ECParameterSpec p256Parameters() throws Exception {
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec(P256_CURVE));
        return params.getParameterSpec(ECParameterSpec.class);
    }

    /** Rebuilds a public key from the raw X‖Y the protocol carries. */
    private static PublicKey decodePoint(byte[] raw, ECParameterSpec params) throws Exception {
        int half = P256_KEY_LENGTH / 2;
        // Positive, so a coordinate with the high bit set is not read as negative
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 0, half));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, half, P256_KEY_LENGTH));

        return KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), params));
    }

    /**
     * Writes a point as raw X‖Y into the first 64 bytes of {@code out}.
     *
     * <p>Each coordinate is left-padded to exactly 32 bytes. {@code BigInteger.toByteArray} gives
     * neither a fixed width nor a guaranteed absence of a leading sign byte, so copying it in
     * directly would misalign the key whenever a coordinate happened to be short or signed.
     */
    private static void encodePoint(ECPoint point, byte[] out) {
        int half = P256_KEY_LENGTH / 2;

        writeCoordinate(point.getAffineX(), out, 0, half);
        writeCoordinate(point.getAffineY(), out, half, half);
    }

    private static void writeCoordinate(BigInteger value, byte[] out, int offset, int width) {
        byte[] bytes = value.toByteArray();
        int from = Math.max(0, bytes.length - width);
        int length = bytes.length - from;

        System.arraycopy(bytes, from, out, offset + width - length, length);
    }

    /**
     * Reads a DER {@code RSAPublicKey} into a key object.
     *
     * <p>Only enough DER to walk two INTEGERs is implemented, deliberately. The blob comes out of a
     * certificate that Microsoft issues in violation of RFC 5280 — empty subject, no
     * subjectAltName — so a real X.509 parser rejects the certificate it was taken from, and the
     * public key itself is a bare PKCS#1 structure rather than the SubjectPublicKeyInfo that
     * {@code KeyFactory} would otherwise accept.
     *
     * @return the key, or null if the blob is not the expected shape
     */
    private static PublicKey parsePublicKey(byte[] der) {
        try {
            Reader reader = new Reader(der);

            if (!reader.enterSequence()) {
                return null;
            }

            BigInteger modulus = reader.readInteger();
            BigInteger exponent = reader.readInteger();

            if (modulus == null || exponent == null) {
                return null;
            }

            return KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(modulus, exponent));
        } catch (Exception e) {
            LimeLog.warning("GipCrypto: public key parse failed: " + e);
            return null;
        }
    }

    /** Minimal DER walker: just the tag/length decoding the two INTEGERs above need. */
    private static final class Reader {
        private final byte[] data;
        private int pos;

        Reader(byte[] data) {
            this.data = data;
        }

        /** Steps into a SEQUENCE, positioning at its first element. */
        boolean enterSequence() {
            return pos < data.length && data[pos++] == 0x30 && readLength() >= 0;
        }

        BigInteger readInteger() {
            if (pos >= data.length || data[pos++] != 0x02) {
                return null;
            }

            int length = readLength();
            if (length < 0 || pos + length > data.length) {
                return null;
            }

            // Positive: the leading zero byte DER uses as a sign bit must not become a negative
            byte[] value = new byte[length];
            System.arraycopy(data, pos, value, 0, length);
            pos += length;

            return new BigInteger(1, value);
        }

        /** @return the decoded length, or -1 if it is malformed or longer than this blob can be */
        private int readLength() {
            if (pos >= data.length) {
                return -1;
            }

            int first = data[pos++] & 0xFF;
            if ((first & 0x80) == 0) {
                return first;
            }

            int count = first & 0x7F;
            if (count == 0 || count > 3 || pos + count > data.length) {
                return -1;
            }

            int length = 0;
            for (int i = 0; i < count; i++) {
                length = (length << 8) | (data[pos++] & 0xFF);
            }

            return length;
        }
    }
}
