/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

/**
 * One rotation instruction parsed from the {@code /rot} command: either an absolute angle or an
 * offset relative to the current one.
 *
 * @param relative {@code true} for the {@code ~<offset>} form, {@code false} for a plain number
 * @param value    the absolute angle in degrees, or the offset to add to the current angle
 */
public record Angle(boolean relative, double value) {
}
