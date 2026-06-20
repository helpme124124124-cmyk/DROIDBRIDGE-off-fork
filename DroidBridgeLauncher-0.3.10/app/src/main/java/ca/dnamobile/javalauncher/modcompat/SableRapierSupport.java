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

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;

import ca.dnamobile.javalauncher.feature.log.Logging;
import ca.dnamobile.javalauncher.launcher.LaunchPlan;
import ca.dnamobile.javalauncher.utils.path.PathManager;
import net.kdt.pojavlaunch.utils.JREUtils;

/**
 * Android launcher-side support for Sable's Rapier native.
 *
 * Sable normally extracts sable_rapier_aarch64_linux.so into <gameDir>/.sable/natives
 * and then calls System.load(...) on that external/app-scoped storage path. Android blocks
 * that path from the JVM classloader namespace, which makes Rapier fail to link.
 *
 * MioLibPatcher rewrites Sable's Rapier3D.loadLibrary() so it reads the
 * sable_rapier_path JVM property instead. This class makes sure the javaagent exists,
 * points sable_rapier_path at the APK native library directory, and adds both args before
 * ModLauncher/Sable can load.
 */
public final class SableRapierSupport {
    private static final String TAG = "SableRapier";

    private static final String JAVA_AGENT_PREFIX = "-javaagent:";
    private static final String SABLE_PROPERTY_PREFIX = "-Dsable_rapier_path=";

    private static final String MIO_PATCHER_JAR = "MioLibPatcher.jar";
    private static final String SABLE_NATIVE = "libsable_rapier.so";
    private static final String CXX_SHARED = "libc++_shared.so";

    private SableRapierSupport() {
    }

    public static void addJvmArgsIfNeeded(
            @NonNull Context context,
            @NonNull LaunchPlan plan,
            @NonNull List<String> jvmArgs
    ) {
        if (!hasSableMod(plan.getGameDirectory())) {
            return;
        }

        File patcherJar = prepareMioLibPatcher(context);
        if (patcherJar == null || !patcherJar.isFile() || patcherJar.length() <= 0) {
            log("Sable detected, but " + MIO_PATCHER_JAR + " was not found. Rapier override cannot be applied.");
            return;
        }

        File nativeFile = prepareSableRapierNative(context);
        if (nativeFile == null || !nativeFile.isFile() || nativeFile.length() <= 0) {
            log("Sable detected, but " + SABLE_NATIVE + " was not found. Rapier override cannot be applied.");
            return;
        }

        preloadCxxIfPresent(nativeFile.getParentFile());
        preloadSableNative(nativeFile);

        String patcherPath = patcherJar.getAbsolutePath();
        String nativePath = nativeFile.getAbsolutePath();

        try {
            System.setProperty("sable_rapier_path", nativePath);
        } catch (Throwable ignored) {
        }

        // Match the working Zalith order:
        // java, -Dsable_rapier_path=..., -javaagent:..., other JVM args..., mainClass...
        // Keeping the property before the agent avoids agents that read it during premain.
        upsertOrderedSableJvmArgs(jvmArgs, nativePath, patcherPath);

        log("Sable Rapier javaagent enabled: " + patcherPath);
        log("Sable Rapier native override enabled: " + nativePath);
    }

    private static void upsertOrderedSableJvmArgs(
            @NonNull List<String> args,
            @NonNull String nativePath,
            @NonNull String patcherPath
    ) {
        removeArgsStartingWith(args, SABLE_PROPERTY_PREFIX);
        removeArgsStartingWith(args, JAVA_AGENT_PREFIX);

        int insertAt = !args.isEmpty() && "java".equals(args.get(0)) ? 1 : 0;
        args.add(insertAt, SABLE_PROPERTY_PREFIX + nativePath);
        args.add(insertAt + 1, JAVA_AGENT_PREFIX + patcherPath);
    }

    private static void removeArgsStartingWith(@NonNull List<String> args, @NonNull String prefix) {
        for (int i = args.size() - 1; i >= 0; i--) {
            String arg = args.get(i);
            if (arg != null && arg.startsWith(prefix)) {
                args.remove(i);
            }
        }
    }

