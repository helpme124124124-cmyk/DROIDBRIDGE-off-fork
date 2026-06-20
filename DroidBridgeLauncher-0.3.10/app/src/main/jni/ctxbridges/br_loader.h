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
// Created by Vera-Firefly on 28.01.2025.
//
#ifndef BR_LOADER_H
#define BR_LOADER_H

void* load_symbol(void* handle, const char* symbol_name);
void* OSMGetProcAddress(void* handle, const char* symbol_name);
void* GLGetProcAddress(void* handle, const char* symbol_name);

#endif //BR_LOADER_H