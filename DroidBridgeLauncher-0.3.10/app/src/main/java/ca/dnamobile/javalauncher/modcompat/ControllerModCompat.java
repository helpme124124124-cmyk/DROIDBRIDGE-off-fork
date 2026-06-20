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

package ca.dnamobile.javalauncher.modcompat;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import ca.dnamobile.javalauncher.settings.LauncherPreferences;
import ca.dnamobile.javalauncher.utils.path.PathManager;
import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.MinecraftGLSurface;
import net.kdt.pojavlaunch.Tools;

import org.libsdl.app.SDL;
import org.libsdl.app.SDLControllerManager;
import org.libsdl.app.SDLSurface;

/**
 * Compatibility hooks for controller mods that do their own native/controller work.
 *
 * Controlify is handled by ControlifySDL. This class handles Legacy4J and Controllable.
 */
public final class ControllerModCompat {
    private static final String TAG = "ControllerModCompat";

    private static Logger.eventLogListener legacy4JLogListener;
    private static Logger.eventLogListener controllableLogListener;
    private static boolean suppressLauncherGamepadInput;

    private ControllerModCompat() {
    }

    public static synchronized void prepare(@NonNull Context context, @Nullable File gameDirectory) {
        if (gameDirectory == null) return;

        if (!LauncherPreferences.isSdlControllerModCompatEnabled(context)) {
            append("disabled by preference");
            suppressLauncherGamepadInput = false;
            MinecraftGLSurface.sdlEnabled = false;
            return;
        }

        suppressLauncherGamepadInput = false;

        startLegacy4JMitigation(context, gameDirectory);
        startControllableMitigation(context, gameDirectory);
    }

    public static synchronized void reset() {
        if (legacy4JLogListener != null) {
            Logger.removeLogListener(legacy4JLogListener);
            legacy4JLogListener = null;
        }
        if (controllableLogListener != null) {
            Logger.removeLogListener(controllableLogListener);
            controllableLogListener = null;
        }
        suppressLauncherGamepadInput = false;
        MinecraftGLSurface.sdlEnabled = false;
    }

    public static boolean shouldSuppressLauncherGamepadInput() {
        return suppressLauncherGamepadInput;
    }

    public static boolean hasControllable(@Nullable File gameDirectory) {
        return gameDirectory != null && hasMod(gameDirectory, "controllable");
    }

    @NonNull
    public static String buildJnaLibraryPath(
            @NonNull Context context,
            @Nullable File gameDirectory,
            @NonNull String baseJnaPath
    ) {
        StringBuilder builder = new StringBuilder();
        addPathList(builder, baseJnaPath);

        if (gameDirectory != null && hasControllable(gameDirectory)) {
            String version = findControllableSdlVersion(gameDirectory);

            // First choice: load SDL2 directly from the APK/private native dir.
            addPath(builder, new File(context.getApplicationInfo().nativeLibraryDir));

            // Second choice: private app cache. JNA can execute/load from here.
            addPath(builder, new File(PathManager.DIR_CACHE, "ControllableSDL/" + version));
            addPath(builder, new File(PathManager.DIR_CACHE, "controllable_natives/SDL/" + version));

            // Last choice: Controllable's normal extraction path. This is useful for
            // diagnostics, but on Android external storage can be noexec, so do not
            // rely on this as the only path.
            addPath(builder, new File(gameDirectory, "controllable_natives/SDL/" + version));
            addPath(builder, new File(gameDirectory, "controllable_natives/" + version));

            if (PathManager.DIR_NATIVE_LIB != null) {
                addPath(builder, new File(PathManager.DIR_NATIVE_LIB));
            }
        }

        return builder.toString();
    }

