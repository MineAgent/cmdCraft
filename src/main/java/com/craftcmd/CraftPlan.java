/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

/**
 * A fully validated crafting job: which grid positions have to be filled with which items, how many
 * times the recipe has to be crafted and which items the player has to own for it.
 *
 * <p>Everything is derived from the client side recipe book ({@link RecipeDisplay}) because modern
 * Minecraft no longer sends the full {@code Recipe} objects to the client. Every candidate plan is
 * simulated against a copy of the player's inventory before it is accepted, so a plan that cannot be
 * executed never reaches {@link CraftJob}.
 */
public final class CraftPlan {
	/** Main inventory + hotbar; armour and offhand slots are intentionally ignored. */
	public static final int INVENTORY_SLOTS = 36;

	/** A grid position that has to be filled with one of {@code candidates}. */
	public record Position(int gridIndex, List<ItemStack> candidates) {
	}

	/** Thrown when a recipe cannot be crafted; carries the user facing message. */
	public static final class Failure extends Exception {
		public final transient Component message;

		Failure(Component message) {
			super(message.getString());
			this.message = message;
		}
	}

	private final Identifier itemId;
	private final RecipeDisplayId displayId;
	private final ItemStack result;
	private final int resultPerCraft;
	private final int crafts;
	private final int requestedAmount;
	private final List<Position> positions;

	private CraftPlan(Identifier itemId, RecipeDisplayId displayId, ItemStack result, int resultPerCraft, int crafts,
			int requestedAmount, List<Position> positions) {
		this.itemId = itemId;
		this.displayId = displayId;
		this.result = result;
		this.resultPerCraft = resultPerCraft;
		this.crafts = crafts;
		this.requestedAmount = requestedAmount;
		this.positions = List.copyOf(positions);
	}

	public Identifier itemId() {
		return this.itemId;
	}

	public RecipeDisplayId displayId() {
		return this.displayId;
	}

	/** Prototype of the crafted stack; its count is the amount produced by a single craft. */
	public ItemStack result() {
		return this.result;
	}

	public int resultPerCraft() {
		return this.resultPerCraft;
	}

	public int crafts() {
		return this.crafts;
	}

	public int requestedAmount() {
		return this.requestedAmount;
	}

	public List<Position> positions() {
		return this.positions;
	}

	public int totalResultCount() {
		return this.crafts * this.resultPerCraft;
	}

	public int totalIngredientCount() {
		return this.crafts * this.positions.size();
	}

	public static boolean accepts(Position position, ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}

		for (ItemStack candidate : position.candidates()) {
			if (stack.is(candidate.getItem())) {
				return true;
			}
		}

