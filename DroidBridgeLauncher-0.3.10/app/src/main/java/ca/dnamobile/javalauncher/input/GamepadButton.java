/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, PojavLauncher, Zalith Launcher, or any third-party project.
 *
 * Files written entirely by DNA Mobile Applications are proprietary unless
 * a file header or separate license notice states otherwise.
 */

package ca.dnamobile.javalauncher.input;

import android.view.KeyEvent;

import androidx.annotation.Nullable;

/**
 * Stable button names used by JavaLauncher's controller mapper.
 */
public enum GamepadButton {
    BUTTON_A(KeyEvent.KEYCODE_BUTTON_A, "A"),
    BUTTON_B(KeyEvent.KEYCODE_BUTTON_B, "B"),
    BUTTON_X(KeyEvent.KEYCODE_BUTTON_X, "X"),
    BUTTON_Y(KeyEvent.KEYCODE_BUTTON_Y, "Y"),

    BUTTON_L1(KeyEvent.KEYCODE_BUTTON_L1, "L1"),
    BUTTON_R1(KeyEvent.KEYCODE_BUTTON_R1, "R1"),
    BUTTON_L2(KeyEvent.KEYCODE_BUTTON_L2, "L2"),
    BUTTON_R2(KeyEvent.KEYCODE_BUTTON_R2, "R2"),

    BUTTON_THUMBL(KeyEvent.KEYCODE_BUTTON_THUMBL, "Left Stick Press"),
    BUTTON_THUMBR(KeyEvent.KEYCODE_BUTTON_THUMBR, "Right Stick Press"),

    BUTTON_START(KeyEvent.KEYCODE_BUTTON_START, "Start"),
    BUTTON_SELECT(KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),

    DPAD_UP(KeyEvent.KEYCODE_DPAD_UP, "D-Pad Up"),
    DPAD_DOWN(KeyEvent.KEYCODE_DPAD_DOWN, "D-Pad Down"),
    DPAD_LEFT(KeyEvent.KEYCODE_DPAD_LEFT, "D-Pad Left"),
    DPAD_RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT, "D-Pad Right"),
    DPAD_CENTER(KeyEvent.KEYCODE_DPAD_CENTER, "D-Pad Center");

    public final int androidKeyCode;
    private final String displayName;

    GamepadButton(int androidKeyCode, String displayName) {
        this.androidKeyCode = androidKeyCode;
        this.displayName = displayName;
    }

    @Nullable
    public static GamepadButton fromAndroidKeyCode(int keyCode) {
        for (GamepadButton button : values()) {
            if (button.androidKeyCode == keyCode) {
                return button;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
