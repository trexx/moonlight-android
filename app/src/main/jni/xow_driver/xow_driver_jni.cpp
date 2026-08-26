//
// Created by xm1994 on 2024/9/1.
//

#include <iterator>
#include <memory>
#include "utils/crypto.h"

#include <jni.h>
#include "dongle/usb.h"
#include "dongle/dongle.h"
#include "wired/wired.h"

#include "utils/log.h"

namespace
{
    /*
     * Every name this driver needs from the Java side, in one place.
     *
     * The entry points below are bound by JNI_OnLoad through RegisterNatives rather than by the
     * runtime's name mangling, so the consumer's package is four strings here instead of being
     * spelled into twenty exported symbols. Pointing the driver at a different package - or
     * lifting it out of this app entirely - is then an edit to this block and nothing else.
     *
     * It also fails where the failure is legible. A Java declaration that drifts from its native
     * counterpart is rejected at System.loadLibrary(), naming the method; under name mangling the
     * same mistake surfaces as an UnsatisfiedLinkError at the first call, which for the audio and
     * rumble entry points means mid-stream.
     */
    constexpr const char *DONGLE_CLASS = "com/limelight/binding/input/driver/XboxWirelessDongle";
    constexpr const char *CONTROLLER_CLASS = "com/limelight/binding/input/driver/GipController";
    constexpr const char *WIRED_CLASS = "com/limelight/binding/input/driver/XboxWiredGipController";
    constexpr const char *CRYPTO_CLASS = "com/limelight/binding/input/driver/GipCrypto";

    jlong createDriver(JNIEnv *env, jobject thiz, jint fd)
    {
        auto usbDevice = std::make_unique<UsbDevice>(fd);
        JavaVM *jvm = nullptr;
        jint r = env->GetJavaVM(&jvm);
        if(r != JNI_OK || jvm == nullptr) {
            return -1;
        }
        auto dongle = new Dongle(std::move(usbDevice), env->NewGlobalRef(thiz), jvm);
        return (jlong) dongle;
    }

    jboolean startDriver(JNIEnv *env, jobject thiz, jlong handle, jstring fwPath)
    {
        auto *dongle = (Dongle *) handle;
        jboolean copy = false;
        auto cfwPath = env->GetStringUTFChars(fwPath, &copy);
        auto succ = dongle->start(cfwPath);
        env->ReleaseStringUTFChars(fwPath, cfwPath);
        return succ;
    }

