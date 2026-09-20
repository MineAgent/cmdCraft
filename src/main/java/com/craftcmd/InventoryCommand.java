/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Registers {@code /inventory <item id> [1-9]}: swaps a stack from the inventory with a hotbar slot.
 *
 * <p>The swap itself is a single vanilla "number key" click ({@link ContainerInput#SWAP} with the hotbar
 * index as the button), so it is one packet and the server sees exactly what a player pressing 1-9 while
 * hovering a slot would do.
 */
public final class InventoryCommand {
	private InventoryCommand() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(
				ClientCommands.literal("inventory")
						.then(ClientCommands.argument("item", ItemIdArgumentType.INSTANCE)
								.executes(context -> swap(context, 1))
								.then(ClientCommands
										.argument("slot", IntegerArgumentType.integer(1, MenuSlots.HOTBAR_SLOTS))
										.executes(context -> swap(context,
												IntegerArgumentType.getInteger(context, "slot"))))));
	}

	private static int swap(CommandContext<FabricClientCommandSource> context, int hotbarSlot) {
		FabricClientCommandSource source = context.getSource();
		Minecraft client = source.getClient();

		if (client.player == null || client.level == null || client.gameMode == null) {
			source.sendError(Component.translatable("craftcmd.error.not_ready"));
			return 0;
		}

		LocalPlayer player = client.player;
		AbstractContainerMenu menu = player.containerMenu;

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

		Inventory inventory = player.getInventory();
		int[] slots = MenuSlots.playerSlots(menu, inventory);
		int target = hotbarSlot - 1;

		if (slots[target] < 0) {
			source.sendError(Component.translatable("craftcmd.inventory.error.no_hotbar"));
			return 0;
		}

		int from = findSource(inventory, item, target);

		if (from < 0) {
			if (inventory.getItem(target).is(item)) {
				source.sendFeedback(Component.translatable("craftcmd.inventory.already",
						inventory.getItem(target).getHoverName(), hotbarSlot));
				return 1;
			}

			source.sendError(Component.translatable("craftcmd.inventory.error.not_found",
					new ItemStack(item).getHoverName()));
			return 0;
		}

		if (slots[from] < 0) {
			source.sendError(Component.translatable("craftcmd.inventory.error.no_hotbar"));
			return 0;
		}

		ItemStack incoming = inventory.getItem(from).copy();
		ItemStack outgoing = inventory.getItem(target).copy();

		client.gameMode.handleContainerInput(menu.containerId, slots[from], target, ContainerInput.SWAP, player);

		if (outgoing.isEmpty()) {
			source.sendFeedback(Component.translatable("craftcmd.inventory.moved", incoming.getHoverName(),
					incoming.getCount(), hotbarSlot));
		} else {
			source.sendFeedback(Component.translatable("craftcmd.inventory.swapped", hotbarSlot,
					incoming.getHoverName(), incoming.getCount(), outgoing.getHoverName(), outgoing.getCount()));
		}

		CraftCmdMod.LOGGER.info("[craftcmd] inventory swap {} x{} into hotbar {} (out {} x{})", itemId,
				incoming.getCount(), hotbarSlot, outgoing.getItem(), outgoing.getCount());
		return 1;
	}

	/**
	 * First matching stack: the 27 main inventory slots first (top left to bottom right), then the rest of
	 * the hotbar. The target slot itself is skipped, it is only reported when nothing else matches.
	 */
	private static int findSource(Inventory inventory, Item item, int target) {
		for (int i = MenuSlots.HOTBAR_SLOTS; i < MenuSlots.INVENTORY_SLOTS; i++) {
			if (i != target && inventory.getItem(i).is(item)) {
				return i;
			}
		}

		for (int i = 0; i < MenuSlots.HOTBAR_SLOTS; i++) {
			if (i != target && inventory.getItem(i).is(item)) {
				return i;
			}
		}

		return -1;
	}
}
