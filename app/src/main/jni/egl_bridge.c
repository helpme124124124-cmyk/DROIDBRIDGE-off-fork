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

#include <jni.h>
#include <assert.h>
#include <dlfcn.h>

#include <stdbool.h>
#include <stdint.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <unistd.h>

#include <EGL/egl.h>
#include <GL/osmesa.h>
#include "ctxbridges/egl_loader.h"
#include "ctxbridges/osmesa_loader.h"
#include "ctxbridges/renderer_config.h"
#include "ctxbridges/virgl_bridge.h"
#include "driver_helper/nsbypass.h"

#ifdef GLES_TEST
#include <GLES2/gl2.h>
#endif

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/rect.h>
#include <string.h>
#include <environ/environ.h>
#include <android/dlext.h>
#include <time.h>
#include "utils.h"
#include "ctxbridges/bridge_tbl.h"
#include "ctxbridges/osm_bridge.h"

#define GLFW_CLIENT_API 0x22001
/* Consider GLFW_NO_API as Vulkan API */
#define GLFW_NO_API 0
#define GLFW_OPENGL_API 0x30001

// This means that the function is an external API and that it will be used
#define EXTERNAL_API __attribute__((used))
// This means that you are forced to have this function/variable for ABI compatibility
#define ABI_COMPAT __attribute__((unused))

EGLConfig config;
struct PotatoBridge potatoBridge;

void* loadTurnipVulkan(void);
void calculateFPS(void);
void load_vulkan(void);

