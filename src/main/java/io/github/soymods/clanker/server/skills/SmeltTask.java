package io.github.soymods.clanker.server.skills;

import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;

import java.util.function.Predicate;

/**
 * Loads a furnace with items and fuel from the inventory, waits nearby, and takes the results.
 */
public class SmeltTask implements Task {
	private static final int SLOT_INPUT = 0;
	private static final int SLOT_FUEL = 1;
	private static final int SLOT_RESULT = 2;
	private static final int COOK_TICKS = 200;

	private final String label;
	private final Predicate<ItemStack> matcher;
	private final int count;
	private BlockPos furnacePos;
	private boolean loaded;
	private int collected;
	private int ticks;
	private int timeout;

	public SmeltTask(String label, Predicate<ItemStack> matcher, int count) {
		this.label = label;
		this.matcher = matcher;
		this.count = count;
		this.timeout = 20 * 60 + count * COOK_TICKS;
	}

	@Override
	public String name() {
		return "smelt";
	}

	@Override
	public int stallTicks() {
		return 0;
	}

	@Override
	public String describe(ClankerBot bot, ClankerPlayer player) {
		return loaded ? "waiting on the furnace (" + collected + "/" + count + " done)" : "taking " + label + " to a furnace";
	}

	@Override
	public Result tick(ClankerBot bot, ClankerPlayer player) {
		ServerLevel level = player.level();
		if (++ticks > timeout) {
			return finish("the furnace took too long");
		}
		if (furnacePos == null) {
			furnacePos = Reach.nearest(level, player, state -> state.getBlock() instanceof net.minecraft.world.level.block.FurnaceBlock);
			if (furnacePos == null) {
				return Result.failed("there's no furnace within " + Reach.SEARCH_RADIUS + " blocks");
			}
		}
		BlockEntity entity = level.getBlockEntity(furnacePos);
		if (!(entity instanceof FurnaceBlockEntity furnace)) {
			return finish("the furnace is gone");
		}
		Reach.Status status = Reach.approach(bot, player, furnacePos);
		if (status == Reach.Status.STUCK) {
			return finish("can't get to the furnace");
		}
		if (status == Reach.Status.MOVING) {
			return Result.RUNNING;
		}

		if (!loaded) {
			String problem = load(level, furnace, player.getInventory());
			if (problem != null) {
				return Result.failed(problem);
			}
			loaded = true;
			return Result.RUNNING;
		}
		ItemStack output = furnace.getItem(SLOT_RESULT);
		if (!output.isEmpty()) {
			collected += output.getCount();
			Crafting.give(player, output.copy());
			furnace.setItem(SLOT_RESULT, ItemStack.EMPTY);
			furnace.awardUsedRecipesAndPopExperience(player);
			furnace.setChanged();
		}
		boolean inputLeft = !furnace.getItem(SLOT_INPUT).isEmpty();
		if (collected >= count || !inputLeft && furnace.getItem(SLOT_RESULT).isEmpty()) {
			return collected > 0 ? Result.success("smelted " + collected + " " + label) : Result.failed("nothing came out of the furnace");
		}
		if (inputLeft && furnace.getItem(SLOT_FUEL).isEmpty() && !isLit(furnace)) {
			return finish("the furnace ran out of fuel");
		}
		return Result.RUNNING;
	}

	private static boolean isLit(AbstractFurnaceBlockEntity furnace) {
		return furnace.getBlockState().getOptionalValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT).orElse(false);
	}

	/** Moves input and enough fuel into the furnace. Returns a problem, or null. */
	private String load(ServerLevel level, AbstractFurnaceBlockEntity furnace, Inventory inventory) {
		ItemStack existing = furnace.getItem(SLOT_INPUT);
		int inputSlot = Matching.find(inventory, stack -> matcher.test(stack) && smeltable(level, stack)
				&& (existing.isEmpty() || ItemStack.isSameItemSameComponents(existing, stack)));
		if (inputSlot < 0) {
			return Matching.count(inventory, matcher) > 0 ? label + " can't be smelted (or the furnace has something else in it)" : "you have no " + label;
		}
		ItemStack source = inventory.getItem(inputSlot);
		int amount = Math.min(Math.min(count, source.getCount()), source.getMaxStackSize() - existing.getCount());
		ItemStack moved = inventory.removeItem(inputSlot, amount);
		if (existing.isEmpty()) {
			furnace.setItem(SLOT_INPUT, moved);
		} else {
			existing.grow(moved.getCount());
		}

		int burnNeeded = amount * COOK_TICKS;
		ItemStack fuelInFurnace = furnace.getItem(SLOT_FUEL);
		int burnHave = fuelInFurnace.isEmpty() ? 0 : level.fuelValues().burnDuration(fuelInFurnace) * fuelInFurnace.getCount();
		while (burnHave < burnNeeded) {
			ItemStack fuelSlot = furnace.getItem(SLOT_FUEL);
			int fuelIndex = Matching.find(inventory, stack -> level.fuelValues().isFuel(stack) && !matcher.test(stack)
					&& (fuelSlot.isEmpty() || ItemStack.isSameItemSameComponents(fuelSlot, stack)));
			if (fuelIndex < 0) {
				break;
			}
			ItemStack fuel = inventory.getItem(fuelIndex);
			int each = level.fuelValues().burnDuration(fuel);
			int take = Math.min(fuel.getCount(), (int) Math.ceil((burnNeeded - burnHave) / (double) each));
			take = Math.min(take, fuel.getMaxStackSize() - fuelSlot.getCount());
			if (take <= 0) {
				break;
			}
			ItemStack movedFuel = inventory.removeItem(fuelIndex, take);
			if (fuelSlot.isEmpty()) {
				furnace.setItem(SLOT_FUEL, movedFuel);
			} else {
				fuelSlot.grow(movedFuel.getCount());
			}
			burnHave += each * take;
		}
		furnace.setChanged();
		if (furnace.getItem(SLOT_FUEL).isEmpty() && !isLit(furnace)) {
			return "you have no fuel for the furnace (the " + label + " are in it now)";
		}
		return null;
	}

	private static boolean smeltable(ServerLevel level, ItemStack stack) {
		return level.getServer().getRecipeManager().getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(stack), level).isPresent();
	}

	private Result finish(String why) {
		return collected > 0 ? Result.success("smelted " + collected + " " + label + ", then stopped: " + why) : Result.failed(why);
	}
}
