package io.github.soymods.clanker.server.skills;

import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import io.github.soymods.clanker.server.perception.Perception;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Walks to the nearest chest or barrel (or a given one) and looks inside, puts items in, or takes items out. */
public class ContainerTask implements Task {
	public enum Mode { LOOK, STORE, TAKE }

	private static final int TIMEOUT_TICKS = 20 * 60;

	private final Mode mode;
	private final String label;
	private final Predicate<ItemStack> matcher;
	private final int count;
	private BlockPos pos;
	private int ticks;

	/** {@code matcher} null means everything (for STORE, everything except gear and food). */
	public ContainerTask(Mode mode, BlockPos pos, String label, Predicate<ItemStack> matcher, int count) {
		this.mode = mode;
		this.pos = pos;
		this.label = label;
		this.matcher = matcher;
		this.count = count;
	}

	@Override
	public String name() {
		return switch (mode) {
			case LOOK -> "open_container";
			case STORE -> "store_items";
			case TAKE -> "take_items";
		};
	}

	@Override
	public String describe(ClankerBot bot, ClankerPlayer player) {
		return "walking to a chest to " + mode.name().toLowerCase() + (label == null ? "" : " " + label);
	}

	@Override
	public Result tick(ClankerBot bot, ClankerPlayer player) {
		ServerLevel level = player.level();
		if (++ticks > TIMEOUT_TICKS) {
			return Result.failed("couldn't get to the container in time");
		}
		if (pos == null) {
			pos = Reach.nearest(level, player, ContainerTask::isStorage);
			if (pos == null) {
				return Result.failed("there's no chest or barrel within " + Reach.SEARCH_RADIUS + " blocks");
			}
		}
		Container container = containerAt(level, pos);
		if (container == null) {
			return Result.failed("there's no chest or barrel at " + Perception.coords(pos));
		}
		Reach.Status status = Reach.approach(bot, player, pos);
		if (status == Reach.Status.STUCK) {
			return Result.failed("can't get to the container at " + Perception.coords(pos));
		}
		if (status == Reach.Status.MOVING) {
			return Result.RUNNING;
		}
		String where = " (container at " + Perception.coords(pos) + ")";
		return switch (mode) {
			case LOOK -> Result.success("it contains: " + contents(container) + where);
			case STORE -> {
				int moved = move(player.getInventory(), 0, 36, container, matcher == null ? ContainerTask::storable : matcher, count);
				container.setChanged();
				yield moved == 0 ? Result.failed("you have nothing to put in" + where)
						: Result.success("put " + moved + " items in; it now contains: " + contents(container) + where);
			}
			case TAKE -> {
				int moved = moveOut(container, player.getInventory(), matcher == null ? stack -> true : matcher, count);
				container.setChanged();
				yield moved == 0 ? Result.failed("there's no " + (label == null ? "items" : label) + " in it; it contains: " + contents(container) + where)
						: Result.success("took " + moved + " " + (label == null ? "items" : label) + where);
			}
		};
	}

	static boolean isStorage(BlockState state) {
		return state.getBlock() instanceof ChestBlock || state.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock;
	}

	static Container containerAt(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock chest) {
			return ChestBlock.getContainer(chest, state, level, pos, true);
		}
		BlockEntity entity = level.getBlockEntity(pos);
		return entity instanceof BarrelBlockEntity || entity instanceof ChestBlockEntity ? (Container) entity : null;
	}

	private static boolean storable(ItemStack stack) {
		String id = Matching.itemId(stack);
		return !EatTask.isFood(stack) && !id.endsWith("_sword") && !id.endsWith("_pickaxe") && !id.endsWith("_axe")
				&& !id.endsWith("_shovel") && !id.endsWith("_hoe") && !id.endsWith("_helmet") && !id.endsWith("_chestplate")
				&& !id.endsWith("_leggings") && !id.endsWith("_boots") && !id.equals("shield") && !id.equals("torch");
	}

	static String contents(Container container) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			if (!stack.isEmpty()) {
				counts.merge(Matching.itemId(stack), stack.getCount(), Integer::sum);
			}
		}
		return counts.isEmpty() ? "nothing" : counts.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "));
	}

	/** Moves matching items from inventory slots [from, to) into the container, merging stacks first. */
	private static int move(Inventory inventory, int from, int to, Container container, Predicate<ItemStack> matcher, int limit) {
		int moved = 0;
		for (int i = from; i < to && moved < limit; i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || !matcher.test(stack)) {
				continue;
			}
			int before = stack.getCount();
			int allowed = Math.min(before, limit - moved);
			ItemStack moving = stack.split(allowed);
			insert(container, moving);
			stack.grow(moving.getCount()); // whatever didn't fit goes back
			moved += allowed - moving.getCount();
			inventory.setChanged();
		}
		return moved;
	}

	private static int moveOut(Container container, Inventory inventory, Predicate<ItemStack> matcher, int limit) {
		int moved = 0;
		for (int i = 0; i < container.getContainerSize() && moved < limit; i++) {
			ItemStack stack = container.getItem(i);
			if (stack.isEmpty() || !matcher.test(stack)) {
				continue;
			}
			ItemStack taking = stack.split(Math.min(stack.getCount(), limit - moved));
			int amount = taking.getCount();
			inventory.add(taking);
			stack.grow(taking.getCount()); // inventory full: put the rest back
			moved += amount - taking.getCount();
			if (!taking.isEmpty()) {
				break;
			}
		}
		return moved;
	}

	private static void insert(Container container, ItemStack moving) {
		for (int i = 0; i < container.getContainerSize() && !moving.isEmpty(); i++) {
			ItemStack slot = container.getItem(i);
			if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, moving)) {
				int room = Math.min(slot.getMaxStackSize(), container.getMaxStackSize()) - slot.getCount();
				int add = Math.min(room, moving.getCount());
				slot.grow(add);
				moving.shrink(add);
			}
		}
		for (int i = 0; i < container.getContainerSize() && !moving.isEmpty(); i++) {
			if (container.getItem(i).isEmpty() && container.canPlaceItem(i, moving)) {
				container.setItem(i, moving.split(moving.getCount()));
			}
		}
	}
}