    jboolean setPairingModeNative(JNIEnv *env, jobject thiz, jlong handle, jboolean enable)
    {
        auto *dongle = (Dongle *) handle;
        return dongle->setPairing(enable == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
    }

    void stopDriver(JNIEnv *env, jobject thiz, jlong handle)
    {
        auto *dongle = (Dongle *) handle;
        dongle->stop();
    }

    void destroyDriver(JNIEnv *env, jobject thiz, jlong handle)
    {
        auto *dongle = (Dongle *) handle;
        dongle->stop();
        delete dongle;
    }

    void sendRumble(JNIEnv *env, jobject thiz, jlong handle,
                    jshort low_freq_motor, jshort high_freq_motor)
    {
        auto *controller = (Controller *) handle;
        controller->inputRumble(low_freq_motor, high_freq_motor);
    }

    void sendrumbleTriggers(JNIEnv *env, jobject thiz, jlong handle,
                            jshort left_trigger, jshort right_trigger)
    {
        auto *controller = (Controller *) handle;
        controller->inputRumbleTrigger(left_trigger, right_trigger);
    }

    jboolean setAudioEnabledNative(JNIEnv *env, jobject thiz, jlong handle, jboolean enable)
    {
        auto *controller = (Controller *) handle;
        return controller->setAudioEnabled(enable == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
    }

    jboolean hasAudioSupportNative(JNIEnv *env, jobject thiz, jlong handle)
    {
        auto *controller = (Controller *) handle;
        return controller->supportsAudioOut() ? JNI_TRUE : JNI_FALSE;
    }

    void forgetAudioDeviceNative(JNIEnv *env, jobject thiz, jlong handle)
    {
        auto *controller = (Controller *) handle;

        controller->forgetAudioDevice();
    }

    jboolean audioNeedsReplugNative(JNIEnv *env, jobject thiz, jlong handle)
    {
        auto *controller = (Controller *) handle;

        return controller->audioNeedsReplug() ? JNI_TRUE : JNI_FALSE;
    }

    jboolean setAudioVolumeNative(JNIEnv *env, jobject thiz, jlong handle, jint percent)
    {
        auto *controller = (Controller *) handle;

        return controller->setAudioVolume((uint8_t) percent) ? JNI_TRUE : JNI_FALSE;
    }

    jint audioVolumeNative(JNIEnv *env, jobject thiz, jlong handle)
    {
        auto *controller = (Controller *) handle;

        return (jint) controller->audioVolume();
    }

    jintArray audioStatsNative(JNIEnv *env, jobject thiz, jlong handle)
    {
        auto *controller = (Controller *) handle;
        uint32_t stats[6];

        controller->audioStats(stats);

        jintArray out = env->NewIntArray(6);
        if (out == nullptr) {
            return nullptr;
        }

        env->SetIntArrayRegion(out, 0, 6, reinterpret_cast<const jint *>(stats));

        return out;
    }

    void queueAudioNative(JNIEnv *env, jobject thiz, jlong handle, jshortArray samples, jint count)
    {
        auto *controller = (Controller *) handle;

        // Critical section rather than a copy: this runs per audio frame, and GetPrimitiveArrayCritical
        // hands back the array's own storage where the runtime allows it. Nothing between the calls
        // may enter the JVM or block.
        auto *data = (jshort *) env->GetPrimitiveArrayCritical(samples, nullptr);
        if (data == nullptr) {
            return;
        }

        controller->queueAudio(reinterpret_cast<const int16_t *>(data), (size_t) count);

        env->ReleasePrimitiveArrayCritical(samples, data, JNI_ABORT);
    }

    void registerNative(JNIEnv *env, jobject thiz, jlong handle)
    {
        JavaVM *jvm = nullptr;
        jint r = env->GetJavaVM(&jvm);
        if(r != JNI_OK || jvm == nullptr) {
            Log::error("GetJavaVM failed");
        }

        auto *controller = (Controller *) handle;
        controller->registerJavaContext(jvm, env, env->NewGlobalRef(thiz));
    }

    /*
     * A cabled GIP pad. Separate entry points from the dongle's because the two share nothing at the
     * transport level - one cable is one device, with no pairing, no client slots and no firmware to
     * load - while everything above GipDevice is common.
     */
    jlong createWiredDriver(JNIEnv *env, jclass clazz, jint fd)
    {
        JavaVM *jvm = nullptr;

        if (env->GetJavaVM(&jvm) != JNI_OK) {
            return 0;
        }

        return (jlong) new WiredController(fd, jvm);
    }

    jboolean startWiredDriver(JNIEnv *env, jclass clazz, jlong handle)
    {
        auto *wired = (WiredController *) handle;

        return wired->start() ? JNI_TRUE : JNI_FALSE;
    }

    jlong wiredControllerHandle(JNIEnv *env, jclass clazz, jlong handle)
    {
        auto *wired = (WiredController *) handle;

        // The GIP device beneath, so rumble and audio reuse GipController's entry points
        // rather than being duplicated for the cable.
        return (jlong) wired->controller();
    }

    void destroyWiredDriver(JNIEnv *env, jclass clazz, jlong handle)
    {
        delete (WiredController *) handle;
    }

    const JNINativeMethod DONGLE_METHODS[] = {
        {"createDriver",          "(I)J",                      (void *) createDriver},
        {"startDriver",           "(JLjava/lang/String;)Z",    (void *) startDriver},
        {"setPairingModeNative",  "(JZ)Z",                     (void *) setPairingModeNative},
        {"stopDriver",            "(J)V",                      (void *) stopDriver},
        {"destroyDriver",         "(J)V",                      (void *) destroyDriver},
    };

    const JNINativeMethod CONTROLLER_METHODS[] = {
        {"registerNative",          "(J)V",     (void *) registerNative},
        {"setAudioEnabledNative",   "(JZ)Z",    (void *) setAudioEnabledNative},
        {"audioStatsNative",        "(J)[I",    (void *) audioStatsNative},
        {"setAudioVolumeNative",    "(JI)Z",    (void *) setAudioVolumeNative},
        {"audioVolumeNative",       "(J)I",     (void *) audioVolumeNative},
        {"hasAudioSupportNative",   "(J)Z",     (void *) hasAudioSupportNative},
        {"forgetAudioDeviceNative", "(J)V",     (void *) forgetAudioDeviceNative},
        {"audioNeedsReplugNative",  "(J)Z",     (void *) audioNeedsReplugNative},
        {"queueAudioNative",        "(J[SI)V",  (void *) queueAudioNative},
        {"sendRumble",              "(JSS)V",   (void *) sendRumble},
        {"sendrumbleTriggers",      "(JSS)V",   (void *) sendrumbleTriggers},
    };

    const JNINativeMethod WIRED_METHODS[] = {
        {"createWiredDriver",      "(I)J",  (void *) createWiredDriver},
        {"startWiredDriver",       "(J)Z",  (void *) startWiredDriver},
        {"wiredControllerHandle",  "(J)J",  (void *) wiredControllerHandle},
        {"destroyWiredDriver",     "(J)V",  (void *) destroyWiredDriver},
    };

    bool bind(JNIEnv *env, const char *name, const JNINativeMethod *methods, jint count)
    {
        jclass clazz = env->FindClass(name);

        if (clazz == nullptr)
        {
            env->ExceptionClear();
            Log::error("JNI: %s not found", name);

            return false;
        }

        // RegisterNatives reports only that something failed, so the pending exception - which
        // names the method whose signature did not match - is what makes this diagnosable.
        bool ok = env->RegisterNatives(clazz, methods, count) == JNI_OK;

        if (!ok)
        {
            env->ExceptionDescribe();
            env->ExceptionClear();
            Log::error("JNI: could not bind %s", name);
        }

        env->DeleteLocalRef(clazz);

        return ok;
    }
}

/*
 * Binds every entry point above, on the thread that called System.loadLibrary().
 *
 * That thread matters: it is a Java thread, so FindClass here uses the application class loader.
 * The driver's read threads attach to the JVM themselves and get the system class loader, which
 * cannot see application classes at all - which is why the crypto class is resolved here too,
 * rather than on first use.
 *
 * FindClass initialises the class it returns, so binding a class whose static initialiser is the
 * one that got us here re-enters System.loadLibrary() for this library on this thread. ART
 * recognises that case by thread id and returns success rather than waiting on itself, logging
 * "recursive attempt to load library" as it does. Three classes here each load the library, so
 * whichever is touched first takes that path for the other two; it is expected, and the log line
 * is not a fault.
 */
extern "C"
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *)
{
    JNIEnv *env = nullptr;

    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK)
    {
        return JNI_ERR;
    }

    if (!bind(env, DONGLE_CLASS, DONGLE_METHODS, std::size(DONGLE_METHODS)) ||
        !bind(env, CONTROLLER_CLASS, CONTROLLER_METHODS, std::size(CONTROLLER_METHODS)) ||
        !bind(env, WIRED_CLASS, WIRED_METHODS, std::size(WIRED_METHODS)))
    {
        return JNI_ERR;
    }

    // Not fatal: a pad that cannot run the security handshake stays silent, where refusing the
    // load takes the wired pads and the adapter itself down with it. GipCrypto logs and every
    // caller handles the empty result, so the failure is reported once here and once per use.
    jclass crypto = env->FindClass(CRYPTO_CLASS);

    if (crypto == nullptr)
    {
        env->ExceptionClear();
        Log::error("JNI: %s not found", CRYPTO_CLASS);
    }
    else
    {
        GipCrypto::init(env, crypto);
        env->DeleteLocalRef(crypto);
    }

    return JNI_VERSION_1_6;
}
