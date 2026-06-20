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

package ca.dnamobile.javalauncher.game;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.javalauncher.R;
import ca.dnamobile.javalauncher.settings.GameOverlayPreferences;
import ca.dnamobile.javalauncher.settings.LauncherPreferences;
import org.lwjgl.glfw.CallbackBridge;

public final class FloatingGameSettingsOverlayController {
    private static final String WRAPPER_TAG = "game_settings_floating_wrapper";
    private static final long FPS_POLL_MS = 500L;
    private static final int DEFAULT_MARGIN_DP = 16;
    private static final int WRAPPER_SIZE_DP = 58;
    private static final int BUTTON_SIZE_DP = 48;

    @NonNull private final Activity activity;
    @NonNull private final ImageButton settingsButton;
    @NonNull private final Handler handler = new Handler(Looper.getMainLooper());
    private final int touchSlop;

    @Nullable private FrameLayout floatingContainer;
    @Nullable private TextView fpsText;
    private boolean attached;
    private boolean dragging;
    private float downRawX;
    private float downRawY;
    private int startLeft;
    private int startTop;

    private final Runnable fpsTicker = new Runnable() {
        @Override
        public void run() {
            refreshFromPreferences(false);
            handler.postDelayed(this, FPS_POLL_MS);
        }
    };

    public FloatingGameSettingsOverlayController(
            @NonNull Activity activity,
            @NonNull ImageButton settingsButton
    ) {
        this.activity = activity;
        this.settingsButton = settingsButton;
        this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
    }

    public void attach() {
        if (attached) return;
        attached = true;
        ensureWrapped();
        settingsButton.setOnTouchListener(this::onSettingsButtonTouch);
        refreshFromPreferences(true);
    }

    public void resume() {
        handler.removeCallbacks(fpsTicker);
        refreshFromPreferences(true);
        handler.post(fpsTicker);
    }

    public void pause() {
        handler.removeCallbacks(fpsTicker);
    }

    public void detach() {
        pause();
        settingsButton.setOnTouchListener(null);
        attached = false;
    }

    public void bringToFront() {
        FrameLayout wrapper = ensureWrapped();
        if (wrapper == null) return;
        wrapper.bringToFront();
        wrapper.setElevation(10000f);
        wrapper.setTranslationZ(10000f);
    }

    public void refreshFromPreferences() {
        refreshFromPreferences(true);
    }

    private void refreshFromPreferences(boolean applyPosition) {
        FrameLayout wrapper = ensureWrapped();
        if (wrapper == null) return;

        boolean showButton = LauncherPreferences.isShowInGameSettingsButton(activity);
        wrapper.setVisibility(showButton ? View.VISIBLE : View.GONE);
        settingsButton.setVisibility(showButton ? View.VISIBLE : View.GONE);

        if (applyPosition && showButton) {
            wrapper.post(this::applySavedPosition);
        }

        updateFpsText(showButton);
        if (showButton) bringToFront();
    }

    @Nullable
    private FrameLayout ensureWrapped() {
        if (floatingContainer != null) return floatingContainer;

        ViewGroup parent = settingsButton.getParent() instanceof ViewGroup
                ? (ViewGroup) settingsButton.getParent()
                : null;
        if (parent == null) return null;

        if (parent instanceof FrameLayout && WRAPPER_TAG.equals(parent.getTag())) {
            floatingContainer = (FrameLayout) parent;
            fpsText = findOrCreateFpsText(floatingContainer);
            return floatingContainer;
        }

        ViewGroup.LayoutParams originalParams = settingsButton.getLayoutParams();
        int index = parent.indexOfChild(settingsButton);
        parent.removeView(settingsButton);

        FrameLayout wrapper = new FrameLayout(activity);
        wrapper.setTag(WRAPPER_TAG);
        wrapper.setClipChildren(false);
        wrapper.setClipToPadding(false);
        wrapper.setClickable(false);
        wrapper.setFocusable(false);

        ViewGroup.LayoutParams wrapperParams = buildWrapperLayoutParams(originalParams);
        parent.addView(wrapper, Math.max(0, index), wrapperParams);

        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                dpToPx(BUTTON_SIZE_DP),
                dpToPx(BUTTON_SIZE_DP),
                Gravity.CENTER
        );
        settingsButton.setLayoutParams(buttonParams);
        wrapper.addView(settingsButton);

