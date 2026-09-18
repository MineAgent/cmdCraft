/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Drives a single {@link CraftPlan} to completion, one step per client tick.
 *
 * <p>The crafting grid is filled with real container clicks ({@code PICKUP}) and the output slot is
 * harvested with {@code QUICK_MOVE}, exactly like a player would do it. The server stays fully
 * authoritative: the only thing the mod relies on is that the client prediction of a click is applied
 * locally, which is what {@code MultiPlayerGameMode#handleContainerInput} does.
 */
public final class CraftJob {
	/** How many ticks we wait for the server to (re)compute the crafting result. */
	private static final int RESULT_WAIT_TICKS = 100;

	private static CraftJob active;

	private final Minecraft client;
	private final CraftPlan plan;
	private final FabricClientCommandSource source;
	private final AbstractCraftingMenu menu;
	private final int containerId;
	private final int resultSlot;
	private final int[] gridSlots;
	private final int[] inventorySlots;
	private int craftsLeft;
	private int resultWait;
	private int deadline;

	private CraftJob(Minecraft client, CraftPlan plan, FabricClientCommandSource source, AbstractCraftingMenu menu) {
		this.client = client;
		this.plan = plan;
		this.source = source;
		this.menu = menu;
		this.containerId = menu.containerId;
		this.resultSlot = menu.slots.indexOf(menu.getResultSlot());

		List<Slot> inputs = menu.getInputGridSlots();
		this.gridSlots = new int[inputs.size()];

		for (int i = 0; i < inputs.size(); i++) {
			this.gridSlots[i] = menu.slots.indexOf(inputs.get(i));
		}

		this.inventorySlots = new int[CraftPlan.INVENTORY_SLOTS];
		Arrays.fill(this.inventorySlots, -1);

		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.slots.get(i);
			int containerSlot = slot.getContainerSlot();

			if (slot.container == client.player.getInventory() && containerSlot >= 0
					&& containerSlot < CraftPlan.INVENTORY_SLOTS) {
				this.inventorySlots[containerSlot] = i;
			}
		}

		this.craftsLeft = plan.crafts();
		this.resultWait = RESULT_WAIT_TICKS;
		this.deadline = 200 + plan.crafts() * 20;
	}

	public static boolean isRunning() {
		return active != null;
	}

	public static void start(Minecraft client, CraftPlan plan, FabricClientCommandSource source) {
		active = new CraftJob(client, plan, source, (AbstractCraftingMenu) client.player.containerMenu);
		CraftCmdMod.LOGGER.info("[craftcmd] start {} x{} ({} crafts, {} ingredients per craft)", plan.itemId(),
				plan.totalResultCount(), plan.crafts(), plan.positions().size());
	}

	public static void tick(Minecraft client) {
		CraftJob job = active;

		if (job == null) {
			return;
		}

		try {
			job.step();
		} catch (Throwable throwable) {
			CraftCmdMod.LOGGER.error("[craftcmd] unexpected error while crafting", throwable);
			job.abort(Component.translatable("craftcmd.error.internal"));
		}
	}

	private void step() {
		LocalPlayer player = this.client.player;

		if (player == null || this.client.level == null || this.client.gameMode == null) {
			this.abort(Component.translatable("craftcmd.error.not_ready"));
			return;
		}

		if (player.containerMenu != this.menu) {
			// The container is gone, nothing can be returned any more.
			fail(Component.translatable("craftcmd.error.menu_changed"));
			return;
		}

		if (--this.deadline <= 0) {
			this.abort(Component.translatable("craftcmd.error.timeout"));
			return;
		}

		if (!this.menu.getCarried().isEmpty()) {
			// Something is sitting on the cursor (usually a leftover of a clicked recipe); put it back
			// instead of giving up, and only abort when there is really nowhere to put it.
			if (!this.depositCarried(player)) {
				this.abort(Component.translatable("craftcmd.error.cursor"));
			}

			return;
		}

		boolean needsIngredients = false;

		for (CraftPlan.Position position : this.plan.positions()) {
			ItemStack stack = this.menu.getSlot(this.gridSlots[position.gridIndex()]).getItem();

			if (stack.isEmpty()) {
				needsIngredients = true;
			} else if (!CraftPlan.accepts(position, stack)) {
				this.abort(Component.translatable("craftcmd.error.grid_not_empty"));
				return;
			}
		}

		if (needsIngredients) {
			this.fillGrid(player);
			return;
		}

		ItemStack result = this.menu.getSlot(this.resultSlot).getItem();

		if (result.isEmpty() || !result.is(this.plan.result().getItem())) {
			if (--this.resultWait <= 0) {
				this.abort(Component.translatable("craftcmd.error.timeout"));
			}

			return;
		}

		this.click(this.resultSlot, ContainerInput.QUICK_MOVE);
		this.craftsLeft--;
		this.resultWait = RESULT_WAIT_TICKS;

		if (this.craftsLeft <= 0) {
			this.finish();
		}
	}

	/**
	 * Puts exactly one craft worth of ingredients into the crafting grid.
	 *
	 * <p>A single {@code QUICK_MOVE} on the output slot crafts as often as the grid allows (vanilla
	 * shift clicking crafts everything it can), so the grid is only ever stocked with one set of
	 * ingredients. That keeps both the local prediction and the server side result count at exactly one
	 * craft per click.
	 */
	private void fillGrid(LocalPlayer player) {
		for (CraftPlan.Position position : this.plan.positions()) {
			int gridSlot = this.gridSlots[position.gridIndex()];

			if (!this.menu.getSlot(gridSlot).getItem().isEmpty()) {
				continue;
			}

			int inventoryIndex = this.findSource(player, position);

			if (inventoryIndex < 0) {
				ItemStack missing = position.candidates().get(0);
				int groupSize = 0;

				for (CraftPlan.Position other : this.plan.positions()) {
					if (CraftPlan.acceptsItem(other, missing.getItem())) {
						groupSize++;
					}
				}

				this.abort(Component.translatable("craftcmd.error.not_enough", missing.getHoverName(),
						this.craftsLeft * groupSize, this.countAvailable(player, position)));
				return;
			}

			int sourceSlot = this.inventorySlots[inventoryIndex];
			this.click(sourceSlot, 0, ContainerInput.PICKUP);
			int carried = this.menu.getCarried().getCount();

			if (carried <= 0) {
				continue;
			}

			if (carried == 1) {
				this.click(gridSlot, 0, ContainerInput.PICKUP);
			} else {
				// Right click drops exactly one item, the rest goes back to where it came from.
				this.click(gridSlot, 1, ContainerInput.PICKUP);
				this.click(sourceSlot, 0, ContainerInput.PICKUP);
			}
		}

		this.resultWait = RESULT_WAIT_TICKS;
	}

	/** Returns the inventory slot (0..35) holding an item usable for {@code position}, or -1. */
	private int findSource(LocalPlayer player, CraftPlan.Position position) {
		Inventory inventory = player.getInventory();
		int bestSlot = -1;
		int bestFlexibility = Integer.MAX_VALUE;

		for (int i = 0; i < CraftPlan.INVENTORY_SLOTS; i++) {
			ItemStack stack = inventory.getItem(i);

			if (stack.isEmpty() || this.inventorySlots[i] < 0 || !CraftPlan.accepts(position, stack)) {
				continue;
			}

			int flexibility = 0;

			for (CraftPlan.Position other : this.plan.positions()) {
				if (CraftPlan.accepts(other, stack)) {
					flexibility++;
				}
			}

			if (flexibility < bestFlexibility) {
				bestFlexibility = flexibility;
				bestSlot = i;
			}
		}

		return bestSlot;
	}

	private int countAvailable(LocalPlayer player, CraftPlan.Position position) {
		Inventory inventory = player.getInventory();
		int count = 0;

		for (int i = 0; i < CraftPlan.INVENTORY_SLOTS; i++) {
			ItemStack stack = inventory.getItem(i);

			if (CraftPlan.accepts(position, stack)) {
				count += stack.getCount();
			}
		}

		return count;
	}

	/** Moves whatever is held on the cursor back into the inventory. Returns true when it is empty. */
	private boolean depositCarried(LocalPlayer player) {
		Inventory inventory = player.getInventory();

		for (int i = 0; i < CraftPlan.INVENTORY_SLOTS && !this.menu.getCarried().isEmpty(); i++) {
			if (this.inventorySlots[i] < 0) {
				continue;
			}

			ItemStack stack = inventory.getItem(i);

			if (!stack.isEmpty() && !ItemStack.isSameItemSameComponents(stack, this.menu.getCarried())) {
				continue;
			}

			if (!stack.isEmpty() && stack.getCount() >= stack.getMaxStackSize()) {
				continue;
			}

			this.click(this.inventorySlots[i], ContainerInput.PICKUP);
		}

		return this.menu.getCarried().isEmpty();
	}

	private void finish() {
		this.returnGridContents();
		this.source.sendFeedback(
				Component.translatable("craftcmd.msg.done", this.plan.result().getHoverName(), this.plan.totalResultCount()));
		CraftCmdMod.LOGGER.info("[craftcmd] done {} x{}", this.plan.itemId(), this.plan.totalResultCount());
		active = null;
	}

	private void abort(Component message) {
		this.returnGridContents();
		this.source.sendError(message);
		CraftCmdMod.LOGGER.warn("[craftcmd] aborted: {}", message.getString());
		active = null;
	}

	private void fail(Component message) {
		this.source.sendError(message);
		CraftCmdMod.LOGGER.warn("[craftcmd] failed: {}", message.getString());
		active = null;
	}

	/** Puts the (unused) ingredients back into the inventory. */
	private void returnGridContents() {
		LocalPlayer player = this.client.player;

		if (player == null || this.client.gameMode == null || player.containerMenu != this.menu) {
			return;
		}

		for (int slot : this.gridSlots) {
			if (!this.menu.getSlot(slot).getItem().isEmpty()) {
				this.click(slot, ContainerInput.QUICK_MOVE);
			}
		}
	}

	private void click(int slot, ContainerInput input) {
		this.click(slot, 0, input);
	}

	private void click(int slot, int button, ContainerInput input) {
		this.client.gameMode.handleContainerInput(this.containerId, slot, button, input, this.client.player);
	}
}
