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

package ca.dnamobile.javalauncher.utils;

import android.content.Context;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.IOException;

import ca.dnamobile.javalauncher.utils.path.PathManager;

public final class CopyDefaultFromAssets {
    private CopyDefaultFromAssets() {
    }

    public static void copyFromAssets(@Nullable Context context) throws IOException {
        if (context == null) return;

        // Default control layout, matching Zalith behavior.
        if (checkDirectoryEmpty(PathManager.DIR_CTRLMAP_PATH)) {
            Tools.copyAssetFile(context, "default.json", PathManager.DIR_CTRLMAP_PATH, false);
        }
    }

    private static boolean checkDirectoryEmpty(@Nullable String dir) {
        if (dir == null) return true;
        File controlDir = new File(dir);
        File[] files = controlDir.listFiles();
        return files == null || files.length == 0;
    }
}
