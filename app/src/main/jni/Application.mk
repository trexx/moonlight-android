# Application.mk for Moonlight

# Our minimum version is Android 11
APP_PLATFORM := android-30

# We support 16KB pages
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true

# NB: APP_ABI is deliberately not set here. AGP passes its own APP_ABI on the
# ndk-build command line from defaultConfig.ndk.abiFilters, which silently
# overrides anything set in this file. The ABI list lives in app/build.gradle.

APP_STL := c++_static