		return false;
	}

	// ------------------------------------------------------------------
	// plan building
	// ------------------------------------------------------------------

	public static CraftPlan build(Minecraft client, Identifier itemId, int requestedAmount, AbstractCraftingMenu menu)
			throws Failure {
		if (client.player == null || client.level == null) {
			throw new Failure(Component.translatable("craftcmd.error.not_ready"));
		}

		Item item = BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);

		if (item == null) {
			throw new Failure(Component.translatable("craftcmd.error.unknown_item", itemId.toString()));
		}

		ContextMap context;

		try {
			context = SlotDisplayContext.fromLevel(client.level);
		} catch (RuntimeException exception) {
			CraftCmdMod.LOGGER.warn("[craftcmd] could not build a recipe context", exception);
			throw new Failure(Component.translatable("craftcmd.error.not_ready"));
		}

		List<RecipeDisplayEntry> entries = collectEntries(client, item, context);

		if (entries.isEmpty()) {
			throw new Failure(Component.translatable("craftcmd.error.no_recipe", itemId.toString()));
		}

		List<CraftPlan> candidates = new ArrayList<>();
		Component lastFailure = null;

		for (RecipeDisplayEntry entry : entries) {
			try {
				candidates.add(fromEntry(client, menu, entry, context, requestedAmount, itemId));
			} catch (Failure failure) {
				lastFailure = failure.message;
			}
		}

		if (candidates.isEmpty()) {
			throw new Failure(lastFailure != null ? lastFailure
					: Component.translatable("craftcmd.error.unsupported", itemId.toString()));
		}

		candidates.sort(Comparator.comparingInt(CraftPlan::totalIngredientCount));
		return candidates.get(0);
	}

	private static List<RecipeDisplayEntry> collectEntries(Minecraft client, Item item, ContextMap context) {
		List<RecipeDisplayEntry> entries = new ArrayList<>();

		for (RecipeCollection collection : client.player.getRecipeBook().getCollections()) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				RecipeDisplay display = entry.display();

				if (!isCraftingDisplay(display)) {
					continue;
				}

				List<ItemStack> results;

				try {
					results = display.result().resolveForStacks(context);
				} catch (RuntimeException exception) {
					continue;
				}

				if (results.isEmpty() || !results.get(0).is(item)) {
					continue;
				}

				entries.add(entry);
			}
		}

		return entries;
	}

	private static CraftPlan fromEntry(Minecraft client, AbstractCraftingMenu menu, RecipeDisplayEntry entry,
			ContextMap context, int requestedAmount, Identifier itemId) throws Failure {
		RecipeDisplay display = entry.display();
		List<ItemStack> results = display.result().resolveForStacks(context);

		if (results.isEmpty()) {
			throw new Failure(Component.translatable("craftcmd.error.unsupported", itemId.toString()));
		}

		ItemStack result = results.get(0).copy();
		int resultPerCraft = Math.max(1, result.getCount());
		result.setCount(resultPerCraft);
		int crafts = Math.max(1, (requestedAmount + resultPerCraft - 1) / resultPerCraft);

		int gridWidth = menu.getGridWidth();
		int gridHeight = menu.getGridHeight();
		List<Position> positions = new ArrayList<>();

		if (display instanceof ShapedCraftingRecipeDisplay shaped) {
			int width = shaped.width();
			int height = shaped.height();

			if (width > gridWidth || height > gridHeight) {
				throw new Failure(Component.translatable("craftcmd.error.needs_table"));
			}

			List<SlotDisplay> ingredients = shaped.ingredients();

			for (int i = 0; i < width * height && i < ingredients.size(); i++) {
				SlotDisplay slot = ingredients.get(i);

				if (slot instanceof SlotDisplay.Empty) {
					continue;
				}

				int x = i % width;
				int y = i / width;
				positions.add(new Position(y * gridWidth + x, resolve(slot, context, itemId)));
			}
		} else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
			List<SlotDisplay> ingredients = shapeless.ingredients();

			if (ingredients.size() > gridWidth * gridHeight) {
				throw new Failure(Component.translatable("craftcmd.error.needs_table"));
			}

			int index = 0;

			for (SlotDisplay slot : ingredients) {
				if (slot instanceof SlotDisplay.Empty) {
					continue;
				}

				positions.add(new Position(index++, resolve(slot, context, itemId)));
			}
		} else {
			throw new Failure(Component.translatable("craftcmd.error.unsupported", itemId.toString()));
		}

		if (positions.isEmpty()) {
			throw new Failure(Component.translatable("craftcmd.error.unsupported", itemId.toString()));
		}

		Inventory inventory = client.player.getInventory();
		List<ItemStack> pool = new ArrayList<>(INVENTORY_SLOTS);

		for (int i = 0; i < INVENTORY_SLOTS; i++) {
			pool.add(inventory.getItem(i).copy());
		}

		consume(pool, positions, crafts);
		int remaining = insert(pool, result, crafts * resultPerCraft);

		if (remaining > 0) {
			throw new Failure(Component.translatable("craftcmd.error.no_space", result.getHoverName(),
					crafts * resultPerCraft));
		}

		return new CraftPlan(itemId, entry.id(), result, resultPerCraft, crafts, requestedAmount, positions);
	}

	private static boolean isCraftingDisplay(RecipeDisplay display) {
		return display instanceof ShapedCraftingRecipeDisplay || display instanceof ShapelessCraftingRecipeDisplay;
	}

	private static List<ItemStack> resolve(SlotDisplay display, ContextMap context, Identifier itemId) throws Failure {
		List<ItemStack> stacks;

		try {
			stacks = display.resolveForStacks(context);
		} catch (RuntimeException exception) {
			throw new Failure(Component.translatable("craftcmd.error.unsupported", itemId.toString()));
		}

		List<ItemStack> usable = new ArrayList<>(stacks.size());

		for (ItemStack stack : stacks) {
			if (!stack.isEmpty()) {
				usable.add(stack);
			}
		}

		if (usable.isEmpty()) {
			throw new Failure(Component.translatable("craftcmd.error.unsupported", itemId.toString()));
		}

		return usable;
	}

	/**
	 * Consumes the ingredients for {@code crafts} crafts from the given inventory copy. Items that are
	 * accepted by fewer grid positions are preferred, so that flexible ingredients (tags) are kept for
	 * the positions that can only use them.
	 */
	private static void consume(List<ItemStack> pool, List<Position> positions, int crafts) throws Failure {
		List<ItemStack> initial = new ArrayList<>(pool.size());

		for (ItemStack stack : pool) {
			initial.add(stack.copy());
		}

		for (int craft = 0; craft < crafts; craft++) {
			for (int p = 0; p < positions.size(); p++) {
				Position position = positions.get(p);
				int bestSlot = -1;
				int bestFlexibility = Integer.MAX_VALUE;

				for (int slot = 0; slot < pool.size(); slot++) {
					ItemStack stack = pool.get(slot);

					if (stack.isEmpty() || !accepts(position, stack)) {
						continue;
					}

					int flexibility = 0;

					for (Position other : positions) {
						if (accepts(other, stack)) {
							flexibility++;
						}
					}

					if (flexibility < bestFlexibility) {
						bestFlexibility = flexibility;
						bestSlot = slot;
					}
				}

				if (bestSlot < 0) {
					// Report how much of that item the whole recipe needs, not just this one slot.
					ItemStack missing = position.candidates().get(0);
					Item item = missing.getItem();
					int groupSize = 0;

					for (Position other : positions) {
						if (acceptsItem(other, item)) {
							groupSize++;
						}
					}

					int available = 0;

					for (ItemStack stack : initial) {
						if (stack.is(item)) {
							available += stack.getCount();
						}
					}

					throw new Failure(Component.translatable("craftcmd.error.not_enough", missing.getHoverName(),
							crafts * groupSize, available));
				}

				pool.get(bestSlot).shrink(1);
			}
		}
	}

	public static boolean acceptsItem(Position position, Item item) {
		for (ItemStack candidate : position.candidates()) {
			if (candidate.is(item)) {
				return true;
			}
		}

		return false;
	}

	/** Inserts {@code amount} items into the inventory copy; returns the amount that did not fit. */
	private static int insert(List<ItemStack> pool, ItemStack prototype, int amount) {
		int remaining = amount;

		for (int i = 0; i < pool.size() && remaining > 0; i++) {
			ItemStack stack = pool.get(i);

			if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, prototype)) {
				continue;
			}

			int moved = Math.min(stack.getMaxStackSize() - stack.getCount(), remaining);

			if (moved > 0) {
				stack.grow(moved);
				remaining -= moved;
			}
		}

		for (int i = 0; i < pool.size() && remaining > 0; i++) {
			if (!pool.get(i).isEmpty()) {
				continue;
			}

			int moved = Math.min(prototype.getMaxStackSize(), remaining);
			pool.set(i, prototype.copyWithCount(moved));
			remaining -= moved;
		}

		return remaining;
	}
}
