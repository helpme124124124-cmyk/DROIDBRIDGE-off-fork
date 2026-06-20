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

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Locale;

/**
 * Launcher-side Simple Voice Chat helper.
 *
 * This does not modify Simple Voice Chat itself. It does the Android launcher
 * work that the mod cannot do on its own:
 * - detect the voicechat jar in common mod locations
 * - make sure Android RECORD_AUDIO permission is granted before launch
 * - provide a Settings entry point for users to grant/recover permission
 */
public final class SimpleVoiceChatCompat {
    private static final String TAG = "SimpleVoiceChatCompat";

    private SimpleVoiceChatCompat() {
    }

    public static boolean isInstalledForInstance(@Nullable File gameDirectory) {
        if (gameDirectory == null) {
            return false;
        }

        // Most JavaLauncher instances use: instance/game/mods
        if (containsVoiceChatJar(new File(gameDirectory, "mods"))) {
            return true;
        }

        File instanceDir = gameDirectory.getParentFile();
        if (instanceDir != null && containsVoiceChatJar(new File(instanceDir, "mods"))) {
            return true;
        }

        // Walk up a few levels and check shared .minecraft/mods if present.
        File cursor = gameDirectory;
        for (int i = 0; i < 5 && cursor != null; i++) {
            if (".minecraft".equals(cursor.getName()) && containsVoiceChatJar(new File(cursor, "mods"))) {
                return true;
            }
            cursor = cursor.getParentFile();
        }

        return false;
    }

    public static boolean containsVoiceChatJar(@Nullable File modsDir) {
        if (modsDir == null || !modsDir.isDirectory()) {
            return false;
        }

        File[] files = modsDir.listFiles();
        if (files == null) {
            return false;
        }

        for (File file : files) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (!file.isFile() || !name.endsWith(".jar")) {
                continue;
            }

            // Current Simple Voice Chat jars are usually voicechat-*.jar,
            // but allow simple-voice-chat naming too.
            if (name.startsWith("voicechat-")
                    || name.contains("simple-voice-chat")
                    || name.contains("simplevoicechat")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Call this from the Activity that owns the launch button, before starting Minecraft.
     *
     * @return true when launch can continue, false when a permission prompt was shown
     */
    public static boolean ensureMicrophoneReadyBeforeLaunch(
            @NonNull Activity activity,
            @Nullable File gameDirectory
    ) {
        if (!isInstalledForInstance(gameDirectory)) {
            return true;
        }

        if (AndroidMicrophonePermission.isGranted(activity)) {
            Log.i(TAG, "Simple Voice Chat detected and microphone permission is granted");
            return true;
        }

        Log.i(TAG, "Simple Voice Chat detected but microphone permission is missing");
        AndroidMicrophonePermission.showRequestDialog(activity);
        return false;
    }

    /**
     * Safe non-Activity check for launch code that cannot request permissions.
     * Use this only to warn/log. Runtime permission prompts require an Activity.
     */
    public static boolean isMicrophoneReady(@NonNull Context context) {
        return AndroidMicrophonePermission.isGranted(context);
    }
}
