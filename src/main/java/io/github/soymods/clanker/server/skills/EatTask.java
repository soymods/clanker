package io.github.soymods.clanker.server.skills;

import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

/** Holds a food item and eats it the normal way (using it until the animation finishes). */
public class EatTask implements Task {
	private static final int TIMEOUT_TICKS = 20 * 6;

	private final String label;
	private final int slot;
	private boolean started;
	private int ticks;
	private int foodBefore;

	public EatTask(String label, int slot) {
		this.label = label;
		this.slot = slot;
	}

	public static boolean isFood(ItemStack stack) {
		return stack.has(DataComponents.FOOD);
	}

	@Override
	public String name() {
		return "eat";
	}

	@Override
	public String describe(ClankerBot bot, ClankerPlayer player) {
		return "eating " + label;
	}

	@Override
	public Result tick(ClankerBot bot, ClankerPlayer player) {
		if (!started) {
			Matching.hold(player.getInventory(), slot);
			ItemStack food = player.getMainHandItem();
			foodBefore = player.getFoodData().getFoodLevel();
			InteractionResult result = player.gameMode.useItem(player, player.level(), food, InteractionHand.MAIN_HAND);
			if (!result.consumesAction() && !player.isUsingItem()) {
				return Result.failed("couldn't eat " + label + " (you may not be hungry)");
			}
			started = true;
			return Result.RUNNING;
		}
		if (player.isUsingItem() && ++ticks < TIMEOUT_TICKS) {
			return Result.RUNNING;
		}
		return Result.success("ate " + label + "; food " + foodBefore + " -> " + player.getFoodData().getFoodLevel() + "/20");
	}

	@Override
	public void end(ClankerBot bot, ClankerPlayer player) {
		if (player.isUsingItem()) {
			player.stopUsingItem();
		}
	}
}
