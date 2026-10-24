package io.github.soymods.clanker.server.skills;

import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import io.github.soymods.clanker.server.pathing.Goal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Finds blocks of a kind nearby, walks to them, breaks them at survival speed with the best tool on hand, and
 * picks up what drops.
 */
public class MineTask implements Task {
	public static final int SEARCH_RADIUS = 24;
	private static final int SEARCH_HEIGHT = 12;
	private static final double REACH = 4.5;
	private static final int TIMEOUT_TICKS = 20 * 300;
	private static final int COLLECT_TICKS = 50;
	private static final int MAX_SKIPPED = 12;

	private final String label;
	private final Predicate<BlockState> matcher;
	private final int wanted;
	private final Set<Long> skipped = new HashSet<>();

	private int broken;
	private BlockPos target;
	private float progress;
	private int ticks;
	private int ticksAtTarget;
	private int collectTicks;
	private BlockPos lastBroken;
	private boolean sawNeedsTool;

	public MineTask(String label, Predicate<BlockState> matcher, int wanted) {
		this.label = label;
		this.matcher = matcher;
		this.wanted = wanted;
	}

	@Override
	public String name() {
		return "mine";
	}

	@Override
	public String describe(ClankerBot bot, ClankerPlayer player) {
		return "mining " + label + " (" + broken + "/" + wanted + " broken)";
	}

	@Override
	public Result tick(ClankerBot bot, ClankerPlayer player) {
		ServerLevel level = player.level();
		if (++ticks > TIMEOUT_TICKS) {
			return done("ran out of time");
		}

		if (collectTicks > 0) {
			collectTicks--;
			ItemEntity drop = nearestDrop(level, player);
			if (drop != null) {
				bot.movement().moveTo(drop.position(), 0.4, false);
				return Result.RUNNING;
			}
			collectTicks = 0;
		}
		if (broken >= wanted) {
			return Result.success("broke " + broken + " " + label);
		}

		if (target == null || !matcher.test(level.getBlockState(target))) {
			clearProgress(level, player);
			target = findTarget(level, player);
			ticksAtTarget = 0;
			if (target == null) {
				return done(sawNeedsTool
						? "the " + label + " nearby need a better tool to drop anything"
						: "no reachable " + label + " within " + SEARCH_RADIUS + " blocks");
			}
		}

		BlockState state = level.getBlockState(target);
		if (canReach(level, player, target)) {
			bot.movement().stop();
			bot.movement().lookAt(Vec3.atCenterOf(target));
			holdBestTool(player.getInventory(), state);
			float speed = state.getDestroyProgress(player, level, target);
			if (speed <= 0) {
				skip(level, player);
				return Result.RUNNING;
			}
			progress += speed;
			if (ticks % 4 == 0) {
				player.swing(InteractionHand.MAIN_HAND, true);
			}
			level.destroyBlockProgress(player.getId(), target, Math.min(9, (int) (progress * 10)));
			if (progress >= 1) {
				level.destroyBlockProgress(player.getId(), target, -1);
				progress = 0;
				if (player.gameMode.destroyBlock(target)) {
					broken++;
					lastBroken = target;
					collectTicks = COLLECT_TICKS;
					target = null;
				} else {
					skip(level, player);
				}
			}
			return Result.RUNNING;
		}

		clearProgress(level, player);
		bot.movement().moveTo(Goal.reach(target, REACH - 0.6), true);
		boolean arrivedButBlocked = bot.movement().hasArrived() && ++ticksAtTarget > 30;
		if (bot.movement().isStuck() || arrivedButBlocked) {
			skip(level, player);
			if (skipped.size() > MAX_SKIPPED) {
				return done("couldn't get to any " + label);
			}
		}
		return Result.RUNNING;
	}

	@Override
	public void end(ClankerBot bot, ClankerPlayer player) {
		clearProgress(player.level(), player);
	}

	private Result done(String why) {
		return broken > 0 ? Result.success("broke " + broken + " " + label + ", then stopped: " + why) : Result.failed(why);
	}

	private void skip(ServerLevel level, ClankerPlayer player) {
		if (target != null) {
			skipped.add(target.asLong());
		}
		clearProgress(level, player);
		target = null;
	}

	private void clearProgress(ServerLevel level, ClankerPlayer player) {
		if (target != null && progress > 0) {
			level.destroyBlockProgress(player.getId(), target, -1);
		}
		progress = 0;
	}

	private BlockPos findTarget(ServerLevel level, ClankerPlayer player) {
		BlockPos origin = player.blockPosition();
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dy = -SEARCH_HEIGHT; dy <= SEARCH_HEIGHT; dy++) {
			for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
				for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
					pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					// Prefer blocks near eye level: cheaper to reach than ones far above or below.
					double score = dx * dx + dz * dz + dy * dy * 2.0;
					if (score >= bestScore || skipped.contains(pos.asLong())) {
						continue;
					}
					BlockState state = level.getBlockState(pos);
					// mayInteract covers spawn protection and the world border, same as for a real player
					if (!matcher.test(state) || state.getDestroySpeed(level, pos) < 0 || !exposed(level, pos) || !level.mayInteract(player, pos)) {
						continue;
					}
					if (state.requiresCorrectToolForDrops() && !hasToolFor(player.getInventory(), state)) {
						sawNeedsTool = true;
						continue;
					}
					best = pos.immutable();
					bestScore = score;
				}
			}
		}
		return best;
	}

	private static boolean exposed(ServerLevel level, BlockPos pos) {
		for (Direction direction : Direction.values()) {
			BlockPos side = pos.relative(direction);
			if (level.getBlockState(side).getCollisionShape(level, side).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private static boolean canReach(ServerLevel level, ClankerPlayer player, BlockPos pos) {
		Vec3 eye = player.getEyePosition();
		Vec3 center = Vec3.atCenterOf(pos);
		if (eye.distanceTo(center) > REACH) {
			return false;
		}
		BlockHitResult hit = level.clip(new ClipContext(eye, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
	}

	private ItemEntity nearestDrop(ServerLevel level, ClankerPlayer player) {
		if (lastBroken == null) {
			return null;
		}
		return level.getEntitiesOfClass(ItemEntity.class, new AABB(lastBroken).inflate(6), ItemEntity::isAlive).stream()
				.min(Comparator.comparingDouble(item -> item.distanceToSqr(player)))
				.orElse(null);
	}

	private static boolean hasToolFor(Inventory inventory, BlockState state) {
		for (int i = 0; i < 36; i++) {
			if (inventory.getItem(i).isCorrectToolForDrops(state)) {
				return true;
			}
		}
		return false;
	}

	static void holdBestTool(Inventory inventory, BlockState state) {
		int bestSlot = -1;
		float bestSpeed = inventory.getSelectedItem().getDestroySpeed(state);
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inventory.getItem(i);
			float speed = stack.getDestroySpeed(state);
			if (speed > bestSpeed) {
				bestSpeed = speed;
				bestSlot = i;
			}
		}
		Matching.hold(inventory, bestSlot);
	}
}
