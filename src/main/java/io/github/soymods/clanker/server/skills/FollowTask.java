package io.github.soymods.clanker.server.skills;

import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import io.github.soymods.clanker.server.perception.Perception;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Stay near a player until told otherwise. */
public class FollowTask implements Task {
	private static final int GIVE_UP_TICKS = 20 * 15;

	private final UUID targetId;
	private final String targetName;
	private final double distance;
	private int stuckTicks;

	public FollowTask(ServerPlayer target, double distance) {
		this.targetId = target.getUUID();
		this.targetName = target.getName().getString();
		this.distance = distance;
	}

	public UUID targetId() {
		return targetId;
	}

	@Override
	public String name() {
		return "follow_player";
	}

	@Override
	public String describe(ClankerBot bot, ClankerPlayer player) {
		ServerPlayer target = player.level().getServer().getPlayerList().getPlayer(targetId);
		return target == null ? "following " + targetName : "following " + targetName + " (" + Perception.blocks(player.distanceTo(target)) + " away)";
	}

	@Override
	public Result tick(ClankerBot bot, ClankerPlayer player) {
		ServerPlayer target = player.level().getServer().getPlayerList().getPlayer(targetId);
		if (target == null) {
			return Result.failed(targetName + " is no longer online");
		}
		if (target.level() != player.level()) {
			return Result.failed(targetName + " went to another dimension");
		}
		bot.movement().lookAt(target);
		double current = player.distanceTo(target);
		if (current > distance + 1.5) {
			bot.movement().moveTo(target.position(), distance, true);
		} else if (bot.movement().hasArrived() || current <= distance) {
			bot.movement().stop();
		}

		if (bot.movement().isMoving() && bot.movement().isStuck()) {
			stuckTicks++;
		} else {
			stuckTicks = 0;
		}
		if (stuckTicks > GIVE_UP_TICKS) {
			return Result.failed("can't reach " + targetName + ", stuck " + Perception.blocks(current) + " away");
		}
		return Result.RUNNING;
	}
}
