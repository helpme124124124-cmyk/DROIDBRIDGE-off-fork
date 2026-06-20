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

package ca.dnamobile.javalauncher.renderer;

import android.content.Context;
import android.system.Os;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import ca.dnamobile.javalauncher.feature.log.Logging;
import ca.dnamobile.javalauncher.launcher.LaunchPlan;
import ca.dnamobile.javalauncher.utils.path.PathManager;
import net.kdt.pojavlaunch.utils.JREUtils;

/**
 * Renderer-specific native environment wiring.
 *
 * LTW is more strict than MobileGlues/GL4ES: it needs real process env values
 * before the JVM/native bridge creates the OpenGL context. Zalith sets these;
 * JavaLauncher needs to mirror the important pieces.
 */
public final class RendererLaunchEnvironment {
    private static final String TAG = "RendererLaunchEnvironment";

    private RendererLaunchEnvironment() {
    }

    public static void applyBeforeJvmLaunch(
            @NonNull Context context,
            @NonNull LaunchPlan plan,
            @NonNull RendererInterface renderer
    ) {
        PathManager.initContextConstants(context);

        // Always expose renderer-provided env values first.
        for (java.util.Map.Entry<String, String> entry : renderer.getRendererEnv().entrySet()) {
            setEnv(entry.getKey(), entry.getValue());
        }

        if (!isLtwRenderer(renderer)) {
            return;
        }

        File ltwLibrary = resolveRendererLibrary(context, renderer, "libltw.so");
        File ltwDir = ltwLibrary != null && ltwLibrary.isFile() ? ltwLibrary.getParentFile() : null;
        File appNativeDir = resolveAppNativeDir(context);
        File runtimeDir = plan.getRuntimeDirectory();

        int width = Math.max(1, org.lwjgl.glfw.CallbackBridge.windowWidth);
        int height = Math.max(1, org.lwjgl.glfw.CallbackBridge.windowHeight);

        setEnv("POJAV_RENDERER", "opengles3_ltw");
        setEnv("LIBGL_ES", "3");
        setEnv("POJAVEXEC_EGL", "libltw.so");
        setEnv("POJAV_EGL_LIBRARY", "libltw.so");
        setEnv("POJAVEXEC_EGL_LIBRARY", "libltw.so");
        setEnv("POJAV_RENDERER_LIBRARY", "libltw.so");
        setEnv("POJAVEXEC_RENDERER", "libltw.so");
        setEnv("DRIVER_PATH", ltwDir != null ? ltwDir.getAbsolutePath() : appNativeDir.getAbsolutePath());
        setEnv("POJAV_NATIVEDIR", appNativeDir.getAbsolutePath());
        setEnv("TMPDIR", context.getCacheDir().getAbsolutePath());
        setEnv("JAVA_HOME", runtimeDir.getAbsolutePath());
        setEnv("AWTSTUB_WIDTH", String.valueOf(width));
        setEnv("AWTSTUB_HEIGHT", String.valueOf(height));

        if (PathManager.DIR_FILE != null) {
            setEnv("HOME", PathManager.DIR_FILE.getAbsolutePath());
        }

        String ldLibraryPath = buildLdLibraryPath(context, runtimeDir, appNativeDir, ltwDir);
        setEnv("LD_LIBRARY_PATH", ldLibraryPath);
        try {
            JREUtils.setLdLibraryPath(ldLibraryPath);
            Logging.i(TAG, "Native LD_LIBRARY_PATH set for LTW: " + ldLibraryPath);
        } catch (Throwable throwable) {
            Logging.e(TAG, "JREUtils.setLdLibraryPath failed for LTW", throwable);
        }

        if (ltwLibrary != null && ltwLibrary.isFile()) {
            try {
                JREUtils.dlopen(ltwLibrary.getAbsolutePath());
                Logging.i(TAG, "LTW dlopen requested: " + ltwLibrary.getAbsolutePath());
            } catch (Throwable throwable) {
                Logging.e(TAG, "LTW dlopen failed; launch will continue", throwable);
            }
        }
    }

    /**
     * Optional early bridge call for GameActivity. Safe to call before CallbackBridge.init(...).
     */
    public static void applyEarlyBridgeEnvironment(
            @NonNull Context context,
            @NonNull RendererInterface renderer
    ) {
        applyEarlyBridgeEnvironment(context, renderer,
                Math.max(1, org.lwjgl.glfw.CallbackBridge.windowWidth),
                Math.max(1, org.lwjgl.glfw.CallbackBridge.windowHeight));
    }

    /**
     * Optional early bridge call for GameActivity. Safe to call before CallbackBridge.init(...).
     */
    public static void applyEarlyBridgeEnvironment(
            @NonNull Context context,
            @NonNull RendererInterface renderer,
            int width,
            int height
    ) {
        PathManager.initContextConstants(context);
        for (java.util.Map.Entry<String, String> entry : renderer.getRendererEnv().entrySet()) {
            setEnv(entry.getKey(), entry.getValue());
        }

        if (!isLtwRenderer(renderer)) {
            return;
        }

        File ltwLibrary = resolveRendererLibrary(context, renderer, "libltw.so");
        File ltwDir = ltwLibrary != null && ltwLibrary.isFile() ? ltwLibrary.getParentFile() : null;
        File appNativeDir = resolveAppNativeDir(context);

        setEnv("POJAV_RENDERER", "opengles3_ltw");
        setEnv("LIBGL_ES", "3");
        setEnv("POJAVEXEC_EGL", "libltw.so");
        setEnv("POJAV_EGL_LIBRARY", "libltw.so");
        setEnv("POJAVEXEC_EGL_LIBRARY", "libltw.so");
        setEnv("POJAV_RENDERER_LIBRARY", "libltw.so");
        setEnv("POJAVEXEC_RENDERER", "libltw.so");
        setEnv("DRIVER_PATH", ltwDir != null ? ltwDir.getAbsolutePath() : appNativeDir.getAbsolutePath());
        setEnv("POJAV_NATIVEDIR", appNativeDir.getAbsolutePath());
        setEnv("TMPDIR", context.getCacheDir().getAbsolutePath());
        setEnv("AWTSTUB_WIDTH", String.valueOf(Math.max(1, width)));
        setEnv("AWTSTUB_HEIGHT", String.valueOf(Math.max(1, height)));

        Logging.i(TAG, "Early LTW bridge env applied. lib="
                + (ltwLibrary != null ? ltwLibrary.getAbsolutePath() : "<missing>")
                + " appNativeDir=" + appNativeDir.getAbsolutePath());
    }

