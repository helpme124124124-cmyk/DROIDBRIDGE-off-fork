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

package ca.dnamobile.javalauncher.controls;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import ca.dnamobile.javalauncher.feature.log.Logging;

public final class TouchControlsStore {
    private static final String TAG = "TouchControlsStore";
    private static final String DEFAULT_FILE = "droidbridge_default.json";
    private static final String DEFAULT_ASSET = "touch_controls/droidbridge_default.json";
    private static final String[] DEFAULT_ASSET_CANDIDATES = new String[]{
            DEFAULT_ASSET,
            "touch_controls/default.json",
            "touch_controls/default_touch_controls.json",
            "droidbridge_default.json",
            "default.json",
            "default_touch_controls.json"
    };

    private TouchControlsStore() {
    }

    @NonNull
    public static File getControlsDir(@NonNull Context context) {
        File dir = new File(context.getFilesDir(), "touch_controls");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return dir;
    }

    @NonNull
    public static File getDefaultLayoutFile(@NonNull Context context) {
        return new File(getControlsDir(context), DEFAULT_FILE);
    }

    @NonNull
    public static File ensureDefaultLayout(@NonNull Context context) {
        File target = getDefaultLayoutFile(context);

        // Do not overwrite this file once it exists. After first launch it is the user's
        // editable copy. If they edit the default layout, their changes must win.
        if (!target.isFile() || target.length() == 0) {
            try {
                TouchControlsLayoutData bundled = loadBundledDefaultLayout(context);
                saveLayout(target, bundled);
                Logging.i(TAG, "Created default touch controls from bundled asset: " + DEFAULT_ASSET);
            } catch (Throwable assetThrowable) {
                Logging.e(TAG, "Bundled default_touch.json missing or invalid. Falling back to emergency in-code layout.", assetThrowable);
                try {
                    saveLayout(target, TouchControlsLayoutData.defaultLayout());
                } catch (Throwable fallbackThrowable) {
                    Logging.e(TAG, "Unable to create fallback default touch controls", fallbackThrowable);
                }
            }
        }

        return target;
    }

    @NonNull
    public static File getSelectedLayoutFile(@NonNull Context context) {
        File defaultFile = ensureDefaultLayout(context);
        String selected = ControlsPreferences.getSelectedLayoutPath(context);
        if (selected != null) {
            File selectedFile = new File(selected);
            if (selectedFile.isFile()) return selectedFile;
        }

        ControlsPreferences.setSelectedLayoutPath(context, defaultFile.getAbsolutePath());
        return defaultFile;
    }

    @NonNull
    public static TouchControlsLayoutData loadSelectedLayout(@NonNull Context context) {
        return loadLayout(getSelectedLayoutFile(context));
    }

    @NonNull
    public static TouchControlsLayoutData loadLayout(@NonNull File file) {
        try {
            return readLayoutFromFile(file);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to read touch layout " + file.getAbsolutePath(), throwable);

            File backup = backupFileFor(file);
            if (backup.isFile()) {
                try {
                    TouchControlsLayoutData data = readLayoutFromFile(backup);
                    Logging.i(TAG, "Recovered touch layout from backup: " + backup.getAbsolutePath());
                    return data;
                } catch (Throwable backupThrowable) {
                    Logging.e(TAG, "Unable to read backup touch layout " + backup.getAbsolutePath(), backupThrowable);
                }
            }

            return TouchControlsLayoutData.defaultLayout();
        }
    }

    public static void saveLayout(@NonNull File file, @NonNull TouchControlsLayoutData data) throws Exception {
        writeTextAtomically(file, data.toJson().toString(2));
    }

    @NonNull
    private static TouchControlsLayoutData readLayoutFromFile(@NonNull File file) throws Exception {
        String text = readText(file);
        return TouchControlsLayoutData.fromJson(new JSONObject(text));
    }

