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

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.LwjglGlfwKeycode;

import org.lwjgl.glfw.CallbackBridge;

import java.lang.reflect.Method;

public final class GameActivityKeyboardInputFix {
    private static final String PLACEHOLDER_BEFORE_CURSOR = " ";

    private GameActivityKeyboardInputFix() {
    }
    public static void install(@NonNull View targetView) {
        targetView.setFocusable(true);
        targetView.setFocusableInTouchMode(true);
        targetView.requestFocus();
    }
    public static void showKeyboard(@NonNull View targetView) {
        install(targetView);
        targetView.post(() -> {
            Context context = targetView.getContext();
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(targetView, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    public static void hideKeyboard(@NonNull View targetView) {
        targetView.post(() -> {
            Context context = targetView.getContext();
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(targetView.getWindowToken(), 0);
            }
        });
    }

    @NonNull
    public static InputConnection createInputConnection(
            @NonNull View targetView,
            @NonNull EditorInfo outAttrs
    ) {
        install(targetView);

        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        outAttrs.initialSelStart = 0;
        outAttrs.initialSelEnd = 0;

        return new MinecraftInputConnection(targetView);
    }

    private static final class MinecraftInputConnection extends BaseInputConnection {
        private final StringBuilder shadowText = new StringBuilder();
        private String composingText = "";

        MinecraftInputConnection(@NonNull View targetView) {
            super(targetView, false);
        }

        @Override
        public CharSequence getTextBeforeCursor(int length, int flags) {
            if (shadowText.length() <= 0) {
                // Keep Android keyboards willing to send Backspace even when the
                // launcher cannot know Minecraft's current text, for example the
                // built-in "New World" default in the Create World screen.
                return PLACEHOLDER_BEFORE_CURSOR;
            }

            int start = Math.max(0, shadowText.length() - Math.max(0, length));
            return shadowText.substring(start);
        }

        @Override
        public CharSequence getSelectedText(int flags) {
            return "";
        }

        @Override
        public CharSequence getTextAfterCursor(int length, int flags) {
            return "";
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            String value = cleanText(text);
            if (value.isEmpty()) return true;

            if (!composingText.isEmpty()) {
                if (value.equals(composingText)) {
                    composingText = "";
                    return true;
                }
                backspaceCodePoints(codePointCount(composingText));
                trimShadowByCodePoints(codePointCount(composingText));
                composingText = "";
            }

            typeText(value);
            appendShadow(value);
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            String value = cleanText(text);

            if (!composingText.isEmpty()) {
                backspaceCodePoints(codePointCount(composingText));
                trimShadowByCodePoints(codePointCount(composingText));
            }

            composingText = value;
            if (!value.isEmpty()) {
                typeText(value);
                appendShadow(value);
            }
            return true;
        }

        @Override
        public boolean finishComposingText() {
            composingText = "";
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            sendDeleteEvents(beforeLength, afterLength);
            return true;
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
            sendDeleteEvents(beforeLength, afterLength);
            return true;
        }

        @Override
        public boolean setSelection(int start, int end) {
            // Minecraft owns the real cursor/selection. Returning true prevents
            // Android IMEs from falling back to broken default behavior.
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            if (event == null) return false;

            if (event.getAction() == KeyEvent.ACTION_MULTIPLE) {
                String chars = event.getCharacters();
                if (chars != null && !chars.isEmpty()) {
                    typeText(chars);
                    appendShadow(chars);
                    return true;
                }
                return true;
            }

            int action = event.getAction();
            if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
                return true;
            }

            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) {
                return true;
            }

            boolean down = action == KeyEvent.ACTION_DOWN;
            int glfwKey = androidKeyCodeToGlfw(event.getKeyCode());
            char keyChar = down ? resolveKeyChar(event) : 0;

            if (glfwKey >= 0) {
                sendKey(glfwKey, keyChar, 0, CallbackBridge.getCurrentMods(), down);
                if (down && glfwKey == LwjglGlfwKeycode.GLFW_KEY_BACKSPACE) trimShadowByCodePoints(1);
                return true;
            }

            if (down && keyChar != 0) {
                sendCharacter(keyChar);
                shadowText.append(keyChar);
            }
            return true;
        }

        @Override
        public boolean performEditorAction(int editorAction) {
            sendKeyTap(LwjglGlfwKeycode.GLFW_KEY_ENTER);
            return true;
        }

        @Override
        public boolean commitCompletion(android.view.inputmethod.CompletionInfo text) {
            if (text == null || text.getText() == null) return true;
            return commitText(text.getText(), 1);
        }

        @Override
        public boolean performPrivateCommand(String action, Bundle data) {
            return true;
        }

        @Override
        public boolean beginBatchEdit() {
            return true;
        }

        @Override
        public boolean endBatchEdit() {
            return true;
        }

        @Override
        public boolean clearMetaKeyStates(int states) {
            return true;
        }

        private void sendDeleteEvents(int beforeLength, int afterLength) {
            int before = Math.max(0, beforeLength);
            int after = Math.max(0, afterLength);

            if (!composingText.isEmpty()) {
                int composingCount = codePointCount(composingText);
                before = Math.max(before, composingCount);
                composingText = "";
            }

            backspaceCodePoints(before);
            trimShadowByCodePoints(before);

            for (int i = 0; i < after; i++) {
                sendKeyTap(LwjglGlfwKeycode.GLFW_KEY_DELETE);
            }
        }

        private void appendShadow(@NonNull String value) {
            shadowText.append(value);
            // Keep the local IME buffer tiny. Minecraft is the source of truth;
            // this is only to keep soft-keyboard delete behavior alive.
            if (shadowText.length() > 96) {
                shadowText.delete(0, shadowText.length() - 96);
            }
        }

        private void trimShadowByCodePoints(int count) {
            int safeCount = Math.max(0, count);
            for (int i = 0; i < safeCount && shadowText.length() > 0; i++) {
                int deleteFrom = shadowText.offsetByCodePoints(shadowText.length(), -1);
                shadowText.delete(deleteFrom, shadowText.length());
            }
        }
    }

    private static void backspaceCodePoints(int count) {
        for (int i = 0; i < Math.max(0, count); i++) {
            sendKeyTap(LwjglGlfwKeycode.GLFW_KEY_BACKSPACE);
        }
    }

    private static void typeText(@NonNull String text) {
        int offset = 0;
        while (offset < text.length()) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (codePoint == '\n' || codePoint == '\r') {
                sendKeyTap(LwjglGlfwKeycode.GLFW_KEY_ENTER);
                continue;
            }

            if (!Character.isValidCodePoint(codePoint) || Character.isISOControl(codePoint)) {
                continue;
            }

            if (codePoint <= Character.MAX_VALUE) {
                sendCharacter((char) codePoint);
            }
        }
    }

