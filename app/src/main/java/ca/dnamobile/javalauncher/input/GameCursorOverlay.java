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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

import ca.dnamobile.javalauncher.controls.ControlsPreferences;

import org.lwjgl.glfw.CallbackBridge;
public final class GameCursorOverlay extends View {
    private static final float CURSOR_CANVAS_DP = 28f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path cursorPath = new Path();
    private final GamepadMappingStore mappingStore;
    @Nullable private Bitmap cursorBitmap;
    @NonNull private String loadedCursorStyle = "";
    @Nullable private String loadedCustomCursorPath;
    private int loadedCursorSizePercent = -1;

    @Nullable private ViewGroup overlayParent;
    private boolean drawableAdded;
    private boolean removed;
    private boolean cursorVisible;
    private boolean lastMenuMode;
    private boolean lastShouldShow;

    private final Drawable cursorDrawable = new Drawable() {
        @Override
        public void draw(@NonNull Canvas canvas) {
            if (!cursorVisible) return;

            Rect bounds = getBounds();
            if (bounds.width() <= 0 || bounds.height() <= 0) return;

            Bitmap bitmap = cursorBitmap;
            if (bitmap != null && !bitmap.isRecycled()) {
                canvas.drawBitmap(bitmap, null, bounds, null);
                return;
            }

            canvas.save();
            canvas.translate(bounds.left, bounds.top);
            drawFallbackCrosshair(canvas, bounds.width(), bounds.height());
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            fillPaint.setAlpha(alpha);
            strokePaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            fillPaint.setColorFilter(colorFilter);
            strokePaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    };

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            updateFromBridge();
            if (!removed) {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

    public GameCursorOverlay(@NonNull Context context) {
        super(context);
        mappingStore = GamepadMappingStore.get(context);
        reloadCursorBitmapIfNeeded(true);

        // This view is only a lifecycle owner for the overlay drawable.
        // Keep it out of layout hit testing completely.
        setVisibility(GONE);
        setWillNotDraw(true);
        setClickable(false);
        setLongClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setHapticFeedbackEnabled(false);
        setSoundEffectsEnabled(false);
        setEnabled(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        fillPaint.setColor(Color.WHITE);
        fillPaint.setStyle(Paint.Style.FILL);

        strokePaint.setColor(Color.BLACK);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(4.0f));
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStrokeWidth(dp(1.8f));
        fillPaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.STROKE);

        buildPath();

        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setVisibility(GONE);
        attachDrawableToParent();
    }

    @Override
    protected void onDetachedFromWindow() {
        detachDrawableFromParent();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Stay effectively non-existent for hit testing/layout.
        setMeasuredDimension(1, 1);
    }

    public void removeSelf() {
        removed = true;
        cursorVisible = false;
        cursorDrawable.setBounds(0, 0, 0, 0);
        cursorDrawable.invalidateSelf();
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        detachDrawableFromParent();
    }

    private void attachDrawableToParent() {
        if (drawableAdded) return;
        if (!(getParent() instanceof ViewGroup)) return;

        overlayParent = (ViewGroup) getParent();
        overlayParent.getOverlay().add(cursorDrawable);
        drawableAdded = true;
    }

    private void detachDrawableFromParent() {
        if (!drawableAdded || overlayParent == null) return;

        overlayParent.getOverlay().remove(cursorDrawable);
        drawableAdded = false;
        overlayParent = null;
    }

    private void buildPath() {
        // The old GameCursorOverlay drew a classic arrow here. That caused the
        // visible "mouse over crosshair" bug once TouchControlsOverlay also
        // drew the Mojo-style pointer. Keep the method for source compatibility;
        // drawing now happens through drawFallbackCrosshair() or the bundled
        // ic_gamepad_pointer.png asset.
        cursorPath.reset();
    }

    private void drawFallbackCrosshair(@NonNull Canvas canvas, int width, int height) {
        float centerX = width / 2f;
        float centerY = height / 2f;
        float density = getResources().getDisplayMetrics().density;
        float arm = 11f * density;
        float gap = 3.5f * density;
        float ringRadius = 6.5f * density;

        drawCrosshairLines(canvas, centerX, centerY, arm, gap, ringRadius, strokePaint);
        drawCrosshairLines(canvas, centerX, centerY, arm, gap, ringRadius, fillPaint);
    }

    private static void drawCrosshairLines(
            @NonNull Canvas canvas,
            float x,
            float y,
            float arm,
            float gap,
            float ringRadius,
            @NonNull Paint paint
    ) {
        canvas.drawLine(x - arm, y, x - gap, y, paint);
        canvas.drawLine(x + gap, y, x + arm, y, paint);
        canvas.drawLine(x, y - arm, x, y - gap, paint);
        canvas.drawLine(x, y + gap, x, y + arm, paint);
        canvas.drawCircle(x, y, ringRadius, paint);
    }

    private void reloadCursorBitmapIfNeeded(boolean force) {
        String style = ControlsPreferences.getMouseCursorStyle(getContext());
        String customPath = ControlsPreferences.getCustomMouseCursorPath(getContext());
        int sizePercent = ControlsPreferences.getMouseCursorSizePercent(getContext());

        boolean sameStyle = style.equals(loadedCursorStyle);
        boolean samePath = customPath == null ? loadedCustomCursorPath == null : customPath.equals(loadedCustomCursorPath);
        boolean sameSize = sizePercent == loadedCursorSizePercent;
        if (!force && sameStyle && samePath && sameSize) return;

        loadedCursorStyle = style;
        loadedCustomCursorPath = customPath;
        loadedCursorSizePercent = sizePercent;
        cursorBitmap = loadCursorBitmap(getContext(), style, customPath);
        cursorDrawable.invalidateSelf();
    }

    @Nullable
    private static Bitmap loadCursorBitmap(
            @NonNull Context context,
            @NonNull String style,
            @Nullable String customPath
    ) {
        if (ControlsPreferences.MOUSE_CURSOR_STYLE_CUSTOM.equals(style) && customPath != null) {
            try {
                File file = new File(customPath);
                if (file.isFile() && file.length() > 0L) {
                    Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                    if (bitmap != null) return bitmap;
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            int id = context.getResources().getIdentifier(
                    ControlsPreferences.getMouseCursorResourceName(style),
                    "drawable",
                    context.getPackageName()
            );
            return id != 0 ? BitmapFactory.decodeResource(context.getResources(), id) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void updateFromBridge() {
        attachDrawableToParent();
        reloadCursorBitmapIfNeeded(false);

        boolean menuMode = !mappingStore.isForceGameMode() && !CallbackBridge.isGrabbing();
        boolean physicalPointerConnected = hasPhysicalPointerDevice();

        // Touch virtual mouse and controller cursor are both menu cursors.
        // The controller cursor must still show when a controller is attached;
        // some Android handhelds expose controller hardware with pointer-ish
        // sources, so do not let physicalPointerConnected hide controller mode.
        boolean showTouchVirtualCursor = ControlsPreferences.isVirtualMouseEnabled(getContext())
                && menuMode
                && !physicalPointerConnected;
        boolean showControllerMenuCursor = mappingStore.isShowCursorOverlay()
                && menuMode;
        boolean shouldShow = showTouchVirtualCursor || showControllerMenuCursor;

        if (shouldShow && (!lastShouldShow || (!lastMenuMode && menuMode))) {
            resetBridgeCursorToCenter();
        }

        lastMenuMode = menuMode;
        lastShouldShow = shouldShow;
        cursorVisible = shouldShow;

        if (!shouldShow || overlayParent == null) {
            cursorDrawable.setBounds(0, 0, 0, 0);
            cursorDrawable.invalidateSelf();
            return;
        }

        int rootWidth = Math.max(1, overlayParent.getWidth());
        int rootHeight = Math.max(1, overlayParent.getHeight());
        int cursorSize = Math.max(1, Math.round(
                dp(CURSOR_CANVAS_DP)
                        * ControlsPreferences.getMouseCursorSizePercent(getContext())
                        / 100f
        ));

        float bridgeWidth = Math.max(1f, CallbackBridge.windowWidth > 0
                ? CallbackBridge.windowWidth : CallbackBridge.physicalWidth);
        float bridgeHeight = Math.max(1f, CallbackBridge.windowHeight > 0
                ? CallbackBridge.windowHeight : CallbackBridge.physicalHeight);

        float drawX = CallbackBridge.mouseX * rootWidth / bridgeWidth;
        float drawY = CallbackBridge.mouseY * rootHeight / bridgeHeight;

        // Crosshair hotspot is its centre. Clamp the hotspot, not the whole
        // drawable, so the cursor can still reach the screen edges naturally.
        drawX = clamp(drawX, 0f, Math.max(0f, rootWidth - 1f));
        drawY = clamp(drawY, 0f, Math.max(0f, rootHeight - 1f));

        int left = Math.round(drawX - (cursorSize / 2f));
        int top = Math.round(drawY - (cursorSize / 2f));
        cursorDrawable.setBounds(left, top, left + cursorSize, top + cursorSize);
        cursorDrawable.invalidateSelf();
    }

    private static void resetBridgeCursorToCenter() {
        try {
            float width = Math.max(1f, CallbackBridge.windowWidth > 0
                    ? CallbackBridge.windowWidth : CallbackBridge.physicalWidth);
            float height = Math.max(1f, CallbackBridge.windowHeight > 0
                    ? CallbackBridge.windowHeight : CallbackBridge.physicalHeight);
            CallbackBridge.setInputReady(true);
            CallbackBridge.mouseX = Math.max(0f, width - 1f) / 2f;
            CallbackBridge.mouseY = Math.max(0f, height - 1f) / 2f;
            CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
        } catch (Throwable ignored) {
        }
    }

    private static boolean hasPhysicalPointerDevice() {
        try {
            for (int id : android.view.InputDevice.getDeviceIds()) {
                android.view.InputDevice device = android.view.InputDevice.getDevice(id);
                if (isRealExternalPointerDevice(device)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Real USB/Bluetooth mice should hide the launcher software cursor.
     * Controllers must not. Some Android handhelds and Bluetooth pads expose
     * misleading pointer-like sources, especially TOUCHPAD/MOUSE, even though
     * they are still the game controller. The previous detector trusted those
     * sources too much, so attaching a controller could hide the virtual cursor.
     *
     * Keep this intentionally conservative: only a confident external mouse
     * source hides the virtual cursor. A controller/touchpad-like device should
     * not stop the user from toggling the on-screen cursor with the touch button.
     */
    private static boolean isRealExternalPointerDevice(@Nullable android.view.InputDevice device) {
        if (device == null) return false;

        int sources = device.getSources();
        if (isControllerLikeDevice(device, sources)) return false;

        boolean hasPointerSource = (sources & android.view.InputDevice.SOURCE_MOUSE) == android.view.InputDevice.SOURCE_MOUSE
                || (sources & android.view.InputDevice.SOURCE_MOUSE_RELATIVE) == android.view.InputDevice.SOURCE_MOUSE_RELATIVE
                || (sources & android.view.InputDevice.SOURCE_TOUCHPAD) == android.view.InputDevice.SOURCE_TOUCHPAD
                || device.supportsSource(android.view.InputDevice.SOURCE_MOUSE)
                || device.supportsSource(android.view.InputDevice.SOURCE_MOUSE_RELATIVE)
                || device.supportsSource(android.view.InputDevice.SOURCE_TOUCHPAD);
        if (!hasPointerSource) return false;

        String name = safeLower(device.getName());
        if (looksLikeControllerName(name) || looksLikeVirtualTouchName(name)) return false;

        // Bluetooth mice and keyboard-touchpads can report generic names or bad
        // isExternal() metadata. If Android exposes a mouse/touchpad source and
        // the device is not a controller/virtual touch helper, treat it as a real
        // pointer so the launcher software cursor hides correctly.
        return true;
    }

    private static boolean isControllerLikeDevice(@NonNull android.view.InputDevice device, int sources) {
        if ((sources & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
                || (sources & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK) {
            return true;
        }
        return looksLikeControllerName(safeLower(device.getName()));
    }

    private static boolean looksLikeControllerName(@NonNull String name) {
        return name.contains("controller")
                || name.contains("gamepad")
                || name.contains("joystick")
                || name.contains("xbox")
                || name.contains("dualshock")
                || name.contains("dualsense")
                || name.contains("playstation")
                || name.contains("8bitdo")
                || name.contains("gamesir")
                || name.contains("ipega")
                || name.contains("backbone")
                || name.contains("kishi")
                || name.contains("odin")
                || name.contains("retroid")
                || name.contains("anbernic")
                || name.contains("aya")
                || name.contains("gpd")
                || name.contains("legion go")
                || name.contains("steam deck")
                || name.contains("razer raiju")
                || name.contains("moga");
    }

    private static boolean looksLikeVirtualTouchName(@NonNull String name) {
        return name.contains("virtual")
                || name.contains("touchscreen")
                || name.contains("touch mapping")
                || name.contains("touchmapping")
                || name.contains("uinput")
                || name.contains("gpio")
                || name.contains("keypad");
    }

    private static boolean looksLikeMouseName(@NonNull String name) {
        return name.contains("mouse")
                || name.contains("trackball")
                || name.contains("trackpad")
                || name.contains("receiver")
                || name.contains("logitech")
                || name.contains("razer")
                || name.contains("microsoft")
                || name.contains("hid-compliant");
    }

    @NonNull
    private static String safeLower(@Nullable String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.US);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Drawing happens through the parent ViewGroupOverlay instead.
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
