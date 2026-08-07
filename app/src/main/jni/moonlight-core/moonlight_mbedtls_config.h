/*
 * Minimal Mbed TLS configuration for Moonlight.
 *
 * moonlight-common-c only uses Mbed TLS for symmetric crypto on the control
 * stream (see PlatformCrypto.c): AES-CBC, AES-GCM, and a CTR-DRBG seeded from
 * platform entropy. No TLS, X.509, or public-key code is required - HTTPS to
 * the host is handled on the Java side by OkHttp and BouncyCastle.
 *
 * Everything not listed here stays disabled, which is what keeps this build
 * substantially smaller than the OpenSSL static libraries it replaces.
 */

#ifndef MOONLIGHT_MBEDTLS_CONFIG_H
#define MOONLIGHT_MBEDTLS_CONFIG_H

/* Ciphers used by PltEncryptMessage()/PltDecryptMessage() */
#define MBEDTLS_AES_C
#define MBEDTLS_CIPHER_C
#define MBEDTLS_CIPHER_MODE_CBC
#define MBEDTLS_GCM_C

/*
 * Required for AES-CBC, which carries the audio stream. From Mbed TLS 3.5.0 a CBC
 * context has no padding mode until mbedtls_cipher_set_padding_mode() is called -
 * 2.x and OpenSSL's EVP layer both defaulted to PKCS7, which is what the GameStream
 * protocol uses. Without this define that call has no PKCS7 case compiled in and
 * returns MBEDTLS_ERR_CIPHER_FEATURE_UNAVAILABLE, so every audio packet fails to
 * decrypt and the stream plays silently.
 *
 * Paired with patches/moonlight-common-c/0001-mbedtls3-cbc-pkcs7-and-iv.patch, which
 * adds the call itself. Neither half works alone.
 */
#define MBEDTLS_CIPHER_PADDING_PKCS7

/* Random data for PltGenerateRandomData() */
#define MBEDTLS_CTR_DRBG_C
#define MBEDTLS_ENTROPY_C
#define MBEDTLS_SHA256_C

/*
 * The entropy accumulator prefers SHA-512. Forcing SHA-256 avoids pulling in
 * a second hash implementation we have no other use for.
 */
#define MBEDTLS_ENTROPY_FORCE_SHA256

/*
 * Platform entropy (getrandom()/dev/urandom on Android) is deliberately left
 * enabled - MBEDTLS_NO_PLATFORM_ENTROPY is NOT defined, so entropy_poll.c
 * seeds the DRBG from the OS.
 */

/* Hardware AES. Both are runtime-detected, so they are safe to compile in. */
#define MBEDTLS_HAVE_ASM
#define MBEDTLS_AESNI_C     /* x86/x86_64 */
#define MBEDTLS_AESCE_C     /* armv8-a crypto extensions */

#endif /* MOONLIGHT_MBEDTLS_CONFIG_H */
