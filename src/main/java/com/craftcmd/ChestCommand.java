/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Builds the {@code /cmdop chest put|get <item id> [amount]} subcommand for the chest screen that is
 * currently open.
 *
 * <p>Any {@link ChestMenu} works, which covers chests, trapped chests, double chests and barrels. Items are
 * only ever matched as identical stacks (same item, same components) and both directions are validated up
 * front, so a failing command moves nothing.
 */
public final class ChestCommand {
	/** Upper bound for the amount argument; 100 stacks of 64. */
	public static final int MAX_AMOUNT = 6400;

	private ChestCommand() {
	}

	/** @return the {@code chest} node, to be attached below {@code /cmdop} */
	public static LiteralArgumentBuilder<FabricClientCommandSource> command() {
		return ClientCommands.literal("chest")
				.then(ClientCommands.literal("put")
						.then(ClientCommands.argument("item", ItemIdArgumentType.INSTANCE)
								.executes(context -> put(context, 1))
								.then(ClientCommands
										.argument("amount", IntegerArgumentType.integer(1, MAX_AMOUNT))
										.executes(context -> put(context,
												IntegerArgumentType.getInteger(context, "amount"))))))
				.then(ClientCommands.literal("get")
						.then(ClientCommands.argument("item", ItemIdArgumentType.INSTANCE)
								.executes(context -> get(context, 1))
								.then(ClientCommands
										.argument("amount", IntegerArgumentType.integer(1, MAX_AMOUNT))
										.executes(context -> get(context,
												IntegerArgumentType.getInteger(context, "amount"))))));
	}

	// ------------------------------------------------------------------
	// /cmdop chest put <item> [amount]
	// ------------------------------------------------------------------

	private static int put(CommandContext<FabricClientCommandSource> context, int amount) {
		FabricClientCommandSource source = context.getSource();
		ChestMenu menu = chestMenu(source);

		if (menu == null) {
			return 0;
		}

		if (!menu.getCarried().isEmpty()) {
			source.sendError(Component.translatable("craftcmd.error.cursor"));
			return 0;
		}

		Identifier itemId = context.getArgument("item", Identifier.class);
		Item item = BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);

		if (item == null) {
			source.sendError(Component.translatable("craftcmd.error.unknown_item", itemId.toString()));
			return 0;
		}

		Minecraft client = source.getClient();
		Inventory inventory = client.player.getInventory();
		int[] slots = MenuSlots.playerSlots(menu, inventory);
		int chestSlots = menu.getRowCount() * 9;
		ItemStack reference = MenuSlots.firstMatching(inventory, item);

		if (reference.isEmpty()) {
			source.sendError(Component.translatable("craftcmd.chest.error.not_carried",
					new ItemStack(item).getHoverName()));
			return 0;
		}

		int available = MenuSlots.countExact(inventory, reference);

		if (available < amount) {
			source.sendError(Component.translatable("craftcmd.chest.error.not_enough_carried",
					reference.getHoverName(), available, amount));
			return 0;
		}

		int room = 0;

		for (int i = 0; i < chestSlots; i++) {
			ItemStack stack = menu.getSlot(i).getItem();

			if (stack.isEmpty()) {
				room += reference.getMaxStackSize();
			} else if (ItemStack.isSameItemSameComponents(stack, reference)) {
				room += Math.max(0, stack.getMaxStackSize() - stack.getCount());
			}
		}

		if (room < amount) {
			source.sendError(Component.translatable("craftcmd.chest.error.full", reference.getHoverName(), amount,
					room));
			return 0;
		}

		int remaining = amount;

		for (int i = 0; i < chestSlots && remaining > 0; i++) {
			ItemStack stack = menu.getSlot(i).getItem();
			int slotRoom;

			if (stack.isEmpty()) {
				slotRoom = reference.getMaxStackSize();
			} else if (ItemStack.isSameItemSameComponents(stack, reference)) {
				slotRoom = stack.getMaxStackSize() - stack.getCount();
			} else {
				continue;
			}

			if (slotRoom <= 0) {
				continue;
			}

			remaining -= MenuSlots.moveIntoSlot(client, menu, i, reference, Math.min(slotRoom, remaining), inventory,
					slots);
		}

		int inserted = amount - remaining;

