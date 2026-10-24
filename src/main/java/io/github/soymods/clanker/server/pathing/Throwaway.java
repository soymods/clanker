/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */
// Adapted from Baritone's InventoryBehavior (branch 26.2, 9fadf7cf) for Clanker: only throwaway-block
// selection, over the whole inventory.

package io.github.soymods.clanker.server.pathing;

import io.github.soymods.clanker.server.skills.Matching;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * The part of Baritone's InventoryBehavior that pathing uses: finding junk blocks to pillar and bridge with. Looks
 * through the whole inventory, not just the hotbar, since swapping a stack into the hand is instant server-side.
 */
public final class Throwaway {
	private final BotContext ctx;

	Throwaway(BotContext ctx) {
		this.ctx = ctx;
	}

	public boolean hasGenericThrowaway() {
		return find(Throwaway::isGenericThrowaway) >= 0;
	}

	/** Puts a placeable throwaway block in the main hand (if {@code select}); false if there is none. */
	public boolean selectThrowawayForLocation(boolean select, int x, int y, int z) {
		int slot = find(Throwaway::isGenericThrowaway);
		if (slot < 0) {
			return false;
		}
		if (select) {
			Matching.hold(ctx.player().getInventory(), slot);
		}
		return true;
	}

	private static boolean isGenericThrowaway(ItemStack stack) {
		if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
			return false;
		}
		for (Item item : PathingSettings.get().acceptableThrowawayItems.value) {
			if (stack.is(item)) {
				return true;
			}
		}
		return false;
	}

	private int find(Predicate<ItemStack> desired) {
		Inventory inventory = ctx.player().getInventory();
		if (desired.test(inventory.getSelectedItem())) {
			return inventory.getSelectedSlot();
		}
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			if (desired.test(inventory.getItem(i))) {
				return i;
			}
		}
		return -1;
	}
}
