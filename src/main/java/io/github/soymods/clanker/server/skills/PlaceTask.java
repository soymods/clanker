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
		if (target == null) {
			target = spotNextTo(level, player);
			if (target == null) {
				return Result.failed("there's no free spot next to you to place it");
			}
		}
		if (!level.getBlockState(target).canBeReplaced()) {
			return Result.failed("there's already " + Perception.blockName(level.getBlockState(target)) + " at " + Perception.coords(target));
		}
		Direction face = supportFace(level, target);
		if (face == null) {
			return Result.failed("nothing to place it against at " + Perception.coords(target) + " (it needs a neighboring block)");
		}
		BlockPos against = target.relative(face);
		Reach.Status status = Reach.approach(bot, player, against);
		if (status == Reach.Status.STUCK) {
			return Result.failed("can't get close enough to " + Perception.coords(target));
		}
		if (status == Reach.Status.MOVING) {
			return Result.RUNNING;
		}
		if (player.getBoundingBox().intersects(new AABB(target))) {
			bot.movement().moveTo(Vec3.atBottomCenterOf(target).add(face.getStepX() * -2, 0, face.getStepZ() * -2), 0.5, false);
			return Result.RUNNING;
		}
		Matching.hold(player.getInventory(), slot);
		ItemStack stack = player.getMainHandItem();
		Vec3 hitPoint = Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(face.getOpposite().getUnitVec3i()).scale(0.5));
		BlockHitResult hit = new BlockHitResult(hitPoint, face.getOpposite(), against, false);
		player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND, hit);
		player.swing(InteractionHand.MAIN_HAND, true);
		BlockState placed = level.getBlockState(target);
		return placed.isAir() || placed.canBeReplaced()
				? Result.failed("the block didn't place at " + Perception.coords(target))
				: Result.success("placed " + Perception.blockName(placed) + " at " + Perception.coords(target));
	}

	/** The direction from {@code pos} to a solid neighbor to click on; prefers the block below. */
	private static Direction supportFace(ServerLevel level, BlockPos pos) {
		Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
		for (Direction direction : order) {
			BlockState neighbor = level.getBlockState(pos.relative(direction));
			if (!neighbor.isAir() && !neighbor.canBeReplaced()) {
				return direction;
			}
		}
		return null;
	}

	private static BlockPos spotNextTo(ServerLevel level, ClankerPlayer player) {
		BlockPos feet = player.blockPosition();
		Direction facing = player.getDirection();
		Direction[] order = {facing, facing.getClockWise(), facing.getCounterClockWise(), facing.getOpposite()};
		for (Direction direction : order) {
			BlockPos spot = feet.relative(direction);
			if (level.getBlockState(spot).canBeReplaced() && supportFace(level, spot) != null) {
				return spot;
			}
		}
		return null;
	}
}
