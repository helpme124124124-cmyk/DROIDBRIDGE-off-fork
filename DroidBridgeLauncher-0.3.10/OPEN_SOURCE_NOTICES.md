# Open Source Notices

This file tracks open-source attribution for DroidBridge Launcher.

Keep this file updated whenever code, assets, native components, build scripts, or implementation details are copied from, modified from, derived from, or bundled from another project.

This file is not a substitute for the full license texts. Include the full license texts in the app and repository where required.

---

## PojavLauncher

- Project: PojavLauncher
- Repository: https://github.com/PojavLauncherTeam/PojavLauncher
- License: GNU Lesser General Public License v3.0 unless a file says otherwise.
- Notice: PojavLauncher is an Android/iOS Minecraft: Java Edition launcher based on Boardwalk.

DroidBridge Launcher contains or may contain PojavLauncher-derived compatibility interfaces, runtime bridge logic, input/surface handling concepts, package-level compatibility classes, or other launcher-side implementation details.

Known areas that should be audited and treated as PojavLauncher-derived unless independently documented otherwise:

```text
app/src/main/java/net/kdt/pojavlaunch/
app/src/main/java/net/kdt/pojavlaunch/**
```

If a file is copied or modified from PojavLauncher, preserve the original copyright notice if one exists, keep the applicable license terms, and document the source file/commit when known.

Suggested file header for small PojavLauncher-derived compatibility files without an upstream header:

```java
/*
 * Derived from or compatible with PojavLauncher.
 * Original project: https://github.com/PojavLauncherTeam/PojavLauncher
 * License: GNU Lesser General Public License v3.0.
 */
```

---

## Boardwalk

- Project: Boardwalk
- Repository: https://github.com/zhuowei/Boardwalk
- License: Apache License 2.0 unless a file says otherwise.

Boardwalk is credited for early Minecraft: Java Edition on Android launcher/runtime work and historical concepts that influenced later Android Java launchers.

If any Boardwalk-derived source is included, preserve the Apache License 2.0 notice requirements.

---

## LWJGL / LWJGL3

- Project: LWJGL
- Repository: https://github.com/LWJGL/lwjgl3
- License: BSD-3-Clause unless a file says otherwise.

DroidBridge Launcher may use, reference, bundle, or integrate LWJGL/LWJGL3 Java classes, native libraries, or Android compatibility glue depending on the branch or build configuration.

---

## OpenJDK / Java Runtime Components

DroidBridge Launcher may use or integrate Java runtime components for Android builds.

OpenJDK components are commonly licensed under GPLv2 with the Classpath Exception, but exact obligations depend on the specific runtime distribution bundled or downloaded by the app.

Document the exact runtime distribution, source link, build scripts, and license texts for every release that bundles Java runtime files.

---

## Mesa 3D Graphics Library

- Project: Mesa 3D Graphics Library
- Repository: https://gitlab.freedesktop.org/mesa/mesa
- License: MIT-style licenses depending on component/file.

DroidBridge Launcher may use or integrate Mesa-related renderer components depending on release/build configuration.

---

## GL4ES

- Project: GL4ES
- Repository: https://github.com/ptitSeb/gl4es
- License: MIT unless a file says otherwise.

DroidBridge Launcher may use or integrate GL4ES-related renderer components depending on release/build configuration.

---

## AndroidX / Google Material Components / Gradle Dependencies

Android application dependencies may include AndroidX, Google Material Components, Gradle plugins, and other open-source libraries.

Use Gradle dependency metadata and license reports to update this section before release.

---

## Release checklist

Before publishing an APK or public release:

- [ ] Remove secrets and local machine paths from the repo.
- [ ] Remove `local.properties` from Git history if it was ever committed.
- [ ] Rotate any exposed API keys or app credentials.
- [ ] Add full license texts under `LICENSES/`.
- [ ] Keep or restore license headers for copied/modified third-party files.
- [ ] Add source links for LGPL/GPL-covered components.
- [ ] Add in-app open-source notices.
- [ ] Verify privacy policy and Data safety disclosures.
