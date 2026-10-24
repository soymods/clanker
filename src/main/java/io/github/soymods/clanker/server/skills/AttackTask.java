package io.github.soymods.clanker.server.skills;

import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import io.github.soymods.clanker.server.perception.Perception;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Chases an entity and hits it whenever the attack cooldown is ready, until it dies or gets away. */
public class AttackTask implements Task {
	private static final int TIMEOUT_TICKS = 20 * 60;
	private static final double MELEE_RANGE = 2.8;
	private static final double GIVE_UP_DISTANCE = 40;

	private final Entity target;
	private final String targetName;
	private int ticks;
	private int hits;

	public AttackTask(Entity target) {
		this.target = target;
		this.targetName = Perception.entityName(target);
	}

	@Override
	public String name() {
		return "attack";
	}

	@Override
	public String describe(ClankerBot bot, ClankerPlayer player) {
		return "fighting " + targetName + " (" + hits + " hits so far)";
	}

	@Override
	public Result tick(ClankerBot bot, ClankerPlayer player) {
		if (!target.isAlive() || (target instanceof LivingEntity living && living.isDeadOrDying())) {
			return Result.success(targetName + " is dead after " + hits + " hits");
		}
		if (target.level() != player.level() || player.distanceTo(target) > GIVE_UP_DISTANCE) {
			return Result.failed(targetName + " got away");
		}
		if (++ticks > TIMEOUT_TICKS) {
			return Result.failed("gave up after a minute; hit " + targetName + " " + hits + " times");
		}
		bot.movement().lookAt(target);
		if (player.distanceTo(target) > MELEE_RANGE || !player.hasLineOfSight(target)) {
			bot.movement().moveTo(target.position(), MELEE_RANGE - 1, true);
			if (bot.movement().isStuck()) {
				return Result.failed("can't reach " + targetName);
			}
			return Result.RUNNING;
		}
		bot.movement().stop();
		holdBestWeapon(player.getInventory());
		if (player.getAttackStrengthScale(0.5f) >= 0.95f) {
			player.attack(target);
			player.swing(InteractionHand.MAIN_HAND, true);
			hits++;
		}
		return Result.RUNNING;
	}

	private static void holdBestWeapon(Inventory inventory) {
		int best = -1;
		int bestRank = rank(inventory.getSelectedItem());
		for (int i = 0; i < 36; i++) {
			int rank = rank(inventory.getItem(i));
			if (rank > bestRank) {
				bestRank = rank;
				best = i;
			}
		}
		Matching.hold(inventory, best);
	}

	/** Swords beat axes beat anything else; better materials beat worse ones. */
	private static int rank(ItemStack stack) {
		if (stack.isEmpty()) {
			return 0;
		}
		String id = Matching.itemId(stack);
		int kind = id.endsWith("_sword") ? 20 : id.endsWith("_axe") ? 10 : id.endsWith("_spear") ? 15 : -1;
		if (kind < 0) {
			return 0;
		}
		String[] tiers = {"wooden", "golden", "stone", "copper", "iron", "diamond", "netherite"};
		for (int t = 0; t < tiers.length; t++) {
			if (id.startsWith(tiers[t])) {
				return kind + t;
			}
		}
		return kind;
	}
}
