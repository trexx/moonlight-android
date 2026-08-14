/*
 * Android port addition - not part of upstream xow.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

#pragma once

#include <jni.h>

/*
 * Returns the JNIEnv for the calling thread, which must already be attached.
 *
 * The driver's read threads are attached once when they start and detached when they exit, rather
 * than around each callback. Attaching per callback was measurably wasteful: input reports arrive
 * at up to ~125 Hz per controller and the adapter hosts four, and detaching tears down the thread's
 * JNI state so the next report has to rebuild it.
 *
 * It also means nothing on a callback path may call DetachCurrentThread - doing so would detach the
 * read thread mid-loop - and that cached jclass references must be global. A local reference from
 * GetObjectClass used to be freed implicitly by the detach; without one it would accumulate until
 * the local reference table overflows.
 */
inline JNIEnv *getAttachedEnv(JavaVM *jvm)
{
    JNIEnv *env = nullptr;

    if (jvm == nullptr)
    {
        return nullptr;
    }

    if (jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK)
    {
        return nullptr;
    }

    return env;
}
