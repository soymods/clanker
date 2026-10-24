package io.github.soymods.clanker.server.skills;

import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import io.github.soymods.clanker.server.pathing.Goal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/** Getting within arm's reach of a block, the way a player has to before using it. */
final class Reach {
	static final double REACH = 4.5;
	static final int SEARCH_RADIUS = 24;

	enum Status { REACHED, MOVING, STUCK }

	private Reach() {
	}

	static boolean canReach(ServerLevel level, ClankerPlayer player, BlockPos pos) {
		Vec3 eye = player.getEyePosition();
		Vec3 center = Vec3.atCenterOf(pos);
		if (eye.distanceTo(center) > REACH) {
			return false;
		}
		BlockHitResult hit = level.clip(new ClipContext(eye, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
	}

	/** Walks toward {@code pos} until it can be reached; looks at it once there. */
	static Status approach(ClankerBot bot, ClankerPlayer player, BlockPos pos) {
		if (canReach(player.level(), player, pos)) {
			bot.movement().stop();
			bot.movement().lookAt(Vec3.atCenterOf(pos));
			return Status.REACHED;
		}
		bot.movement().moveTo(Goal.reach(pos, REACH - 0.6), true);
		return bot.movement().isStuck() ? Status.STUCK : Status.MOVING;
	}

	/** Nearest block within the search radius that satisfies {@code test}, or null. */
	static BlockPos nearest(ServerLevel level, ClankerPlayer player, Predicate<BlockState> test) {
		BlockPos origin = player.blockPosition();
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dy = -8; dy <= 8; dy++) {
			for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
				for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
					double distance = dx * dx + dy * dy + dz * dz;
					if (distance >= bestDistance) {
						continue;
					}
					pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					if (test.test(level.getBlockState(pos))) {
						best = pos.immutable();
						bestDistance = distance;
					}
				}
			}
		}
		return best;
	}

	static Predicate<BlockState> is(Block block) {
		return state -> state.is(block);
	}

	static Predicate<BlockState> is(TagKey<Block> tag) {
		return state -> state.is(tag);
	}
}
