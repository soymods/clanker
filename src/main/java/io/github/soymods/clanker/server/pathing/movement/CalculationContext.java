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
// Ported from Baritone (branch 26.2, 9fadf7cf) for Clanker. Changes: package names; built from Clanker's server-side
// BotContext; break/place permission comes from the host's config; spawn protection counts as protected; a water
// bucket anywhere in the inventory counts; no world cache.

package io.github.soymods.clanker.server.pathing.movement;

import io.github.soymods.clanker.server.pathing.BotContext;
import io.github.soymods.clanker.server.pathing.PathingSettings;

import io.github.soymods.clanker.config.ServerConfig;
import io.github.soymods.clanker.server.pathing.precompute.PrecomputedData;
import io.github.soymods.clanker.server.pathing.util.BlockStateInterface;
import io.github.soymods.clanker.server.pathing.util.ToolSet;
import io.github.soymods.clanker.server.pathing.util.BetterWorldBorder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

import static io.github.soymods.clanker.server.pathing.movement.ActionCosts.COST_INF;

/**
 * @author Brady
 * @since 8/7/2018
 */
public class CalculationContext {

    private static final ItemStack STACK_BUCKET_WATER = new ItemStack(Items.WATER_BUCKET);

    public final boolean safeForThreadedUse;
    public final BotContext baritone;
    public final Level world;
    public final BlockStateInterface bsi;
    public final ToolSet toolSet;
    public final boolean hasWaterBucket;
    public final boolean hasThrowaway;
    public final boolean canSprint;
    protected final double placeBlockCost; // protected because you should call the function instead
    public final boolean allowBreak;
    public final List<Block> allowBreakAnyway;
    public final boolean allowParkour;
    public final boolean allowParkourPlace;
    public final boolean allowJumpAtBuildLimit;
    public final boolean allowParkourAscend;
    public final boolean assumeWalkOnWater;
    public boolean allowFallIntoLava;
    public final int frostWalker;
    public final boolean allowDiagonalDescend;
    public final boolean allowDiagonalAscend;
    public final boolean allowDownward;
    public int minFallHeight;
    public int maxFallHeightNoWater;
    public final int maxFallHeightBucket;
    public final double waterWalkSpeed;
    public final double breakBlockAdditionalCost;
    public double backtrackCostFavoringCoefficient;
    public double jumpPenalty;
    public final double walkOnWaterOnePenalty;
    public final boolean allowWalkOnMagmaBlocks;
    public final BetterWorldBorder worldBorder;
    private final BlockPos protectedCenter;
    private final int protectedRadius;

    public final PrecomputedData precomputedData;

    public CalculationContext(BotContext baritone) {
        this(baritone, false);
    }

