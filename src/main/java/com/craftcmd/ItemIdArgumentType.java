/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.resources.Identifier;

/**
 * Brigadier argument for an item id, e.g. {@code minecraft:crafting_table}. A missing namespace
 * defaults to {@code minecraft}, so {@code crafting_table} works too.
 *
 * <p>This is the command line form of the ids that used to be typed after {@code /cmdop craft};
 * {@code POST /op/} parses the very same argument, which keeps the accepted syntax identical.</p>
 */
public final class ItemIdArgumentType implements ArgumentType<Identifier> {
	public static final ItemIdArgumentType INSTANCE = new ItemIdArgumentType();

	private ItemIdArgumentType() {
	}

	@Override
	public Identifier parse(StringReader reader) throws CommandSyntaxException {
		return Identifier.read(reader);
	}
}
