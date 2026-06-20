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

package ca.dnamobile.javalauncher.ui.instance;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

import ca.dnamobile.javalauncher.R;
import ca.dnamobile.javalauncher.instance.LauncherInstance;

public final class InstanceIconResolver {
    private InstanceIconResolver() {
    }

    @DrawableRes
    public static int getDefaultIcon(@NonNull LauncherInstance instance) {
        return getDefaultIcon(
                instance.getLoader(),
                instance.getBaseVersionId(),
                instance.getMinecraftVersionId(),
                instance.getName()
        );
    }

    @DrawableRes
    public static int getDefaultIcon(
            @Nullable String loader,
            @Nullable String baseVersionId,
            @Nullable String minecraftVersionId,
            @Nullable String instanceName
    ) {
        String combined = normalize(loader)
                + " " + normalize(baseVersionId)
                + " " + normalize(minecraftVersionId)
                + " " + normalize(instanceName);

        // Check NeoForge before Forge because "neoforge" contains "forge".
        if (combined.contains("neoforge") || combined.contains("neo forge")) {
            return R.drawable.ic_neoforge;
        }

        if (combined.contains("forge")) {
            return R.drawable.ic_forge;
        }

        if (combined.contains("fabric")) {
            return R.drawable.ic_fabric;
        }

        return R.drawable.ic_old_grass_block;
    }

    @NonNull
    private static String normalize(@Nullable String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
