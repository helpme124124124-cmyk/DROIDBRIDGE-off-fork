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

#ifndef LINKER_NSBYPASS_H
#define LINKER_NSBYPASS_H

#include <stdbool.h>

bool linker_ns_load(const char* lib_search_path);
void* linker_ns_dlopen(const char* name, int flag);
void* linker_ns_dlopen_unique(const char* tmpdir, const char* name, int flag);

#endif //LINKER_NSBYPASS_H