//
// Created by xm1994 on 2024/9/1.
//

#include <memory>
#include "utils/crypto.h"

#include <jni.h>
#include "dongle/usb.h"
#include "dongle/dongle.h"
#include "wired/wired.h"

#include "utils/log.h"

extern "C"
JNIEXPORT jlong JNICALL
Java_com_limelight_binding_input_driver_XboxWirelessDongle_createDriver(JNIEnv *env, jobject thiz, jint fd) {

    auto usbDevice = std::make_unique<UsbDevice>(fd);
    JavaVM *jvm = nullptr;
    jint r = env->GetJavaVM(&jvm);
    if(r != JNI_OK || jvm == nullptr) {
        return -1;
    }
    auto dongle = new Dongle(std::move(usbDevice), env->NewGlobalRef(thiz), jvm);
    return (jlong) dongle;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_limelight_binding_input_driver_XboxWirelessDongle_startDriver(JNIEnv *env, jobject thiz,
                                                                       jlong handle, jstring fwPath) {
    auto *dongle = (Dongle *) handle;
    jboolean copy = false;
    auto cfwPath = env->GetStringUTFChars(fwPath, &copy);
    auto succ = dongle->start(cfwPath);
    env->ReleaseStringUTFChars(fwPath, cfwPath);
    return succ;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_limelight_binding_input_driver_XboxWirelessDongle_setPairingModeNative(JNIEnv *env,
                                                                                jobject thiz,
                                                                                jlong handle,
                                                                                jboolean enable) {
    auto *dongle = (Dongle *) handle;
    return dongle->setPairing(enable == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_limelight_binding_input_driver_XboxWirelessDongle_stopDriver(JNIEnv *env, jobject thiz,
                                                                      jlong handle) {
    auto *dongle = (Dongle *) handle;
    dongle->stop();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_limelight_binding_input_driver_XboxWirelessDongle_destroyDriver(JNIEnv *env, jobject thiz,
                                                                         jlong handle) {
    auto *dongle = (Dongle *) handle;
    dongle->stop();
    delete dongle;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_limelight_binding_input_driver_GipController_sendRumble(JNIEnv *env, jobject thiz,
                                                                          jlong handle,
                                                                          jshort low_freq_motor,
                                                                          jshort high_freq_motor) {
    auto *controller = (Controller *) handle;
    controller->inputRumble(low_freq_motor, high_freq_motor);
}
extern "C"
JNIEXPORT void JNICALL
Java_com_limelight_binding_input_driver_GipController_sendrumbleTriggers(JNIEnv *env,
                                                                                  jobject thiz,
                                                                                  jlong handle,
                                                                                  jshort left_trigger,
                                                                                  jshort right_trigger) {
    auto *controller = (Controller *) handle;
    controller->inputRumbleTrigger(left_trigger, right_trigger);
}
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_limelight_binding_input_driver_GipController_setAudioEnabledNative(JNIEnv *env,
                                                                                     jobject thiz,
                                                                                     jlong handle,
                                                                                     jboolean enable) {
    auto *controller = (Controller *) handle;
    return controller->setAudioEnabled(enable == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_limelight_binding_input_driver_GipController_hasAudioSupportNative(JNIEnv *env,
                                                                                     jobject thiz,
                                                                                     jlong handle) {
    auto *controller = (Controller *) handle;
    return controller->supportsAudioOut() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_limelight_binding_input_driver_GipController_setAudioVolumeNative(JNIEnv *env,
                                                                                    jobject thiz,
                                                                                    jlong handle,
                                                                                    jint percent) {
    auto *controller = (Controller *) handle;

    return controller->setAudioVolume((uint8_t) percent) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_limelight_binding_input_driver_GipController_audioVolumeNative(JNIEnv *env,
                                                                                 jobject thiz,
                                                                                 jlong handle) {
    auto *controller = (Controller *) handle;

    return (jint) controller->audioVolume();
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_com_limelight_binding_input_driver_GipController_audioStatsNative(JNIEnv *env,
                                                                                jobject thiz,
                                                                                jlong handle) {
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

extern "C"
JNIEXPORT void JNICALL
Java_com_limelight_binding_input_driver_GipController_queueAudioNative(JNIEnv *env,
                                                                                jobject thiz,
                                                                                jlong handle,
                                                                                jshortArray samples,
                                                                                jint count) {
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

extern "C"
JNIEXPORT void JNICALL
Java_com_limelight_binding_input_driver_GipController_registerNative(JNIEnv *env,
                                                                              jobject thiz,
                                                                              jlong handle) {
    JavaVM *jvm = nullptr;
    jint r = env->GetJavaVM(&jvm);
    if(r != JNI_OK || jvm == nullptr) {
        Log::error("GetJavaVM failed");
    }
    // Resolved here because this runs on a thread the JVM created. The driver's read threads
    // attach themselves and get the system class loader, which cannot find application classes.
    GipCrypto::init(env);

    auto *controller = (Controller *) handle;
    controller->registerJavaContext(jvm, env, env->NewGlobalRef(thiz));
}
/*
 * A cabled GIP pad. Separate entry points from the dongle's because the two share nothing at the
 * transport level - one cable is one device, with no pairing, no client slots and no firmware to
 * load - while everything above GipDevice is common.
 */
extern "C"
JNIEXPORT jlong JNICALL
Java_com_limelight_binding_input_driver_XboxWiredGipController_createWiredDriver(JNIEnv *env,
                                                                                 jclass clazz,
                                                                                 jint fd) {
    JavaVM *jvm = nullptr;

    if (env->GetJavaVM(&jvm) != JNI_OK) {
        return 0;
    }

    return (jlong) new WiredController(fd, jvm);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_limelight_binding_input_driver_XboxWiredGipController_startWiredDriver(JNIEnv *env,
                                                                                jclass clazz,
                                                                                jlong handle) {
    auto *wired = (WiredController *) handle;

    return wired->start() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_limelight_binding_input_driver_XboxWiredGipController_wiredControllerHandle(JNIEnv *env,
                                                                                     jclass clazz,
                                                                                     jlong handle) {
    auto *wired = (WiredController *) handle;

    // The GIP device beneath, so rumble and audio reuse GipController's entry points
    // rather than being duplicated for the cable.
    return (jlong) wired->controller();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_limelight_binding_input_driver_XboxWiredGipController_destroyWiredDriver(JNIEnv *env,
                                                                                  jclass clazz,
                                                                                  jlong handle) {
    delete (WiredController *) handle;
}

/*
 * Re-enumerates the device, which is the software equivalent of unplugging it.
 *
 * A pad left configured by a run that died mid-stream stutters, and nothing sent over GIP shifts
 * it: not Set Device State: STOP, not RESET, not adopting its configuration, not renegotiating the
 * format. Only pulling the cable clears it - the pad is battery powered, so that is not a power
 * cycle but a *disconnect*, one of the three things MS-GIPUSB 2.2.11 says ends an audio stream and
 * something 3.1.1 requires every GIP state to handle. This is that disconnect, without the cable.
 *
 * Driven from the game menu rather than automatically. It was tried automatically once and
 * withdrawn: on this hardware it left the pad unclaimed with input dead, and input is the product.
 * What has changed since is that the teardown underneath it exists - the service now handles
 * ACTION_USB_DEVICE_DETACHED - and that permission survives re-enumeration, so this no longer
 * costs a modal dialog over a running stream.
 */
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_limelight_binding_input_driver_XboxWiredGipController_resetWiredDevice(JNIEnv *env,
                                                                                jclass clazz,
                                                                                jint fd) {
    libusb_context *ctx = nullptr;
    libusb_device_handle *handle = nullptr;

    if (libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr) != LIBUSB_SUCCESS) {
        return JNI_FALSE;
    }

    if (libusb_init(&ctx) < 0) {
        return JNI_FALSE;
    }

    if (libusb_wrap_sys_device(ctx, (intptr_t) fd, &handle) < 0 || handle == nullptr) {
        libusb_exit(ctx);

        return JNI_FALSE;
    }

    /*
     * NOT_FOUND means the device had to re-enumerate to come back, which is a success here - it is
     * the whole point - and leaves the handle invalid either way, so it is closed regardless.
     */
    int error = libusb_reset_device(handle);

    libusb_close(handle);
    libusb_exit(ctx);

    return (error == 0 || error == LIBUSB_ERROR_NOT_FOUND) ? JNI_TRUE : JNI_FALSE;
}
