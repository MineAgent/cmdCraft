/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Builds the {@code furnace put|get ...} command and drives the open furnace through ordinary
 * container clicks.
 *
 * <p>The commands work while a furnace screen is open (furnace, blast furnace or smoker — they all
 * share {@link AbstractFurnaceMenu}). A client cannot open a furnace by itself and it does not know
 * the contents of a closed furnace block entity, so an open screen is both necessary and sufficient.
 */
public final class FurnaceCommand {
	/** Upper bound for the amount argument; 100 stacks of 64. */
	public static final int MAX_AMOUNT = 6400;

	private FurnaceCommand() {
	}

	/** @return the {@code furnace} node, registered at the dispatcher root */
	public static LiteralArgumentBuilder<OpCommandSource> command() {
		return LiteralArgumentBuilder.<OpCommandSource>literal("furnace")
				.then(LiteralArgumentBuilder.<OpCommandSource>literal("put")
						.then(RequiredArgumentBuilder
								.<OpCommandSource, FurnaceSlot>argument("slot", FurnaceSlotArgumentType.put())
								.then(RequiredArgumentBuilder
										.<OpCommandSource, Identifier>argument("item", ItemIdArgumentType.INSTANCE)
										.executes(context -> put(context, 1))
										.then(RequiredArgumentBuilder
												.<OpCommandSource, Integer>argument("amount",
														IntegerArgumentType.integer(1, MAX_AMOUNT))
												.executes(context -> put(context,
														IntegerArgumentType.getInteger(context, "amount")))))))
				.then(LiteralArgumentBuilder.<OpCommandSource>literal("get")
						.then(RequiredArgumentBuilder
								.<OpCommandSource, FurnaceSlot>argument("slot", FurnaceSlotArgumentType.get())
								.executes(context -> get(context, 1))
								.then(RequiredArgumentBuilder
										.<OpCommandSource, Integer>argument("amount",
												IntegerArgumentType.integer(1, MAX_AMOUNT))
										.executes(context -> get(context,
												IntegerArgumentType.getInteger(context, "amount"))))));
	}

	// ------------------------------------------------------------------
	// furnace put <raw|fuel> <item> [amount]
	// ------------------------------------------------------------------

	private static int put(CommandContext<OpCommandSource> context, int amount) {
		OpCommandSource source = context.getSource();
		AbstractFurnaceMenu menu = furnaceMenu(source);

		if (menu == null) {
			return 0;
		}

		if (!menu.getCarried().isEmpty()) {
			source.sendError(Component.translatable("craftcmd.error.cursor"));
			return 0;
		}

		FurnaceSlot slot = context.getArgument("slot", FurnaceSlot.class);
		Identifier itemId = context.getArgument("item", Identifier.class);
		Item item = BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);

		if (item == null) {
			source.sendError(Component.translatable("craftcmd.error.unknown_item", itemId.toString()));
			return 0;
		}

		Minecraft client = Minecraft.getInstance();
		Inventory inventory = client.player.getInventory();
		Slot targetSlot = menu.getSlot(slot.menuSlot());
		ItemStack target = targetSlot.getItem();
		Component slotName = Component.translatable(slot.translationKey());

		if (!target.isEmpty() && !target.is(item)) {
			source.sendError(
					Component.translatable("craftcmd.furnace.error.occupied", slotName, target.getHoverName()));
			return 0;
		}

		// The stack already in the slot (or the first matching one in the inventory) decides what exactly
		// is moved: only identical stacks are ever merged into the same slot.
		ItemStack reference = target.isEmpty() ? MenuSlots.firstMatching(inventory, item) : target.copy();

		if (reference.isEmpty()) {
			source.sendError(Component.translatable("craftcmd.furnace.error.not_enough_item",
					new ItemStack(item).getHoverName(), 0, amount));
			return 0;
		}

		if (!targetSlot.mayPlace(reference)) {
			source.sendError(
					Component.translatable("craftcmd.furnace.error.cannot_place", reference.getHoverName(), slotName));
			return 0;
		}

		int max = targetSlot.getMaxStackSize(reference);

