/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */
// Adapted from Baritone's Settings (branch 26.2, 9fadf7cf) for Clanker: only the settings the ported
// pathing reads, with Clanker's choices marked.

package io.github.soymods.clanker.server.pathing;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.stream.Stream;

/**
 * The Baritone settings the ported pathing code reads, with Baritone's defaults unless noted. Break and place
 * permissions come from the host config per search (see {@link io.github.soymods.clanker.server.pathing.movement.CalculationContext}).
 */
public final class PathingSettings {
	private static final PathingSettings INSTANCE = new PathingSettings();

	public static final class Setting<T> {
		public T value;

		Setting(T value) {
			this.value = value;
		}
	}

	public static PathingSettings get() {
		return INSTANCE;
	}

	private PathingSettings() {
	}

	// Permissions (overridden from ServerConfig when a search starts)
	public final Setting<Boolean> allowBreak = new Setting<>(true);
	public final Setting<Boolean> allowPlace = new Setting<>(true);
	public final Setting<List<Block>> allowBreakAnyway = new Setting<>(List.of());

	// Movement options
	public final Setting<Boolean> allowSprint = new Setting<>(true);
	public final Setting<Boolean> allowParkour = new Setting<>(true); // Clanker: on (Baritone: off); players jump gaps
	public final Setting<Boolean> allowParkourPlace = new Setting<>(false);
	public final Setting<Boolean> allowParkourAscend = new Setting<>(true);
	public final Setting<Boolean> allowJumpAtBuildLimit = new Setting<>(false);
	public final Setting<Boolean> allowDiagonalDescend = new Setting<>(false);
	public final Setting<Boolean> allowDiagonalAscend = new Setting<>(false);
	public final Setting<Boolean> allowOvershootDiagonalDescend = new Setting<>(true);
	public final Setting<Boolean> allowDownward = new Setting<>(true);
	public final Setting<Boolean> allowVines = new Setting<>(false);
	public final Setting<Boolean> allowWalkOnBottomSlab = new Setting<>(true);
	public final Setting<Boolean> allowWalkOnMagmaBlocks = new Setting<>(false);
	public final Setting<Boolean> allowWaterBucketFall = new Setting<>(true);
	public final Setting<Boolean> allowPlaceInFluidsSource = new Setting<>(true);
	public final Setting<Boolean> allowPlaceInFluidsFlow = new Setting<>(true);
	public final Setting<Boolean> assumeWalkOnWater = new Setting<>(false);
	public final Setting<Boolean> assumeWalkOnLava = new Setting<>(false);
	public final Setting<Boolean> assumeSafeWalk = new Setting<>(false);
	public final Setting<Boolean> assumeStep = new Setting<>(false);
	public final Setting<Boolean> sprintInWater = new Setting<>(true);
	public final Setting<Boolean> sprintAscends = new Setting<>(true);
	public final Setting<Boolean> overshootTraverse = new Setting<>(true);
	public final Setting<Boolean> walkWhileBreaking = new Setting<>(true);
	public final Setting<Boolean> considerPotionEffects = new Setting<>(true);
	public final Setting<Boolean> strictLiquidCheck = new Setting<>(false);
	public final Setting<Boolean> avoidUpdatingFallingBlocks = new Setting<>(true);
	public final Setting<Boolean> pauseMiningForFallingBlocks = new Setting<>(true);
	public final Setting<Boolean> remainWithExistingLookDirection = new Setting<>(true);
	public final Setting<Integer> maxFallHeightNoWater = new Setting<>(3);
	public final Setting<Integer> maxFallHeightBucket = new Setting<>(20);

	// Costs
	public final Setting<Double> blockPlacementPenalty = new Setting<>(20D);
	public final Setting<Double> blockBreakAdditionalPenalty = new Setting<>(2D);
	public final Setting<Double> jumpPenalty = new Setting<>(2D);
	public final Setting<Double> walkOnWaterOnePenalty = new Setting<>(3D);
	public final Setting<Double> avoidBreakingMultiplier = new Setting<>(.1);
	public final Setting<Double> backtrackCostFavoringCoefficient = new Setting<>(0.5);
	public final Setting<Double> costHeuristic = new Setting<>(3.563);
	public final Setting<Double> maxCostIncrease = new Setting<>(10D);
	public final Setting<Integer> costVerificationLookahead = new Setting<>(5);

