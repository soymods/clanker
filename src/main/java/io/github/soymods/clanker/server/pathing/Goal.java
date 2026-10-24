package io.github.soymods.clanker.server.pathing;

import io.github.soymods.clanker.server.pathing.movement.ActionCosts;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Where movement is trying to get. {@code slack} widens the check so a moving target doesn't flip arrival on and off.
 * The block-coordinate methods are what Baritone's search uses; they only do arithmetic, so they're safe to call from
 * the search thread.
 */
public interface Goal {
	double EYE_HEIGHT = 1.62;
	double SQRT_2 = Math.sqrt(2);

	boolean reached(Vec3 feet, double slack);

	/** Point to head for, also used for the search heuristic. */
	Vec3 center();

	/** How far from {@link #center()} the goal can be satisfied; keeps the heuristic honest. */
	double radius();

	/**
	 * True if standing somewhere in this block satisfies the goal: the search works in whole blocks, and the last
	 * fraction of a block is walked directly.
	 */
	default boolean isInGoal(int x, int y, int z) {
		Vec3 center = center();
		return reached(new Vec3(Math.clamp(center.x, x + 0.3, x + 0.7), y, Math.clamp(center.z, z + 0.3, z + 0.7)), 0);
	}

	default boolean isInGoal(BlockPos pos) {
		return isInGoal(pos.getX(), pos.getY(), pos.getZ());
	}

	/** Estimated cost in ticks from this block to the goal, in the units of Baritone's GoalBlock. */
	default double heuristic(int x, int y, int z) {
		Vec3 center = center();
		double dx = center.x - (x + 0.5);
		double dz = center.z - (z + 0.5);
		double flat = Math.sqrt(dx * dx + dz * dz);
		if (flat > 0) {
			double scale = Math.max(0, flat - radius()) / flat;
			dx *= scale;
			dz *= scale;
		}
		return flatCost(dx, dz) + verticalCost((int) Math.floor(center.y), y);
	}

	default double heuristic(BlockPos pos) {
		return heuristic(pos.getX(), pos.getY(), pos.getZ());
	}

	/** Baritone's GoalXZ.calculate. */
	static double flatCost(double xDiff, double zDiff) {
		double x = Math.abs(xDiff);
		double z = Math.abs(zDiff);
		double straight;
		double diagonal;
		if (x < z) {
			straight = z - x;
			diagonal = x;
		} else {
			straight = x - z;
			diagonal = z;
		}
		diagonal *= SQRT_2;
		return (diagonal + straight) * PathingSettings.get().costHeuristic.value;
	}

	/** Baritone's GoalYLevel.calculate. */
	static double verticalCost(int goalY, int currentY) {
		if (currentY > goalY) {
			// need to descend
			return ActionCosts.FALL_N_BLOCKS_COST[2] / 2 * (currentY - goalY);
		}
		if (currentY < goalY) {
			// need to ascend
			return (goalY - currentY) * ActionCosts.JUMP_ONE_BLOCK_COST;
		}
		return 0;
	}

	/** Within {@code distance} blocks horizontally and roughly the same height. */
	static Goal near(Vec3 target, double distance) {
		return new Goal() {
			@Override
			public boolean reached(Vec3 feet, double slack) {
				double dx = target.x - feet.x;
				double dz = target.z - feet.z;
				return Math.sqrt(dx * dx + dz * dz) <= distance + slack && Math.abs(target.y - feet.y) < 2.5 + slack;
			}

			@Override
			public Vec3 center() {
				return target;
			}

			@Override
			public double radius() {
				return distance;
			}

			@Override
			public String toString() {
				return "near " + target + " within " + distance;
			}
		};
	}

	/** Close enough to reach a block with a hand (eye to block center). */
	static Goal reach(BlockPos block, double reach) {
		Vec3 center = Vec3.atCenterOf(block);
		return new Goal() {
			@Override
			public boolean reached(Vec3 feet, double slack) {
				return feet.add(0, EYE_HEIGHT, 0).distanceTo(center) <= reach + slack;
			}

			@Override
			public Vec3 center() {
				return center;
			}

			@Override
			public double radius() {
				return reach;
			}

			@Override
			public String toString() {
				return "within " + reach + " of " + block.toShortString();
			}
		};
	}
}
