/*
 * Derived from PojavLauncher.
 *
 * Original project:
 * https://github.com/PojavLauncherTeam/PojavLauncher
 *
 * Original license: GNU Lesser General Public License v3.0.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * This file remains available under the terms of the GNU LGPLv3
 * unless the original file or bundled component states a different license.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package net.kdt.pojavlaunch;

public final class AWTInputBridge {
    private AWTInputBridge() {
    }

    public static native void nativeSendData(int type, int i1, int i2, int i3, int i4);

    public static native void nativeClipboardReceived(String clipboardData, String clipboardDataMime);
}