        fpsText = findOrCreateFpsText(wrapper);
        floatingContainer = wrapper;
        return wrapper;
    }

    @NonNull
    private ViewGroup.LayoutParams buildWrapperLayoutParams(@Nullable ViewGroup.LayoutParams originalParams) {
        int size = dpToPx(WRAPPER_SIZE_DP);

        if (originalParams instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) originalParams;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Math.max(size, old.width > 0 ? old.width : size),
                    Math.max(size, old.height > 0 ? old.height : size)
            );
            lp.gravity = old.gravity;
            lp.leftMargin = old.leftMargin;
            lp.topMargin = old.topMargin;
            lp.rightMargin = old.rightMargin;
            lp.bottomMargin = old.bottomMargin;
            return lp;
        }

        return new FrameLayout.LayoutParams(
                size,
                size,
                Gravity.END | Gravity.BOTTOM
        );
    }

    @NonNull
    private TextView findOrCreateFpsText(@NonNull FrameLayout wrapper) {
        View existing = wrapper.findViewWithTag("game_settings_fps_badge");
        if (existing instanceof TextView) return (TextView) existing;

        TextView text = new TextView(activity);
        text.setTag("game_settings_fps_badge");
        text.setBackgroundResource(R.drawable.bg_fps_badge);
        text.setGravity(Gravity.CENTER);
        text.setIncludeFontPadding(false);
        text.setMinWidth(dpToPx(42));
        text.setPadding(dpToPx(5), 0, dpToPx(5), 0);
        text.setText("FPS");
        text.setTextColor(0xFFFFFFFF);
        text.setTextSize(8f);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setVisibility(View.GONE);
        text.setClickable(false);
        text.setFocusable(false);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dpToPx(18),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        wrapper.addView(text, params);
        return text;
    }

    private void updateFpsText(boolean showButton) {
        TextView text = fpsText;
        if (text == null) return;

        boolean showFps = showButton && GameOverlayPreferences.isShowGameFpsCounter(activity);
        if (!showFps) {
            text.setVisibility(View.GONE);
            return;
        }

        int fps;
        try {
            fps = Math.max(0, CallbackBridge.getCurrentFps());
        } catch (Throwable ignored) {
            fps = 0;
        }

        text.setText(fps > 0 ? fps + " FPS" : "FPS");
        text.setVisibility(View.VISIBLE);
    }

    private boolean onSettingsButtonTouch(View view, MotionEvent event) {
        FrameLayout wrapper = ensureWrapped();
        if (wrapper == null) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = false;
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                startLeft = wrapper.getLeft();
                startTop = wrapper.getTop();
                if (view.getParent() != null) {
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    dragging = true;
                    forceAbsolutePosition(startLeft, startTop);
                }
                if (dragging) {
                    moveTo(Math.round(startLeft + dx), Math.round(startTop + dy), false);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (view.getParent() != null) {
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                }
                if (dragging) {
                    saveCurrentPosition();
                    dragging = false;
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    view.performClick();
                }
                return true;

            default:
                return true;
        }
    }

    private void applySavedPosition() {
        FrameLayout wrapper = ensureWrapped();
        ViewGroup parent = parentViewGroup();
        if (wrapper == null || parent == null) return;

        if (GameOverlayPreferences.hasCustomGameSettingsButtonPosition(activity)) {
            int left = dpToPx(GameOverlayPreferences.getGameSettingsButtonCustomLeftDp(activity));
            int top = dpToPx(GameOverlayPreferences.getGameSettingsButtonCustomTopDp(activity));
            moveTo(left, top, false);
            return;
        }

        FrameLayout.LayoutParams lp = ensureFrameLayoutParams(wrapper);
        lp.gravity = gravityForPlacement(GameOverlayPreferences.getGameSettingsButtonPlacement(activity));
        int margin = dpToPx(DEFAULT_MARGIN_DP);
        lp.leftMargin = margin;
        lp.topMargin = margin;
        lp.rightMargin = margin;
        lp.bottomMargin = margin;
        wrapper.setTranslationX(0f);
        wrapper.setTranslationY(0f);
        wrapper.setLayoutParams(lp);
    }

    private void forceAbsolutePosition(int left, int top) {
        FrameLayout wrapper = ensureWrapped();
        if (wrapper == null) return;
        FrameLayout.LayoutParams lp = ensureFrameLayoutParams(wrapper);
        lp.gravity = Gravity.START | Gravity.TOP;
        lp.rightMargin = 0;
        lp.bottomMargin = 0;
        wrapper.setLayoutParams(lp);
        moveTo(left, top, false);
    }

    private void moveTo(int requestedLeft, int requestedTop, boolean save) {
        FrameLayout wrapper = ensureWrapped();
        ViewGroup parent = parentViewGroup();
        if (wrapper == null || parent == null) return;

        int maxLeft = Math.max(0, parent.getWidth() - wrapper.getWidth());
        int maxTop = Math.max(0, parent.getHeight() - wrapper.getHeight());
        int left = clamp(requestedLeft, 0, maxLeft);
        int top = clamp(requestedTop, 0, maxTop);

        FrameLayout.LayoutParams lp = ensureFrameLayoutParams(wrapper);
        lp.gravity = Gravity.START | Gravity.TOP;
        lp.leftMargin = left;
        lp.topMargin = top;
        lp.rightMargin = 0;
        lp.bottomMargin = 0;
        wrapper.setTranslationX(0f);
        wrapper.setTranslationY(0f);
        wrapper.setLayoutParams(lp);

        if (save) {
            GameOverlayPreferences.setGameSettingsButtonCustomPosition(activity, pxToDp(left), pxToDp(top));
        }
    }

    private void saveCurrentPosition() {
        FrameLayout wrapper = ensureWrapped();
        if (wrapper == null) return;
        moveTo(wrapper.getLeft(), wrapper.getTop(), true);
    }

    @Nullable
    private ViewGroup parentViewGroup() {
        FrameLayout wrapper = floatingContainer;
        return wrapper != null && wrapper.getParent() instanceof ViewGroup
                ? (ViewGroup) wrapper.getParent()
                : null;
    }

    @NonNull
    private FrameLayout.LayoutParams ensureFrameLayoutParams(@NonNull FrameLayout wrapper) {
        ViewGroup.LayoutParams current = wrapper.getLayoutParams();
        if (current instanceof FrameLayout.LayoutParams) {
            return (FrameLayout.LayoutParams) current;
        }
        return new FrameLayout.LayoutParams(current.width, current.height);
    }

    private static int gravityForPlacement(@Nullable String placement) {
        if (GameOverlayPreferences.PLACEMENT_TOP_LEFT.equals(placement)) {
            return Gravity.START | Gravity.TOP;
        }
        if (GameOverlayPreferences.PLACEMENT_TOP_RIGHT.equals(placement)) {
            return Gravity.END | Gravity.TOP;
        }
        if (GameOverlayPreferences.PLACEMENT_BOTTOM_LEFT.equals(placement)) {
            return Gravity.START | Gravity.BOTTOM;
        }
        return Gravity.END | Gravity.BOTTOM;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * activity.getResources().getDisplayMetrics().density);
    }

    private int pxToDp(int px) {
        return Math.round(px / activity.getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
