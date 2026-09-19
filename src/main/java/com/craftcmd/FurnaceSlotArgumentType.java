/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

/** Brigadier argument for one of the furnace slots ({@code raw}, {@code fuel}, {@code product}). */
public final class FurnaceSlotArgumentType implements ArgumentType<FurnaceSlot> {
	private static final SimpleCommandExceptionType ERROR_UNKNOWN = new SimpleCommandExceptionType(
			Component.translatable("craftcmd.furnace.error.unknown_slot"));

	private static final FurnaceSlotArgumentType PUT = new FurnaceSlotArgumentType(FurnaceSlot.PUT_SLOTS);
	private static final FurnaceSlotArgumentType GET = new FurnaceSlotArgumentType(FurnaceSlot.GET_SLOTS);

	private final List<FurnaceSlot> allowed;

	private FurnaceSlotArgumentType(List<FurnaceSlot> allowed) {
		this.allowed = allowed;
	}

	public static FurnaceSlotArgumentType put() {
		return PUT;
	}

	public static FurnaceSlotArgumentType get() {
		return GET;
	}

	@Override
	public FurnaceSlot parse(StringReader reader) throws CommandSyntaxException {
		String name = reader.readUnquotedString();

		for (FurnaceSlot slot : this.allowed) {
			if (slot.id().equalsIgnoreCase(name)) {
				return slot;
			}
		}

		throw ERROR_UNKNOWN.createWithContext(reader);
	}

	@Override
	public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(this.allowed.stream().map(FurnaceSlot::id).toList(), builder);
	}
}
