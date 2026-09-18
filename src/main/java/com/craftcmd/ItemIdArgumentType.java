/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

/**
 * Brigadier argument for an item id. Missing namespaces default to {@code minecraft}, and the
 * suggestions only contain items that the client knows a crafting recipe for.
 */
public final class ItemIdArgumentType implements ArgumentType<Identifier> {
	public static final ItemIdArgumentType INSTANCE = new ItemIdArgumentType();

	private ItemIdArgumentType() {
	}

	@Override
	public Identifier parse(StringReader reader) throws CommandSyntaxException {
		return Identifier.read(reader);
	}

	@Override
	public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggestResource(craftableItemIds(), builder);
	}

	public static Collection<Identifier> craftableItemIds() {
		Minecraft client = Minecraft.getInstance();
		Set<Identifier> ids = new TreeSet<>();

		if (client.player == null || client.level == null) {
			return ids;
		}

		ContextMap context;

		try {
			context = SlotDisplayContext.fromLevel(client.level);
		} catch (RuntimeException exception) {
			return ids;
		}

		try {
			for (var collection : client.player.getRecipeBook().getCollections()) {
				for (RecipeDisplayEntry entry : collection.getRecipes()) {
					RecipeDisplay display = entry.display();

					if (!(display instanceof ShapedCraftingRecipeDisplay)
							&& !(display instanceof ShapelessCraftingRecipeDisplay)) {
						continue;
					}

					for (ItemStack stack : display.result().resolveForStacks(context)) {
						if (stack.isEmpty()) {
							continue;
						}

						Item item = stack.getItem();
						ids.add(BuiltInRegistries.ITEM.getKey(item));
					}
				}
			}
		} catch (RuntimeException exception) {
			CraftCmdMod.LOGGER.debug("[craftcmd] could not build item suggestions", exception);
		}

		return ids;
	}
}
