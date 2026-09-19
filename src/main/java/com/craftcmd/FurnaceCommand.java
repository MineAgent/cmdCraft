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
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Registers the furnace commands and drives the open furnace through ordinary container clicks.
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

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(
				ClientCommands.literal("furnace")
						.then(ClientCommands.literal("getinfo").executes(FurnaceCommand::getInfo))
						.then(ClientCommands.literal("put")
								.then(ClientCommands.argument("slot", FurnaceSlotArgumentType.put())
										.then(ClientCommands.argument("item", ItemIdArgumentType.INSTANCE)
												.executes(context -> put(context, 1))
												.then(ClientCommands
														.argument("amount", IntegerArgumentType.integer(1, MAX_AMOUNT))
														.executes(context -> put(context,
																IntegerArgumentType.getInteger(context, "amount")))))))
						.then(ClientCommands.literal("get")
								.then(ClientCommands.argument("slot", FurnaceSlotArgumentType.get())
										.executes(context -> get(context, 1))
										.then(ClientCommands
												.argument("amount", IntegerArgumentType.integer(1, MAX_AMOUNT))
												.executes(context -> get(context,
														IntegerArgumentType.getInteger(context, "amount")))))));
	}

	// ------------------------------------------------------------------
	// /furnace getinfo
	// ------------------------------------------------------------------

	private static int getInfo(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		AbstractFurnaceMenu menu = furnaceMenu(source);

		if (menu == null) {
			return 0;
		}

		source.sendFeedback(Component.translatable("craftcmd.furnace.info", describe(menu, FurnaceSlot.RAW),
				describe(menu, FurnaceSlot.FUEL), describe(menu, FurnaceSlot.PRODUCT)));
		return 1;
	}

	private static Component describe(AbstractFurnaceMenu menu, FurnaceSlot slot) {
		ItemStack stack = menu.getSlot(slot.menuSlot()).getItem();

		if (stack.isEmpty()) {
			return Component.translatable("craftcmd.furnace.empty");
		}

		return Component.translatable("craftcmd.furnace.item", stack.getHoverName(), stack.getCount());
	}

	// ------------------------------------------------------------------
	// /furnace put <raw|fuel> <item> [amount]
	// ------------------------------------------------------------------

	private static int put(CommandContext<FabricClientCommandSource> context, int amount) {
		FabricClientCommandSource source = context.getSource();
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

		Minecraft client = source.getClient();
		LocalPlayer player = client.player;
		Inventory inventory = player.getInventory();
		Slot targetSlot = menu.getSlot(slot.menuSlot());
		ItemStack target = targetSlot.getItem();
		ItemStack prototype = new ItemStack(item);
		Component slotName = Component.translatable(slot.translationKey());

		if (!target.isEmpty() && !target.is(item)) {
			source.sendError(Component.translatable("craftcmd.furnace.error.occupied", slotName, target.getHoverName()));
			return 0;
		}

		if (!targetSlot.mayPlace(prototype)) {
			source.sendError(Component.translatable("craftcmd.furnace.error.cannot_place", prototype.getHoverName(),
					slotName));
			return 0;
		}

		int max = targetSlot.getMaxStackSize(prototype);

		if (target.getCount() + amount > max) {
			source.sendError(Component.translatable("craftcmd.furnace.error.stack_limit", slotName, max,
					target.getCount(), amount));
			return 0;
		}

		int available = MenuSlots.count(inventory, item);

		if (available < amount) {
			source.sendError(Component.translatable("craftcmd.furnace.error.not_enough_item",
					prototype.getHoverName(), available, amount));
			return 0;
		}

		int[] slots = MenuSlots.playerSlots(menu, inventory);
		int remaining = amount;

		while (remaining > 0) {
			int inventoryIndex = MenuSlots.findItem(inventory, item);

			if (inventoryIndex < 0 || slots[inventoryIndex] < 0) {
				break;
			}

			int from = slots[inventoryIndex];
			click(client, menu, from, 0);
			int carried = menu.getCarried().getCount();

			if (carried <= 0) {
				break;
			}

			int place = Math.min(remaining, carried);

			if (place >= carried) {
				click(client, menu, slot.menuSlot(), 0);
			} else {
				for (int i = 0; i < place; i++) {
					click(client, menu, slot.menuSlot(), 1);
				}

				click(client, menu, from, 0);
			}

			remaining -= place;
		}

		int inserted = amount - remaining;

		if (inserted <= 0) {
			source.sendError(Component.translatable("craftcmd.furnace.error.not_enough_item",
					prototype.getHoverName(), available, amount));
			return 0;
		}

		source.sendFeedback(Component.translatable("craftcmd.furnace.put.done", prototype.getHoverName(), inserted,
				slotName));
		CraftCmdMod.LOGGER.info("[craftcmd] furnace put {} x{} into {}", itemId, inserted, slot.id());
		return inserted;
	}

	// ------------------------------------------------------------------
	// /furnace get <raw|fuel|product> [amount]
	// ------------------------------------------------------------------

	private static int get(CommandContext<FabricClientCommandSource> context, int amount) {
		FabricClientCommandSource source = context.getSource();
		AbstractFurnaceMenu menu = furnaceMenu(source);

		if (menu == null) {
			return 0;
		}

		if (!menu.getCarried().isEmpty()) {
			source.sendError(Component.translatable("craftcmd.error.cursor"));
			return 0;
		}

		FurnaceSlot slot = context.getArgument("slot", FurnaceSlot.class);
		Minecraft client = source.getClient();
		LocalPlayer player = client.player;
		Inventory inventory = player.getInventory();
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
		int excess = count - take;

		click(client, menu, slot.menuSlot(), 0);

		if (menu.getCarried().getCount() < take) {
			// Should not happen; put everything back and report the failure.
			MenuSlots.depositCarried(client, menu, inventory, slots);
			source.sendError(Component.translatable("craftcmd.furnace.error.empty_slot", slotName));
			return 0;
		}

		if (excess == 0) {
			MenuSlots.depositCarried(client, menu, inventory, slots);
		} else if (take <= excess) {
			// Take items one by one into a single inventory slot, then dump the rest back.
			int destination = MenuSlots.findDestination(inventory, current, take);

			if (destination >= 0 && slots[destination] >= 0) {
				for (int i = 0; i < take; i++) {
					click(client, menu, slots[destination], 1);
				}

				click(client, menu, slot.menuSlot(), 0);
			} else {
				putBackExcess(client, menu, slot, excess);
				MenuSlots.depositCarried(client, menu, inventory, slots);
			}
		} else {
			putBackExcess(client, menu, slot, excess);
			MenuSlots.depositCarried(client, menu, inventory, slots);
		}

		if (wholeStack) {
			source.sendFeedback(
					Component.translatable("craftcmd.furnace.get.whole", current.getHoverName(), take, slotName));
		} else {
			source.sendFeedback(
					Component.translatable("craftcmd.furnace.get.done", current.getHoverName(), take, slotName));
		}

		CraftCmdMod.LOGGER.info("[craftcmd] furnace get {} x{} from {}", current.getItem(), take, slot.id());
		return take;
	}

	private static void putBackExcess(Minecraft client, AbstractFurnaceMenu menu, FurnaceSlot slot, int excess) {
		for (int i = 0; i < excess; i++) {
			click(client, menu, slot.menuSlot(), 1);
		}
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private static AbstractFurnaceMenu furnaceMenu(FabricClientCommandSource source) {
		Minecraft client = source.getClient();

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

	private static void click(Minecraft client, AbstractContainerMenu menu, int slot, int button) {
		client.gameMode.handleContainerInput(menu.containerId, slot, button, ContainerInput.PICKUP, client.player);
	}
}