	// Blocks. Clanker: containers and beds are never broken on the way somewhere (they hold other players' things),
	// and a few more common junk blocks count as building material.
	public final Setting<List<Block>> blocksToAvoid = new Setting<>(List.of(Blocks.TRIPWIRE));
	public final Setting<List<Block>> blocksToDisallowBreaking = new Setting<>(Stream.concat(Stream.of(
			Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.ENDER_CHEST, Blocks.FURNACE, Blocks.BLAST_FURNACE,
			Blocks.SMOKER, Blocks.HOPPER, Blocks.DISPENSER, Blocks.DROPPER, Blocks.BREWING_STAND, Blocks.SPAWNER),
			Blocks.BED.asList().stream()).toList());
	public final Setting<List<Block>> blocksToAvoidBreaking = new Setting<>(List.of(
			Blocks.CRAFTING_TABLE, Blocks.ENCHANTING_TABLE, Blocks.ANVIL, Blocks.BOOKSHELF, Blocks.TORCH, Blocks.WALL_TORCH));
	public final Setting<List<Item>> acceptableThrowawayItems = new Setting<>(List.of(
			Items.DIRT, Items.COBBLESTONE, Items.NETHERRACK, Items.STONE, Items.COBBLED_DEEPSLATE, Items.ANDESITE,
			Items.DIORITE, Items.GRANITE, Items.TUFF, Items.BLACKSTONE));

	// Tools
	public final Setting<Boolean> autoTool = new Setting<>(true);
	public final Setting<Boolean> assumeExternalAutoTool = new Setting<>(false);
	public final Setting<Boolean> preferSilkTouch = new Setting<>(false);
	public final Setting<Boolean> useSwordToMine = new Setting<>(true);
	public final Setting<Boolean> itemSaver = new Setting<>(false);
	public final Setting<Integer> itemSaverThreshold = new Setting<>(10);

	// Search and execution
	public final Setting<Long> primaryTimeoutMS = new Setting<>(500L);
	public final Setting<Long> failureTimeoutMS = new Setting<>(2000L);
	public final Setting<Long> planAheadPrimaryTimeoutMS = new Setting<>(4000L);
	public final Setting<Long> planAheadFailureTimeoutMS = new Setting<>(5000L);
	public final Setting<Boolean> slowPath = new Setting<>(false);
	public final Setting<Long> slowPathTimeDelayMS = new Setting<>(100L);
	public final Setting<Long> slowPathTimeoutMS = new Setting<>(40000L);
	public final Setting<Boolean> minimumImprovementRepropagation = new Setting<>(true);
	public final Setting<Boolean> cutoffAtLoadBoundary = new Setting<>(false);
	public final Setting<Double> pathCutoffFactor = new Setting<>(0.9);
	public final Setting<Integer> pathCutoffMinimumLength = new Setting<>(30);
	public final Setting<Integer> pathingMaxChunkBorderFetch = new Setting<>(50);
	public final Setting<Float> pathingMapLoadFactor = new Setting<>(0.75f);
	public final Setting<Integer> pathingMapDefaultSize = new Setting<>(1024);
	public final Setting<Boolean> pathThroughCachedOnly = new Setting<>(false);
	public final Setting<Integer> maxPathHistoryLength = new Setting<>(300);
	public final Setting<Integer> pathHistoryCutoffAmount = new Setting<>(50);
	public final Setting<Boolean> splicePath = new Setting<>(true);
	public final Setting<Integer> planningTickLookahead = new Setting<>(150);
	public final Setting<Integer> movementTimeoutTicks = new Setting<>(100);
	public final Setting<Integer> blockBreakSpeed = new Setting<>(6);
	public final Setting<Integer> rightClickSpeed = new Setting<>(4);
}
