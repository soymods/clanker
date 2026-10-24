package io.github.soymods.clanker.server.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* over walkable block positions (feet positions): walk, step up one block, drop up to three, swim.
 * Interim until the Baritone port (DESIGN.md roadmap 0.2), which also digs and places.
 */
public final class PathFinder {
	private static final int MAX_NODES = 10_000;
	private static final int MAX_DROP = 3;
	private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

	private PathFinder() {
	}

	private static final class Node {
		final BlockPos pos;
		final double cost;
		final double estimate;
		final Node parent;

		Node(BlockPos pos, double cost, double estimate, Node parent) {
			this.pos = pos;
			this.cost = cost;
			this.estimate = estimate;
			this.parent = parent;
		}
	}

	/**
	 * Returns the waypoints after {@code start} leading to the goal, or to the closest reachable spot if the goal
	 * can't be reached within the search budget. Empty when there's nowhere better to go.
	 */
	public static List<BlockPos> find(ServerLevel level, BlockPos start, Goal goal) {
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.cost + a.estimate, b.cost + b.estimate));
		Map<Long, Double> best = new HashMap<>();
		Node first = new Node(start, 0, heuristic(start, goal), null);
		open.add(first);
		best.put(start.asLong(), 0.0);
		Node closest = first;
		int expanded = 0;

		while (!open.isEmpty() && expanded < MAX_NODES) {
			Node node = open.poll();
			if (node.cost > best.getOrDefault(node.pos.asLong(), Double.MAX_VALUE)) {
				continue;
			}
			if (goal.reached(node.pos)) {
				return unwind(node);
			}
			if (node.estimate < closest.estimate) {
				closest = node;
			}
			expanded++;
			for (Move move : neighbors(level, node.pos)) {
				double cost = node.cost + move.cost;
				if (cost < best.getOrDefault(move.pos.asLong(), Double.MAX_VALUE)) {
					best.put(move.pos.asLong(), cost);
					open.add(new Node(move.pos, cost, heuristic(move.pos, goal), node));
				}
			}
		}
		return closest == first ? List.of() : unwind(closest);
	}

	private static double heuristic(BlockPos pos, Goal goal) {
		return Math.max(0, Vec3.atBottomCenterOf(pos).distanceTo(goal.center()) - goal.radius());
	}

	private static List<BlockPos> unwind(Node node) {
		List<BlockPos> path = new ArrayList<>();
		for (Node n = node; n.parent != null; n = n.parent) {
			path.add(n.pos);
		}
		Collections.reverse(path);
		return path;
	}

	private record Move(BlockPos pos, double cost) {
	}

	private static List<Move> neighbors(ServerLevel level, BlockPos pos) {
		List<Move> moves = new ArrayList<>(8);
		for (int[] d : DIRECTIONS) {
			BlockPos side = pos.offset(d[0], 0, d[1]);
			if (!level.isLoaded(side)) {
				continue;
			}
			if (d[0] != 0 && d[1] != 0) {
				// No cutting corners: both sides of a diagonal step must be open for the whole body.
				if (clear(level, pos.offset(d[0], 0, 0)) && clear(level, pos.offset(0, 0, d[1])) && standable(level, side)) {
					moves.add(new Move(side, 1.42));
				}
				continue;
			}
			if (standable(level, side)) {
				moves.add(new Move(side, water(level, side) ? 2.5 : 1));
				continue;
			}
			BlockPos up = side.above();
			if (ground(level, side) && standable(level, up) && passable(level, pos.above(2))) {
				moves.add(new Move(up, 2));
				continue;
			}
			if (clear(level, side)) {
				for (int drop = 1; drop <= MAX_DROP; drop++) {
					BlockPos down = side.below(drop);
					if (standable(level, down)) {
						moves.add(new Move(down, 1 + drop * 0.5));
						break;
					}
					if (!passable(level, down)) {
						break;
					}
				}
			}
		}
		// Swim straight up out of deep water.
		if (water(level, pos) && standable(level, pos.above())) {
			moves.add(new Move(pos.above(), 2));
		}
		return moves;
	}

	/** Feet and head both fit here. */
	private static boolean clear(ServerLevel level, BlockPos pos) {
		return passable(level, pos) && passable(level, pos.above());
	}

	public static boolean standable(ServerLevel level, BlockPos pos) {
		return clear(level, pos) && (ground(level, pos.below()) || water(level, pos));
	}

	public static boolean passable(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return !dangerous(state) && !state.getFluidState().is(FluidTags.LAVA) && state.getCollisionShape(level, pos).isEmpty();
	}

	/** Something solid to stand on, no taller than a full block (fences and walls can't be stepped onto). */
	public static boolean ground(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (dangerous(state)) {
			return false;
		}
		VoxelShape shape = state.getCollisionShape(level, pos);
		return !shape.isEmpty() && shape.max(Direction.Axis.Y) <= 1.0;
	}

	private static boolean water(ServerLevel level, BlockPos pos) {
		return level.getFluidState(pos).is(FluidTags.WATER);
	}

	private static boolean dangerous(BlockState state) {
		return state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.MAGMA_BLOCK)
				|| state.is(Blocks.CACTUS) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
				|| state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.POWDER_SNOW)
				|| state.is(Blocks.COBWEB);
	}
}