EXTERNAL_API void pojavTerminate(void) {
    printf("EGLBridge: Terminating\n");

    switch (pojav_environ->config_renderer) {
        case RENDERER_GL4ES: {
            eglMakeCurrent_p(potatoBridge.eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroySurface_p(potatoBridge.eglDisplay, potatoBridge.eglSurface);
            eglDestroyContext_p(potatoBridge.eglDisplay, potatoBridge.eglContext);
            eglTerminate_p(potatoBridge.eglDisplay);
            eglReleaseThread_p();

            potatoBridge.eglContext = EGL_NO_CONTEXT;
            potatoBridge.eglDisplay = EGL_NO_DISPLAY;
            potatoBridge.eglSurface = EGL_NO_SURFACE;
        } break;

        case RENDERER_VK_ZINK: {
            // Nothing to do here.
        } break;
    }
}

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_setupBridgeWindow(JNIEnv* env,
                                                                                 ABI_COMPAT jclass clazz,
                                                                                 jobject surface) {
    pojav_environ->pojavWindow = ANativeWindow_fromSurface(env, surface);
    if (br_setup_window) {
        br_setup_window();
    }
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_releaseBridgeWindow(ABI_COMPAT JNIEnv* env,
                                                            ABI_COMPAT jclass clazz) {
    ANativeWindow_release(pojav_environ->pojavWindow);
}

EXTERNAL_API void* pojavGetCurrentContext(void) {
    if (pojav_environ->config_renderer == RENDERER_VIRGL) {
        return virglGetCurrentContext();
    }

    return br_get_current();
}

static void set_vulkan_ptr(void* ptr) {
    if (ptr == NULL) {
        unsetenv("VULKAN_PTR");
        return;
    }

    char envval[64];
    sprintf(envval, "%" PRIxPTR, (uintptr_t) ptr);
    setenv("VULKAN_PTR", envval, 1);
}

static void* try_open_vulkan_loader(void) {
    dlerror();
    void* vulkanPtr = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
    if (vulkanPtr != NULL) {
        printf("OSMDroid: Loaded Vulkan via libvulkan.so.1, ptr=%p\n", vulkanPtr);
        return vulkanPtr;
    }

    const char* firstError = dlerror();
    printf("OSMDroid: libvulkan.so.1 failed: %s\n", firstError != NULL ? firstError : "unknown error");

    dlerror();
    vulkanPtr = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (vulkanPtr != NULL) {
        printf("OSMDroid: Loaded Vulkan via libvulkan.so, ptr=%p\n", vulkanPtr);
        return vulkanPtr;
    }

    const char* secondError = dlerror();
    printf("OSMDroid: libvulkan.so failed: %s\n", secondError != NULL ? secondError : "unknown error");
    return NULL;
}

void load_vulkan(void) {
    const char* zinkPreferSystemDriver = getenv("POJAV_ZINK_PREFER_SYSTEM_DRIVER");
    int deviceApiLevel = android_get_device_api_level();

    if (zinkPreferSystemDriver == NULL && deviceApiLevel >= 28) {
#ifdef ADRENO_POSSIBLE
        void* result = loadTurnipVulkan();
        if (result != NULL) {
            printf("AdrenoSupp: Loaded Turnip, loader address: %p\n", result);
            set_vulkan_ptr(result);
            return;
        }
#endif
    }

    printf("OSMDroid: Loading Vulkan regularly...\n");
    void* vulkanPtr = try_open_vulkan_loader();
    set_vulkan_ptr(vulkanPtr);
}

int pojavInitOpenGL(void) {
    const char* forceVsync = getenv("FORCE_VSYNC");
    if (forceVsync != NULL && strcmp(forceVsync, "true") == 0) {
        pojav_environ->force_vsync = true;
    }

    const char* renderer = getenv("POJAV_RENDERER");
    if (renderer == NULL) {
        printf("EGLBridge: POJAV_RENDERER is not set\n");
        return 0;
    }

    load_vulkan();

    if (strncmp("opengles", renderer, 8) == 0) {
        pojav_environ->config_renderer = RENDERER_GL4ES;
        set_gl_bridge_tbl();
    }

    if (strcmp(renderer, "custom_gallium") == 0) {
        pojav_environ->config_renderer = RENDERER_VK_ZINK;
        set_osm_bridge_tbl();
    }

    if (strcmp(renderer, "vulkan_zink") == 0) {
        pojav_environ->config_renderer = RENDERER_VK_ZINK;
        load_vulkan();
        setenv("GALLIUM_DRIVER", "zink", 1);
        set_osm_bridge_tbl();
    }

    if (strcmp(renderer, "gallium_freedreno") == 0) {
        pojav_environ->config_renderer = RENDERER_VK_ZINK;
        setenv("MESA_LOADER_DRIVER_OVERRIDE", "kgsl", 1);
        setenv("GALLIUM_DRIVER", "freedreno", 1);
        set_osm_bridge_tbl();
    }

    if (strcmp(renderer, "gallium_panfrost") == 0) {
        pojav_environ->config_renderer = RENDERER_VK_ZINK;
        setenv("GALLIUM_DRIVER", "panfrost", 1);
        setenv("MESA_DISK_CACHE_SINGLE_FILE", "1", 1);
        set_osm_bridge_tbl();
    }

    if (strcmp(renderer, "gallium_virgl") == 0) {
        pojav_environ->config_renderer = RENDERER_VIRGL;
        setenv("GALLIUM_DRIVER", "virpipe", 1);
        setenv("OSMESA_NO_FLUSH_FRONTBUFFER", "1", false);
        setenv("MESA_GL_VERSION_OVERRIDE", "4.3", 1);
        setenv("MESA_GLSL_VERSION_OVERRIDE", "430", 1);

        const char* noFlushFrontbuffer = getenv("OSMESA_NO_FLUSH_FRONTBUFFER");
        if (noFlushFrontbuffer != NULL && strcmp(noFlushFrontbuffer, "1") == 0) {
            printf("VirGL: OSMesa buffer flush is DISABLED!\n");
        }

        loadSymbolsVirGL();
        virglInit();
        return 0;
    }

    if (br_init()) {
        br_setup_window();
    }

    return 0;
}

EXTERNAL_API int pojavInit(void) {
    ANativeWindow_acquire(pojav_environ->pojavWindow);
    pojav_environ->savedWidth = ANativeWindow_getWidth(pojav_environ->pojavWindow);
    pojav_environ->savedHeight = ANativeWindow_getHeight(pojav_environ->pojavWindow);
    ANativeWindow_setBuffersGeometry(
            pojav_environ->pojavWindow,
            pojav_environ->savedWidth,
            pojav_environ->savedHeight,
            AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM
    );
    pojavInitOpenGL();
    return 1;
}

EXTERNAL_API void pojavSetWindowHint(int hint, int value) {
    if (hint != GLFW_CLIENT_API) {
        return;
    }

    switch (value) {
        case GLFW_NO_API:
            pojav_environ->config_renderer = RENDERER_VULKAN;
            /* Nothing to do: initialization is handled in Java-side */
            break;

        case GLFW_OPENGL_API:
            /* Nothing to do: initialization is called in pojavCreateContext */
            break;

        default:
            printf("GLFW: Unimplemented API 0x%x\n", value);
            abort();
    }
}

EXTERNAL_API void pojavSwapBuffers(void) {
    calculateFPS();

    if (pojav_environ->config_renderer == RENDERER_VK_ZINK ||
        pojav_environ->config_renderer == RENDERER_GL4ES) {
        br_swap_buffers();
    }

    if (pojav_environ->config_renderer == RENDERER_VIRGL) {
        virglSwapBuffers();
    }
}

EXTERNAL_API void pojavMakeCurrent(void* window) {
    if (pojav_environ->config_renderer == RENDERER_VK_ZINK ||
        pojav_environ->config_renderer == RENDERER_GL4ES) {
        br_make_current((basic_render_window_t*) window);
    }

    if (pojav_environ->config_renderer == RENDERER_VIRGL) {
        virglMakeCurrent(window);
    }
}

EXTERNAL_API void* pojavCreateContext(void* contextSrc) {
    if (pojav_environ->config_renderer == RENDERER_VULKAN) {
        return (void*) pojav_environ->pojavWindow;
    }

    if (pojav_environ->config_renderer == RENDERER_VIRGL) {
        return virglCreateContext(contextSrc);
    }

    return br_init_context((basic_render_window_t*) contextSrc);
}

void* maybe_load_vulkan(void) {
    const char* current = getenv("VULKAN_PTR");
    if (current == NULL || current[0] == '\0') {
        load_vulkan();
        current = getenv("VULKAN_PTR");
    }

    if (current == NULL || current[0] == '\0') {
        printf("OSMDroid: maybe_load_vulkan(): no Vulkan pointer available\n");
        return NULL;
    }

    return (void*) strtoull(current, NULL, 16);
}

static int frameCount = 0;
static int fps = 0;
static time_t lastTime = 0;

void calculateFPS(void) {
    frameCount++;
    time_t currentTime = time(NULL);

    if (currentTime != lastTime) {
        lastTime = currentTime;
        fps = frameCount;
        frameCount = 0;
    }
}

EXTERNAL_API JNIEXPORT jint JNICALL
Java_org_lwjgl_glfw_CallbackBridge_getCurrentFps(JNIEnv* env, jclass clazz) {
    return fps;
}

EXTERNAL_API JNIEXPORT jlong JNICALL
Java_org_lwjgl_vulkan_VK_getVulkanDriverHandle(ABI_COMPAT JNIEnv* env, ABI_COMPAT jclass thiz) {
    printf("EGLBridge: LWJGL-side Vulkan loader requested the Vulkan handle\n");
    return (jlong) maybe_load_vulkan();
}

EXTERNAL_API JNIEXPORT void JNICALL
Java_org_lwjgl_vulkan_VK_onVKFrame(ABI_COMPAT JNIEnv* env, ABI_COMPAT jclass clazz) {
    calculateFPS();
}

EXTERNAL_API void pojavSwapInterval(int interval) {
    if (pojav_environ->config_renderer == RENDERER_VK_ZINK ||
        pojav_environ->config_renderer == RENDERER_GL4ES) {
        br_swap_interval(interval);
    }

    if (pojav_environ->config_renderer == RENDERER_VIRGL) {
        virglSwapInterval(interval);
    }
}
