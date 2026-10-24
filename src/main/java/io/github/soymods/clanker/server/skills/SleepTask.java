package io.github.soymods.clanker.server.skills;

import com.mojang.datafixers.util.Either;
import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/** Walks to the nearest bed and gets in it. Clanker wakes up with everyone else in the morning. */
public class SleepTask implements Task {
	private static final int TIMEOUT_TICKS = 20 * 60;

	private BlockPos bed;
	private int ticks;

	@Override
	public String name() {
		return "sleep";
	}

	@Override
	public String describe(ClankerBot bot, ClankerPlayer player) {
		return "going to bed";
	}

	@Override
	public Result tick(ClankerBot bot, ClankerPlayer player) {
		if (++ticks > TIMEOUT_TICKS) {
			return Result.failed("couldn't get to a bed in time");
		}
		if (bed == null || !player.level().getBlockState(bed).is(BlockTags.BEDS)) {
			bed = Reach.nearest(player.level(), player, Reach.is(BlockTags.BEDS));
			if (bed == null) {
				return Result.failed("there's no bed within " + Reach.SEARCH_RADIUS + " blocks");
			}
		}
		Reach.Status status = Reach.approach(bot, player, bed);
		if (status == Reach.Status.STUCK) {
			return Result.failed("can't get to the bed");
		}
		if (status == Reach.Status.MOVING) {
			return Result.RUNNING;
		}
		BlockState state = player.level().getBlockState(bed);
		BlockPos head = state.getValue(BedBlock.PART) == BedPart.HEAD ? bed : bed.relative(state.getValue(BedBlock.FACING));
		Either<Player.BedSleepingProblem, Unit> result = player.startSleepInBed(head);
		return result.map(problem -> Result.failed("can't sleep: " + problem.message().getString()),
				ok -> Result.success("you're in bed; you'll wake up in the morning"));
	}
}
