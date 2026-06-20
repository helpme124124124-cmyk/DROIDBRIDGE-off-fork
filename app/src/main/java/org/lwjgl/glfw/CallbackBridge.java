/*
 * Derived from PojavLauncher.
 *
 * Original project:
 * https://github.com/PojavLauncherTeam/PojavLauncher
 *
 * Original license: GNU Lesser General Public License v3.0.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * This file remains available under the terms of the GNU LGPLv3
 * unless the original file or bundled component states a different license.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package org.lwjgl.glfw;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.Choreographer;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.GrabListener;
import net.kdt.pojavlaunch.LwjglGlfwKeycode;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

import dalvik.annotation.optimization.CriticalNative;

public class CallbackBridge {
    public static final Choreographer sChoreographer = Choreographer.getInstance();
    private static boolean isGrabbing = false;
    private static final ArrayList<GrabListener> grabListeners = new ArrayList<>();
    private static @Nullable WeakReference<Object> sDirectGamepadEnableHandler;

    private static @Nullable Context sAppContext;
    private static @Nullable ClipboardManager sClipboard;

    private static volatile boolean sInputReady;
    private static volatile boolean sUseInputStackQueue;

    public static final int CLIPBOARD_COPY = 2000;
    public static final int CLIPBOARD_PASTE = 2001;
    public static final int CLIPBOARD_OPEN = 2002;

    public static volatile int windowWidth, windowHeight;
    public static volatile int physicalWidth, physicalHeight;
    public static float mouseX, mouseY;
    public volatile static boolean holdingAlt, holdingCapslock, holdingCtrl,
            holdingNumlock, holdingShift;
    public static boolean sGamepadDirectInput = false;

    public static void init(Context context) {
        sAppContext = context.getApplicationContext();
        sClipboard = (ClipboardManager) sAppContext.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    /**
     * Zalith/Pojav set this before launch.
     *
     * Newer Minecraft/LWJGL versions use the input stack queue path. If this is left false,
     * mouse cursor movement can appear to work visually in JavaLauncher while Minecraft ignores
     * button events because the native bridge is trying the wrong callback path.
     */
    public static void setUseInputStackQueue(boolean useInputStackQueue) {
        sUseInputStackQueue = useInputStackQueue;
        try {
            nativeSetUseInputStackQueue(useInputStackQueue);
            Log.i("CallbackBridge", "Input stack queue=" + useInputStackQueue);
        } catch (Throwable throwable) {
            Log.e("CallbackBridge", "nativeSetUseInputStackQueue failed: " + useInputStackQueue, throwable);
        }
    }

    public static boolean isUseInputStackQueue() {
        return sUseInputStackQueue;
    }

    /**
     * Required by input_bridge_v3.c. Native input is dropped while this is false.
     */
    public static boolean setInputReady(boolean ready) {
        sInputReady = ready;
        try {
            boolean nativeStackMode = nativeSetInputReady(ready);
            // Keep Java's cached state aligned if native already had a value.
            sUseInputStackQueue = nativeStackMode;
            return nativeStackMode;
        } catch (Throwable throwable) {
            Log.e("CallbackBridge", "nativeSetInputReady failed: " + ready, throwable);
            return false;
        }
    }

    private static void ensureNativeInputReady() {
        if (!sInputReady) {
            setInputReady(true);
        }
    }

    public static void ensureInputFocus() {
        ensureNativeInputReady();
        try {
            nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 1);
            nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 1);
            nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 1);
        } catch (Throwable ignored) {
            // GLFW window may not exist yet.
        }
    }

    public static void clearInputFocus() {
        try {
            nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 0);
            nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 0);
        } catch (Throwable ignored) {
        }
    }

    public static void putMouseEventWithCoords(int button, float x, float y) {
        putMouseEventWithCoords(button, true, x, y);
        sChoreographer.postFrameCallbackDelayed(
                ignored -> putMouseEventWithCoords(button, false, x, y),
                33
        );
    }

    public static void putMouseEventWithCoords(int button, boolean isDown, float x, float y) {
        ensureInputFocus();
        sendCursorPos(x, y);
        sendMouseKeycode(button, getCurrentMods(), isDown);
    }

    public static void sendCursorPos(float x, float y) {
        ensureNativeInputReady();
        mouseX = x;
        mouseY = y;
        nativeSendCursorPos(mouseX, mouseY);
    }

    public static void sendMouseButton(int button, boolean status) {
        sendMouseKeycode(button, CallbackBridge.getCurrentMods(), status);
    }

    public static void sendMouseKeycode(int button, int modifiers, boolean isDown) {
        ensureInputFocus();
        nativeSendMouseButton(button, isDown ? 1 : 0, modifiers);
    }

    public static void clickMouseButtonAtCurrentPosition(int button) {
        ensureInputFocus();
        putMouseEventWithCoords(button, mouseX, mouseY);
    }

    public static void sendScroll(double xoffset, double yoffset) {
        ensureInputFocus();
        nativeSendScroll(xoffset, yoffset);
    }

    public static void sendUpdateWindowSize(int w, int h) {
        nativeSendScreenSize(w, h);
    }

    public static boolean isGrabbing() {
        return isGrabbing;
    }

    public static void sendKeycode(int keycode, char keychar, int scancode, int modifiers, boolean isDown) {
        ensureInputFocus();

        if (keycode != 0) {
            nativeSendKey(keycode, scancode, isDown ? 1 : 0, modifiers);
        }

        if (isDown && keychar != '\u0000' && !Character.isISOControl(keychar)) {
            nativeSendCharMods(keychar, modifiers);
            nativeSendChar(keychar);
        }
    }

    public static void sendKeyPress(int keyCode, int modifiers, boolean status) {
        sendKeyPress(keyCode, '\u0000', 0, modifiers, status);
    }

    public static void sendKeyPress(int keyCode, char keyChar, int modifiers, boolean status) {
        sendKeyPress(keyCode, keyChar, 0, modifiers, status);
    }

    public static void sendKeyPress(int keyCode, char keyChar, int scancode, int modifiers, boolean status) {
        sendKeycode(keyCode, keyChar, scancode, modifiers, status);
    }

    public static void sendKeyPress(int keyCode) {
        sendKeyPress(keyCode, getCurrentMods(), true);
        sendKeyPress(keyCode, getCurrentMods(), false);
    }

    public static void sendChar(char keychar, int modifiers) {
        ensureInputFocus();
        nativeSendCharMods(keychar, modifiers);
        nativeSendChar(keychar);
    }

    @SuppressWarnings("unused")
    public static @Nullable String accessAndroidClipboard(int type, String copy) {
        switch (type) {
            case CLIPBOARD_COPY:
                if (sClipboard != null) {
                    sClipboard.setPrimaryClip(ClipData.newPlainText("Copy", copy));
                }
                return null;
            case CLIPBOARD_PASTE:
                if (sClipboard != null
                        && sClipboard.hasPrimaryClip()
                        && sClipboard.getPrimaryClipDescription() != null
                        && sClipboard.getPrimaryClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
                    ClipData clipData = sClipboard.getPrimaryClip();
                    if (clipData == null || clipData.getItemCount() <= 0) return "";
                    CharSequence text = clipData.getItemAt(0).getText();
                    return text != null ? text.toString() : "";
                }
                return "";
            case CLIPBOARD_OPEN:
                if (sAppContext != null && copy != null && copy.trim().length() > 0) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(copy));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        sAppContext.startActivity(intent);
                    } catch (Throwable t) {
                        Log.e("CallbackBridge", "Failed to open link", t);
                    }
                }
                return null;
            default:
                return null;
        }
    }

    public static int getCurrentMods() {
        int currMods = 0;
        if (holdingAlt) currMods |= LwjglGlfwKeycode.GLFW_MOD_ALT;
        if (holdingCapslock) currMods |= LwjglGlfwKeycode.GLFW_MOD_CAPS_LOCK;
        if (holdingCtrl) currMods |= LwjglGlfwKeycode.GLFW_MOD_CONTROL;
        if (holdingNumlock) currMods |= LwjglGlfwKeycode.GLFW_MOD_NUM_LOCK;
        if (holdingShift) currMods |= LwjglGlfwKeycode.GLFW_MOD_SHIFT;
        return currMods;
    }

    public static void setModifiers(int keyCode, boolean isDown) {
        switch (keyCode) {
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT:
            case LwjglGlfwKeycode.GLFW_KEY_RIGHT_SHIFT:
                holdingShift = isDown;
                return;
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL:
            case LwjglGlfwKeycode.GLFW_KEY_RIGHT_CONTROL:
                holdingCtrl = isDown;
                return;
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_ALT:
            case LwjglGlfwKeycode.GLFW_KEY_RIGHT_ALT:
                holdingAlt = isDown;
                return;
            case LwjglGlfwKeycode.GLFW_KEY_CAPS_LOCK:
                holdingCapslock = isDown;
                return;
            case LwjglGlfwKeycode.GLFW_KEY_NUM_LOCK:
                holdingNumlock = isDown;
        }
    }

    @SuppressWarnings("unused")
    @Keep
    private static void onDirectInputEnable() {
        sGamepadDirectInput = true;
        Object enableHandler = sDirectGamepadEnableHandler != null ? sDirectGamepadEnableHandler.get() : null;
        if (enableHandler instanceof Runnable) {
            ((Runnable) enableHandler).run();
        }
    }

    @SuppressWarnings("unused")
    private static void onGrabStateChanged(final boolean grabbing) {
        isGrabbing = grabbing;
        sChoreographer.postFrameCallbackDelayed((time) -> {
            if (isGrabbing != grabbing) return;
            synchronized (grabListeners) {
                for (GrabListener g : grabListeners) g.onGrabState(grabbing);
            }
        }, 16);
    }

    public static void setDirectGamepadEnableHandler(Object h) {
        sDirectGamepadEnableHandler = new WeakReference<>(h);
    }

    public static void addGrabListener(GrabListener listener) {
        synchronized (grabListeners) {
            listener.onGrabState(isGrabbing);
            grabListeners.add(listener);
        }
    }

    public static void removeGrabListener(GrabListener listener) {
        synchronized (grabListeners) {
            grabListeners.remove(listener);
        }
    }

    @Keep public static native boolean nativeSetInputReady(boolean inputReady);

    @Keep @CriticalNative public static native void nativeSetUseInputStackQueue(boolean useInputStackQueue);
    @Keep @CriticalNative private static native boolean nativeSendChar(char codepoint);
    @Keep @CriticalNative private static native boolean nativeSendCharMods(char codepoint, int mods);
    @Keep @CriticalNative private static native void nativeSendKey(int key, int scancode, int action, int mods);
    @Keep @CriticalNative private static native void nativeSendCursorPos(float x, float y);
    @Keep @CriticalNative private static native void nativeSendMouseButton(int button, int action, int mods);
    @Keep @CriticalNative private static native void nativeSendScroll(double xoffset, double yoffset);
    @Keep @CriticalNative private static native void nativeSendScreenSize(int width, int height);
    @Keep public static native void nativeSetWindowAttrib(int attrib, int value);
    @Keep public static native int getCurrentFps();
    @Keep @CriticalNative public static native void nativeSetCursorShape(int shape);

    static {
        System.loadLibrary("pojavexec");
    }
}
