/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.network.chat.Component;

/**
 * Brigadier argument for a single rotation value, using vanilla's {@code ~} notation:
 *
 * <ul>
 *   <li>{@code 90} sets the angle to 90°</li>
 *   <li>{@code ~0.1} adds 0.1° to the current angle</li>
 *   <li>{@code ~-20} subtracts 20° from the current angle</li>
 *   <li>{@code ~} on its own keeps the current angle (offset 0)</li>
 * </ul>
 */
public final class AngleArgumentType implements ArgumentType<Angle> {
	public static final AngleArgumentType INSTANCE = new AngleArgumentType();

	private static final SimpleCommandExceptionType ERROR_INVALID = new SimpleCommandExceptionType(
			Component.translatable("craftcmd.look.error.invalid_value"));

	private AngleArgumentType() {
	}

	@Override
	public Angle parse(StringReader reader) throws CommandSyntaxException {
		if (reader.canRead() && reader.peek() == '~') {
			reader.skip();

			if (!reader.canRead()) {
				return new Angle(true, 0.0);
			}

			return new Angle(true, readValue(reader));
		}

		return new Angle(false, readValue(reader));
	}

	/** Reads the number, turning Brigadier's built-in (untranslated) error into a localised one. */
	private static double readValue(StringReader reader) throws CommandSyntaxException {
		try {
			return reader.readDouble();
		} catch (CommandSyntaxException exception) {
			throw ERROR_INVALID.createWithContext(reader);
		}
	}
}