    public static void patchJvmArgsForRenderer(
            @NonNull Context context,
            @NonNull RendererInterface renderer,
            @NonNull List<String> args
    ) {
        if (!isLtwRenderer(renderer)) {
            return;
        }

        File ltwLibrary = resolveRendererLibrary(context, renderer, "libltw.so");
        if (ltwLibrary == null || !ltwLibrary.isFile()) {
            Logging.i(TAG, "LTW libltw.so could not be resolved; keeping existing JVM args.");
            return;
        }

        String value = "-Dorg.lwjgl.opengl.libname=" + ltwLibrary.getAbsolutePath();
        for (Iterator<String> iterator = args.iterator(); iterator.hasNext(); ) {
            String arg = iterator.next();
            if (arg != null && arg.startsWith("-Dorg.lwjgl.opengl.libname=")) {
                iterator.remove();
            }
        }
        args.add(value);
        Logging.i(TAG, "LTW JVM arg patched: " + value);
    }

    public static boolean isLtwRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;
        String identity = (safe(renderer.getRendererName()) + " "
                + safe(renderer.getRendererId()) + " "
                + safe(renderer.getUniqueIdentifier()) + " "
                + safe(renderer.getRendererLibrary()) + " "
                + safe(renderer.getRendererEGL())).toLowerCase(Locale.ROOT);
        return identity.contains("ltw") || identity.contains("libltw.so");
    }

    @Nullable
    private static File resolveRendererLibrary(
            @NonNull Context context,
            @NonNull RendererInterface renderer,
            @NonNull String expectedName
    ) {
        String raw = renderer.getRendererLibrary();
        if (raw != null && !raw.trim().isEmpty()) {
            File direct = new File(raw.trim());
            if (direct.isAbsolute() && direct.isFile()) return direct;

            File appNativeCandidate = new File(resolveAppNativeDir(context), direct.getName());
            if (appNativeCandidate.isFile()) return appNativeCandidate;
        }

        File appNative = new File(resolveAppNativeDir(context), expectedName);
        if (appNative.isFile()) return appNative;

        String pathManagerNative = PathManager.DIR_NATIVE_LIB;
        if (pathManagerNative != null && !pathManagerNative.trim().isEmpty()) {
            File candidate = new File(pathManagerNative, expectedName);
            if (candidate.isFile()) return candidate;
        }

        return null;
    }

    @NonNull
    private static File resolveAppNativeDir(@NonNull Context context) {
        String nativeDir = context.getApplicationInfo() != null
                ? context.getApplicationInfo().nativeLibraryDir
                : null;
        if (nativeDir != null && !nativeDir.trim().isEmpty()) {
            return new File(nativeDir);
        }
        String pathManagerNative = PathManager.DIR_NATIVE_LIB;
        if (pathManagerNative != null && !pathManagerNative.trim().isEmpty()) {
            return new File(pathManagerNative);
        }
        return new File(context.getFilesDir(), "lib");
    }

    @NonNull
    private static String buildLdLibraryPath(
            @NonNull Context context,
            @NonNull File runtimeDir,
            @NonNull File appNativeDir,
            @Nullable File rendererPluginDir
    ) {
        StringBuilder builder = new StringBuilder();
        appendPath(builder, rendererPluginDir);
        appendPath(builder, new File(runtimeDir, "lib/jli"));
        appendPath(builder, new File(runtimeDir, "lib"));
        appendPath(builder, new File(runtimeDir, "lib/server"));
        appendRaw(builder, "/system/lib64");
        appendRaw(builder, "/vendor/lib64");
        appendRaw(builder, "/vendor/lib64/hw");
        appendPath(builder, new File(context.getApplicationInfo().dataDir, "app_runtime_mod"));
        appendPath(builder, appNativeDir);

        String existing = System.getenv("LD_LIBRARY_PATH");
        if (existing != null && !existing.trim().isEmpty()) {
            appendRaw(builder, existing);
        }
        return builder.toString();
    }

    private static void appendPath(@NonNull StringBuilder builder, @Nullable File file) {
        if (file == null) return;
        appendRaw(builder, file.getAbsolutePath());
    }

    private static void appendRaw(@NonNull StringBuilder builder, @Nullable String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (builder.length() > 0) builder.append(':');
        builder.append(value.trim());
    }

    private static void setEnv(@NonNull String key, @Nullable String value) {
        if (value == null) return;
        try {
            if (value.isEmpty()) {
                Os.unsetenv(key);
            } else {
                Os.setenv(key, value, true);
                Logging.i(TAG, "Added custom env: " + key + "=" + value);
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to set env " + key, throwable);
        }
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }
}
