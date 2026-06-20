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

package ca.dnamobile.javalauncher.settings;

import android.app.ActivityManager;
import android.content.Context;

import androidx.annotation.NonNull;

/**
 * Shared RAM allocation helper for settings UI and launch args.
 *
 * The slider maximum is based on the RAM Android currently reports as available.
 * The default is rounded down to the nearest safe 2048 MB block so the launcher
 * does not over-allocate memory on first run.
 */
public final class MemoryAllocationUtils {
    public static final int RAM_STEP_MB = 256;
    public static final int RAM_DEFAULT_ROUND_MB = 2048;
    private static final int ABSOLUTE_MIN_MEMORY_MB = 512;

    private MemoryAllocationUtils() {
    }

    public static int getTotalMemoryMb(@NonNull Context context) {
        ActivityManager.MemoryInfo info = readMemoryInfo(context);
        long totalMb = info.totalMem / 1024L / 1024L;
        return (int) Math.max(ABSOLUTE_MIN_MEMORY_MB, Math.min(Integer.MAX_VALUE, totalMb));
    }

    public static int getAvailableMemoryMb(@NonNull Context context) {
        ActivityManager.MemoryInfo info = readMemoryInfo(context);
        long availableMb = info.availMem / 1024L / 1024L;
        return (int) Math.max(ABSOLUTE_MIN_MEMORY_MB, Math.min(Integer.MAX_VALUE, availableMb));
    }

    public static int getMaxAllocatableMemoryMb(@NonNull Context context) {
        int availableMb = getAvailableMemoryMb(context);
        int max = roundDownToStep(availableMb, RAM_STEP_MB);
        return Math.max(ABSOLUTE_MIN_MEMORY_MB, max);
    }

    public static int getMinimumMemoryMb(int maxMemoryMb) {
        return Math.min(ABSOLUTE_MIN_MEMORY_MB, Math.max(RAM_STEP_MB, maxMemoryMb));
    }

    public static int getDefaultAllocatedMemoryMb(@NonNull Context context) {
        int maxMemoryMb = getMaxAllocatableMemoryMb(context);
        int rounded = roundDownToStep(maxMemoryMb, RAM_DEFAULT_ROUND_MB);

        if (rounded < ABSOLUTE_MIN_MEMORY_MB) {
            rounded = maxMemoryMb;
        }

        return clampToAllowedRam(context, rounded);
    }

    public static int resolveAllocatedMemoryMb(@NonNull Context context) {
        int fallback = getDefaultAllocatedMemoryMb(context);
        int saved = LauncherPreferences.getAllocatedMemoryMb(context, fallback);
        int clamped = clampToAllowedRam(context, saved);

        if (saved != clamped) {
            LauncherPreferences.setAllocatedMemoryMb(context, clamped);
        }

        return clamped;
    }

    public static int clampToAllowedRam(@NonNull Context context, int requestedMb) {
        int maxMemoryMb = getMaxAllocatableMemoryMb(context);
        int minMemoryMb = getMinimumMemoryMb(maxMemoryMb);
        int rounded = roundToNearestStep(requestedMb, RAM_STEP_MB);

        if (rounded < minMemoryMb) return minMemoryMb;
        if (rounded > maxMemoryMb) return maxMemoryMb;
        return rounded;
    }

    private static ActivityManager.MemoryInfo readMemoryInfo(@NonNull Context context) {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        ActivityManager activityManager = (ActivityManager) context.getApplicationContext()
                .getSystemService(Context.ACTIVITY_SERVICE);

        if (activityManager != null) {
            activityManager.getMemoryInfo(info);
        }

        return info;
    }

    private static int roundDownToStep(int value, int step) {
        if (step <= 0) return value;
        return Math.max(0, (value / step) * step);
    }

    private static int roundToNearestStep(int value, int step) {
        if (step <= 0) return value;
        return Math.round(value / (float) step) * step;
    }
}
