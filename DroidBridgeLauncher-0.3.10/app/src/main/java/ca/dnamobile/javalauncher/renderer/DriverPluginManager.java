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

package ca.dnamobile.javalauncher.renderer;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ca.dnamobile.javalauncher.feature.log.Logging;
import ca.dnamobile.javalauncher.settings.LauncherPreferences;
import ca.dnamobile.javalauncher.utils.path.PathManager;

/**
 * Vulkan driver manager used only by Vulkan Zink.
 *
 * Renderer plugins are OpenGL wrappers. Driver plugins are Vulkan ICDs such as
 * Turnip/Freedreno. Keep these separate so the Zink renderer dropdown does not
 * become polluted with normal renderer plugins like MobileGlues/GL4ES.
 */
public final class DriverPluginManager {
    private static final String TAG = "DriverPluginManager";

    public static final String DEFAULT_MESA_DRIVER = "Default Mesa driver";
    public static final String SYSTEM_VULKAN_DRIVER = "System Vulkan driver";

    private static final String[] KNOWN_DRIVER_PLUGIN_PACKAGES = new String[]{
            "com.fcl.plugin.driver.freedreno",
            "com.fcl.plugin.driver.turnip",
            "com.fcl.plugin.turnip",
            "com.fcl.plugin.adreno",
            "com.mio.plugin.driver.freedreno",
            "com.mio.plugin.driver.turnip",
            "com.mio.plugin.driver.adreno",
            "com.bzlzhh.plugin.driver.freedreno",
            "com.bzlzhh.plugin.driver.turnip",
            "com.bzlzhh.plugin.turnip",
            "com.bzlzhh.plugin.adreno",
            "com.tungsten.fcl.plugin.driver.freedreno",
            "com.tungsten.fcl.plugin.driver.turnip"
    };

    private static final String[] VULKAN_LIBRARY_NAMES = new String[]{
            "libvulkan_freedreno.so",
            "libvulkan_turnip.so",
            "libvulkan_adreno.so"
    };

    private static final ArrayList<Driver> DRIVERS = new ArrayList<>();
    private static boolean initialized;

    private DriverPluginManager() {
    }

    public static synchronized void reload(@NonNull Context context) {
        initialized = false;
        init(context);
    }

    public static synchronized void init(@NonNull Context context) {
        PathManager.initContextConstants(context);
        if (initialized) return;
        initialized = true;
        DRIVERS.clear();

        addDriver(new Driver(DEFAULT_MESA_DRIVER, Driver.Type.DEFAULT_MESA, null, null, null));

        addBuiltInTurnipIfAvailable(context);
        discoverInstalledDriverPlugins(context);
    }

    @NonNull
    public static synchronized List<Driver> getDrivers(@NonNull Context context) {
        init(context);
        return new ArrayList<>(DRIVERS);
    }

    @NonNull
    public static synchronized Driver getSelectedDriver(@NonNull Context context) {
        init(context);
        String selected = LauncherPreferences.getSelectedVulkanDriverName(context);
        for (int i = DRIVERS.size() - 1; i >= 0; i--) {
            Driver driver = DRIVERS.get(i);
            if (driver.getName().equals(selected)) return driver;
        }

        return DRIVERS.get(0);
    }

    public static synchronized int indexOfDriver(@NonNull Context context, @Nullable String selectedName) {
        init(context);
        Driver selected = getSelectedDriver(context);
        String effectiveName = selectedName != null ? selectedName : selected.getName();
        for (int i = 0; i < DRIVERS.size(); i++) {
            if (effectiveName.equals(DRIVERS.get(i).getName())) return i;
        }
        for (int i = 0; i < DRIVERS.size(); i++) {
            if (selected.getName().equals(DRIVERS.get(i).getName())) return i;
        }
        return 0;
    }