    private static void startLegacy4JMitigation(@NonNull Context context, @NonNull File gameDirectory) {
        if (!hasMod(gameDirectory, "legacy4j", "legacy-4j", "legacy")) return;

        append("Legacy4J detected; enabling launcher SDL bridge before Minecraft starts");
        logAndroidControllers("Legacy4J");

        // Legacy4J's libsdl4j path often falls back to GLFW on Android. The GLFW
        // controller path still needs the launcher-side SDL controller backend to
        // be initialized before Legacy4J checks for controllers. Do this up-front,
        // instead of waiting for the warning after Legacy4J has already failed.
        suppressLauncherGamepadInput = true;

        if (tryEnableLauncherSdlBridge(context, "Legacy4J")) {
            scheduleSdlPoll("Legacy4J initial poll", 0L);
            scheduleSdlPoll("Legacy4J delayed poll", 750L);
            scheduleSdlPoll("Legacy4J late poll", 2500L);
        } else {
            append("Legacy4J launcher SDL bridge did not initialize; JavaLauncher gamepad overlay stays suppressed so GLFW fallback can try to receive input");
        }

        showWarning(context, "Legacy4J controller compatibility mode is active.");

        if (legacy4JLogListener != null) {
            Logger.removeLogListener(legacy4JLogListener);
        }

        legacy4JLogListener = line -> {
            if (line == null) return;
            if (line.contains(TAG + ":")) return;

            if (line.contains("Added SDL Controller Mappings")) {
                append("Legacy4J SDL mappings loaded successfully");
                scheduleSdlPoll("Legacy4J mappings poll", 0L);
                scheduleSdlPoll("Legacy4J mappings delayed poll", 1000L);
                Logger.removeLogListener(legacy4JLogListener);
                legacy4JLogListener = null;
                return;
            }

            if (line.contains("SDL Game Controller failed to start")
                    || line.contains("GLFW will be used instead")
                    || line.contains("SDL3 (isXander's libsdl4j)")) {
                append("Legacy4J SDL/GLFW fallback detected; keeping launcher SDL bridge and gamepad overlay suppression active");
                scheduleSdlPoll("Legacy4J fallback poll", 0L);
                scheduleSdlPoll("Legacy4J fallback delayed poll", 1500L);
                return;
            }

            if (line.contains("Sound engine started")) {
                scheduleSdlPoll("Legacy4J sound engine poll", 0L);
                scheduleSdlPoll("Legacy4J sound engine delayed poll", 1500L);
                return;
            }

            if (line.contains("Stopping!") || line.contains("Game crashed!")) {
                Logger.removeLogListener(legacy4JLogListener);
                legacy4JLogListener = null;
            }
        };

        Logger.addLogListener(legacy4JLogListener);
    }

