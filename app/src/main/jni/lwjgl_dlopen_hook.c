/*
 * Derived from PojavLauncher native bridge code.
 *
 * Original project:
 * https://github.com/PojavLauncherTeam/PojavLauncher
 *
 * Original license: GNU Lesser General Public License v3.0,
 * unless this file or a bundled component states a different license.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * This file remains available under the terms of the GNU LGPLv3
 * unless the original file or bundled component states a different license.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

//
// Created by maks on 06.01.2025.
// Cleaned up for robust Vulkan loader interception.
//

#include <android/api-level.h>
#include <android/log.h>
#include <jni.h>

#include <environ/environ.h>

#include <dlfcn.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

extern void* maybe_load_vulkan(void);

/**
 * Returns true when the requested library name is a Vulkan loader soname.
 * Accepts both direct names and full paths.
 */
static bool is_vulkan_loader_name(const char* filename) {
    if (filename == NULL) {
        return false;
    }

    const char* base = strrchr(filename, '/');
    base = (base != NULL) ? (base + 1) : filename;

    return strcmp(base, "libvulkan.so") == 0 ||
           strcmp(base, "libvulkan.so.1") == 0;
}

/**
 * LWJGL dlopen hook.
 *
 * This keeps the normal dlopen() behavior for most libraries, but intercepts
 * Vulkan loader requests so Android can provide the system/custom Vulkan handle
 * through the launcher bridge first.
 */
static jlong ndlopen_bugfix(__attribute__((unused)) JNIEnv* env,
                            __attribute__((unused)) jclass clazz,
                            jlong filename_ptr,
                            jint jmode) {
    const char* filename = (const char*) filename_ptr;
    int mode = (int) jmode;

    if (is_vulkan_loader_name(filename)) {
        printf("LWJGL linkerhook: intercepted Vulkan load for %s\n", filename);

        void* handle = maybe_load_vulkan();
        if (handle != NULL) {
            printf("LWJGL linkerhook: using custom/system Vulkan handle %p for %s\n",
                   handle,
                   filename);
            return (jlong) handle;
        }

        printf("LWJGL linkerhook: maybe_load_vulkan() returned NULL, falling back to dlopen(%s)\n",
               filename);
    }

    return (jlong) dlopen(filename, mode);
}

/**
 * Install the LWJGL dlopen hook.
 *
 * This allows us to:
 * - override Vulkan loader resolution when needed
 * - keep library loading inside the launcher namespace on older Android builds
 */
void installLwjglDlopenHook(void) {
    __android_log_print(ANDROID_LOG_INFO, "LwjglLinkerHook", "Installing LWJGL dlopen() hook");

    JNIEnv* env = pojav_environ->runtimeJNIEnvPtr_JRE;
    jclass dynamicLinkLoader = (*env)->FindClass(env, "org/lwjgl/system/linux/DynamicLinkLoader");
    if (dynamicLinkLoader == NULL) {
        __android_log_print(ANDROID_LOG_ERROR,
                            "LwjglLinkerHook",
                            "Failed to find org/lwjgl/system/linux/DynamicLinkLoader");
        (*env)->ExceptionClear(env);
        return;
    }

    JNINativeMethod ndlopenMethod[] = {
            {"ndlopen", "(JI)J", &ndlopen_bugfix}
    };

    if ((*env)->RegisterNatives(env, dynamicLinkLoader, ndlopenMethod, 1) != 0) {
        __android_log_print(ANDROID_LOG_ERROR,
                            "LwjglLinkerHook",
                            "Failed to register hooked ndlopen() implementation");
        (*env)->ExceptionClear(env);
    }
}
