/*
 * Android port addition - not part of upstream xow.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

#include "crypto.h"
#include "jni.h"
#include "log.h"

namespace
{
    // Resolved once by init(), from a thread the JVM created. Global references, so the class stays
    // loaded and the method IDs below stay valid for the life of the process.
    JavaVM *vm = nullptr;
    jclass cryptoClass = nullptr;
    jmethodID sha256Method = nullptr;
    jmethodID hmacMethod = nullptr;
    jmethodID randomMethod = nullptr;
    jmethodID rsaMethod = nullptr;
    jmethodID ecdhMethod = nullptr;

    jbyteArray toJava(JNIEnv *env, const uint8_t *data, size_t length)
    {
        jbyteArray array = env->NewByteArray(static_cast<jsize>(length));

        if (array == nullptr)
        {
            return nullptr;
        }

        env->SetByteArrayRegion(array, 0, static_cast<jsize>(length),
                                reinterpret_cast<const jbyte *>(data));

        return array;
    }

    std::vector<uint8_t> fromJava(JNIEnv *env, jbyteArray array)
    {
        if (array == nullptr)
        {
            return {};
        }

        jsize length = env->GetArrayLength(array);
        std::vector<uint8_t> out(static_cast<size_t>(length));

        env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte *>(out.data()));

        return out;
    }

    /*
     * A pending exception poisons every later JNI call on this thread, and the read thread makes
     * one per input report. Clearing it here keeps a crypto failure from taking input with it.
     */
    bool failed(JNIEnv *env, const char *what)
    {
        if (!env->ExceptionCheck())
        {
            return false;
        }

        env->ExceptionClear();
        Log::error("Crypto: %s threw", what);

        return true;
    }
}

bool GipCrypto::init(JNIEnv *env)
{
    if (cryptoClass != nullptr)
    {
        return true;
    }

    if (env->GetJavaVM(&vm) != JNI_OK)
    {
        Log::error("Crypto: no JavaVM");

        return false;
    }

    jclass local = env->FindClass("com/limelight/binding/input/driver/GipCrypto");

    if (local == nullptr || env->ExceptionCheck())
    {
        env->ExceptionClear();
        Log::error("Crypto: GipCrypto not found");

        return false;
    }

    cryptoClass = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);

    sha256Method = env->GetStaticMethodID(cryptoClass, "sha256", "([B)[B");
    hmacMethod = env->GetStaticMethodID(cryptoClass, "hmacSha256", "([B[B)[B");
    randomMethod = env->GetStaticMethodID(cryptoClass, "randomBytes", "(I)[B");
    rsaMethod = env->GetStaticMethodID(cryptoClass, "rsaEncrypt", "([B[B)[B");
    ecdhMethod = env->GetStaticMethodID(cryptoClass, "ecdhP256", "([B)[B");

    if (sha256Method == nullptr || hmacMethod == nullptr ||
        randomMethod == nullptr || rsaMethod == nullptr || ecdhMethod == nullptr)
    {
        env->ExceptionClear();
        Log::error("Crypto: failed to resolve GipCrypto methods");

        return false;
    }

    Log::info("Crypto: GipCrypto ready");

    return true;
}

namespace
{
    /* Every entry point needs the same env and the same "was init() called" check. */
    JNIEnv *ready()
    {
        if (cryptoClass == nullptr)
        {
            Log::error("Crypto: used before init()");

            return nullptr;
        }

        return getAttachedEnv(vm);
    }
}

std::vector<uint8_t> GipCrypto::sha256(const uint8_t *data, size_t length)
{
    JNIEnv *env = ready();

    if (env == nullptr)
    {
        return {};
    }

    jbyteArray in = toJava(env, data, length);

    if (in == nullptr)
    {
        return {};
    }

    auto result = static_cast<jbyteArray>(
            env->CallStaticObjectMethod(cryptoClass, sha256Method, in));

    std::vector<uint8_t> out = failed(env, "sha256") ? std::vector<uint8_t>() : fromJava(env, result);

    env->DeleteLocalRef(in);

    if (result != nullptr)
    {
        env->DeleteLocalRef(result);
    }

    return out;
}

std::vector<uint8_t> GipCrypto::hmacSha256(const uint8_t *key, size_t keyLength,
                                           const uint8_t *data, size_t length)
{
    JNIEnv *env = ready();

    if (env == nullptr)
    {
        return {};
    }

    jbyteArray jkey = toJava(env, key, keyLength);
    jbyteArray jdata = toJava(env, data, length);

    if (jkey == nullptr || jdata == nullptr)
    {
        return {};
    }

    auto result = static_cast<jbyteArray>(
            env->CallStaticObjectMethod(cryptoClass, hmacMethod, jkey, jdata));

    std::vector<uint8_t> out = failed(env, "hmac") ? std::vector<uint8_t>() : fromJava(env, result);

    env->DeleteLocalRef(jkey);
    env->DeleteLocalRef(jdata);

    if (result != nullptr)
    {
        env->DeleteLocalRef(result);
    }

    return out;
}

std::vector<uint8_t> GipCrypto::randomBytes(size_t count)
{
    JNIEnv *env = ready();

    if (env == nullptr)
    {
        return {};
    }

    auto result = static_cast<jbyteArray>(
            env->CallStaticObjectMethod(cryptoClass, randomMethod, static_cast<jint>(count)));

    std::vector<uint8_t> out = failed(env, "randomBytes") ? std::vector<uint8_t>() : fromJava(env, result);

    if (result != nullptr)
    {
        env->DeleteLocalRef(result);
    }

    return out;
}

std::vector<uint8_t> GipCrypto::rsaEncrypt(const uint8_t *publicKey, size_t keyLength,
                                           const uint8_t *data, size_t length)
{
    JNIEnv *env = ready();

    if (env == nullptr)
    {
        return {};
    }

    jbyteArray jkey = toJava(env, publicKey, keyLength);
    jbyteArray jdata = toJava(env, data, length);

    if (jkey == nullptr || jdata == nullptr)
    {
        return {};
    }

    auto result = static_cast<jbyteArray>(
            env->CallStaticObjectMethod(cryptoClass, rsaMethod, jkey, jdata));

    std::vector<uint8_t> out = failed(env, "rsaEncrypt") ? std::vector<uint8_t>() : fromJava(env, result);

    env->DeleteLocalRef(jkey);
    env->DeleteLocalRef(jdata);

    if (result != nullptr)
    {
        env->DeleteLocalRef(result);
    }

    return out;
}

std::vector<uint8_t> GipCrypto::ecdhP256(const uint8_t *peerKey, size_t length)
{
    JNIEnv *env = ready();

    if (env == nullptr)
    {
        return {};
    }

    jbyteArray in = toJava(env, peerKey, length);

    if (in == nullptr)
    {
        return {};
    }

    auto result = static_cast<jbyteArray>(
            env->CallStaticObjectMethod(cryptoClass, ecdhMethod, in));

    std::vector<uint8_t> out = failed(env, "ecdhP256") ? std::vector<uint8_t>() : fromJava(env, result);

    env->DeleteLocalRef(in);

    if (result != nullptr)
    {
        env->DeleteLocalRef(result);
    }

    return out;
}
