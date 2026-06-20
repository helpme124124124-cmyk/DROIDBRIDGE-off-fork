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

package ca.dnamobile.javalauncher.instance;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.dnamobile.javalauncher.feature.log.Logging;

/**
 * Seeds a small Android-friendly options.txt for new instances only.
 *
 * Asset files must be stored in:
 * app/src/main/assets/minecraft_defaults/
 *
 * Expected files:
 * - minecraft_defaults/options-beta-legacy-optional.txt
 * - minecraft_defaults/options-release-1.8-to-1.16.txt
 * - minecraft_defaults/options-modern-1.17-plus.txt
 */
public final class DefaultMinecraftOptionsInstaller {
    private static final String TAG = "DefaultOptions";

    private static final String ASSET_BETA_LEGACY = "minecraft_defaults/options-beta-legacy-optional.txt";
    private static final String ASSET_RELEASE_1_8_TO_1_16 = "minecraft_defaults/options-release-1.8-to-1.16.txt";
    private static final String ASSET_MODERN_1_17_PLUS = "minecraft_defaults/options-modern-1.17-plus.txt";

    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");

    private DefaultMinecraftOptionsInstaller() {
    }

    /**
     * Backwards-compatible entry point.
     *
     * This intentionally does not seed when the Minecraft version is unknown. Passing no version
     * is unsafe because Beta/Alpha/Classic and modern releases use different option keys.
     * Prefer installIfMissingForNewInstance(context, gameDirectory, minecraftVersionId).
     */
    public static void installIfMissingForNewInstance(
            @NonNull Context context,
            @NonNull File gameDirectory
    ) {
        installIfMissingForNewInstance(context, gameDirectory, null);
    }

    /**
     * Call this when creating a new instance, using the selected Minecraft version id/name.
     * Examples: "b1.7.3", "1.12.2", "1.21.11", "26.1.2", "26.2-snapshot-2".
     */
    public static void installIfMissingForNewInstance(
            @NonNull Context context,
            @NonNull File gameDirectory,
            @Nullable String minecraftVersionId
    ) {
        File optionsFile = new File(gameDirectory, "options.txt");
        if (optionsFile.exists()) {
            Logging.i(TAG, "options.txt already exists, not overwriting: " + optionsFile.getAbsolutePath());
            return;
        }

        OptionsPreset preset = choosePreset(minecraftVersionId);
        if (preset == OptionsPreset.SKIP_UNKNOWN) {
            Logging.i(TAG, "Skipping default options.txt for unknown Minecraft version: "
                    + String.valueOf(minecraftVersionId));
            return;
        }

        File parent = optionsFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Logging.i(TAG, "Unable to create game directory for default options: " + parent.getAbsolutePath());
            return;
        }

        try (InputStream input = context.getAssets().open(preset.assetPath);
             FileOutputStream output = new FileOutputStream(optionsFile, false)) {

            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();

            Logging.i(TAG, "Seeded " + preset.logName + " options.txt for "
                    + String.valueOf(minecraftVersionId) + ": " + optionsFile.getAbsolutePath());
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to seed default options.txt from asset "
                    + preset.assetPath + " to " + optionsFile.getAbsolutePath(), throwable);
        }
    }

    @NonNull
    private static OptionsPreset choosePreset(@Nullable String minecraftVersionId) {
        if (minecraftVersionId == null) {
            return OptionsPreset.SKIP_UNKNOWN;
        }

        String version = minecraftVersionId.trim().toLowerCase(Locale.US);
        if (version.length() == 0) {
            return OptionsPreset.SKIP_UNKNOWN;
        }

        // Beta/Alpha/Classic/old development versions need the legacy-safe options file.
        if (version.startsWith("b")
                || version.startsWith("a")
                || version.startsWith("rd")
                || version.startsWith("c0")
                || version.contains("beta")
                || version.contains("alpha")
                || version.contains("classic")
                || version.contains("infdev")
                || version.contains("indev")) {
            return OptionsPreset.BETA_LEGACY;
        }

        // Mojang-style weekly snapshots like 24w45a/25w31a are modern enough for modern options.
        if (version.matches("^\\d{2}w\\d{2}[a-z].*")) {
            return OptionsPreset.MODERN_1_17_PLUS;
        }

        int[] numbers = extractFirstThreeNumbers(version);
        int major = numbers[0];
        int minor = numbers[1];

        if (major < 0) {
            return OptionsPreset.SKIP_UNKNOWN;
        }

        // DroidBridge/modern Minecraft naming such as 26.1.2 / 26.2-snapshot-2.
        if (major >= 26) {
            return OptionsPreset.MODERN_1_17_PLUS;
        }

        // Standard Java Edition releases such as 1.8, 1.12.2, 1.16.5, 1.21.11.
        if (major == 1) {
            if (minor >= 17) {
                return OptionsPreset.MODERN_1_17_PLUS;
            }
            if (minor >= 8) {
                return OptionsPreset.RELEASE_1_8_TO_1_16;
            }

            // 1.0 through 1.7.x are closer to legacy options than the 1.8+ format.
            return OptionsPreset.BETA_LEGACY;
        }

        return OptionsPreset.SKIP_UNKNOWN;
    }

    @NonNull
    private static int[] extractFirstThreeNumbers(@NonNull String text) {
        int[] result = new int[]{-1, -1, -1};
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        int index = 0;
        while (matcher.find() && index < result.length) {
            try {
                result[index] = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                result[index] = -1;
            }
            index++;
        }
        return result;
    }

    private enum OptionsPreset {
        SKIP_UNKNOWN("skip", ""),
        BETA_LEGACY("beta-legacy", ASSET_BETA_LEGACY),
        RELEASE_1_8_TO_1_16("release-1.8-to-1.16", ASSET_RELEASE_1_8_TO_1_16),
        MODERN_1_17_PLUS("modern-1.17-plus", ASSET_MODERN_1_17_PLUS);

        final String logName;
        final String assetPath;

        OptionsPreset(@NonNull String logName, @NonNull String assetPath) {
            this.logName = logName;
            this.assetPath = assetPath;
        }
    }
}
