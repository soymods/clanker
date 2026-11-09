package io.github.soymods.clanker.server.perception;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A rough picture of the land around Clanker, direction by direction ("east: trees, then water from ~32"), from the
 * surface heightmaps. It's what lets Clanker talk about the view, not just the blocks next to it. Facts only.
 */
public final class Landscape {
	private static final int[] DISTANCES = {8, 16, 24, 32, 48, 64};
	private static final String[] NAMES = {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};
	private static final int[][] STEPS = {{0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}};
	private static final int HILL = 10;

	/**
	 * {@code text} for the prompt; {@code features} maps a notable kind of terrain ("water", "lava", "buildings",
	 * "paths") to where it's nearest, e.g. "east, about 32 blocks away".
	 */
	public record View(String text, Map<String, String> features) {
	}

	private Landscape() {
	}

	public static View look(ServerLevel level, BlockPos from) {
		int surfaceHere = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, from.getX(), from.getZ());
		if (!level.canSeeSky(from.above()) && surfaceHere - from.getY() > 4) {
			return new View("You're underground, about " + (surfaceHere - from.getY()) + " blocks below the surface.", Map.of());
		}
		Map<String, String> features = new LinkedHashMap<>();
		Map<String, Integer> featureDistance = new LinkedHashMap<>();
		List<String> parts = new ArrayList<>();
		for (int d = 0; d < NAMES.length; d++) {
			List<String> segments = new ArrayList<>();
			String previous = null;
			int highest = Integer.MIN_VALUE;
			int lowest = Integer.MAX_VALUE;
			for (int distance : DISTANCES) {
				// Diagonal steps cover more ground per block; scale them back so "32 blocks" means 32 blocks.
				int scaled = STEPS[d][0] != 0 && STEPS[d][1] != 0 ? (int) Math.round(distance / Math.sqrt(2)) : distance;
				int x = from.getX() + STEPS[d][0] * scaled;
				int z = from.getZ() + STEPS[d][1] * scaled;
				if (!level.isLoaded(new BlockPos(x, from.getY(), z))) {
					break;
				}
				String kind = surface(level, x, z);
				int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - from.getY();
				highest = Math.max(highest, ground);
				lowest = Math.min(lowest, ground);
				if (!kind.equals(previous)) {
					segments.add(previous == null ? kind : kind + " from ~" + distance);
					previous = kind;
				}
				if (!kind.equals("open ground") && !kind.equals("trees") && !kind.equals("rock")
						&& distance < featureDistance.getOrDefault(kind, Integer.MAX_VALUE)) {
					featureDistance.put(kind, distance);
					features.put(kind, NAMES[d] + ", about " + distance + " blocks away");
				}
			}
			if (segments.isEmpty()) {
				continue;
			}
			StringBuilder part = new StringBuilder(NAMES[d]).append(": ")
					.append(String.join(", then ", segments.subList(0, Math.min(3, segments.size()))));
			if (highest >= HILL) {
				part.append(", rising ~").append(highest);
			} else if (lowest <= -HILL) {
				part.append(", dropping ~").append(-lowest);
			}
			parts.add(part.toString());
		}
		String text = parts.isEmpty() ? "" : "Around you: " + String.join("; ", parts) + ".";
		return new View(text, features);
	}

	private static String surface(ServerLevel level, int x, int z) {
		int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
		BlockPos pos = new BlockPos(x, top, z);
		FluidState fluid = level.getFluidState(pos);
		if (fluid.is(FluidTags.WATER)) {
			return "water";
		}
		if (fluid.is(FluidTags.LAVA)) {
			return "lava";
		}
		BlockState state = level.getBlockState(pos);
		if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) {
			return "trees";
		}
		if (state.is(Blocks.DIRT_PATH)) {
			return "paths";
		}
		if (state.is(BlockTags.PLANKS) || state.is(BlockTags.STAIRS) || state.is(BlockTags.SLABS) || state.is(BlockTags.DOORS)
				|| state.is(BlockTags.FENCES) || state.is(BlockTags.BEDS) || state.is(BlockTags.WOOL) || state.is(Blocks.BELL)
				|| state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE) || state.is(Blocks.COBBLESTONE) || state.is(Blocks.TORCH)) {
			return "buildings";
		}
		if (state.is(BlockTags.SAND)) {
			return "sand";
		}
		if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW) || state.is(BlockTags.ICE)) {
			return "snow";
		}
		if (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(Blocks.GRAVEL)) {
			return "rock";
		}
		return "open ground";
	}

	/** "water" -> "Open water", for event text. */
	public static String featureName(String kind) {
		return switch (kind) {
			case "water" -> "Open water";
			case "lava" -> "Lava";
			case "buildings" -> "Something built (planks, stairs, cobblestone or the like)";
			case "paths" -> "A path";
			default -> kind.substring(0, 1).toUpperCase(Locale.ROOT) + kind.substring(1);
		};
	}
}
