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
// Created by maks on 15.01.2025.
//

#ifndef POJAVLAUNCHER_STDIO_IS_H
#define POJAVLAUNCHER_STDIO_IS_H

#include <stdbool.h>

_Noreturn void nominal_exit(int code, bool is_signal);

#endif //POJAVLAUNCHER_STDIO_IS_H
