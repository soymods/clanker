package io.github.soymods.clanker.server.skills;

import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import io.github.soymods.clanker.server.perception.Perception;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Walk to a player once, then stop. */
public class ComeToTask implements Task {
	private static final int TIMEOUT_TICKS = 20 * 90;

	private final UUID targetId;
	private final String targetName;
	private int ticks;

	public ComeToTask(ServerPlayer target) {
		this.targetId = target.getUUID();
		this.targetName = target.getName().getString();
	}

	@Override
	public String name() {
		return "come_to";
	}

	@Override
	public String describe(ClankerBot bot, ClankerPlayer player) {
		ServerPlayer target = player.level().getServer().getPlayerList().getPlayer(targetId);
		return target == null ? "walking to " + targetName : "walking to " + targetName + " (" + Perception.blocks(player.distanceTo(target)) + " away)";
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
		bot.movement().moveTo(target.position(), 2.0, true);
		bot.movement().lookAt(target);
		if (bot.movement().hasArrived()) {
			return Result.success("reached " + targetName);
		}
		if (bot.movement().isStuck()) {
			return Result.failed("stuck " + Perception.blocks(player.distanceTo(target)) + " away from " + targetName);
		}
		if (++ticks > TIMEOUT_TICKS) {
			return Result.failed("gave up after 90 seconds, " + Perception.blocks(player.distanceTo(target)) + " away from " + targetName);
		}
		return Result.RUNNING;
	}
}