    @Nullable
    private static File prepareMioLibPatcher(@NonNull Context context) {
        File componentsDir = resolveComponentsDirectory(context);
        File target = new File(componentsDir, MIO_PATCHER_JAR);

        // Support either asset layout:
        // app/src/main/assets/MioLibPatcher.jar
        // app/src/main/assets/components/MioLibPatcher.jar
        if (copyFirstExistingAsset(context, target, MIO_PATCHER_JAR, "components/" + MIO_PATCHER_JAR)) {
            return target;
        }

        File[] candidates = new File[]{
                target,
                new File(context.getFilesDir(), MIO_PATCHER_JAR),
                new File(new File(context.getFilesDir(), "components"), MIO_PATCHER_JAR),
                new File(PathManager.DIR_FILE, MIO_PATCHER_JAR),
                new File(new File(PathManager.DIR_FILE, "components"), MIO_PATCHER_JAR),
                new File(new File(PathManager.DIR_FILE, "mio"), MIO_PATCHER_JAR),
                new File(new File(PathManager.DIR_FILE, "patcher"), MIO_PATCHER_JAR),
                new File(new File(PathManager.DIR_FILE, "runtime_mod"), MIO_PATCHER_JAR)
        };

        for (File candidate : candidates) {
            if (candidate.isFile() && candidate.length() > 0) {
                return candidate;
            }
        }
        return null;
    }

    @NonNull
    private static File resolveComponentsDirectory(@NonNull Context context) {
        File filesDir = context.getFilesDir();
        File dataDir = filesDir != null ? filesDir.getParentFile() : null;
        File componentsDir = dataDir != null
                ? new File(dataDir, "components")
                : new File(context.getFilesDir(), "components");

        if (!componentsDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            componentsDir.mkdirs();
        }
        return componentsDir;
    }

    @Nullable
    private static File prepareSableRapierNative(@NonNull Context context) {
        // Prefer the APK nativeLibraryDir. This is the Android linker namespace-safe path and
        // matches the working Zalith log: /data/app/.../lib/arm64/libsable_rapier.so
        String appNativeDir = context.getApplicationInfo() != null
                ? context.getApplicationInfo().nativeLibraryDir
                : null;
        if (appNativeDir != null && !appNativeDir.trim().isEmpty()) {
            File appNative = new File(appNativeDir, SABLE_NATIVE);
            if (appNative.isFile() && appNative.length() > 0) return appNative;
        }

        String nativeLibDir = PathManager.DIR_NATIVE_LIB;
        if (nativeLibDir != null && !nativeLibDir.trim().isEmpty()) {
            File nativeDirFile = new File(nativeLibDir, SABLE_NATIVE);
            if (nativeDirFile.isFile() && nativeDirFile.length() > 0) return nativeDirFile;
        }

        File runtimeModNative = findInRuntimeModDirectory();
        if (runtimeModNative != null && runtimeModNative.isFile() && runtimeModNative.length() > 0) {
            return runtimeModNative;
        }

        // Last resort: app-private copy from assets. Prefer not to use this when nativeLibraryDir is available,
        // because some Android linker namespace configs only allow the APK native lib directory.
        File privateDir = context.getDir("sable_rapier", Context.MODE_PRIVATE);
        File privateNative = new File(privateDir, SABLE_NATIVE);
        if (copyFirstExistingAsset(context, privateNative, SABLE_NATIVE, "native/" + SABLE_NATIVE)) {
            makeLoadable(privateNative);
            log("Using fallback app-private Sable native path. If Android blocks this, package "
                    + SABLE_NATIVE + " under app/src/main/jniLibs/arm64-v8a/.");
            return privateNative;
        }

        if (privateNative.isFile() && privateNative.length() > 0) {
            makeLoadable(privateNative);
            log("Using existing fallback app-private Sable native path. If Android blocks this, package "
                    + SABLE_NATIVE + " under app/src/main/jniLibs/arm64-v8a/.");
            return privateNative;
        }

        File[] fallbackCandidates = new File[]{
                new File(PathManager.DIR_FILE, SABLE_NATIVE),
                new File(new File(PathManager.DIR_FILE, "runtime_mod"), SABLE_NATIVE),
                new File(new File(PathManager.DIR_FILE, "sable"), SABLE_NATIVE)
        };

        for (File candidate : fallbackCandidates) {
            if (candidate.isFile() && candidate.length() > 0) return candidate;
        }

        return null;
    }