    @NonNull
    public static File saveImportedLayout(@NonNull Context context, @NonNull Uri uri) throws Exception {
        String source = readUriText(context, uri);
        TouchControlsLayoutData data = TouchControlsLayoutData.fromJson(new JSONObject(source));
        String cleanName = sanitizeFileName(data.name);
        if (cleanName.isEmpty()) cleanName = "imported_controls";
        File target = uniqueFile(getControlsDir(context), cleanName, ".json");
        saveLayout(target, data);
        ControlsPreferences.setSelectedLayoutPath(context, target.getAbsolutePath());
        return target;
    }

    @NonNull
    public static List<File> listLayouts(@NonNull Context context) {
        ensureDefaultLayout(context);
        ArrayList<File> layouts = new ArrayList<>();
        File[] files = getControlsDir(context).listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().toLowerCase().endsWith(".json")) {
                    layouts.add(file);
                }
            }
        }
        return layouts;
    }

    @NonNull
    public static String readText(@NonNull File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            return readStreamText(input);
        }
    }

    @NonNull
    private static TouchControlsLayoutData loadBundledDefaultLayout(@NonNull Context context) throws Exception {
        Throwable lastError = null;

        for (String assetPath : DEFAULT_ASSET_CANDIDATES) {
            try (InputStream input = context.getAssets().open(assetPath)) {
                String text = readStreamText(input);

                // Important: parse through the same importer as manual Import.
                // This keeps JavaLauncher JSON and Zalith/Mojo/Amethyst-style
                // mControlDataList / mJoystickDataList layouts working the same way.
                TouchControlsLayoutData data = TouchControlsLayoutData.fromJson(new JSONObject(text));
                if (data.name == null || data.name.trim().isEmpty()) {
                    data.name = "Default Touch Controls";
                }
                Logging.i(TAG, "Loaded bundled default touch controls from asset: " + assetPath);
                return data;
            } catch (Throwable throwable) {
                lastError = throwable;
            }
        }

        throw new IllegalStateException("No bundled default touch layout found. Expected " + DEFAULT_ASSET, lastError);
    }

    @NonNull
    private static String readUriText(@NonNull Context context, @NonNull Uri uri) throws Exception {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("Unable to open selected controls file.");
            return readStreamText(input);
        }
    }

    @NonNull
    private static String readStreamText(@NonNull InputStream input) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        StringBuilder builder = new StringBuilder();
        int read;
        while ((read = input.read(buffer)) != -1) {
            builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private static void writeTextAtomically(@NonNull File file, @NonNull String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Unable to create touch controls directory: " + parent.getAbsolutePath());
        }

        File directory = parent != null ? parent : new File(".");
        File temp = new File(directory, file.getName() + ".tmp");
        File backup = backupFileFor(file);

        writeText(temp, text);

        if (file.isFile()) {
            try {
                copyFile(file, backup);
            } catch (Throwable backupThrowable) {
                Logging.e(TAG, "Unable to update touch layout backup " + backup.getAbsolutePath(), backupThrowable);
            }
        }

        if (file.exists() && !file.delete()) {
            // Some file systems refuse delete immediately after the copy. Fall back
            // to overwriting the target after the temp file has been fully synced.
            copyFile(temp, file);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return;
        }

        if (!temp.renameTo(file)) {
            copyFile(temp, file);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static void writeText(@NonNull File file, @NonNull String text) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            output.write(bytes);
            output.flush();
            try {
                output.getFD().sync();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void copyFile(@NonNull File source, @NonNull File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Unable to create touch controls directory: " + parent.getAbsolutePath());
        }

        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target, false)) {
            copy(input, output);
            output.flush();
            try {
                output.getFD().sync();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void copy(@NonNull InputStream input, @NonNull OutputStream output) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    @NonNull
    private static File backupFileFor(@NonNull File file) {
        File parent = file.getParentFile();
        File directory = parent != null ? parent : new File(".");
        return new File(directory, file.getName() + ".bak");
    }

    @NonNull
    private static File uniqueFile(@NonNull File dir, @NonNull String base, @NonNull String suffix) {
        File file = new File(dir, base + suffix);
        int index = 2;
        while (file.exists()) {
            file = new File(dir, base + "_" + index + suffix);
            index++;
        }
        return file;
    }

    @NonNull
    private static String sanitizeFileName(@NonNull String name) {
        return name.trim().replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("_+", "_");
    }
}
