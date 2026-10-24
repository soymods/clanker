package io.github.soymods.clanker.server.skills;

import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.function.Predicate;

/**
 * Crafts an item from what's in the inventory. Small recipes use the 2x2 grid anywhere; bigger ones walk to a
 * crafting table first.
 */
public class CraftTask implements Task {
	private static final int TIMEOUT_TICKS = 20 * 60;
	private static final int TICKS_PER_CRAFT = 6;

	private final String label;
	private final Predicate<ItemStack> wanted;
	private final int count;
	private int made;
	private int ticks;
	private BlockPos table;

	public CraftTask(String label, Predicate<ItemStack> wanted, int count) {
		this.label = label;
		this.wanted = wanted;
		this.count = count;
	}

	@Override
	public String name() {
		return "craft";
	}

	@Override
	public String describe(ClankerBot bot, ClankerPlayer player) {
		return "crafting " + label + " (" + made + "/" + count + ")";
	}

	@Override
	public Result tick(ClankerBot bot, ClankerPlayer player) {
		if (made >= count) {
			return Result.success("crafted " + made + " " + label);
		}
		if (++ticks > TIMEOUT_TICKS) {
			return finish("ran out of time");
		}
		List<Crafting.Plan> plans = Crafting.plansFor(player.level(), wanted);
		if (plans.isEmpty()) {
			return Result.failed("there's no crafting recipe for " + label);
		}
		Crafting.Plan plan = Crafting.bestPlan(plans, player.getInventory());
		if (Crafting.allocate(plan, player.getInventory()) == null) {
			return finish("not enough materials for " + Matching.itemId(plan.result()) + "; missing " + Crafting.missing(plan, player.getInventory()));
		}
		if (plan.needsTable()) {
			if (table == null || !player.level().getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
				table = Reach.nearest(player.level(), player, Reach.is(Blocks.CRAFTING_TABLE));
				if (table == null) {
					return finish(Matching.itemId(plan.result()) + " needs a crafting table and there isn't one within " + Reach.SEARCH_RADIUS + " blocks");
				}
			}
			Reach.Status status = Reach.approach(bot, player, table);
			if (status == Reach.Status.STUCK) {
				return finish("can't get to the crafting table");
			}
			if (status == Reach.Status.MOVING) {
				return Result.RUNNING;
			}
		}
		if (ticks % TICKS_PER_CRAFT != 0) {
			return Result.RUNNING;
		}
		ItemStack result = Crafting.craftOnce(player.level(), plan, player);
		if (result.isEmpty()) {
			return finish("crafting " + Matching.itemId(plan.result()) + " didn't work");
		}
		player.swing(InteractionHand.MAIN_HAND, true);
		made += result.getCount();
		return Result.RUNNING;
	}

	private Result finish(String why) {
		return made > 0 ? Result.success("crafted " + made + " " + label + ", then stopped: " + why) : Result.failed(why);
	}
}
