package io.github.soymods.clanker.server.skills;

import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import io.github.soymods.clanker.server.perception.Perception;
import net.minecraft.world.phys.Vec3;

public class GoToTask implements Task {
	private static final int TIMEOUT_TICKS = 20 * 90;

	private final Vec3 target;
	private final double arriveDistance;
	private final String name;
	private int ticks;

	public GoToTask(Vec3 target) {
		this(target, 0.8, "go_to");
	}

	public GoToTask(Vec3 target, double arriveDistance, String name) {
		this.target = target;
		this.arriveDistance = arriveDistance;
		this.name = name;
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public String describe(ClankerBot bot, ClankerPlayer player) {
		return "walking to " + Perception.coords(target) + " (" + Perception.blocks(player.position().distanceTo(target)) + " away)";
	}

	@Override
	public Result tick(ClankerBot bot, ClankerPlayer player) {
		bot.movement().moveTo(target, arriveDistance, true);
		if (bot.movement().hasArrived()) {
			return Result.success("arrived at " + Perception.coords(target));
		}
		if (bot.movement().isStuck()) {
			return Result.failed("stuck at " + Perception.coords(player.position()) + ", "
					+ Perception.blocks(player.position().distanceTo(target)) + " from " + Perception.coords(target));
		}
		if (++ticks > TIMEOUT_TICKS) {
			return Result.failed("gave up after 90 seconds, " + Perception.blocks(player.position().distanceTo(target)) + " away");
		}
		return Result.RUNNING;
	}
}
