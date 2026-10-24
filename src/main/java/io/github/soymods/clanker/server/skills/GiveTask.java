package io.github.soymods.clanker.server.skills;

import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import io.github.soymods.clanker.server.perception.Perception;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;
import java.util.function.Predicate;

/** Walks up to a player and tosses them items. */
public class GiveTask implements Task {
	private static final int TIMEOUT_TICKS = 20 * 90;
	private static final double HAND_OFF_DISTANCE = 2.5;

	private final UUID targetId;
	private final String targetName;
	private final String label;
	private final Predicate<ItemStack> matcher;
	private final int count;
	private int ticks;
	private int lookTicks;

	public GiveTask(ServerPlayer target, String label, Predicate<ItemStack> matcher, int count) {
		this.targetId = target.getUUID();
		this.targetName = target.getName().getString();
		this.label = label;
		this.matcher = matcher;
		this.count = count;
	}

	@Override
	public String name() {
		return "give_item";
	}

	@Override
	public String describe(ClankerBot bot, ClankerPlayer player) {
		return "bringing " + count + " " + label + " to " + targetName;
	}

	@Override
	public Result tick(ClankerBot bot, ClankerPlayer player) {
		ServerPlayer target = player.level().getServer().getPlayerList().getPlayer(targetId);
		if (target == null) {
			return Result.failed(targetName + " is no longer online");
		}
		if (target.level() != player.level()) {
			return Result.failed(targetName + " is in another dimension");
		}
		if (++ticks > TIMEOUT_TICKS) {
			return Result.failed("couldn't get to " + targetName + " in time; " + Perception.blocks(player.distanceTo(target)) + " away");
		}
		bot.movement().lookAt(target);
		if (player.distanceTo(target) > HAND_OFF_DISTANCE + 0.5) {
			bot.movement().moveTo(target.position(), HAND_OFF_DISTANCE, true);
			lookTicks = 0;
			if (bot.movement().isStuck()) {
				return Result.failed("can't reach " + targetName + ", stuck " + Perception.blocks(player.distanceTo(target)) + " away");
			}
			return Result.RUNNING;
		}
		bot.movement().stop();
		if (++lookTicks < 4) {
			return Result.RUNNING; // turn toward them first so the toss goes their way
		}
		int given = give(player, matcher, count);
		return given == 0 ? Result.failed("you have no " + label)
				: Result.success("tossed " + given + " " + label + " to " + targetName);
	}

	/** Drops up to {@code count} matching items in the direction the player faces. Returns how many. */
	public static int give(ClankerPlayer player, Predicate<ItemStack> matcher, int count) {
		Inventory inventory = player.getInventory();
		int remaining = count;
		for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || !matcher.test(stack)) {
				continue;
			}
			ItemStack taken = inventory.removeItem(i, Math.min(remaining, stack.getCount()));
			remaining -= taken.getCount();
			player.drop(taken, false, true);
		}
		return count - remaining;
	}
}
