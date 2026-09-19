/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Small helpers shared by the container driving commands. */
public final class MenuSlots {
	/** Main inventory + hotbar; armour and offhand slots are intentionally ignored. */
	public static final int INVENTORY_SLOTS = 36;

	private MenuSlots() {
	}

	/** Maps every player inventory index (0..35, hotbar first) to its menu slot index, or -1. */
	public static int[] playerSlots(AbstractContainerMenu menu, Inventory inventory) {
		int[] slots = new int[INVENTORY_SLOTS];
		Arrays.fill(slots, -1);

		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.slots.get(i);
			int containerSlot = slot.getContainerSlot();

			if (slot.container == inventory && containerSlot >= 0 && containerSlot < INVENTORY_SLOTS) {
				slots[containerSlot] = i;
			}
		}

		return slots;
	}

	/** How many items of {@code item} the inventory holds in total. */
	public static int count(Inventory inventory, Item item) {
		int count = 0;

		for (int i = 0; i < INVENTORY_SLOTS; i++) {
			ItemStack stack = inventory.getItem(i);

			if (stack.is(item)) {
				count += stack.getCount();
			}
		}

		return count;
	}

	/** How many items of {@code stack} still fit into the inventory (empty slots count as a stack). */
	public static int roomFor(Inventory inventory, ItemStack stack) {
		int room = 0;

		for (int i = 0; i < INVENTORY_SLOTS; i++) {
			ItemStack existing = inventory.getItem(i);

			if (existing.isEmpty()) {
				room += stack.getMaxStackSize();
			} else if (ItemStack.isSameItemSameComponents(existing, stack)) {
				room += Math.max(0, existing.getMaxStackSize() - existing.getCount());
			}
		}

		return room;
	}

	/** First inventory index holding {@code item}, or -1. */
	public static int findItem(Inventory inventory, Item item) {
		for (int i = 0; i < INVENTORY_SLOTS; i++) {
			if (inventory.getItem(i).is(item)) {
				return i;
			}
		}

		return -1;
	}

	/** Moves whatever is held on the cursor back into the player inventory. */
	public static boolean depositCarried(Minecraft client, AbstractContainerMenu menu, Inventory inventory,
			int[] inventorySlots) {
		for (int i = 0; i < INVENTORY_SLOTS && !menu.getCarried().isEmpty(); i++) {
			if (inventorySlots[i] < 0) {
				continue;
			}

			ItemStack existing = inventory.getItem(i);

			if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, menu.getCarried())) {
				continue;
			}

			if (!existing.isEmpty() && existing.getCount() >= existing.getMaxStackSize()) {
				continue;
			}

			client.gameMode.handleContainerInput(menu.containerId, inventorySlots[i], 0, ContainerInput.PICKUP,
					client.player);
		}

		return menu.getCarried().isEmpty();
	}

	/**
	 * First inventory index that can receive at least {@code amount} items of {@code stack} (an empty
	 * slot or a stack of the same item with enough room), or -1.
	 */
	public static int findDestination(Inventory inventory, ItemStack stack, int amount) {
		for (int i = 0; i < INVENTORY_SLOTS; i++) {
			ItemStack existing = inventory.getItem(i);

			if (existing.isEmpty()) {
				if (stack.getMaxStackSize() >= amount) {
					return i;
				}
			} else if (ItemStack.isSameItemSameComponents(existing, stack)
					&& existing.getMaxStackSize() - existing.getCount() >= amount) {
				return i;
			}
		}

		return -1;
	}
}