    public static boolean isVulkanZinkRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;
        String combined = (renderer.getRendererId() + " " + renderer.getRendererName() + " " + renderer.getRendererLibrary())
                .toLowerCase(Locale.ROOT);
        return combined.contains("vulkan_zink") || combined.contains("zink") || combined.contains("osmesa");
    }

    @NonNull
    public static List<File> getSelectedDriverLibrarySearchPaths(@NonNull Context context, @Nullable RendererInterface renderer) {
        ArrayList<File> paths = new ArrayList<>();

        // Driver plugins are Vulkan ICDs. Do not leak Turnip/Freedreno into LTW,
        // MobileGlues, GL4ES, or Krypton. LTW especially must use the normal
        // system Vulkan loader path used by Zalith, otherwise AdrenoSupp/Turnip
        // can be loaded before LTW and glMapBuffer/glDraw paths become unstable.
        if (!isVulkanZinkRenderer(renderer)) {
            return paths;
        }

        if (LauncherPreferences.isUseSystemVulkanDriver(context)) {
            return paths;
        }

        Driver driver = getSelectedDriver(context);
        File dir;

        if (driver.getType() == Driver.Type.TURNIP) {
            dir = driver.getNativeLibraryDir();
        } else {
            dir = findBundledDriverDir(context);
        }

        if (dir != null && dir.isDirectory()) paths.add(dir);
        return paths;
    }

    @NonNull
    public static Map<String, String> buildEnvironment(@NonNull Context context, @Nullable RendererInterface renderer) {
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        boolean zink = isVulkanZinkRenderer(renderer);
        boolean useSystemVulkan = LauncherPreferences.isUseSystemVulkanDriver(context);
        Driver driver = getSelectedDriver(context);

        env.put("JAVA_LAUNCHER_USE_SYSTEM_VULKAN_DRIVER", useSystemVulkan ? "1" : "0");
        env.put("JAVA_LAUNCHER_VULKAN_DRIVER", useSystemVulkan ? SYSTEM_VULKAN_DRIVER : driver.getName());

        if (!zink) {
            // Non-Zink renderers are OpenGL wrappers. Force them away from custom
            // Vulkan ICD plugins and clear stale driver variables from previous launches.
            env.put("POJAV_USE_SYSTEM_VULKAN", "1");
            env.put("DRIVER_PATH", "");
            env.put("VK_ICD_FILENAMES", "");
            env.put("VK_DRIVER_FILES", "");
            env.put("LIBGL_DRIVERS_PATH", "");
            env.put("EGL_DRIVERS_PATH", "");
            env.put("MESA_LOADER_DRIVER_OVERRIDE", "");
            env.put("GALLIUM_DRIVER", "");
            return env;
        }

        applyGlobalVulkanDriverEnvironment(context, env, driver, true, useSystemVulkan);
        applyZinkMesaEnvironment(env);
        return env;
    }

    private static void applyGlobalVulkanDriverEnvironment(
            @NonNull Context context,
            @NonNull LinkedHashMap<String, String> env,
            @NonNull Driver driver,
            boolean zink,
            boolean useSystemVulkan
    ) {
        if (useSystemVulkan) {
            env.put("POJAV_USE_SYSTEM_VULKAN", "1");
            env.put("DRIVER_PATH", "");
            env.put("VK_ICD_FILENAMES", "");
            env.put("VK_DRIVER_FILES", "");
            return;
        }

        env.put("POJAV_USE_SYSTEM_VULKAN", "0");

        if (driver.getType() == Driver.Type.TURNIP) {
            applyTurnipDriverEnvironment(context, env, driver);
            return;
        }

        if (zink) {
            File bundled = findBundledDriverDir(context);
            if (bundled != null && bundled.isDirectory()) {
                env.put("DRIVER_PATH", bundled.getAbsolutePath());
            } else {
                // No custom/bundled Turnip is available. Fall back to system Vulkan
                // so Zink still has a usable Vulkan loader instead of a null path.
                env.put("POJAV_USE_SYSTEM_VULKAN", "1");
                env.put("DRIVER_PATH", "");
            }
        } else {
            // Non-Zink renderers can still launch Vulkan mods. If no custom driver
            // is selected, clear any stale Turnip env left from an earlier launch.
            env.put("DRIVER_PATH", "");
            env.put("VK_ICD_FILENAMES", "");
            env.put("VK_DRIVER_FILES", "");
        }
    }

    private static void applyTurnipDriverEnvironment(
            @NonNull Context context,
            @NonNull LinkedHashMap<String, String> env,
            @NonNull Driver driver
    ) {
        File nativeDir = driver.getNativeLibraryDir();
        if (nativeDir != null && nativeDir.isDirectory()) {
            env.put("DRIVER_PATH", nativeDir.getAbsolutePath());
        }

        File icd = buildIcdFile(context, driver);
        if (icd != null && icd.isFile()) {
            env.put("VK_ICD_FILENAMES", icd.getAbsolutePath());
            env.put("VK_DRIVER_FILES", icd.getAbsolutePath());
        } else {
            env.put("VK_ICD_FILENAMES", "");
            env.put("VK_DRIVER_FILES", "");
        }
    }

    private static void applyZinkMesaEnvironment(@NonNull LinkedHashMap<String, String> env) {
        // Pojav/Zalith's OSMesa/Zink path expects these basic Mesa values.
        env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
        env.put("GALLIUM_DRIVER", "zink");
        env.put("LIBGL_ES", "3");
        env.put("LIBGL_NOERROR", "1");
        env.put("LIBGL_NORMALIZE", "1");
        env.put("LIBGL_MIPMAP", "3");
        env.put("MESA_GLSL_CACHE_DIR", PathManager.DIR_CACHE.getAbsolutePath());
        env.put("MESA_SHADER_CACHE_DIR", PathManager.DIR_CACHE.getAbsolutePath());
        env.put("MESA_VK_WSI_PRESENT_MODE", "mailbox");
    }

    @Nullable
    private static File findBundledDriverDir(@NonNull Context context) {
        ArrayList<File> dirs = new ArrayList<>();
        if (context.getApplicationInfo().nativeLibraryDir != null) {
            addDirIfValid(dirs, new File(context.getApplicationInfo().nativeLibraryDir));
        }
        if (PathManager.DIR_NATIVE_LIB != null) addDirIfValid(dirs, new File(PathManager.DIR_NATIVE_LIB));
        for (File dir : dirs) {
            if (findVulkanLibrary(dir) != null) return dir;
        }
        return null;
    }

    @Nullable
    private static File buildIcdFile(@NonNull Context context, @NonNull Driver driver) {
        File vulkan = driver.getVulkanLibrary();
        if (vulkan == null || !vulkan.isFile()) return null;

        File dir = new File(PathManager.DIR_CACHE, "vulkan_icd");
        if (!dir.exists() && !dir.mkdirs()) return null;

        String safe = driver.getName().replaceAll("[^A-Za-z0-9_.-]", "_");
        File icd = new File(dir, safe + ".json");
        try {
            JSONObject root = new JSONObject();
            JSONObject icdObject = new JSONObject();
            root.put("file_format_version", "1.0.0");
            icdObject.put("library_path", vulkan.getAbsolutePath());
            icdObject.put("api_version", "1.3.0");
            root.put("ICD", icdObject);

            try (FileOutputStream output = new FileOutputStream(icd)) {
                output.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            icd.setReadable(true, false);
            return icd;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to write Vulkan ICD file for " + driver.getName(), throwable);
            return null;
        }
    }

    private static void addBuiltInTurnipIfAvailable(@NonNull Context context) {
        ArrayList<File> dirs = new ArrayList<>();
        if (context.getApplicationInfo().nativeLibraryDir != null) {
            addDirIfValid(dirs, new File(context.getApplicationInfo().nativeLibraryDir));
        }
        if (PathManager.DIR_NATIVE_LIB != null) addDirIfValid(dirs, new File(PathManager.DIR_NATIVE_LIB));

        for (File dir : dirs) {
            File vulkan = findVulkanLibrary(dir);
            if (vulkan != null) {
                addDriver(new Driver("Built-in Turnip / Adreno", Driver.Type.TURNIP, context.getPackageName(), dir, vulkan));
                return;
            }
        }
    }

    private static void discoverInstalledDriverPlugins(@NonNull Context context) {
        PackageManager pm = context.getPackageManager();

        for (String packageName : KNOWN_DRIVER_PLUGIN_PACKAGES) {
            try {
                PackageInfo info = getPackageInfo(pm, packageName);
                if (info != null) parsePluginPackage(context, info);
            } catch (Throwable ignored) {
            }
        }

        try {
            List<ApplicationInfo> apps;
            int flags = PackageManager.GET_META_DATA;
            if (Build.VERSION.SDK_INT >= 33) {
                apps = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags));
            } else {
                //noinspection deprecation
                apps = pm.getInstalledApplications(flags);
            }
            for (ApplicationInfo app : apps) {
                if (app == null || app.packageName == null) continue;
                PackageInfo info = getPackageInfo(pm, app.packageName);
                if (info != null) parsePluginPackage(context, info);
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "Installed driver plugin scan was limited by Android package visibility: " + throwable);
        }
    }

    @Nullable
    private static PackageInfo getPackageInfo(@NonNull PackageManager pm, @NonNull String packageName) {
        try {
            long flags = PackageManager.GET_META_DATA;
            if (Build.VERSION.SDK_INT >= 33) {
                return pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags));
            } else {
                //noinspection deprecation
                return pm.getPackageInfo(packageName, (int) flags);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void parsePluginPackage(@NonNull Context context, @NonNull PackageInfo info) {
        ApplicationInfo app = info.applicationInfo;
        if (app == null || app.packageName == null) return;
        if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return;

        String lowerPackage = app.packageName.toLowerCase(Locale.ROOT);
        Bundle meta = app.metaData;
        boolean fclPlugin = meta != null && meta.getBoolean("fclPlugin", false);
        String declaredDriver = meta != null ? meta.getString("driver") : null;
        boolean packageLooksLikeDriver = lowerPackage.contains("driver")
                || lowerPackage.contains("turnip")
                || lowerPackage.contains("freedreno")
                || lowerPackage.contains("adreno");

        File nativeDir = app.nativeLibraryDir != null ? new File(app.nativeLibraryDir) : null;
        File vulkan = nativeDir != null ? findVulkanLibrary(nativeDir) : null;

        // Only real Vulkan driver plugins belong in this dropdown. Do not show
        // renderer plugins or generic FCL plugins unless a Turnip/Freedreno
        // Vulkan library is actually present.
        if (vulkan == null) return;
        if (!fclPlugin && !packageLooksLikeDriver) return;
        if (nativeDir == null || !nativeDir.isDirectory()) return;

        String label = declaredDriver;
        if (label == null || label.trim().isEmpty()) {
            try {
                CharSequence appLabel = context.getPackageManager().getApplicationLabel(app);
                label = appLabel != null ? appLabel.toString() : app.packageName;
            } catch (Throwable ignored) {
                label = app.packageName;
            }
        }

        addDriver(new Driver(label, Driver.Type.TURNIP, app.packageName, nativeDir, vulkan));
    }

    @Nullable
    private static File findVulkanLibrary(@NonNull File nativeDir) {
        for (String name : VULKAN_LIBRARY_NAMES) {
            File candidate = new File(nativeDir, name);
            if (candidate.isFile()) return candidate;
        }
        return null;
    }

    private static void addDriver(@NonNull Driver driver) {
        for (int i = 0; i < DRIVERS.size(); i++) {
            Driver existing = DRIVERS.get(i);
            if (existing.getName().equals(driver.getName())) {
                DRIVERS.set(i, driver);
                Logging.i(TAG, "Driver replaced: " + driver.getName());
                return;
            }
        }
        DRIVERS.add(driver);
        Logging.i(TAG, "Driver loaded: " + driver.getName());
    }

    private static void addDirIfValid(@NonNull List<File> dirs, @Nullable File dir) {
        if (dir != null && dir.isDirectory() && !dirs.contains(dir)) dirs.add(dir);
    }
}
