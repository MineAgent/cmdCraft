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
	/** Hotbar, inventory indices 0..8. */
	public static final int HOTBAR_SLOTS = 9;
	/** Main inventory, inventory indices 9..35. */
	public static final int MAIN_SLOTS = 27;

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

	/** How many items of {@code item} the inventory holds in total (item id only, components ignored). */
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

	/** How many items the inventory holds that are identical to {@code reference} (same components). */
	public static int countExact(Inventory inventory, ItemStack reference) {
		int count = 0;

		for (int i = 0; i < INVENTORY_SLOTS; i++) {
			ItemStack stack = inventory.getItem(i);

			if (ItemStack.isSameItemSameComponents(stack, reference)) {
				count += stack.getCount();
			}
		}

		return count;
	}

	/** How many items identical to {@code reference} still fit (empty slots count as a full stack). */
	public static int roomFor(Inventory inventory, ItemStack reference) {
		int room = 0;

		for (int i = 0; i < INVENTORY_SLOTS; i++) {
			ItemStack existing = inventory.getItem(i);

			if (existing.isEmpty()) {
				room += reference.getMaxStackSize();
			} else if (ItemStack.isSameItemSameComponents(existing, reference)) {
				room += Math.max(0, existing.getMaxStackSize() - existing.getCount());
			}
		}

		return room;
	}

	/** First inventory index holding {@code item} (item id only), or -1. */
	public static int findItem(Inventory inventory, Item item) {
		for (int i = 0; i < INVENTORY_SLOTS; i++) {
			if (inventory.getItem(i).is(item)) {
				return i;
			}
		}

		return -1;
	}

	/** First inventory index holding a stack identical to {@code reference}, or -1. */
	public static int findExact(Inventory inventory, ItemStack reference) {
		for (int i = 0; i < INVENTORY_SLOTS; i++) {
			if (ItemStack.isSameItemSameComponents(inventory.getItem(i), reference)) {
				return i;
			}
		}

		return -1;
	}

	/**
	 * Copy of the first inventory stack whose item id matches, or {@link ItemStack#EMPTY}. The copy is
	 * used as the template for everything that follows, so stacks with different components are never
	 * mixed into the same slot.
	 */
	public static ItemStack firstMatching(Inventory inventory, Item item) {
		int index = findItem(inventory, item);
		return index < 0 ? ItemStack.EMPTY : inventory.getItem(index).copy();
	}

	/**
	 * First inventory index that can receive at least {@code amount} items identical to
	 * {@code reference} (an empty slot or a stack with enough room), or -1.
	 */
	public static int findDestination(Inventory inventory, ItemStack reference, int amount) {
		for (int i = 0; i < INVENTORY_SLOTS; i++) {
			ItemStack existing = inventory.getItem(i);

			if (existing.isEmpty()) {
				if (reference.getMaxStackSize() >= amount) {
					return i;
				}
			} else if (ItemStack.isSameItemSameComponents(existing, reference)
					&& existing.getMaxStackSize() - existing.getCount() >= amount) {
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

			click(client, menu, inventorySlots[i], 0);
		}

		return menu.getCarried().isEmpty();
	}

	/**
	 * Moves up to {@code amount} items identical to {@code reference} from the inventory into
	 * {@code targetSlot}. The target slot must be empty or hold that very same stack.
	 *
	 * @return how many items were moved
	 */
	public static int moveIntoSlot(Minecraft client, AbstractContainerMenu menu, int targetSlot, ItemStack reference,
			int amount, Inventory inventory, int[] inventorySlots) {
		ItemStack target = menu.getSlot(targetSlot).getItem();

		if (!target.isEmpty() && !ItemStack.isSameItemSameComponents(target, reference)) {
			return 0;
		}

		int remaining = amount;

		while (remaining > 0) {
			int from = findExact(inventory, reference);

			if (from < 0 || inventorySlots[from] < 0) {
				break;
			}

			int fromSlot = inventorySlots[from];
			click(client, menu, fromSlot, 0);
			int carried = menu.getCarried().getCount();

			if (carried <= 0) {
				break;
			}

			int place = Math.min(remaining, carried);

			if (place >= carried) {
				click(client, menu, targetSlot, 0);
			} else {
				for (int i = 0; i < place; i++) {
					click(client, menu, targetSlot, 1);
				}

				click(client, menu, fromSlot, 0);
			}

			remaining -= place;
		}

		return amount - remaining;
	}

	/**
	 * Moves exactly {@code amount} items out of {@code sourceSlot} into the player inventory.
	 *
	 * <p>Taking less than the whole stack means the remainder has to go back into the source slot, which
	 * requires that slot to accept items again (a crafting or furnace output slot does not, and there the
	 * whole stack is taken instead).
	 *
	 * @return how many items were moved
	 */
	public static int takeFromSlot(Minecraft client, AbstractContainerMenu menu, int sourceSlot, int amount,
			Inventory inventory, int[] inventorySlots) {
		ItemStack current = menu.getSlot(sourceSlot).getItem();

		if (current.isEmpty() || amount <= 0) {
			return 0;
		}

		int count = current.getCount();
		ItemStack reference = current.copyWithCount(1);
		boolean canPutBack = menu.getSlot(sourceSlot).mayPlace(reference);
		int take = canPutBack ? Math.min(amount, count) : count;

		click(client, menu, sourceSlot, 0);
		int carried = menu.getCarried().getCount();

		if (carried <= 0) {
			return 0;
		}

		take = Math.min(take, carried);
		int excess = carried - take;

		if (excess == 0) {
			depositCarried(client, menu, inventory, inventorySlots);
		} else if (excess <= take) {
			// Cheaper to put the excess back and then deposit the rest.
			for (int i = 0; i < excess; i++) {
				click(client, menu, sourceSlot, 1);
			}

			depositCarried(client, menu, inventory, inventorySlots);
		} else {
			// Cheaper to drop the wanted items into a single inventory slot, then dump the rest back.
			int destination = findDestination(inventory, reference, take);

			if (destination >= 0 && inventorySlots[destination] >= 0) {
				for (int i = 0; i < take; i++) {
					click(client, menu, inventorySlots[destination], 1);
				}

				click(client, menu, sourceSlot, 0);
			} else {
				for (int i = 0; i < excess; i++) {
					click(client, menu, sourceSlot, 1);
				}

				depositCarried(client, menu, inventory, inventorySlots);
			}
		}

		return take;
	}

	/** One left (button 0) or right (button 1) click with the pickup input. */
	public static void click(Minecraft client, AbstractContainerMenu menu, int slot, int button) {
		client.gameMode.handleContainerInput(menu.containerId, slot, button, ContainerInput.PICKUP, client.player);
	}
}