    public CalculationContext(BotContext baritone, boolean forUseOnAnotherThread) {
        this.precomputedData = new PrecomputedData();
        this.safeForThreadedUse = forUseOnAnotherThread;
        this.baritone = baritone;
        ServerPlayer player = baritone.getPlayerContext().player();
        ServerLevel level = baritone.getPlayerContext().world();
        this.world = level;
        ServerConfig config = ServerConfig.get();
        this.bsi = new BlockStateInterface(baritone.getPlayerContext(), forUseOnAnotherThread);
        this.toolSet = new ToolSet(player);
        this.hasThrowaway = PathingSettings.get().allowPlace.value && config.canPlaceBlocks && baritone.getInventoryBehavior().hasGenericThrowaway();
        this.hasWaterBucket = PathingSettings.get().allowWaterBucketFall.value && player.getInventory().findSlotMatchingItem(STACK_BUCKET_WATER) >= 0 && world.dimension() != Level.NETHER;
        this.canSprint = PathingSettings.get().allowSprint.value && baritone.sprintAllowed && player.getFoodData().getFoodLevel() > 6;
        this.placeBlockCost = PathingSettings.get().blockPlacementPenalty.value;
        this.allowBreak = PathingSettings.get().allowBreak.value && config.canBreakBlocks;
        this.allowBreakAnyway = config.canBreakBlocks ? new ArrayList<>(PathingSettings.get().allowBreakAnyway.value) : List.of();
        this.allowParkour = PathingSettings.get().allowParkour.value;
        this.allowParkourPlace = PathingSettings.get().allowParkourPlace.value;
        this.allowJumpAtBuildLimit = PathingSettings.get().allowJumpAtBuildLimit.value;
        this.allowParkourAscend = PathingSettings.get().allowParkourAscend.value;
        this.assumeWalkOnWater = PathingSettings.get().assumeWalkOnWater.value;
        this.allowFallIntoLava = false; // Super secret internal setting for ElytraBehavior
        // todo: technically there can now be datapack enchants that replace blocks with any other at any range
        int frostWalkerLevel = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantments itemEnchantments = baritone.getPlayerContext()
                .player()
                .getItemBySlot(slot)
                .getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                if (enchant.is(Enchantments.FROST_WALKER)) {
                    frostWalkerLevel = itemEnchantments.getLevel(enchant);
                }
            }
        }
        this.frostWalker = frostWalkerLevel;
        this.allowDiagonalDescend = PathingSettings.get().allowDiagonalDescend.value;
        this.allowDiagonalAscend = PathingSettings.get().allowDiagonalAscend.value;
        this.allowDownward = PathingSettings.get().allowDownward.value;
        this.minFallHeight = 3; // Minimum fall height used by MovementFall
        this.maxFallHeightNoWater = PathingSettings.get().maxFallHeightNoWater.value;
        this.maxFallHeightBucket = PathingSettings.get().maxFallHeightBucket.value;
        float waterSpeedMultiplier = 1.0f;
        OUTER: for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantments itemEnchantments = baritone.getPlayerContext()
                .player()
                .getItemBySlot(slot)
                .getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                List<EnchantmentAttributeEffect> effects = enchant.value()
                    .getEffects(EnchantmentEffectComponents.ATTRIBUTES);
                for (EnchantmentAttributeEffect effect : effects) {
                    if (effect.attribute().is(Attributes.WATER_MOVEMENT_EFFICIENCY.unwrapKey().get())) {
                        waterSpeedMultiplier = effect.amount().calculate(itemEnchantments.getLevel(enchant));
                        break OUTER;
                    }
                }
            }
        }
        this.waterWalkSpeed = ActionCosts.WALK_ONE_IN_WATER_COST * (1 - waterSpeedMultiplier) + ActionCosts.WALK_ONE_BLOCK_COST * waterSpeedMultiplier;
        this.breakBlockAdditionalCost = PathingSettings.get().blockBreakAdditionalPenalty.value;
        this.backtrackCostFavoringCoefficient = PathingSettings.get().backtrackCostFavoringCoefficient.value;
        this.jumpPenalty = PathingSettings.get().jumpPenalty.value;
        this.walkOnWaterOnePenalty = PathingSettings.get().walkOnWaterOnePenalty.value;
        this.allowWalkOnMagmaBlocks = PathingSettings.get().allowWalkOnMagmaBlocks.value;
        // why cache these things here, why not let the movements just get directly from settings?
        // because if some movements are calculated one way and others are calculated another way,
        // then you get a wildly inconsistent path that isn't optimal for either scenario.
        this.worldBorder = new BetterWorldBorder(world.getWorldBorder());
        // Clanker: find the spawn protection square (if it applies to this player) here on the server thread
        BlockPos spawn = level.getRespawnData().pos();
        if (level.getServer().isUnderSpawnProtection(level, spawn, player)) {
            int low = 0;
            int high = 1024;
            while (low < high) {
                int mid = (low + high + 1) / 2;
                if (level.getServer().isUnderSpawnProtection(level, spawn.offset(mid, 0, 0), player)) {
                    low = mid;
                } else {
                    high = mid - 1;
                }
            }
            this.protectedCenter = spawn;
            this.protectedRadius = low;
        } else {
            this.protectedCenter = null;
            this.protectedRadius = -1;
        }
    }

    public final BotContext getBaritone() {
        return baritone;
    }

    public BlockState get(int x, int y, int z) {
        return bsi.get0(x, y, z); // laughs maniacally
    }

    public boolean isLoaded(int x, int z) {
        return bsi.isLoaded(x, z);
    }

    public BlockState get(BlockPos pos) {
        return get(pos.getX(), pos.getY(), pos.getZ());
    }

    public Block getBlock(int x, int y, int z) {
        return get(x, y, z).getBlock();
    }

    public double costOfPlacingAt(int x, int y, int z, BlockState current) {
        if (!hasThrowaway) { // only true if allowPlace is true, see constructor
            return COST_INF;
        }
        if (isPossiblyProtected(x, y, z)) {
            return COST_INF;
        }
        if (!worldBorder.canPlaceAt(x, z)) {
            return COST_INF;
        }
        if (!PathingSettings.get().allowPlaceInFluidsSource.value && current.getFluidState().isSource()) {
            return COST_INF;
        }
        if (!PathingSettings.get().allowPlaceInFluidsFlow.value && !current.getFluidState().isEmpty() && !current.getFluidState().isSource()) {
            return COST_INF;
        }
        return placeBlockCost;
    }

    public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
        if (!allowBreak && !allowBreakAnyway.contains(current.getBlock())) {
            return COST_INF;
        }
        if (isPossiblyProtected(x, y, z)) {
            return COST_INF;
        }
        return 1;
    }

    public double placeBucketCost() {
        return placeBlockCost; // shrug
    }

    public boolean isPossiblyProtected(int x, int y, int z) {
        // TODO more protection logic here; see #220
        return protectedCenter != null && Math.abs(x - protectedCenter.getX()) <= protectedRadius && Math.abs(z - protectedCenter.getZ()) <= protectedRadius;
    }
}
