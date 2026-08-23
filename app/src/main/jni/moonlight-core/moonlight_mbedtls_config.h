/*
 * Minimal Mbed TLS configuration for Moonlight.
 *
 * moonlight-common-c only uses Mbed TLS for symmetric crypto on the control
 * stream (see PlatformCrypto.c): AES-CBC, AES-GCM, and random data. Since
 * upstream 518b244 that all goes through the PSA API rather than the legacy
 * mbedtls_cipher_* one. No TLS, X.509, or public-key code is required - HTTPS
 * to the host is handled on the Java side by OkHttp and BouncyCastle.
 *
 * Everything not listed here stays disabled, which is what keeps this build
 * substantially smaller than the OpenSSL static libraries it replaces.
 */

#ifndef MOONLIGHT_MBEDTLS_CONFIG_H
#define MOONLIGHT_MBEDTLS_CONFIG_H

/*
 * Ciphers used by PltEncryptMessage()/PltDecryptMessage(). These are the legacy
 * feature macros, not PSA_WANT_* ones: without MBEDTLS_PSA_CRYPTO_CONFIG the PSA
 * layer derives its own capabilities from these, so MBEDTLS_CIPHER_MODE_CBC plus
 * MBEDTLS_CIPHER_PADDING_PKCS7 below is what makes PSA_ALG_CBC_PKCS7 exist, and
 * MBEDTLS_GCM_C is what makes PSA_ALG_GCM exist. Dropping either does not fail to
 * build - the algorithm just stops being supported at runtime.
 */
#define MBEDTLS_AES_C
#define MBEDTLS_CIPHER_C
#define MBEDTLS_CIPHER_MODE_CBC
#define MBEDTLS_GCM_C

/*
 * Required for AES-CBC, which carries the audio stream. The GameStream protocol
 * pads CBC with PKCS7, so PltDecryptMessage() asks for PSA_ALG_CBC_PKCS7; without
 * this define that algorithm is not compiled in and every audio packet fails to
 * decrypt, giving perfect video and total silence. Video, control and RTSP are all
 * AES-GCM and would keep working, which is what made this hard to spot the first
 * time it happened (see HARDWARE_TESTING.md section 5).
 */
#define MBEDTLS_CIPHER_PADDING_PKCS7

/*
 * PSA is the only crypto API PlatformCrypto.c uses now. Without this the legacy
 * headers still resolve, so the build compiles and then fails at link with a dozen
 * undefined psa_* symbols.
 */
#define MBEDTLS_PSA_CRYPTO_C

/*
 * Without this, every PSA entry point defensively copies its caller's buffers: it
 * mbedtls_calloc()s a zeroed block, memcpy()s the argument in, runs, copies the result
 * back and frees. That is five heap allocations per video packet (set_nonce 1, aead_update
 * 2, aead_verify 2) and four per audio packet (set_iv 1, cipher_update 2, cipher_finish 1),
 * on the receive threads, plus a full payload copy in and out - which is more copying than
 * the USE_MBEDTLS_CRYPTO_EXT memmove that moving to PSA was supposed to remove. Measured on
 * the Shield, it is 1.6 us of the 2.1 us that PSA adds to a 1392-byte video packet, and
 * 0.9 us of the 1.7 us it adds to an audio packet.
 *
 * The copying exists to protect against a caller mutating a buffer mid-call across a trust
 * boundary - PSA-as-a-service, where arguments live in shared memory. Nothing here is such a
 * caller: every buffer PlatformCrypto.c passes is ordinary process-local heap or stack.
 *
 * The one constraint it imposes is that input and output buffers must not overlap. They do
 * not at any call site: VideoStream decrypts the receive buffer into a separate one,
 * AudioStream into a stack buffer, ControlStream encrypts a stack tempBuffer into the enet
 * packet, InputStream and RtspConnection likewise. Check this again before adding a call
 * site that decrypts in place.
 */
#define MBEDTLS_PSA_ASSUME_EXCLUSIVE_BUFFERS

/*
 * PSA keeps its key slots and init state in file-scope globals, and every guard
 * around them in psa_crypto.c and psa_crypto_slot_management.c is compiled out
 * unless MBEDTLS_THREADING_C is set. moonlight-common-c first touches crypto from
 * whichever stream thread gets there first - AudioStream, VideoStream, ControlStream,
 * InputStream and RtspConnection each create their own context and import their key
 * lazily on their own thread - so the psa_crypto_init() and psa_import_key() calls at
 * stream start genuinely can race. The legacy code this replaced had one unsynchronised
 * global, the CTR-DRBG, which upstream had marked "FIXME: This is not thread safe";
 * PSA has more of them, so the mutexes stop being optional.
 *
 * Costs two uncontended pthread mutex operations per packet on the audio and video
 * receive threads, because both re-enter setup for every packet and setup takes the
 * key slot lock. Neither thread is the decode or render path.
 */
#define MBEDTLS_THREADING_C
#define MBEDTLS_THREADING_PTHREAD

/*
 * Entropy source. Mbed TLS defaults MBEDTLS_PLATFORM_DEV_RANDOM to "/dev/random"
 * (platform.h), and only bypasses it via the getrandom() syscall when __GLIBC__ is
 * defined - which it is not on bionic, so this build reads the device file.
 *
 * On the Shield's 4.9 kernel /dev/random is the *blocking* pool: a read waits until
 * the kernel's entropy estimate covers it. entropy_gather_internal() asks for 128
 * bytes (1024 bits) per poll, and the box idles at a few hundred bits, so the read
 * blocks for as long as it takes the pool to refill.
 *
 * That is invisible until something seeds a DRBG on a latency-critical path, and PSA
 * does exactly that: psa_crypto_init() seeds the CTR-DRBG, PltEncryptMessage() calls
 * psa_crypto_init() lazily, and the first caller is sealRtspMessage() encrypting the
 * OPTIONS request that opens the RTSP handshake. The connection thread then sits in
 * read() and the client shows a spinner until the pool refills. The pre-PSA code never
 * hit this: it seeded the CTR-DRBG only inside PltGenerateRandomData(), which the RTSP
 * path does not call. Captured stack in psa-freeze-investigation/.
 *
 * /dev/urandom is the correct source: on Android the CRNG is seeded during early boot,
 * long before an app runs, and it never blocks. It is not weaker - the blocking pool
 * buys nothing here.
 */
#define MBEDTLS_PLATFORM_DEV_RANDOM "/dev/urandom"

/* Random data for PltGenerateRandomData(), via psa_generate_random() */
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
