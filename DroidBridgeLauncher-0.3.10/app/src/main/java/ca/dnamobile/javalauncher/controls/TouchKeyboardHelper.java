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

package ca.dnamobile.javalauncher.controls;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.lwjgl.glfw.CallbackBridge;

import java.lang.reflect.Method;

import ca.dnamobile.javalauncher.feature.log.Logging;

/**
 * Runtime Android keyboard bridge for touch controls.
 *
 * Android IMEs always open from the bottom of the screen, so this helper keeps the
 * system keyboard as-is and adds a small top overlay showing what the user is typing.
 */
final class TouchKeyboardHelper {
    private static final String TAG = "TouchKeyboardHelper";

    private static final int GLFW_PRESS_KEY_ENTER = 257;
    private static final int GLFW_PRESS_KEY_BACKSPACE = 259;
    private static final int GLFW_PRESS_KEY_ESCAPE = 256;
    private static final int DEFAULT_CLEAR_BACKSPACES = 64;
    private static final long CHAT_KEYBOARD_MODE_GRACE_MS = 10_000L;
    static final String DEFAULT_WORLD_NAME_TEXT = "New World";

    @Nullable private static KeyboardInputOverlay activeOverlay;
    private static long lastChatKeyPressUptimeMs;

    private TouchKeyboardHelper() {
    }

    static void showKeyboard(@NonNull View source) {
        // Capture the mode at the moment the keyboard is opened.
        // In the normal game view, Minecraft is grabbing input, so Done/Enter
        // should submit chat. If the player pressed the launcher Chat/T key and
        // then opened the Android keyboard, Minecraft may already have released
        // grab for the chat screen, so keep a short chat grace window too.
        // Menu text fields such as Create World name are opened through
        // showMenuTextKeyboard(...) so they use DONE instead.
        showKeyboard(source, shouldSubmitWithEnterByDefault(), null, false);
    }

    static void showChatKeyboard(@NonNull View source) {
        // Explicit launcher keyboard buttons are intended for Minecraft chat. Do not
        // depend on CallbackBridge.isGrabbing() here, because opening chat releases
        // mouse grab before the user presses the Android keyboard button.
        markChatKeyPressed();
        showKeyboard(source, true, null, false);
    }

    static void showMenuTextKeyboard(@NonNull View source) {
        showKeyboard(source, false, null, false);
    }

    static void showWorldNameKeyboard(@NonNull View source) {
        // Best-effort seed for the vanilla Create World name box. Android cannot
        // read Minecraft's internal text field, but seeding the IME makes the
        // common "New World" flow editable without manual selection.
        showKeyboard(source, false, DEFAULT_WORLD_NAME_TEXT, true);
    }

    static void markChatKeyPressed() {
        lastChatKeyPressUptimeMs = SystemClock.uptimeMillis();
    }

    private static boolean shouldSubmitWithEnterByDefault() {
        if (CallbackBridge.isGrabbing()) return true;
        long ageMs = SystemClock.uptimeMillis() - lastChatKeyPressUptimeMs;
        return ageMs >= 0L && ageMs <= CHAT_KEYBOARD_MODE_GRACE_MS;
    }

    static void showKeyboard(@NonNull View source, boolean submitSendsEnter) {
        showKeyboard(source, submitSendsEnter, null, false);
    }

    private static void showKeyboard(
            @NonNull View source,
            boolean submitSendsEnter,
            @Nullable String initialText,
            boolean selectAllInitialText
    ) {
        hideKeyboard(false);

        View root = source.getRootView();
        if (root == null) root = source;

        FrameLayout host = findFrameLayout(root);
        if (host == null) {
            // Last-resort fallback. This shows the keyboard but cannot draw the preview.
            source.requestFocus();
            InputMethodManager manager = (InputMethodManager) source.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.showSoftInput(source, InputMethodManager.SHOW_IMPLICIT);
            }
            return;
        }

