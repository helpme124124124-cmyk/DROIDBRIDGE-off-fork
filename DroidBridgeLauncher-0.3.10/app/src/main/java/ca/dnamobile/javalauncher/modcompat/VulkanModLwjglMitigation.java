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

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import ca.dnamobile.javalauncher.feature.log.Logging;
import net.kdt.pojavlaunch.Logger;

public final class VulkanModLwjglMitigation {
    private static final String TAG = "VulkanModLwjglMitigation";
    private static final String MARKER_ENTRY = "META-INF/javalauncher/vulkanmod_lwjgl_override";
    private static final String LEGACY_MARKER_ENTRY = "META-INF/zalith/vulkanmod_lwjgl_override";
    private static final String WORK_DIR_NAME = ".javalauncher_patch";

    private VulkanModLwjglMitigation() {
    }

    public static void prepare(@Nullable File gameDir) {
        if (gameDir == null) return;

        appendBlankLogLine();
        appendLog("VulkanMod mitigation: about to run on " + gameDir.getAbsolutePath());

        try {
            List<File> modsDirs = getCandidateModsDirs(gameDir);
            boolean foundAnyJar = false;

            for (File modsDir : modsDirs) {
                appendLog("VulkanMod mitigation: scanning " + modsDir.getAbsolutePath() + " exists=" + modsDir.exists());

                File[] mods = modsDir.listFiles(file -> file.isFile() && file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
                if (mods == null || mods.length == 0) continue;

                for (File modJar : mods) {
                    String lowerName = modJar.getName().toLowerCase(java.util.Locale.ROOT);
                    if (!lowerName.contains("vulkanmod")) continue;

                    foundAnyJar = true;
                    appendLog("VulkanMod mitigation: found mod jar " + modJar.getAbsolutePath());

                    try {
                        if (!containsBundledLwjglVulkan(modJar)) {
                            appendLog("VulkanMod mitigation: no bundled lwjgl-vulkan nested jar in " + modJar.getName());
                            Log.i(TAG, "VulkanMod found but no bundled lwjgl-vulkan nested jar was detected: " + modJar.getName());
                            continue;
                        }

                        if (isAlreadyPatched(modJar)) {
                            appendLog("VulkanMod mitigation: already patched " + modJar.getAbsolutePath());
                            Log.i(TAG, "VulkanMod already patched: " + modJar.getName());
                            continue;
                        }

                        patchVulkanModJar(modJar);
                        appendLog("VulkanMod mitigation: patched successfully " + modJar.getAbsolutePath());
                        Log.i(TAG, "Patched VulkanMod to strip bundled lwjgl-vulkan: " + modJar.getAbsolutePath());
                    } catch (Throwable throwable) {
                        appendLog("VulkanMod mitigation: failed for " + modJar.getAbsolutePath() + ": " + throwable);
                        Log.e(TAG, "Failed to patch VulkanMod jar: " + modJar.getAbsolutePath(), throwable);
                    }
                }
            }

            if (!foundAnyJar) {
                appendLog("VulkanMod mitigation: no VulkanMod jar found in candidate mod directories");
            }
        } finally {
            appendLog("VulkanMod mitigation: finished");
            appendBlankLogLine();
        }
    }

    @NonNull
    private static List<File> getCandidateModsDirs(@NonNull File gameDir) {
        Set<File> dirs = new LinkedHashSet<>();

        // JavaLauncher isolated instance:
        // .minecraft/instances/<instance>/game/mods
        dirs.add(new File(gameDir, "mods"));

        // Some layouts place mods beside the game folder.
        File parent = gameDir.getParentFile();
        if (parent != null) dirs.add(new File(parent, "mods"));

        // Shared/global install:
        // .minecraft/mods
        File minecraftRoot = findMinecraftRoot(gameDir);
        if (minecraftRoot != null) dirs.add(new File(minecraftRoot, "mods"));

        return new ArrayList<>(dirs);
    }

    @Nullable
    private static File findMinecraftRoot(@Nullable File start) {
        File cursor = start;
        while (cursor != null) {
            if (".minecraft".equals(cursor.getName())) return cursor;
            cursor = cursor.getParentFile();
        }
        return null;
    }

    private static boolean containsBundledLwjglVulkan(@NonNull File jarFile) throws IOException {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (shouldStripBundledLwjglVulkan(entry.getName())) {
                    appendLog("VulkanMod mitigation: found nested Vulkan jar entry " + entry.getName());
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isAlreadyPatched(@NonNull File jarFile) throws IOException {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            return zipFile.getEntry(MARKER_ENTRY) != null || zipFile.getEntry(LEGACY_MARKER_ENTRY) != null;
        }
    }

    private static void patchVulkanModJar(@NonNull File jarFile) throws IOException {
        File parentDir = jarFile.getParentFile();
        if (parentDir == null) {
            throw new IOException("Could not resolve VulkanMod jar parent directory: " + jarFile.getAbsolutePath());
        }

        File workDir = new File(parentDir, WORK_DIR_NAME);
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw new IOException("Could not create mitigation work directory: " + workDir.getAbsolutePath());
        }

        File backup = new File(workDir, jarFile.getName() + ".backup");
        File tempFile = new File(workDir, jarFile.getName() + ".tmp");

        deleteIfExists(backup);
        deleteIfExists(tempFile);

        copyFile(jarFile, backup);
        appendLog("VulkanMod mitigation: created backup beside mod jar " + backup.getAbsolutePath());

        boolean stripped = false;
        try (ZipFile zipFile = new ZipFile(jarFile);
             ZipOutputStream output = new ZipOutputStream(new FileOutputStream(tempFile))) {

            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            byte[] buffer = new byte[8192];

            while (entries.hasMoreElements()) {
                ZipEntry inEntry = entries.nextElement();
                String name = inEntry.getName();

                if (shouldStripBundledLwjglVulkan(name)) {
                    appendLog("VulkanMod mitigation: stripping nested entry " + name);
                    Log.i(TAG, "Stripping nested Vulkan LWJGL jar entry: " + name);
                    stripped = true;
                    continue;
                }

                ZipEntry outEntry = new ZipEntry(name);
                outEntry.setMethod(inEntry.getMethod());
                if (inEntry.getMethod() == ZipEntry.STORED) {
                    outEntry.setSize(inEntry.getSize());
                    outEntry.setCompressedSize(inEntry.getCompressedSize());
                    outEntry.setCrc(inEntry.getCrc());
                }
                outEntry.setTime(inEntry.getTime());
                output.putNextEntry(outEntry);

                if (!inEntry.isDirectory()) {
                    try (InputStream input = zipFile.getInputStream(inEntry)) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                        }
                    }
                }

                output.closeEntry();
            }

            if (stripped) {
                ZipEntry marker = new ZipEntry(MARKER_ENTRY);
                output.putNextEntry(marker);
                output.write("patched".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }

        if (!stripped) {
            deleteIfExists(tempFile);
            deleteIfExists(backup);
            appendLog("VulkanMod mitigation: nothing stripped, leaving original jar untouched");
            return;
        }

        File originalBackup = new File(workDir, jarFile.getName() + ".original");
        deleteIfExists(originalBackup);

        if (jarFile.exists() && !jarFile.renameTo(originalBackup)) {
            copyFile(jarFile, originalBackup);
            if (!jarFile.delete()) {
                deleteIfExists(tempFile);
                restoreOriginalJar(backup, jarFile);
                throw new IOException("Could not move original VulkanMod jar aside: " + jarFile.getAbsolutePath());
            }
        }

        boolean replaced = tempFile.renameTo(jarFile);
        if (!replaced) {
            copyFile(tempFile, jarFile);
            replaced = jarFile.exists() && jarFile.length() > 0L;
        }

        if (!replaced) {
            restoreOriginalJar(backup, jarFile);
            throw new IOException("Could not replace VulkanMod jar with patched copy: " + jarFile.getAbsolutePath());
        }

        deleteIfExists(originalBackup);
        deleteIfExists(backup);
        deleteIfExists(tempFile);
        appendLog("VulkanMod mitigation: cleaned temporary files for " + jarFile.getName());
    }

    private static void restoreOriginalJar(@NonNull File backup, @NonNull File jarFile) throws IOException {
        if (jarFile.exists() && !jarFile.delete()) {
            throw new IOException("Could not delete failed patched jar: " + jarFile.getAbsolutePath());
        }
        copyFile(backup, jarFile);
    }

    private static boolean shouldStripBundledLwjglVulkan(@NonNull String entryName) {
        String normalized = entryName.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;

        return fileName.endsWith(".jar")
                && fileName.contains("lwjgl")
                && fileName.contains("vulkan");
    }

    private static void copyFile(@NonNull File source, @NonNull File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create target directory: " + parent.getAbsolutePath());
        }

        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static void deleteIfExists(@NonNull File file) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete temporary file: " + file.getAbsolutePath());
        }
    }

    private static void appendLog(@NonNull String message) {
        try {
            Logger.appendToLog(stripTrailingLineBreaks(message));
        } catch (Throwable ignored) {
            Logging.i(TAG, message);
        }
    }

    private static void appendBlankLogLine() {
        try {
            Logger.appendToLog("");
        } catch (Throwable ignored) {
            Logging.i(TAG, "");
        }
    }

    @NonNull
    private static String stripTrailingLineBreaks(@NonNull String message) {
        int end = message.length();
        while (end > 0) {
            char c = message.charAt(end - 1);
            if (c != '\n' && c != '\r') break;
            end--;
        }
        return end == message.length() ? message : message.substring(0, end);
    }
}
