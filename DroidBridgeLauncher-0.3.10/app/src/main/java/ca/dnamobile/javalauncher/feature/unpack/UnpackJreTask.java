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

package ca.dnamobile.javalauncher.feature.unpack;

import android.content.Context;
import android.content.res.AssetManager;

import androidx.annotation.NonNull;

import net.kdt.pojavlaunch.Architecture;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;

import ca.dnamobile.javalauncher.feature.log.Logging;
import ca.dnamobile.javalauncher.launcher.RuntimeCompat;
import ca.dnamobile.javalauncher.utils.path.PathManager;

/**
 * Zalith-style internal runtime unpack task.
 *
 * Important for Android 14-16:
 * - Do not call MultiRTUtils.getRuntimeHome() before installing. It throws when
 *   the runtime is missing/broken, which prevents a fresh install from starting.
 * - Do not invent a custom "version" marker. MultiRTUtils writes/reads
 *   the marker through readInternalRuntimeVersion().
 * - Do not require Java 8 bin/java. Game launches use VMLauncher/JLI. Java 8 is
 *   valid when rt.jar + libjvm.so + pojav_version are present.
 */
public class UnpackJreTask extends AbstractUnpackTask {
    private static final String TAG = "UnpackJreTask";

    private final Context context;
    private final Jre jre;

    private AssetManager assetManager;
    private String launcherRuntimeVersion;
    private boolean checkFailed;

    public UnpackJreTask(@NonNull Context context, @NonNull Jre jre) {
        this.context = context.getApplicationContext();
        this.jre = jre;

        try {
            assetManager = this.context.getAssets();
            try (InputStream versionInput = assetManager.open(jre.jrePath + "/version")) {
                launcherRuntimeVersion = Tools.read(versionInput).trim();
            }
            if ("Internal-8".equals(jre.jreName)) {
                Logging.i(TAG, "Runtime patch active: " + RuntimeCompat.PATCH_ID);
            }
        } catch (Throwable throwable) {
            checkFailed = true;
            Logging.e(TAG, "Failed to read bundled runtime version for " + jre.jreName, throwable);
        }
    }

    public boolean isCheckFailed() {
        return checkFailed;
    }

    @Override
    public boolean isNeedUnpack() {
        if (checkFailed) return false;

        try {
            String installedRuntimeVersion = MultiRTUtils.readInternalRuntimeVersion(jre.jreName);
            boolean versionMatches = launcherRuntimeVersion != null
                    && launcherRuntimeVersion.equals(installedRuntimeVersion != null ? installedRuntimeVersion.trim() : null);

            File runtimeHome = runtimeHomeFile();
            int javaMajor = RuntimeCompat.javaMajorForRuntimeName(jre.jreName);
            boolean runtimeUsable = RuntimeCompat.isRuntimeInstalledForJava(jre.jreName, runtimeHome, javaMajor);

            if (versionMatches && runtimeUsable) {
                Logging.i(TAG, jre.jreName + " is up to date. " + RuntimeCompat.describeRuntimeState(jre.jreName, runtimeHome));
                return false;
            }

            Logging.i(TAG, jre.jreName + " needs unpack. installedVersion=" + installedRuntimeVersion
                    + " bundledVersion=" + launcherRuntimeVersion
                    + " usable=" + runtimeUsable
                    + " state=" + RuntimeCompat.describeRuntimeState(jre.jreName, runtimeHome));

            return true;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Check failed for internal runtime " + jre.jreName, throwable);
            // Match Zalith behavior: do not trap launcher startup if the check itself fails.
            return false;
        }
    }

    @Override
    public void run() {
        if (listener != null) listener.onTaskStart();

        try {
            File runtimeHome = runtimeHomeFile();
            File stagingHome = new File(runtimeHome.getParentFile(), runtimeHome.getName() + " installing");
            PathManager.deleteQuietly(stagingHome);

            try (InputStream universal = assetManager.open(jre.jrePath + "/universal.tar.xz");
                 InputStream bin = openBinPack()) {
                MultiRTUtils.installRuntimeNamedBinpack(
                        universal,
                        bin,
                        jre.jreName,
                        launcherRuntimeVersion
                );
            }

            try {
                MultiRTUtils.postPrepare(jre.jreName);
            } catch (Throwable throwable) {
                // Zalith logs and continues here. Java 8 unpack200 is already run
                // inside installRuntimeNamedBinpack(); postPrepare is mostly native
                // library normalization/copying.
                Logging.e(TAG, "postPrepare failed for " + jre.jreName + "; launcher will validate installed runtime", throwable);
            }


            runtimeHome = runtimeHomeFile();
            int javaMajor = RuntimeCompat.javaMajorForRuntimeName(jre.jreName);
            Logging.i(TAG, "After unpack " + jre.jreName + ": " + RuntimeCompat.describeRuntimeState(jre.jreName, runtimeHome));

            if (!RuntimeCompat.isRuntimeInstalledForJava(jre.jreName, runtimeHome, javaMajor)) {
                Logging.e(TAG, jre.jreName + " unpack finished but runtime is still not usable. "
                        + RuntimeCompat.describeRuntimeState(jre.jreName, runtimeHome), null);
            }
        } catch (Throwable throwable) {
            // Match Zalith: log, end task, and allow the launcher to open. The
            // runtime dropdown/latestlog will show why Internal-8 is not usable.
            Logging.e(TAG, "Internal JRE unpack failed for " + jre.jreName, throwable);
        } finally {
            if (listener != null) listener.onTaskEnd();
        }
    }

    @NonNull
    private File runtimeHomeFile() {
        return new File(PathManager.DIR_MULTIRT_HOME, jre.jreName);
    }

    @NonNull
    private InputStream openBinPack() throws IOException {
        String primary = Architecture.archAsString(Tools.DEVICE_ARCHITECTURE);

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(primary);

        if ("arm64-v8a".equals(primary)) {
            candidates.add("arm64");
            candidates.add("aarch64");
        } else if ("arm64".equals(primary)) {
            candidates.add("arm64-v8a");
            candidates.add("aarch64");
        } else if ("aarch64".equals(primary)) {
            candidates.add("arm64");
            candidates.add("arm64-v8a");
        }

        if ("armeabi-v7a".equals(primary)) {
            candidates.add("arm");
        } else if ("arm".equals(primary)) {
            candidates.add("armeabi-v7a");
        }

        IOException last = null;
        for (String arch : candidates) {
            String path = jre.jrePath + "/bin-" + arch + ".tar.xz";
            try {
                Logging.i(TAG, "Trying runtime bin pack: " + path);
                return assetManager.open(path);
            } catch (IOException e) {
                last = e;
            }
        }

        throw last != null ? last : new IOException("Unable to open runtime bin pack for " + primary);
    }
}
