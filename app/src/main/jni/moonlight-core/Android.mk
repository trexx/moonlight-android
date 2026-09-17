# Android.mk for moonlight-core and binding
MY_LOCAL_PATH := $(call my-dir)

include $(call all-subdir-makefiles)

LOCAL_PATH := $(MY_LOCAL_PATH)

# Mbed TLS provides the symmetric crypto for moonlight-common-c. The whole
# library/ directory is compiled because every file is guarded by its own
# MBEDTLS_*_C feature macro, so the trimmed config in
# moonlight_mbedtls_config.h reduces the unused ones to empty objects.
include $(CLEAR_VARS)
LOCAL_MODULE    := mbedtls
LOCAL_SRC_FILES := $(patsubst $(LOCAL_PATH)/%,%,$(wildcard $(LOCAL_PATH)/mbedtls/library/*.c))
LOCAL_C_INCLUDES := $(LOCAL_PATH) \
                    $(LOCAL_PATH)/mbedtls/include \
                    $(LOCAL_PATH)/mbedtls/library \

LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/mbedtls/include
LOCAL_CFLAGS := -DMBEDTLS_CONFIG_FILE=\"moonlight_mbedtls_config.h\" -ffunction-sections -fdata-sections
LOCAL_EXPORT_CFLAGS := -DMBEDTLS_CONFIG_FILE=\"moonlight_mbedtls_config.h\"

# The config asks for MBEDTLS_AESCE_C on both ABIs, but aesce.c gates its whole body on
# MBEDTLS_ARCH_IS_ARMV8_A, which build_info.h derives from __ARM_ARCH >= 8. The NDK compiles
# armeabi-v7a as armv7-a, so on that ABI the file reduced to a 684-byte stub with no crypto
# instructions in it at all, and both AES and GHASH fell back to table lookups. Measured
# cost of that on a 1392-byte video packet: 36,933 ns against 6,683 ns once the extensions
# are compiled in, and 3,247 ns against 729 ns on a 240-byte audio packet. Per packet, on
# the receive threads.
#
# The only 32-bit consumer is the Homatics Box R 4K, whose Amlogic S905X4 is an ARMv8-A
# Cortex-A55 running a 32-bit userspace - the instructions are in the silicon and were simply
# not being emitted. Raising -march for this module is what makes them reachable; the flag is
# scoped to mbedtls and deliberately kept out of LOCAL_EXPORT_CFLAGS so moonlight-core, enet
# and nanors stay at the ABI default.
#
# Safe to dispatch: aesce.c checks getauxval(AT_HWCAP2) & HWCAP2_AES at runtime and uses the
# table path when the CPU lacks the extension. What is *not* runtime-guarded is that clang may
# now emit ARMv8-A baseline instructions anywhere in mbedtls, so this library would fault on a
# genuine ARMv7 CPU. None is in scope: every Android 11+ TV box is ARMv8 silicon, 32-bit
# userspace or not. If one ever appears, move aesce.c into its own static module carrying this
# flag - it dispatches on HWCAP2_AES at runtime, so the rest of mbedtls can drop back to the
# ABI default without losing the acceleration where it exists.
#
# +crypto is not needed on the command line - aesce.c pushes target("aes") on its own
# functions. Requires clang >= 11 for 32-bit per mbedtls_config.h; the pinned NDK has 21.
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_CFLAGS += -march=armv8-a
endif
LOCAL_BRANCH_PROTECTION := standard
include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE    := moonlight-core

LOCAL_SRC_FILES := moonlight-common-c/src/AudioStream.c \
                   moonlight-common-c/src/ByteBuffer.c \
                   moonlight-common-c/src/Connection.c \
                   moonlight-common-c/src/ConnectionTester.c \
                   moonlight-common-c/src/ControlStream.c \
                   moonlight-common-c/src/FakeCallbacks.c \
                   moonlight-common-c/src/InputStream.c \
                   moonlight-common-c/src/LinkedBlockingQueue.c \
                   moonlight-common-c/src/Misc.c \
                   moonlight-common-c/src/Platform.c \
                   moonlight-common-c/src/PlatformCrypto.c \
                   moonlight-common-c/src/PlatformSockets.c \
                   moonlight-common-c/src/RtpAudioQueue.c \
                   moonlight-common-c/src/RtpVideoQueue.c \
                   moonlight-common-c/src/RtspConnection.c \
                   moonlight-common-c/src/RtspParser.c \
                   moonlight-common-c/src/SdpGenerator.c \
                   moonlight-common-c/src/VideoDepacketizer.c \
                   moonlight-common-c/src/VideoStream.c \
                   moonlight-common-c/nanors/rs.c \
                   moonlight-common-c/nanors/deps/obl/oblas_common.c \
                   moonlight-common-c/nanors/deps/obl/oblas_lite.c \
                   moonlight-common-c/enet/callbacks.c \
                   moonlight-common-c/enet/compress.c \
                   moonlight-common-c/enet/host.c \
                   moonlight-common-c/enet/list.c \
                   moonlight-common-c/enet/packet.c \
                   moonlight-common-c/enet/peer.c \
                   moonlight-common-c/enet/protocol.c \
                   moonlight-common-c/enet/unix.c \
                   moonlight-common-c/enet/win32.c \
                   simplejni.c \
                   callbacks.c \
                   minisdl.c \
                   aaudio_renderer.c \


LOCAL_C_INCLUDES := $(LOCAL_PATH)/moonlight-common-c/enet/include \
                    $(LOCAL_PATH)/moonlight-common-c/nanors \
                    $(LOCAL_PATH)/moonlight-common-c/nanors/deps \
                    $(LOCAL_PATH)/moonlight-common-c/nanors/deps/obl \
                    $(LOCAL_PATH)/moonlight-common-c/src \

# -fvisibility=hidden: without it every internal function in moonlight-common-c, ENet and nanors
# is exported from the .so, so each cross-file call in the stream core (receive -> RTP queue ->
# depacketizer -> submit, several times per packet) goes through the PLT and cannot be inlined or
# optimised across the boundary. The JNI entry points in simplejni.c, callbacks.c and
# aaudio_renderer.c are all declared JNIEXPORT, which is visibility("default"), so name-based
# JNI resolution still finds them; verify with `nm -D` on both ABIs after touching this.
LOCAL_CFLAGS := -DHAS_SOCKLEN_T=1 -DLC_ANDROID -DHAVE_CLOCK_GETTIME=1 -DUSE_MBEDTLS -fvisibility=hidden

LOCAL_LDLIBS := -llog -laaudio

# Trace markers are debug-only, so both halves are gated together: the define that makes the
# ML_TRACE_* macros expand to ATrace calls, and libandroid, which is where those calls live.
# AGP passes NDK_DEBUG=1 for debuggable variants and 0 otherwise, so a release build neither
# compiles a marker nor takes on the library dependency. See profiling.h.
ifeq ($(NDK_DEBUG),1)
LOCAL_CFLAGS += -DLC_DEBUG -DMOONLIGHT_PROFILING
LOCAL_LDLIBS += -landroid
endif

LOCAL_STATIC_LIBRARIES := libopus mbedtls cpufeatures
LOCAL_LDFLAGS += -Wl,--exclude-libs,ALL

LOCAL_BRANCH_PROTECTION := standard

include $(BUILD_SHARED_LIBRARY)

$(call import-module,android/cpufeatures)