    private static void startControllableMitigation(@NonNull Context context, @NonNull File gameDirectory) {
        if (!hasMod(gameDirectory, "controllable")) return;

        append("Controllable detected, preparing SDL/JNA compatibility");
        logAndroidControllers("Controllable");

        // Controllable handles controllers through its own SDL2/JNA layer.
        // JavaLauncher must not route controller motion into its mouse overlay.
        suppressLauncherGamepadInput = true;
        MinecraftGLSurface.sdlEnabled = false;

        try {
            prepareAndroidSdl2ForControllable(context, gameDirectory);
        } catch (Throwable throwable) {
            append("Failed to prepare Android SDL2 for Controllable: " + throwable);
        }

        boolean forceSdlBridge = LauncherPreferences.isForceSdlControllerBridge(context);
        append("Controllable Force SDL bridge=" + forceSdlBridge);
        if (forceSdlBridge && tryEnableLauncherSdlBridge(context, "Controllable")) {
            scheduleSdlPoll("Controllable initial delayed poll", 750L);
            scheduleSdlPoll("Controllable initial late poll", 2500L);
        } else if (!forceSdlBridge) {
            append("Controllable Force SDL bridge is disabled; patched SDL2 only, launcher gamepad overlay remains suppressed");
        }

        showWarning(context, forceSdlBridge
                ? "Controllable compatibility mode is active. Force SDL bridge is enabled."
                : "Controllable compatibility mode is active. Enable Force SDL bridge in settings if the controller is not detected.");

        if (controllableLogListener != null) {
            Logger.removeLogListener(controllableLogListener);
        }

        controllableLogListener = line -> {
            if (line == null) return;
            if (line.contains(TAG + ":")) return;

            if (line.contains("Sound engine started")) {
                append("Controllable compatibility: game reached sound engine start; launcher gamepad overlay remains disabled");
                scheduleSdlPoll("Controllable sound engine poll", 0L);
                scheduleSdlPoll("Controllable sound engine delayed poll", 1500L);
            } else if (line.contains("Applying gamepad mappings")
                    || line.contains("Successfully updated")
                    || line.contains("Finished downloading mappings")) {
                scheduleSdlPoll("Controllable mapping poll", 0L);
                scheduleSdlPoll("Controllable mapping delayed poll", 750L);
            } else if (line.contains("libm.so.6") || line.contains("libc.so.6")) {
                append("Controllable is still loading a desktop SDL2 native; embedded jar patch did not take effect.");
                Logger.removeLogListener(controllableLogListener);
                controllableLogListener = null;
            } else if (line.contains("java.io.File.getName()") && line.contains("file")) {
                append("Controllable hit the JNA nounpack File.getName() bug; make sure -Djna.nounpack is not forced true.");
                Logger.removeLogListener(controllableLogListener);
                controllableLogListener = null;
            } else if (line.contains("Stopping!") || line.contains("Game crashed!")) {
                Logger.removeLogListener(controllableLogListener);
                controllableLogListener = null;
            }
        };
        Logger.addLogListener(controllableLogListener);
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private static boolean tryEnableLauncherSdlBridge(@NonNull Context context, @NonNull String reason) {
        try {
            File sdl2 = findAndroidSdl2Library(context);
            if (sdl2 == null || !sdl2.isFile()) {
                MinecraftGLSurface.sdlEnabled = false;
                append(reason + " Force SDL bridge not enabled: Android libSDL2.so was not found");
                return false;
            }

            // Load SDL3 if it exists because some launcher-side SDL Java glue is SDL3-based.
            // Some builds only ship SDL2, so SDL3 loading is optional.
            try {
                SDL.loadLibrary("SDL3", context);
                append(reason + " Force SDL bridge: SDL3 loaded");
            } catch (Throwable throwable) {
                append(reason + " Force SDL bridge: SDL3 not loaded/available: "
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }

            try {
                SDL.loadLibrary("SDL2", context);
                append(reason + " Force SDL bridge: SDL2 loaded via SDL.loadLibrary");
            } catch (Throwable throwable) {
                append(reason + " Force SDL bridge: SDL.loadLibrary(SDL2) failed, using System.load: "
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                System.load(sdl2.getAbsolutePath());
            }

            try {
                SDL.initialize();
                append(reason + " Force SDL bridge: SDL.initialize() completed");
            } catch (Throwable throwable) {
                append(reason + " Force SDL bridge: SDL.initialize() failed/non-fatal: "
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }

            try {
                SDL.setupJNI();
                append(reason + " Force SDL bridge: SDL.setupJNI() completed");
            } catch (Throwable throwable) {
                append(reason + " Force SDL bridge: SDL.setupJNI() failed/non-fatal: "
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }

            SDL.setContext(context);

            try {
                // This attaches SDLActivity's generic motion listener and prepares
                // Android-side controller/sensor plumbing. It is not displayed.
                new SDLSurface(context);
                append(reason + " Force SDL bridge: SDLSurface created for controller plumbing");
            } catch (Throwable throwable) {
                append(reason + " Force SDL bridge: SDLSurface creation failed/non-fatal: "
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }

            try {
                Tools.SDL.initializeControllerSubsystems();
                append(reason + " Force SDL bridge: Tools.SDL.initializeControllerSubsystems() completed");
            } catch (Throwable throwable) {
                append(reason + " Force SDL bridge: controller subsystem init failed/non-fatal: "
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }

            SDLControllerManager.initialize();
            MinecraftGLSurface.sdlEnabled = true;
            SDLControllerManager.pollInputDevices();
            append(reason + " Force SDL bridge initialized; launcher SDL routing enabled");
            return true;
        } catch (Throwable throwable) {
            MinecraftGLSurface.sdlEnabled = false;
            append(reason + " Force SDL bridge not enabled: "
                    + throwable.getClass().getName() + ": " + throwable.getMessage());
            return false;
        }
    }

    private static void scheduleSdlPoll(@NonNull String reason, long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!MinecraftGLSurface.sdlEnabled) {
                append(reason + ": skipped because launcher SDL routing is disabled");
                return;
            }

            try {
                SDLControllerManager.initialize();
                SDLControllerManager.pollInputDevices();
                append(reason + ": SDLControllerManager.pollInputDevices() completed");
            } catch (Throwable throwable) {
                MinecraftGLSurface.sdlEnabled = false;
                append(reason + ": SDL poll failed, routing disabled: "
                        + throwable.getClass().getName() + ": " + throwable.getMessage());
            }
        }, delayMs);
    }

    private static void prepareAndroidSdl2ForControllable(
            @NonNull Context context,
            @NonNull File gameDirectory
    ) throws IOException {
        File androidSdl2 = findAndroidSdl2Library(context);
        if (androidSdl2 == null || !androidSdl2.isFile()) {
            append("Android libSDL2.so missing. Controllable will likely extract its desktop linux-aarch64 SDL2 and crash.");
            return;
        }

        String version = findControllableSdlVersion(gameDirectory);
        append("Preparing Controllable Android SDL2 version=" + version + " from " + androidSdl2.getAbsolutePath());

        patchControllableJarForAndroidSdl2(gameDirectory, androidSdl2);

        // Controllable normally calls SdlNativeLibraryLoader.setExtractionPath(gameDir/controllable_natives/SDL).
        copyNative(androidSdl2, new File(gameDirectory, "controllable_natives/SDL/" + version + "/libSDL2.so"));

        // Some older/forked builds use controllable_natives/<version> directly.
        copyNative(androidSdl2, new File(gameDirectory, "controllable_natives/" + version + "/libSDL2.so"));

        // Private app-cache locations are executable/loadable by JNA on Android.
        copyNative(androidSdl2, new File(PathManager.DIR_CACHE, "ControllableSDL/" + version + "/libSDL2.so"));
        copyNative(androidSdl2, new File(PathManager.DIR_CACHE, "controllable_natives/SDL/" + version + "/libSDL2.so"));
    }

    private static void patchControllableJarForAndroidSdl2(
            @NonNull File gameDirectory,
            @NonNull File androidSdl2
    ) {
        File modsDir = new File(gameDirectory, "mods");
        File[] mods = modsDir.listFiles(file -> file.isFile()
                && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")
                && file.getName().toLowerCase(Locale.ROOT).contains("controllable"));
        if (mods == null || mods.length == 0) return;

        byte[] androidSdl2Bytes;
        try {
            androidSdl2Bytes = readAllBytes(androidSdl2);
        } catch (IOException e) {
            append("Unable to read Android SDL2 for jar patch: " + e);
            return;
        }

        for (File modJar : mods) {
            try {
                if (patchOuterControllableJar(modJar, androidSdl2Bytes)) {
                    append("Patched Controllable embedded SDL2 resource: " + modJar.getAbsolutePath());
                }
            } catch (Throwable throwable) {
                append("Failed to patch Controllable jar " + modJar.getName() + ": " + throwable);
            }
        }
    }

    private static boolean patchOuterControllableJar(
            @NonNull File modJar,
            @NonNull byte[] androidSdl2Bytes
    ) throws IOException {
        File tempJar = new File(modJar.getParentFile(), modJar.getName() + ".javalauncher.tmp");
        boolean changed = false;
        boolean sawControllableSdl = false;

        try (ZipInputStream input = new ZipInputStream(new FileInputStream(modJar));
             ZipOutputStream output = new ZipOutputStream(new FileOutputStream(tempJar, false))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                if (isJarSignatureEntry(name)) {
                    changed = true;
                    continue;
                }

                byte[] entryBytes = readEntryBytes(input, buffer);
                String lowerName = name.toLowerCase(Locale.ROOT);

                if (lowerName.endsWith(".jar") && lowerName.contains("controllable-sdl")) {
                    sawControllableSdl = true;
                    PatchBytesResult nested = patchNestedControllableSdlJar(entryBytes, androidSdl2Bytes);
                    if (nested.changed) {
                        changed = true;
                        entryBytes = nested.bytes;
                    }
                } else if (isControllableSdlNativeResource(lowerName)) {
                    if (!Arrays.equals(entryBytes, androidSdl2Bytes)) {
                        entryBytes = androidSdl2Bytes;
                        changed = true;
                    }
                }

                writeZipEntry(output, name, entryBytes);
            }
        }

        if (!sawControllableSdl) {
            append("Controllable jar did not contain an embedded controllable-sdl jar: " + modJar.getName());
        }

        if (!changed) {
            //noinspection ResultOfMethodCallIgnored
            tempJar.delete();
            return false;
        }

        File backup = new File(modJar.getParentFile(), modJar.getName() + ".javalauncher.bak");
        if (!backup.exists()) {
            copyFile(modJar, backup);
        }

        if (!tempJar.renameTo(modJar)) {
            copyFile(tempJar, modJar);
            //noinspection ResultOfMethodCallIgnored
            tempJar.delete();
        }
        return true;
    }

    private static PatchBytesResult patchNestedControllableSdlJar(
            @NonNull byte[] originalJar,
            @NonNull byte[] androidSdl2Bytes
    ) throws IOException {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream(Math.max(originalJar.length, androidSdl2Bytes.length));
        boolean changed = false;
        boolean replacedTarget = false;

        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(originalJar));
             ZipOutputStream output = new ZipOutputStream(outBytes)) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                if (isJarSignatureEntry(name)) {
                    changed = true;
                    continue;
                }

                byte[] entryBytes = readEntryBytes(input, buffer);
                String lowerName = name.toLowerCase(Locale.ROOT);
                if (isControllableSdlNativeResource(lowerName)) {
                    replacedTarget = true;
                    if (!Arrays.equals(entryBytes, androidSdl2Bytes)) {
                        entryBytes = androidSdl2Bytes;
                        changed = true;
                    }
                }

                writeZipEntry(output, name, entryBytes);
            }

            if (!replacedTarget) {
                writeZipEntry(output, "linux-aarch64/libSDL2.so", androidSdl2Bytes);
                changed = true;
            }
        }