    private static boolean copyFirstExistingAsset(
            @NonNull Context context,
            @NonNull File target,
            @NonNull String... assetNames
    ) {
        for (String assetName : assetNames) {
            if (copyAsset(context, assetName, target)) {
                return true;
            }
        }
        return target.isFile() && target.length() > 0;
    }

    private static boolean copyAsset(@NonNull Context context, @NonNull String assetName, @NonNull File target) {
        try (InputStream input = context.getAssets().open(assetName)) {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }

            try (FileOutputStream output = new FileOutputStream(target, false)) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }

            makeLoadable(target);
            log("Copied asset " + assetName + " to " + target.getAbsolutePath());
            return target.isFile() && target.length() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void makeLoadable(@NonNull File file) {
        try {
            //noinspection ResultOfMethodCallIgnored
            file.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            file.setExecutable(true, false);
        } catch (Throwable ignored) {
        }
    }

    private static boolean hasSableMod(@NonNull File gameDirectory) {
        if (containsSableJar(new File(gameDirectory, "mods"))) {
            return true;
        }

        String minecraftHome = PathManager.DIR_MINECRAFT_HOME;
        if (minecraftHome != null && !minecraftHome.trim().isEmpty()) {
            File globalMods = new File(minecraftHome, "mods");
            if (!sameFile(globalMods, new File(gameDirectory, "mods")) && containsSableJar(globalMods)) {
                return true;
            }
        }

        return false;
    }

    private static boolean containsSableJar(@NonNull File modsDir) {
        File[] files = modsDir.listFiles();
        if (files == null) return false;

        for (File file : files) {
            if (!file.isFile()) continue;
            String name = file.getName().toLowerCase(Locale.ROOT);

            // Disabled mods should not activate the native override.
            if (name.endsWith(".disabled")) continue;
            if (!name.endsWith(".jar")) continue;
            if (name.contains("sable")) return true;
        }
        return false;
    }

    @Nullable
    private static File findInRuntimeModDirectory() {
        try {
            Field field = PathManager.class.getField("DIR_RUNTIME_MOD");
            Object value = field.get(null);
            if (value instanceof File) {
                return new File((File) value, SABLE_NATIVE);
            }
        } catch (Throwable ignored) {
            // Older JavaLauncher builds may not have DIR_RUNTIME_MOD yet.
        }
        return null;
    }

    private static void preloadCxxIfPresent(@Nullable File parent) {
        if (parent == null) return;
        File cxxShared = new File(parent, CXX_SHARED);
        if (!cxxShared.isFile()) return;

        try {
            if (JREUtils.dlopen(cxxShared.getAbsolutePath())) {
                log("Preloaded " + CXX_SHARED + " for Sable Rapier.");
            }
        } catch (Throwable throwable) {
            log("Unable to preload " + CXX_SHARED + ": " + throwable.getMessage());
        }
    }

    private static void preloadSableNative(@NonNull File nativeFile) {
        try {
            if (JREUtils.dlopen(nativeFile.getAbsolutePath())) {
                log("Preloaded " + SABLE_NATIVE + ".");
            }
        } catch (Throwable throwable) {
            log("Unable to preload " + SABLE_NATIVE + ": " + throwable.getMessage());
        }
    }

    private static boolean sameFile(@NonNull File left, @NonNull File right) {
        try {
            return left.getCanonicalFile().equals(right.getCanonicalFile());
        } catch (Throwable ignored) {
            return left.getAbsolutePath().equals(right.getAbsolutePath());
        }
    }

    private static void log(@NonNull String message) {
        Logging.i(TAG, message);
        try {
            net.kdt.pojavlaunch.Logger.appendToLog(TAG + ": " + message);
        } catch (Throwable ignored) {
        }
    }
}
