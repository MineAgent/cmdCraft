/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

import java.util.List;
import net.minecraft.world.inventory.AbstractFurnaceMenu;

/** The three furnace slots addressable from the {@code /cmdop furnace} command. */
public enum FurnaceSlot {
	RAW(AbstractFurnaceMenu.INGREDIENT_SLOT, "raw"),
	FUEL(AbstractFurnaceMenu.FUEL_SLOT, "fuel"),
	PRODUCT(AbstractFurnaceMenu.RESULT_SLOT, "product");

	/** Slots {@code /cmdop furnace put} accepts. */
	public static final List<FurnaceSlot> PUT_SLOTS = List.of(RAW, FUEL);
	/** Slots {@code /cmdop furnace get} accepts. */
	public static final List<FurnaceSlot> GET_SLOTS = List.of(RAW, FUEL, PRODUCT);

	private final int menuSlot;
	private final String id;

	FurnaceSlot(int menuSlot, String id) {
		this.menuSlot = menuSlot;
		this.id = id;
	}

	public int menuSlot() {
		return this.menuSlot;
	}

	public String id() {
		return this.id;
	}

	/** Translation key of the user facing slot name. */
	public String translationKey() {
		return "craftcmd.furnace.slot." + this.id;
	}
}