		if (inserted < amount) {
			// The checks above should make this impossible; restore the chest and report.
			restore(client, menu, chestSlots, reference, inserted, inventory, slots);
			source.sendError(Component.translatable("craftcmd.chest.error.full", reference.getHoverName(), amount,
					inserted));
			return 0;
		}

		source.sendFeedback(
				Component.translatable("craftcmd.chest.put.done", reference.getHoverName(), inserted));
		CraftCmdMod.LOGGER.info("[craftcmd] chest put {} x{} ({} slots)", itemId, inserted, chestSlots);
		return inserted;
	}

	// ------------------------------------------------------------------
	// /cmdop chest get <item> [amount]
	// ------------------------------------------------------------------

	private static int get(CommandContext<FabricClientCommandSource> context, int amount) {
		FabricClientCommandSource source = context.getSource();
		ChestMenu menu = chestMenu(source);

		if (menu == null) {
			return 0;
		}

		if (!menu.getCarried().isEmpty()) {
			source.sendError(Component.translatable("craftcmd.error.cursor"));
			return 0;
		}

		Identifier itemId = context.getArgument("item", Identifier.class);
		Item item = BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);

		if (item == null) {
			source.sendError(Component.translatable("craftcmd.error.unknown_item", itemId.toString()));
			return 0;
		}

		Minecraft client = source.getClient();
		Inventory inventory = client.player.getInventory();
		int[] slots = MenuSlots.playerSlots(menu, inventory);
		int chestSlots = menu.getRowCount() * 9;
		ItemStack reference = ItemStack.EMPTY;

		for (int i = 0; i < chestSlots && reference.isEmpty(); i++) {
			ItemStack stack = menu.getSlot(i).getItem();

			if (stack.is(item)) {
				reference = stack.copy();
			}
		}

		if (reference.isEmpty()) {
			source.sendError(Component.translatable("craftcmd.chest.error.empty", new ItemStack(item).getHoverName()));
			return 0;
		}

		int stored = 0;

		for (int i = 0; i < chestSlots; i++) {
			ItemStack stack = menu.getSlot(i).getItem();

			if (ItemStack.isSameItemSameComponents(stack, reference)) {
				stored += stack.getCount();
			}
		}

		if (stored < amount) {
			source.sendError(Component.translatable("craftcmd.chest.error.not_enough_stored",
					reference.getHoverName(), stored, amount));
			return 0;
		}

		int room = MenuSlots.roomFor(inventory, reference);

		if (room < amount) {
			source.sendError(Component.translatable("craftcmd.chest.error.no_inventory_space",
					reference.getHoverName(), amount));
			return 0;
		}

		int remaining = amount;

		for (int i = 0; i < chestSlots && remaining > 0; i++) {
			ItemStack stack = menu.getSlot(i).getItem();

			if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, reference)) {
				continue;
			}

			remaining -= MenuSlots.takeFromSlot(client, menu, i, Math.min(stack.getCount(), remaining), inventory,
					slots);
		}

		int taken = amount - remaining;

		if (taken <= 0) {
			source.sendError(Component.translatable("craftcmd.chest.error.empty", reference.getHoverName()));
			return 0;
		}

		source.sendFeedback(Component.translatable("craftcmd.chest.get.done", reference.getHoverName(), taken));
		CraftCmdMod.LOGGER.info("[craftcmd] chest get {} x{} ({} slots)", itemId, taken, chestSlots);
		return taken;
	}

	/** Moves up to {@code amount} identical stacks from the chest back into the inventory (rollback). */
	private static void restore(Minecraft client, ChestMenu menu, int chestSlots, ItemStack reference, int amount,
			Inventory inventory, int[] slots) {
		int remaining = amount;

		for (int i = 0; i < chestSlots && remaining > 0; i++) {
			ItemStack stack = menu.getSlot(i).getItem();

			if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, reference)) {
				continue;
			}

			remaining -= MenuSlots.takeFromSlot(client, menu, i, Math.min(stack.getCount(), remaining), inventory,
					slots);
		}
	}

	private static ChestMenu chestMenu(FabricClientCommandSource source) {
		Minecraft client = source.getClient();

		if (client.player == null || client.level == null || client.gameMode == null) {
			source.sendError(Component.translatable("craftcmd.error.not_ready"));
			return null;
		}

		if (!(client.player.containerMenu instanceof ChestMenu menu)) {
			source.sendError(Component.translatable("craftcmd.chest.error.not_open"));
			return null;
		}

		return menu;
	}
}