        KeyboardInputOverlay overlay = new KeyboardInputOverlay(host.getContext(), submitSendsEnter, initialText, selectAllInitialText);
        activeOverlay = overlay;
        host.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        overlay.openKeyboard();
    }

    static void hideKeyboard(boolean clearText) {
        KeyboardInputOverlay overlay = activeOverlay;
        if (overlay == null) return;
        activeOverlay = null;
        overlay.close(clearText);
    }

    static boolean isKeyboardShowing() {
        return activeOverlay != null;
    }

    static boolean isChatKeyboardShowing() {
        KeyboardInputOverlay overlay = activeOverlay;
        return overlay != null && overlay.submitsEnter();
    }

    static void notifyMinecraftTextChangedExternally() {
        KeyboardInputOverlay overlay = activeOverlay;
        if (overlay != null) {
            overlay.rebaseAfterExternalMinecraftEdit();
        }
    }

    @Nullable
    private static FrameLayout findFrameLayout(@NonNull View view) {
        if (view instanceof FrameLayout) return (FrameLayout) view;

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                FrameLayout found = findFrameLayout(group.getChildAt(i));
                if (found != null) return found;
            }
        }

        return null;
    }

    /**
     * A normal EditText does not always report Backspace when it is already empty.
     * That breaks Minecraft fields that already contain text before this overlay is
     * opened, because Android thinks the overlay has nothing to delete while
     * Minecraft still has text like "New World".
     */
    private static final class MinecraftKeyboardEditText extends EditText {
        MinecraftKeyboardEditText(@NonNull Context context) {
            super(context);
        }

        @Nullable
        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            InputConnection base = super.onCreateInputConnection(outAttrs);
            if (base == null) return null;

            return new InputConnectionWrapper(base, true) {
                @Override
                public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                    if (shouldForwardEmptyBackspace(beforeLength, afterLength)) {
                        sendRepeatedBackspace(Math.max(1, beforeLength));
                        return true;
                    }
                    return super.deleteSurroundingText(beforeLength, afterLength);
                }

                @Override
                public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
                    if (shouldForwardEmptyBackspace(beforeLength, afterLength)) {
                        sendRepeatedBackspace(Math.max(1, beforeLength));
                        return true;
                    }
                    return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
                }

                @Override
                public boolean sendKeyEvent(KeyEvent event) {
                    if (event != null
                            && event.getAction() == KeyEvent.ACTION_DOWN
                            && event.getRepeatCount() == 0
                            && event.getKeyCode() == KeyEvent.KEYCODE_DEL
                            && isOverlayTextEmpty()) {
                        sendKeyTap(GLFW_PRESS_KEY_BACKSPACE);
                        return true;
                    }
                    return super.sendKeyEvent(event);
                }
            };
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_DEL && isOverlayTextEmpty()) {
                sendKeyTap(GLFW_PRESS_KEY_BACKSPACE);
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        private boolean shouldForwardEmptyBackspace(int beforeLength, int afterLength) {
            return beforeLength > 0 && afterLength == 0 && isOverlayTextEmpty();
        }

        private boolean isOverlayTextEmpty() {
            Editable editable = getText();
            return editable == null || editable.length() == 0;
        }
    }

    private static final class KeyboardInputOverlay extends FrameLayout {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final LinearLayout panel;
        private final EditText input;
        private final TextView preview;
        private final boolean submitSendsEnter;
        private String lastText = "";
        private boolean closing;
        private boolean internalChange;

        KeyboardInputOverlay(
                @NonNull Context context,
                boolean submitSendsEnter,
                @Nullable String initialText,
                boolean selectAllInitialText
        ) {
            super(context);
            this.submitSendsEnter = submitSendsEnter;
            setClickable(false);
            setFocusable(false);
            setFocusableInTouchMode(false);
            setClipChildren(false);
            setClipToPadding(false);

            panel = new LinearLayout(context);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(14f), dp(10f), dp(14f), dp(10f));
            panel.setBackground(makePanelBackground());
            panel.setClickable(true);
            panel.setFocusable(false);

            TextView title = new TextView(context);
            title.setText("Android keyboard input");
            title.setTextColor(Color.WHITE);
            title.setTextSize(13f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            panel.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            preview = new TextView(context);
            preview.setText("Type here…");
            preview.setTextColor(0xFFE0E0E0);
            preview.setTextSize(18f);
            preview.setSingleLine(false);
            preview.setMinLines(1);
            preview.setMaxLines(2);
            preview.setPadding(0, dp(6f), 0, dp(6f));
            panel.addView(preview, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            input = new MinecraftKeyboardEditText(context);
            input.setSingleLine(true);
            input.setMinLines(1);
            input.setMaxLines(1);
            input.setTextColor(Color.WHITE);
            input.setHintTextColor(0xAAFFFFFF);
            input.setTextSize(16f);
            input.setHint("Text sent to Minecraft");
            input.setSelectAllOnFocus(false);
            input.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setImeOptions((submitSendsEnter ? EditorInfo.IME_ACTION_SEND : EditorInfo.IME_ACTION_DONE)
                    | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            input.setBackgroundColor(0x22000000);
            input.setPadding(dp(8f), dp(6f), dp(8f), dp(6f));
            panel.addView(input, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            LinearLayout buttons = new LinearLayout(context);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            buttons.setPadding(0, dp(8f), 0, 0);

            TextView clear = actionText(context, "CLEAR");
            clear.setOnClickListener(v -> {
                if (lastText.isEmpty()) {
                    // The Android overlay may be empty while Minecraft's focused
                    // field still contains its default value, such as "New World".
                    // In that case, send enough backspaces to clear Minecraft's field.
                    sendRepeatedBackspace(DEFAULT_CLEAR_BACKSPACES);
                } else {
                    dispatchTextDelta(lastText, "");
                }
                internalChange = true;
                input.setText("");
                lastText = "";
                internalChange = false;
                updatePreview("");
            });
            buttons.addView(clear);

            TextView enter = actionText(context, submitSendsEnter ? "ENTER" : "DONE");
            enter.setOnClickListener(v -> submitCurrentText());
            buttons.addView(enter);

            TextView close = actionText(context, "CLOSE");
            close.setOnClickListener(v -> TouchKeyboardHelper.hideKeyboard(false));
            buttons.addView(close);

            panel.addView(buttons, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL
            );
            panelParams.leftMargin = dp(12f);
            panelParams.rightMargin = dp(12f);
            panelParams.topMargin = dp(12f);
            addView(panel, panelParams);

            input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
                @Override public void afterTextChanged(Editable editable) {
                    if (closing || internalChange) return;
                    String current = editable == null ? "" : editable.toString();
                    dispatchTextDelta(lastText, current);
                    lastText = current;
                    updatePreview(current);
                }
            });

            input.setOnEditorActionListener((v, actionId, event) -> {
                boolean imeDoneOrSend = actionId == EditorInfo.IME_ACTION_DONE
                        || actionId == EditorInfo.IME_ACTION_SEND
                        || actionId == EditorInfo.IME_ACTION_GO
                        || actionId == EditorInfo.IME_ACTION_UNSPECIFIED;
                boolean enterKey = event != null
                        && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0;

                if (imeDoneOrSend || enterKey) {
                    submitCurrentText();
                    return true;
                }
                return false;
            });

            input.setOnKeyListener((v, keyCode, event) -> {
                if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    TouchKeyboardHelper.hideKeyboard(false);
                    return true;
                }
                return false;
            });

            seedInitialText(initialText, selectAllInitialText);
        }

        private void seedInitialText(@Nullable String value, boolean selectAll) {
            if (value == null || value.isEmpty()) return;
            internalChange = true;
            input.setText(value);
            lastText = value;
            int length = input.length();
            try {
                if (selectAll && length > 0) input.setSelection(0, length);
                else input.setSelection(length);
            } catch (Throwable ignored) {
            }
            internalChange = false;
            updatePreview(value);
        }

        void openKeyboard() {
            input.requestFocus();
            handler.postDelayed(this::showSoftInputAgain, 80);
        }

        boolean submitsEnter() {
            return submitSendsEnter;
        }

        void rebaseAfterExternalMinecraftEdit() {
            if (!submitSendsEnter || closing) return;
            handler.post(() -> {
                if (closing) return;
                internalChange = true;
                input.setText("");
                input.setSelection(0);
                lastText = "";
                internalChange = false;
                preview.setText("Minecraft chat updated from selection…");
                input.requestFocus();
                showSoftInputAgain();
            });
        }

        private void showSoftInputAgain() {
            InputMethodManager manager = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        }

        void close(boolean clearText) {
            closing = true;
            InputMethodManager manager = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.hideSoftInputFromWindow(input.getWindowToken(), 0);
            }
            if (clearText) input.setText("");
            ViewGroup parent = (ViewGroup) getParent();
            if (parent != null) parent.removeView(this);
        }

        private void submitCurrentText() {
            // Text is sent as the user types. In menu text fields such as the
            // Create World name box, Android Enter/Done must only close this IME
            // overlay. Pressing Minecraft Enter there activates the focused
            // button and can create the world by accident.
            if (submitSendsEnter) {
                sendKeyTap(GLFW_PRESS_KEY_ENTER);
            }
            TouchKeyboardHelper.hideKeyboard(true);
        }

        private void updatePreview(@NonNull String value) {
            String trimmed = value.trim();
            preview.setText(trimmed.isEmpty() ? "Type here…" : value);
        }

        private void dispatchTextDelta(@NonNull String oldText, @NonNull String newText) {
            int prefix = 0;
            int minLength = Math.min(oldText.length(), newText.length());
            while (prefix < minLength && oldText.charAt(prefix) == newText.charAt(prefix)) {
                prefix++;
            }

            int oldSuffix = oldText.length() - 1;
            int newSuffix = newText.length() - 1;
            while (oldSuffix >= prefix
                    && newSuffix >= prefix
                    && oldText.charAt(oldSuffix) == newText.charAt(newSuffix)) {
                oldSuffix--;
                newSuffix--;
            }

            int removed = oldSuffix - prefix + 1;
            for (int i = 0; i < removed; i++) {
                sendKeyTap(GLFW_PRESS_KEY_BACKSPACE);
            }

            if (newSuffix >= prefix) {
                String inserted = newText.substring(prefix, newSuffix + 1);
                for (int i = 0; i < inserted.length(); i++) {
                    char c = inserted.charAt(i);
                    if (c == '\n' || c == '\r') {
                        // Some IMEs insert a newline instead of sending the Done
                        // editor action. Only forward that as Minecraft Enter in
                        // in-game/chat mode. In menu text fields, close the IME
                        // without pressing Minecraft Enter.
                        if (submitSendsEnter) {
                            sendKeyTap(GLFW_PRESS_KEY_ENTER);
                        }
                        TouchKeyboardHelper.hideKeyboard(true);
                    } else {
                        sendChar(c);
                    }
                }
            }
        }

        private TextView actionText(@NonNull Context context, @NonNull String text) {
            TextView view = new TextView(context);
            view.setText(text);
            view.setTextColor(0xFF7EE787);
            view.setTextSize(13f);
            view.setTypeface(Typeface.DEFAULT_BOLD);
            view.setGravity(Gravity.CENTER);
            view.setPadding(dp(14f), dp(8f), dp(14f), dp(8f));
            return view;
        }

        @NonNull
        private GradientDrawable makePanelBackground() {
            GradientDrawable drawable = new GradientDrawable();
            drawable.setColor(0xEE202124);
            drawable.setStroke(Math.max(1, dp(1.5f)), 0x88FFFFFF);
            drawable.setCornerRadius(dp(18f));
            return drawable;
        }

        private int dp(float value) {
            return Math.round(value * getResources().getDisplayMetrics().density);
        }
    }

    private static void sendChar(char c) {
        CallbackBridge.setInputReady(true);

        if (sendCharByReflection(c)) {
            return;
        }

        // Fallback for bridges that do not expose a char callback. This will at least
        // handle control keys and some old text fields, but the reflection path above is
        // the preferred path for normal Minecraft chat/sign input.
        sendAsciiFallback(c);
    }

    private static boolean sendCharByReflection(char c) {
        Class<?> clazz = CallbackBridge.class;
        Object[][] attempts = new Object[][]{
                // Pojav/Zalith-style bridge: public static void sendChar(char, int).
                // This is the path Minecraft chat/text boxes actually listen to.
                {"sendChar", new Class[]{char.class, int.class}, new Object[]{c, CallbackBridge.getCurrentMods()}},
                {"sendChar", new Class[]{char.class, int.class}, new Object[]{c, 0}},

                // Older/alternate bridge names used by experimental ports.
                {"sendChar", new Class[]{int.class}, new Object[]{(int) c}},
                {"sendChar", new Class[]{char.class}, new Object[]{c}},
                {"sendCharMods", new Class[]{int.class, int.class}, new Object[]{(int) c, CallbackBridge.getCurrentMods()}},
                {"sendCharMods", new Class[]{char.class, int.class}, new Object[]{c, CallbackBridge.getCurrentMods()}},
                {"putChar", new Class[]{int.class}, new Object[]{(int) c}},
                {"putCharEvent", new Class[]{int.class}, new Object[]{(int) c}},

                // Last reflection option: use CallbackBridge.sendKeycode(...) with key=0
                // and a real keychar. This triggers the bridge char callback without
                // needing to expose nativeSendChar directly.
                {"sendKeycode", new Class[]{int.class, char.class, int.class, int.class, boolean.class}, new Object[]{0, c, 0, CallbackBridge.getCurrentMods(), true}}
        };

        for (Object[] attempt : attempts) {
            try {
                String methodName = (String) attempt[0];
                Class<?>[] parameterTypes = (Class<?>[]) attempt[1];
                Object[] args = (Object[]) attempt[2];
                Method method = clazz.getMethod(methodName, parameterTypes);
                method.invoke(null, args);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static void sendAsciiFallback(char c) {
        if (c == '\b') {
            sendKeyTap(GLFW_PRESS_KEY_BACKSPACE);
            return;
        }
        if (c == '\n' || c == '\r') {
            sendKeyTap(GLFW_PRESS_KEY_ENTER);
            return;
        }
        if (c == 27) {
            sendKeyTap(GLFW_PRESS_KEY_ESCAPE);
            return;
        }

        int key = keyCodeForChar(c);
        if (key >= 0) {
            sendKeyTap(key);
        }
    }

    private static int keyCodeForChar(char c) {
        if (c >= 'a' && c <= 'z') return 'A' + (c - 'a');
        if (c >= 'A' && c <= 'Z') return c;
        if (c >= '0' && c <= '9') return c;
        if (c == ' ') return 32;
        if (c == '-') return 45;
        if (c == '=') return 61;
        if (c == '[') return 91;
        if (c == ']') return 93;
        if (c == '\\') return 92;
        if (c == ';') return 59;
        if (c == '\'') return 39;
        if (c == ',') return 44;
        if (c == '.') return 46;
        if (c == '/') return 47;
        if (c == '`') return 96;
        return -1;
    }

    private static void sendRepeatedBackspace(int count) {
        int safeCount = Math.max(1, Math.min(DEFAULT_CLEAR_BACKSPACES, count));
        for (int i = 0; i < safeCount; i++) {
            sendKeyTap(GLFW_PRESS_KEY_BACKSPACE);
        }
    }

    private static void sendKeyTap(int keyCode) {
        try {
            CallbackBridge.setInputReady(true);
            CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), true);
            CallbackBridge.setModifiers(keyCode, true);
            CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), false);
            CallbackBridge.setModifiers(keyCode, false);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send keyboard key tap " + keyCode, throwable);
        }
    }
}