		if (target.getCount() + amount > max) {
			source.sendError(Component.translatable("craftcmd.furnace.error.stack_limit", slotName, max,
					target.getCount(), amount));
			return 0;
		}

		int available = MenuSlots.countExact(inventory, reference);

		if (available < amount) {
			source.sendError(Component.translatable("craftcmd.furnace.error.not_enough_item",
					reference.getHoverName(), available, amount));
			return 0;
		}

		int[] slots = MenuSlots.playerSlots(menu, inventory);
		int inserted = MenuSlots.moveIntoSlot(client, menu, slot.menuSlot(), reference, amount, inventory, slots);

		if (inserted < amount) {
			// The checks above should make this impossible; put back what was moved and report.
			MenuSlots.takeFromSlot(client, menu, slot.menuSlot(), inserted, inventory, slots);
			source.sendError(Component.translatable("craftcmd.furnace.error.not_enough_item",
					reference.getHoverName(), inserted, amount));
			return 0;
		}

		source.sendFeedback(
				Component.translatable("craftcmd.furnace.put.done", reference.getHoverName(), inserted, slotName));
		CraftCmdMod.LOGGER.info("[craftcmd] furnace put {} x{} into {}", itemId, inserted, slot.id());
		return inserted;
	}

	// ------------------------------------------------------------------
	// furnace get <raw|fuel|product> [amount]
	// ------------------------------------------------------------------

	private static int get(CommandContext<OpCommandSource> context, int amount) {
		OpCommandSource source = context.getSource();
		AbstractFurnaceMenu menu = furnaceMenu(source);

		if (menu == null) {
			return 0;
		}

		if (!menu.getCarried().isEmpty()) {
			source.sendError(Component.translatable("craftcmd.error.cursor"));
			return 0;
		}

		FurnaceSlot slot = context.getArgument("slot", FurnaceSlot.class);
		Minecraft client = Minecraft.getInstance();
		Inventory inventory = client.player.getInventory();
		Component slotName = Component.translatable(slot.translationKey());
		// Copy: the slot holds a live stack which is emptied by the clicks below.
		ItemStack current = menu.getSlot(slot.menuSlot()).getItem().copy();

		if (current.isEmpty()) {
			source.sendError(Component.translatable("craftcmd.furnace.error.empty_slot", slotName));
			return 0;
		}

		int count = current.getCount();
		int take = Math.min(amount, count);
		boolean wholeStack = false;

		if (slot == FurnaceSlot.PRODUCT && take < count) {
			// A result slot never accepts items back, so a stack simply cannot be split: take it all.
			take = count;
			wholeStack = true;
		}

		int room = MenuSlots.roomFor(inventory, current);

		if (room < take) {
			source.sendError(Component.translatable("craftcmd.furnace.error.no_space", current.getHoverName(), take));
			return 0;
		}

		int[] slots = MenuSlots.playerSlots(menu, inventory);
		int taken = MenuSlots.takeFromSlot(client, menu, slot.menuSlot(), take, inventory, slots);

		if (taken <= 0) {
			source.sendError(Component.translatable("craftcmd.furnace.error.empty_slot", slotName));
			return 0;
		}

		if (wholeStack) {
			source.sendFeedback(
					Component.translatable("craftcmd.furnace.get.whole", current.getHoverName(), taken, slotName));
		} else {
			source.sendFeedback(
					Component.translatable("craftcmd.furnace.get.done", current.getHoverName(), taken, slotName));
		}

		CraftCmdMod.LOGGER.info("[craftcmd] furnace get {} x{} from {}", current.getItem(), taken, slot.id());
		return taken;
	}

	private static AbstractFurnaceMenu furnaceMenu(OpCommandSource source) {
		Minecraft client = Minecraft.getInstance();

		if (client.player == null || client.level == null || client.gameMode == null) {
			source.sendError(Component.translatable("craftcmd.error.not_ready"));
			return null;
		}

		if (!(client.player.containerMenu instanceof AbstractFurnaceMenu menu)) {
			source.sendError(Component.translatable("craftcmd.furnace.error.not_open"));
			return null;
		}

		return menu;
	}
}
