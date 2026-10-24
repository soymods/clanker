package io.github.soymods.clanker.server.skills;

import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import io.github.soymods.clanker.server.perception.Perception;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/** Places a block from the inventory at a spot, clicking an adjacent face like a player does. */
public class PlaceTask implements Task {
	private static final int TIMEOUT_TICKS = 20 * 45;

	private final String label;
	private final Predicate<ItemStack> matcher;
	private BlockPos target;
	private int ticks;

	/** {@code target} null means a free spot right next to Clanker. */
	public PlaceTask(String label, Predicate<ItemStack> matcher, BlockPos target) {
		this.label = label;
		this.matcher = matcher;
		this.target = target;
	}

	@Override
	public String name() {
		return "place_block";
	}

	@Override
	public String describe(ClankerBot bot, ClankerPlayer player) {
		return "placing " + label + (target == null ? "" : " at " + Perception.coords(target));
	}

	@Override
	public Result tick(ClankerBot bot, ClankerPlayer player) {
		ServerLevel level = player.level();
		if (++ticks > TIMEOUT_TICKS) {
			return Result.failed("couldn't get into position to place it");
		}
		int slot = Matching.find(player.getInventory(), stack -> matcher.test(stack) && stack.getItem() instanceof BlockItem);
		if (slot < 0) {
			return Result.failed("you have no " + label + " that can be placed");
		}
		ItemStack item = player.getInventory().getItem(slot);
		if (target == null) {
			target = spotNextTo(level, player, item);
			if (target == null) {
				return Result.failed("there's no free spot next to you to place it");
			}
		}
		if (!free(level, target, item) && level.getBlockState(target).getBlock() instanceof SnowLayerBlock && free(level, target.above(), item)) {
			// Snow deeper than a single layer can't be built into; like a player, build on top of it.
			target = target.above();
		}
		if (!free(level, target, item)) {
			String above = free(level, target.above(), item) ? "; the spot above it, " + Perception.coords(target.above()) + ", is free" : "";
			return Result.failed("there's already " + Perception.blockName(level.getBlockState(target)) + " at " + Perception.coords(target) + above);
		}
		if (!level.mayInteract(player, target)) {
			return Result.failed("you can't build at " + Perception.coords(target) + " (spawn protection or the world border)");
		}
		Direction face = supportFace(level, target, item);
		if (face == null) {
			return Result.failed("nothing to place it against at " + Perception.coords(target) + " (it needs a neighboring block)");
		}
		BlockPos against = target.relative(face);
		// Reach for the spot itself: something thin in it (snow, grass) would block the view of the face behind.
		Reach.Status status = Reach.approach(bot, player, target);
		if (status == Reach.Status.STUCK) {
			return Result.failed("can't get close enough to " + Perception.coords(target));
		}
		if (status == Reach.Status.MOVING) {
			return Result.RUNNING;
		}
		if (player.getBoundingBox().intersects(new AABB(target))) {
			// Standing in the spot: step out of it, away from its center.
			Vec3 spot = Vec3.atBottomCenterOf(target);
			Vec3 away = player.position().subtract(spot).multiply(1, 0, 1);
			if (away.lengthSqr() < 1.0E-4) {
				away = Vec3.atLowerCornerOf(player.getDirection().getOpposite().getUnitVec3i());
			}
			bot.movement().moveTo(spot.add(away.normalize().scale(1.5)), 0.3, false);
			return Result.RUNNING;
		}
		Matching.hold(player.getInventory(), slot);
		ItemStack stack = player.getMainHandItem();
		Vec3 hitPoint = Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(face.getOpposite().getUnitVec3i()).scale(0.5));
		BlockHitResult hit = new BlockHitResult(hitPoint, face.getOpposite(), against, false);
		player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND, hit);
		player.swing(InteractionHand.MAIN_HAND, true);
		BlockState placed = level.getBlockState(target);
		return free(level, target, item)
				? Result.failed("the block didn't place at " + Perception.coords(target))
				: Result.success("placed " + Perception.blockName(placed) + " at " + Perception.coords(target));
	}

	/**
	 * Whether placing {@code item} would replace what's at {@code pos}, the way the game decides it: air, grass and a
	 * single layer of snow can be built into, deeper snow can't.
	 */
	private static boolean free(ServerLevel level, BlockPos pos, ItemStack item) {
		return level.getBlockState(pos).canBeReplaced(new DirectionalPlaceContext(level, pos, Direction.DOWN, item, Direction.UP));
	}

	/** The direction from {@code pos} to a neighbor to click on; prefers the block below. */
	private static Direction supportFace(ServerLevel level, BlockPos pos, ItemStack item) {
		Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
		for (Direction direction : order) {
			BlockPos neighbor = pos.relative(direction);
			if (!level.getBlockState(neighbor).isAir() && !free(level, neighbor, item)) {
				return direction;
			}
		}
		return null;
	}

	private static BlockPos spotNextTo(ServerLevel level, ClankerPlayer player, ItemStack item) {
		BlockPos feet = player.blockPosition();
		Direction facing = player.getDirection();
		Direction[] order = {facing, facing.getClockWise(), facing.getCounterClockWise(), facing.getOpposite()};
		for (int dy = 0; dy <= 1; dy++) {
			for (Direction direction : order) {
				BlockPos spot = feet.relative(direction).above(dy);
				if (free(level, spot, item) && supportFace(level, spot, item) != null) {
					return spot;
				}
			}
		}
		return null;
	}
}