        return new PatchBytesResult(outBytes.toByteArray(), changed);
    }

    private static boolean isControllableSdlNativeResource(@NonNull String lowerName) {
        return lowerName.equals("linux-aarch64/libsdl2.so")
                || lowerName.equals("linux-aarch64/libsdl2-2.0.so")
                || lowerName.equals("linux-arm64/libsdl2.so")
                || lowerName.equals("linux-arm64/libsdl2-2.0.so");
    }

    private static boolean isJarSignatureEntry(@NonNull String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("META-INF/")
                && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"));
    }

    private static void writeZipEntry(
            @NonNull ZipOutputStream output,
            @NonNull String name,
            @NonNull byte[] bytes
    ) throws IOException {
        ZipEntry outEntry = new ZipEntry(name);
        output.putNextEntry(outEntry);
        output.write(bytes);
        output.closeEntry();
    }

    @NonNull
    private static byte[] readEntryBytes(@NonNull ZipInputStream input, @NonNull byte[] buffer) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    @NonNull
    private static byte[] readAllBytes(@NonNull File source) throws IOException {
        try (FileInputStream input = new FileInputStream(source)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(source.length(), 8 * 1024 * 1024));
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void copyFile(@NonNull File source, @NonNull File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Unable to create directory: " + parent);
        }
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static final class PatchBytesResult {
        final byte[] bytes;
        final boolean changed;

        PatchBytesResult(@NonNull byte[] bytes, boolean changed) {
            this.bytes = bytes;
            this.changed = changed;
        }
    }

    @Nullable
    private static File findAndroidSdl2Library(@NonNull Context context) {
        String[] names = {
                "libSDL2.so",
                "libSDL2-2.0.so",
                "libSDL2_2.0.so"
        };

        File appNativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        for (String name : names) {
            File candidate = new File(appNativeDir, name);
            if (candidate.isFile()) return candidate;
        }

        if (PathManager.DIR_NATIVE_LIB != null) {
            File pathManagerNativeDir = new File(PathManager.DIR_NATIVE_LIB);
            for (String name : names) {
                File candidate = new File(pathManagerNativeDir, name);
                if (candidate.isFile()) return candidate;
            }
        }

        return null;
    }

    @NonNull
    private static String findControllableSdlVersion(@NonNull File gameDirectory) {
        File modsDir = new File(gameDirectory, "mods");
        File[] mods = modsDir.listFiles(file -> file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (mods != null) {
            for (File mod : mods) {
                String version = findControllableSdlVersionInJar(mod);
                if (version != null) return version;
            }
        }

        // 1.20.1 Controllable 0.21.x bundles controllable-sdl-2.30.12-1.1.0.jar.
        return "2.30.12";
    }

    @Nullable
    private static String findControllableSdlVersionInJar(@NonNull File jarFile) {
        try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(jarFile))) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                int index = name.indexOf("controllable-sdl-");
                if (index < 0 || !name.endsWith(".jar")) continue;

                String remaining = name.substring(index + "controllable-sdl-".length());
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("^(\\d+\\.\\d+\\.\\d+)")
                        .matcher(remaining);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void copyNative(@NonNull File source, @NonNull File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Unable to create directory: " + parent);
        }

        try (FileInputStream inputStream = new FileInputStream(source);
             FileOutputStream outputStream = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
        }

        destination.setReadable(true, false);
        destination.setExecutable(true, false);
        append("Prepared Controllable SDL2 native: " + destination.getAbsolutePath());
    }

    private static void logAndroidControllers(@NonNull String reason) {
        int count = 0;
        try {
            int[] ids = InputDevice.getDeviceIds();
            for (int id : ids) {
                InputDevice device = InputDevice.getDevice(id);
                if (device == null) continue;

                int sources = device.getSources();
                boolean isController = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                        || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                        || (sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
                if (!isController) continue;

                count++;
                append(reason + ": Android controller device id=" + id
                        + " name=" + device.getName()
                        + " descriptor=" + device.getDescriptor()
                        + " sources=0x" + Integer.toHexString(sources));
            }
        } catch (Throwable throwable) {
            append(reason + ": Android controller scan failed: " + throwable);
        }
        append(reason + ": Android controller count=" + count);
    }

    private static boolean hasMod(@NonNull File gameDirectory, @NonNull String... tokens) {
        File modsDir = new File(gameDirectory, "mods");
        File[] mods = modsDir.listFiles(file -> file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (mods == null) return false;

        for (File file : mods) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            for (String token : tokens) {
                if (name.contains(token.toLowerCase(Locale.ROOT))) return true;
            }
        }
        return false;
    }

    private static void showWarning(@NonNull Context context, @NonNull String message) {
        // Keep controller compatibility messages in latestlog.txt instead of Toasts.
        // This avoids interrupting the user while still making diagnostics visible.
        if (LauncherPreferences.isShowControllerModCompatWarnings(context)) {
            append(message);
        }
    }

    private static void addPathList(@NonNull StringBuilder builder, @NonNull String pathList) {
        if (pathList.trim().isEmpty()) return;
        String[] parts = pathList.split(":");
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) continue;
            addPath(builder, new File(part.trim()));
        }
    }

    private static void addPath(@NonNull StringBuilder builder, @NonNull File dir) {
        String path = dir.getAbsolutePath();
        if (path.trim().isEmpty()) return;
        String current = builder.toString();
        if (current.equals(path) || current.startsWith(path + ":") || current.contains(":" + path + ":") || current.endsWith(":" + path)) {
            return;
        }
        if (builder.length() > 0) builder.append(':');
        builder.append(path);
    }

    private static void append(@NonNull String message) {
        try {
            Logger.appendToLog(TAG + ": " + (message.endsWith("\n") ? message : message + "\n"));
        } catch (Throwable ignored) {
        }
    }
}
