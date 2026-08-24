/*
 * Android port addition - not part of upstream xow.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

#pragma once

#include <cstddef>
#include <cstdint>
#include <jni.h>
#include <vector>

/*
 * The GIP security handshake's crypto, performed in Java.
 *
 * Android's providers already have SHA-256, HMAC and RSA, where doing this natively would mean
 * enabling bignum, RSA and elliptic curves in the mbedtls configuration shared with
 * moonlight-core - a couple of hundred kilobytes in both libraries for a handshake that runs once
 * per controller connect. Nothing here is on a per-frame or per-report path.
 *
 * init() must be called from a thread the JVM created. The driver's read threads attach
 * themselves, and an attached thread gets the system class loader, which cannot see application
 * classes: FindClass for GipCrypto fails there. Everything else is callable from any attached
 * thread once init() has run.
 */
namespace GipCrypto
{
    bool init(JNIEnv *env);

    /* All return an empty vector on failure, having logged. */
    std::vector<uint8_t> sha256(const uint8_t *data, size_t length);
    std::vector<uint8_t> hmacSha256(const uint8_t *key, size_t keyLength,
                                    const uint8_t *data, size_t length);
    std::vector<uint8_t> randomBytes(size_t count);

    /* Encrypts with PKCS#1 v1.5 under a DER RSAPublicKey. */
    std::vector<uint8_t> rsaEncrypt(const uint8_t *publicKey, size_t keyLength,
                                    const uint8_t *data, size_t length);

    /*
     * The v2 handshake's ECDH over NIST P-256, generating an ephemeral key pair and agreeing with
     * the device's key in one step - the private half is never needed again.
     *
     * Keys are raw affine X||Y, 64 bytes, with no uncompressed-point prefix.
     *
     * @return 96 bytes: our public key, then the SHA-256 of the agreed secret, which is what the
     *         PRF consumes. Empty on failure.
     */
    std::vector<uint8_t> ecdhP256(const uint8_t *peerKey, size_t length);
}
