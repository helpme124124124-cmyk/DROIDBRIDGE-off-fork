/*
 * Derived from PojavLauncher native bridge code.
 *
 * Original project:
 * https://github.com/PojavLauncherTeam/PojavLauncher
 *
 * Original license: GNU Lesser General Public License v3.0,
 * unless this file or a bundled component states a different license.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * This file remains available under the terms of the GNU LGPLv3
 * unless the original file or bundled component states a different license.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

//
// Created by Vera-Firefly on 20.08.2024.
//

#ifndef VIRGL_BRIDGE_H
#define VIRGL_BRIDGE_H

bool loadSymbolsVirGL();
int virglInit();
void* virglCreateContext(void* contextSrc);
void* virglGetCurrentContext();
void virglMakeCurrent(void* window);
void virglSwapBuffers();
void virglSwapInterval(int interval);

#endif //VIRGL_BRIDGE_H