    private static void sendCharacter(char keyChar) {
        int glfwKey = charToGlfwKey(keyChar);
        int modifiers = modifiersForChar(keyChar);
        sendKey(glfwKey, keyChar, 0, modifiers, true);
        sendKey(glfwKey, keyChar, 0, modifiers, false);
    }

    private static void sendKeyTap(int glfwKey) {
        sendKey(glfwKey, (char) 0, 0, CallbackBridge.getCurrentMods(), true);
        sendKey(glfwKey, (char) 0, 0, CallbackBridge.getCurrentMods(), false);
    }

    private static void sendKey(int glfwKey, char keyChar, int scanCode, int modifiers, boolean down) {
        CallbackBridge.setInputReady(true);

        boolean sent = sendKeyByReflection(glfwKey, keyChar, scanCode, modifiers, down);
        if (!sent && glfwKey >= 0) {
            CallbackBridge.sendKeyPress(glfwKey, modifiers, down);
        }
    }

    private static boolean sendKeyByReflection(
            int glfwKey,
            char keyChar,
            int scanCode,
            int modifiers,
            boolean down
    ) {
        Class<?> bridgeClass = CallbackBridge.class;
        Object[][] attempts = new Object[][]{
                {"sendKeycode", new Class[]{int.class, char.class, int.class, int.class, boolean.class}, new Object[]{glfwKey, keyChar, scanCode, modifiers, down}},
                {"sendKeyCode", new Class[]{int.class, char.class, int.class, int.class, boolean.class}, new Object[]{glfwKey, keyChar, scanCode, modifiers, down}},
                {"putKeyboardEvent", new Class[]{int.class, char.class, int.class, int.class, boolean.class}, new Object[]{glfwKey, keyChar, scanCode, modifiers, down}},
                {"sendKeycode", new Class[]{int.class, int.class, boolean.class}, new Object[]{glfwKey, modifiers, down}}
        };

        for (Object[] attempt : attempts) {
            try {
                Method method = bridgeClass.getMethod((String) attempt[0], (Class<?>[]) attempt[1]);
                method.invoke(null, (Object[]) attempt[2]);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    @NonNull
    private static String cleanText(@Nullable CharSequence text) {
        if (text == null) return "";
        return text.toString().replace("\u0000", "");
    }

    private static int codePointCount(@NonNull String value) {
        return value.codePointCount(0, value.length());
    }

    private static char resolveKeyChar(@NonNull KeyEvent event) {
        try {
            int unicode = event.getUnicodeChar(event.getMetaState());
            if (unicode != 0) return (char) unicode;
        } catch (Throwable ignored) {
        }
        try {
            int unicode = event.getUnicodeChar();
            if (unicode != 0) return (char) unicode;
        } catch (Throwable ignored) {
        }
        return (char) 0;
    }

    private static int modifiersForChar(char c) {
        if (Character.isUpperCase(c)) {
            return 0x0001; // GLFW_MOD_SHIFT
        }
        switch (c) {
            case '!': case '@': case '#': case '$': case '%': case '^': case '&': case '*':
            case '(': case ')': case '_': case '+': case '{': case '}': case '|': case ':':
            case '"': case '<': case '>': case '?': case '~':
                return 0x0001; // GLFW_MOD_SHIFT
            default:
                return 0;
        }
    }

    private static int charToGlfwKey(char c) {
        char lower = Character.toLowerCase(c);
        if (lower >= 'a' && lower <= 'z') return 'A' + (lower - 'a');
        if (c >= '0' && c <= '9') return c;

        switch (c) {
            case ' ': return LwjglGlfwKeycode.GLFW_KEY_SPACE;
            case '\'': case '"': return LwjglGlfwKeycode.GLFW_KEY_APOSTROPHE;
            case ',': case '<': return LwjglGlfwKeycode.GLFW_KEY_COMMA;
            case '-': case '_': return LwjglGlfwKeycode.GLFW_KEY_MINUS;
            case '.': case '>': return LwjglGlfwKeycode.GLFW_KEY_PERIOD;
            case '/': case '?': return LwjglGlfwKeycode.GLFW_KEY_SLASH;
            case ';': case ':': return LwjglGlfwKeycode.GLFW_KEY_SEMICOLON;
            case '=': case '+': return LwjglGlfwKeycode.GLFW_KEY_EQUAL;
            case '[': case '{': return LwjglGlfwKeycode.GLFW_KEY_LEFT_BRACKET;
            case '\\': case '|': return LwjglGlfwKeycode.GLFW_KEY_BACKSLASH;
            case ']': case '}': return LwjglGlfwKeycode.GLFW_KEY_RIGHT_BRACKET;
            case '`': case '~': return LwjglGlfwKeycode.GLFW_KEY_GRAVE_ACCENT;
            case '!': return LwjglGlfwKeycode.GLFW_KEY_1;
            case '@': return LwjglGlfwKeycode.GLFW_KEY_2;
            case '#': return LwjglGlfwKeycode.GLFW_KEY_3;
            case '$': return LwjglGlfwKeycode.GLFW_KEY_4;
            case '%': return LwjglGlfwKeycode.GLFW_KEY_5;
            case '^': return LwjglGlfwKeycode.GLFW_KEY_6;
            case '&': return LwjglGlfwKeycode.GLFW_KEY_7;
            case '*': return LwjglGlfwKeycode.GLFW_KEY_8;
            case '(': return LwjglGlfwKeycode.GLFW_KEY_9;
            case ')': return LwjglGlfwKeycode.GLFW_KEY_0;
            default: return -1;
        }
    }

    private static int androidKeyCodeToGlfw(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return 'A' + (keyCode - KeyEvent.KEYCODE_A);
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return '0' + (keyCode - KeyEvent.KEYCODE_0);
        }
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
            return 290 + (keyCode - KeyEvent.KEYCODE_F1);
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return 320 + (keyCode - KeyEvent.KEYCODE_NUMPAD_0);
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE: return LwjglGlfwKeycode.GLFW_KEY_SPACE;
            case KeyEvent.KEYCODE_APOSTROPHE: return LwjglGlfwKeycode.GLFW_KEY_APOSTROPHE;
            case KeyEvent.KEYCODE_COMMA: return LwjglGlfwKeycode.GLFW_KEY_COMMA;
            case KeyEvent.KEYCODE_MINUS: return LwjglGlfwKeycode.GLFW_KEY_MINUS;
            case KeyEvent.KEYCODE_PERIOD: return LwjglGlfwKeycode.GLFW_KEY_PERIOD;
            case KeyEvent.KEYCODE_SLASH: return LwjglGlfwKeycode.GLFW_KEY_SLASH;
            case KeyEvent.KEYCODE_SEMICOLON: return LwjglGlfwKeycode.GLFW_KEY_SEMICOLON;
            case KeyEvent.KEYCODE_EQUALS: return LwjglGlfwKeycode.GLFW_KEY_EQUAL;
            case KeyEvent.KEYCODE_LEFT_BRACKET: return LwjglGlfwKeycode.GLFW_KEY_LEFT_BRACKET;
            case KeyEvent.KEYCODE_BACKSLASH: return LwjglGlfwKeycode.GLFW_KEY_BACKSLASH;
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return LwjglGlfwKeycode.GLFW_KEY_RIGHT_BRACKET;
            case KeyEvent.KEYCODE_GRAVE: return LwjglGlfwKeycode.GLFW_KEY_GRAVE_ACCENT;
            case KeyEvent.KEYCODE_ESCAPE: return LwjglGlfwKeycode.GLFW_KEY_ESCAPE;
            case KeyEvent.KEYCODE_BACK: return LwjglGlfwKeycode.GLFW_KEY_ESCAPE;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return LwjglGlfwKeycode.GLFW_KEY_ENTER;
            case KeyEvent.KEYCODE_TAB: return LwjglGlfwKeycode.GLFW_KEY_TAB;
            case KeyEvent.KEYCODE_DEL: return LwjglGlfwKeycode.GLFW_KEY_BACKSPACE;
            case KeyEvent.KEYCODE_INSERT: return LwjglGlfwKeycode.GLFW_KEY_INSERT;
            case KeyEvent.KEYCODE_FORWARD_DEL: return LwjglGlfwKeycode.GLFW_KEY_DELETE;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return LwjglGlfwKeycode.GLFW_KEY_RIGHT;
            case KeyEvent.KEYCODE_DPAD_LEFT: return LwjglGlfwKeycode.GLFW_KEY_LEFT;
            case KeyEvent.KEYCODE_DPAD_DOWN: return LwjglGlfwKeycode.GLFW_KEY_DOWN;
            case KeyEvent.KEYCODE_DPAD_UP: return LwjglGlfwKeycode.GLFW_KEY_UP;
            case KeyEvent.KEYCODE_PAGE_UP: return LwjglGlfwKeycode.GLFW_KEY_PAGE_UP;
            case KeyEvent.KEYCODE_PAGE_DOWN: return LwjglGlfwKeycode.GLFW_KEY_PAGE_DOWN;
            case KeyEvent.KEYCODE_MOVE_HOME: return LwjglGlfwKeycode.GLFW_KEY_HOME;
            case KeyEvent.KEYCODE_MOVE_END: return LwjglGlfwKeycode.GLFW_KEY_END;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT;
            case KeyEvent.KEYCODE_CTRL_LEFT: return LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL;
            case KeyEvent.KEYCODE_ALT_LEFT: return LwjglGlfwKeycode.GLFW_KEY_LEFT_ALT;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return LwjglGlfwKeycode.GLFW_KEY_RIGHT_SHIFT;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return LwjglGlfwKeycode.GLFW_KEY_RIGHT_CONTROL;
            case KeyEvent.KEYCODE_ALT_RIGHT: return LwjglGlfwKeycode.GLFW_KEY_RIGHT_ALT;
            case KeyEvent.KEYCODE_MENU: return LwjglGlfwKeycode.GLFW_KEY_MENU;
            default: return -1;
        }
    }
}
