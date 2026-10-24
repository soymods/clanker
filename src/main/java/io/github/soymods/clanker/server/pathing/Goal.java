package io.github.soymods.clanker.server.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Where movement is trying to get. {@code slack} widens the check so a moving target doesn't flip arrival on and off.
 */
public interface Goal {
	double EYE_HEIGHT = 1.62;

	boolean reached(Vec3 feet, double slack);

	/** Point to head for, also used as the search heuristic. */
	Vec3 center();

	/** How far from {@link #center()} the goal can be satisfied; keeps the heuristic honest. */
	double radius();

	default boolean reached(BlockPos node) {
		return reached(Vec3.atBottomCenterOf(node), 0);
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
		};
	}
}
