package io.github.soymods.clanker.server.skills;

import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.function.Predicate;

/** Walks over dropped items nearby to pick them up. */
public class CollectTask implements Task {
	private static final double RADIUS = 16;
	private static final int TIMEOUT_TICKS = 20 * 60;

	private final String label;
	private final Predicate<ItemStack> matcher;
	private int ticks;
	private int pickedUp;
	private int carriedBefore = -1;

	public CollectTask(String label, Predicate<ItemStack> matcher) {
		this.label = label;
		this.matcher = matcher;
	}

	@Override
	public String name() {
		return "pick_up_items";
	}

	@Override
	public String describe(ClankerBot bot, ClankerPlayer player) {
		return "picking up " + label;
	}

	@Override
	public Result tick(ClankerBot bot, ClankerPlayer player) {
		int carried = Matching.count(player.getInventory(), matcher);
		if (carriedBefore < 0) {
			carriedBefore = carried;
		}
		pickedUp = Math.max(0, carried - carriedBefore);
		ItemEntity item = player.level().getEntitiesOfClass(ItemEntity.class, new AABB(player.blockPosition()).inflate(RADIUS),
						e -> e.isAlive() && matcher.test(e.getItem())).stream()
				.min(Comparator.comparingDouble(e -> e.distanceToSqr(player)))
				.orElse(null);
		if (item == null) {
			return pickedUp > 0 ? Result.success("picked up " + pickedUp + " " + label) : Result.failed("there are no dropped " + label + " nearby");
		}
		if (++ticks > TIMEOUT_TICKS) {
			return Result.success("picked up " + pickedUp + " " + label + " before giving up on the rest");
		}
		if (player.getInventory().getFreeSlot() < 0 && !canStack(player, item.getItem())) {
			return Result.success("inventory is full; picked up " + pickedUp + " " + label);
		}
		bot.movement().moveTo(item.position(), 0.4, true);
		return Result.RUNNING;
	}

	private static boolean canStack(ClankerPlayer player, ItemStack stack) {
		for (int i = 0; i < 36; i++) {
			ItemStack slot = player.getInventory().getItem(i);
			if (ItemStack.isSameItemSameComponents(slot, stack) && slot.getCount() < slot.getMaxStackSize()) {
				return true;
			}
		}
		return false;
	}
}
