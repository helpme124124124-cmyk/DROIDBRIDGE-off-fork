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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.dnamobile.javalauncher.feature.log.Logging;

public final class VulkanModConfigMitigation {
    private static final String TAG = "VulkanModConfigMitigation";

    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "VulkanMod.*?([0-9]+\\.[0-9]+\\.[0-9]+).*?\\.jar",
            Pattern.CASE_INSENSITIVE
    );

    private VulkanModConfigMitigation() {
    }

    public static void prepare(@Nullable File gameDir) {
        if (gameDir == null) return;

        try {
            File modJar = findVulkanModJar(gameDir);
            if (modJar == null) {
                Logging.i(TAG, "No VulkanMod jar found.");
                return;
            }

            String version = extractVersion(modJar.getName());
            if (version == null || version.trim().isEmpty()) {
                Logging.i(TAG, "VulkanMod found but version could not be parsed: " + modJar.getName());
                return;
            }

            if (compareVersions(version, "0.6.3") < 0) {
                Logging.i(TAG, "Skipped for VulkanMod " + version);
                return;
            }

            File configDir = new File(gameDir, "config");
            if (!configDir.exists() && !configDir.mkdirs()) {
                Logging.i(TAG, "Could not create config dir: " + configDir.getAbsolutePath());
                return;
            }

            File configFile = new File(configDir, "vulkanmod_settings.json");

            JSONObject json = readJson(configFile);
            JSONObject videoMode = json.optJSONObject("videoMode");
            if (videoMode == null) {
                videoMode = new JSONObject();
            }

            videoMode.put("width", -1);
            videoMode.put("height", -1);
            videoMode.put("bitDepth", -1);
            videoMode.put("refreshRate", -1);

            json.put("videoMode", videoMode);

            // 2 = fullscreen/window mode fix used to stop VulkanMod 0.6.3+ crashing
            json.put("windowMode", 2);

            Files.write(
                    configFile.toPath(),
                    json.toString(2).getBytes(StandardCharsets.UTF_8)
            );

            Logging.i(TAG, "Forced windowMode=2 for VulkanMod " + version);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to apply VulkanMod config mitigation", throwable);
        }
    }

    @NonNull
    private static JSONObject readJson(@NonNull File configFile) {
        if (!configFile.isFile()) {
            return new JSONObject();
        }

        try {
            String text = new String(
                    Files.readAllBytes(configFile.toPath()),
                    StandardCharsets.UTF_8
            );
            return new JSONObject(text);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Invalid VulkanMod config JSON, recreating: " + configFile.getAbsolutePath());
            return new JSONObject();
        }
    }

    @Nullable
    private static File findVulkanModJar(@NonNull File gameDir) {
        File parent = gameDir.getParentFile();

        File[] candidates = parent != null
                ? new File[]{
                new File(gameDir, "mods"),
                new File(parent, "mods")
        }
                : new File[]{
                new File(gameDir, "mods")
        };

        for (File dir : candidates) {
            if (!dir.isDirectory()) continue;

            File[] files = dir.listFiles();
            if (files == null) continue;

            for (File file : files) {
                if (!file.isFile()) continue;

                String name = file.getName();
                if (!name.toLowerCase().endsWith(".jar")) continue;
                if (!name.toLowerCase().contains("vulkanmod")) continue;

                return file;
            }
        }

        return null;
    }

    @Nullable
    private static String extractVersion(@NonNull String fileName) {
        Matcher matcher = VERSION_PATTERN.matcher(fileName);
        if (!matcher.find()) return null;
        return matcher.group(1);
    }

    private static int compareVersions(@NonNull String first, @NonNull String second) {
        String[] a = first.split("\\.");
        String[] b = second.split("\\.");
        int max = Math.max(a.length, b.length);

        for (int i = 0; i < max; i++) {
            int av = parseVersionPart(a, i);
            int bv = parseVersionPart(b, i);

            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }

        return 0;
    }

    private static int parseVersionPart(@NonNull String[] parts, int index) {
        if (index >= parts.length) return 0;

        try {
            return Integer.parseInt(parts[index]);
        } catch (Throwable ignored) {
            return 0;
        }
    }
}