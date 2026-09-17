# Application.mk for Moonlight

# Our minimum version is Android 11
APP_PLATFORM := android-30

# We support 16KB pages
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true

# NB: APP_ABI is deliberately not set here. AGP passes its own APP_ABI on the
# ndk-build command line from defaultConfig.ndk.abiFilters, which silently
# overrides anything set in this file. The ABI list lives in app/build.gradle.

APP_STL := c++_static

# ndk-build's release defaults differ by ISA: arm64-v8a gets -O2, but armeabi-v7a is built in
# Thumb mode with -Oz (build/core/toolchains/arm-linux-androideabi-clang/setup.mk), i.e. for
# minimum size. That put the whole 32-bit stream core - RTP reassembly, Reed-Solomon FEC, ENet,
# the mbedtls AES-GCM path, the AAudio renderer - on the size optimiser on any 32-bit box such as
# the Homatics. APP_CFLAGS lands after the per-source defaults on the command line and clang takes
# the last -O it sees, so this lifts every module and ABI to -O2. Thumb stays: on the in-order
# Cortex-A55 cores these boxes use, the denser encoding is worth more than ARM mode. Debug
# builds keep the NDK's -O0 so lldb still lines up with the source.
ifneq ($(NDK_DEBUG),1)
APP_CFLAGS := -O2
endif
