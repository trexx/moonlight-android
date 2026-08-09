# Android.mk for xbox wireless driver
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE    := xow-driver
LOCAL_SRC_FILES := \
    xow_driver_jni.cpp \
    dongle/firmware.cpp \
    dongle/usb.cpp \
    dongle/mt76.cpp \
    dongle/dongle.cpp \
    utils/log.cpp \
    controller/controller.cpp \
    controller/gip.cpp

LOCAL_C_INCLUDES := $(LOCAL_PATH) $(LIBUSB_ROOT_ABS)
LOCAL_SHARED_LIBRARIES += libusb1.0
LOCAL_LDLIBS    := -llog

# NB: this is the only C++ module in the project, so APP_STL is c++_static and libc++ is
# linked in here. Adding -ffunction-sections/-fdata-sections plus -Wl,--gc-sections was
# measured and changed the stripped size by 0 bytes, so it isn't set: the NDK already
# does this. The remaining bulk is libc++ locale machinery pulled in by <fstream>,
# <sstream> and <iomanip> in utils/log.cpp; moving that logging to printf-style would
# be the only way to shrink it further.

ifeq ($(NDK_DEBUG),1)
LOCAL_CFLAGS += -D_DEBUG
endif
include $(BUILD_SHARED_LIBRARY)


