package io.github.soymods.clanker.server.perception;

import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import io.github.soymods.clanker.server.brain.BrainEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Notices things for Clanker between turns and posts them as neutral events with a weight: what other players nearby
 * are up to (getting hurt, falling, swimming, mining, picking things up, using them up) and what newly comes into view
 * (mobs, ores and other notable blocks, a new biome, water or buildings in the distance).
 * <p>
 * It never decides what Clanker says or whether it reacts; the weights only decide when the model gets to look.
 */
public class Observer {
	private static final double WATCH_RANGE = 48;
	private static final double ENTITY_RANGE = 24;
	private static final long FORGET_SIGHTING_MS = 10 * 60_000;
	private static final long FORGET_ENTITY_MS = 3 * 60_000;
	private static final long ACTIVITY_SETTLE_MS = 3_000;
	private static final long DAMAGE_SETTLE_MS = 800;
	private static final double MIN_DROP = 5;

	private final ClankerBot bot;
	private final Map<UUID, Watch> watches = new HashMap<>();
	/** Novelty keys ("block:iron_ore@1,-2,3", "entity:sheep", "biome:...") and when they were last noticed. */
	private final Map<String, Long> noticed = new HashMap<>();
	private final Map<UUID, Long> seenEntities = new HashMap<>();
	private boolean primed;
	private int ticks;

	/** What's being tracked about one other player. */
	private static final class Watch {
		long seenAt;
		boolean airborne;
		double peakY;
		boolean inWater;
		boolean inLava;
		boolean onFire;
		Boolean underground;
		Map<String, Integer> inventory;
		final Map<String, Integer> broken = new TreeMap<>();
		final Map<String, Integer> change = new TreeMap<>();
		long lastActivity;
		float damage;
		String damageFrom;
		long damageAt;
	}

	public Observer(ClankerBot bot) {
		this.bot = bot;
	}

	/** Every server tick while Clanker is alive. */
	public void tick(ClankerPlayer self) {
		ticks++;
		long now = System.currentTimeMillis();
		for (ServerPlayer player : self.level().players()) {
			if (player == self || player instanceof ClankerPlayer || player.isSpectator() || player.distanceTo(self) > WATCH_RANGE) {
				continue;
			}
			watch(player, now);
		}
		watches.entrySet().removeIf(entry -> now - entry.getValue().seenAt > 30_000);
		if (ticks % 20 == 0) {
			scanEntities(self, now);
		}
		if (ticks % 40 == 10) {
			scanBlocks(self, now);
			scanBiome(self, now);
		}
		if (ticks % 100 == 30) {
			scanLandscape(self, now);
		}
		noticed.values().removeIf(at -> now - at > FORGET_SIGHTING_MS);
		seenEntities.values().removeIf(at -> now - at > FORGET_ENTITY_MS);
		primed = primed || ticks >= 100;
	}

	// ---- Other players ----

	private void watch(ServerPlayer player, long now) {
		Watch w = watches.computeIfAbsent(player.getUUID(), id -> new Watch());
		boolean fresh = w.seenAt == 0;
		w.seenAt = now;
		String name = player.getName().getString();
		if (player.isDeadOrDying()) {
			w.inventory = null;
			w.airborne = false;
			return;
		}

		boolean flying = player.getAbilities().flying || player.isFallFlying() || player.isPassenger();
		boolean inWater = player.isInWater();
		boolean grounded = player.onGround() || inWater || player.isInLava() || player.onClimbable() || flying;
		if (!grounded) {
			if (!w.airborne) {
				w.airborne = true;
				w.peakY = player.getY();
			}
			w.peakY = Math.max(w.peakY, player.getY());
		} else if (w.airborne) {
			w.airborne = false;
			double drop = w.peakY - player.getY();
			if (drop >= MIN_DROP && !flying) {
				String into = inWater ? " into water" : player.isInLava() ? " into lava" : "";
				post(BrainEvent.Type.PLAYER_ACTIVITY, String.format(Locale.ROOT, "%s dropped about %d blocks%s.", name, Math.round(drop), into),
						0.3 + drop / 30);
			}
		}

		if (!fresh) {
			if (inWater && !w.inWater && !player.isInLava()) {
				post(BrainEvent.Type.PLAYER_ACTIVITY, name + " is in the water" + (player.isUnderWater() ? ", under the surface." : "."), 0.2);
			}
			if (player.isInLava() && !w.inLava) {
				post(BrainEvent.Type.PLAYER_ACTIVITY, name + " is in lava.", 1);
			} else if (player.isOnFire() && !w.onFire && !player.isInLava()) {
				post(BrainEvent.Type.PLAYER_ACTIVITY, name + " is on fire.", 0.6);
			}
		}
		w.inWater = inWater;
		w.inLava = player.isInLava();
		w.onFire = player.isOnFire();

		if (ticks % 20 == 0) {
			boolean underground = isUnderground(player);
			if (w.underground != null && underground != w.underground) {
				post(BrainEvent.Type.PLAYER_ACTIVITY, underground
						? name + " went underground (y " + player.getBlockY() + ")."
						: name + " came back up to the surface.", 0.2);
			}
			w.underground = underground;
			trackInventory(w, player, now);
		}

		if (w.damage > 0 && now - w.damageAt > DAMAGE_SETTLE_MS) {
			float health = player.getHealth();
			double weight = health <= 6 ? 1 : Math.min(1, 0.35 + w.damage / 8);
			post(BrainEvent.Type.PLAYER_ACTIVITY, String.format(Locale.ROOT, "%s took %.0f damage from %s (health %.0f/20).",
					name, w.damage, w.damageFrom, health), weight);
			w.damage = 0;
		}
		if (w.lastActivity > 0 && now - w.lastActivity > ACTIVITY_SETTLE_MS) {
			flushActivity(w, name);
		}
	}

	private static boolean isUnderground(ServerPlayer player) {
		BlockPos head = BlockPos.containing(player.getEyePosition());
		int surface = player.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, head.getX(), head.getZ());
		return !player.level().canSeeSky(head) && surface - head.getY() > 6;
	}

	private void trackInventory(Watch w, ServerPlayer player, long now) {
		Map<String, Integer> counts = counts(player.getInventory());
		if (w.inventory != null) {
			Map<String, Integer> all = new TreeMap<>(counts);
			w.inventory.keySet().forEach(item -> all.putIfAbsent(item, 0));
			for (String item : all.keySet()) {
				int delta = counts.getOrDefault(item, 0) - w.inventory.getOrDefault(item, 0);
				if (delta != 0) {
					w.change.merge(item, delta, Integer::sum);
					w.lastActivity = now;
				}
			}
		}
		w.inventory = counts;
	}

	private static Map<String, Integer> counts(Inventory inventory) {
		Map<String, Integer> counts = new HashMap<>();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty()) {
				counts.merge(Perception.itemName(stack), stack.getCount(), Integer::sum);
			}
		}
		return counts;
	}

	/** A player near Clanker broke a block. */
	public void playerBrokeBlock(ServerPlayer player, BlockState state) {
		ClankerPlayer self = bot.player();
		if (self == null || player.level() != self.level() || player.distanceTo(self) > WATCH_RANGE) {
			return;
		}
		Watch w = watches.computeIfAbsent(player.getUUID(), id -> new Watch());
		w.broken.merge(Perception.blockName(state), 1, Integer::sum);
		w.lastActivity = System.currentTimeMillis();
	}

	/** A player near Clanker got hurt. */
	public void playerHurt(ServerPlayer player, String source, float amount) {
		ClankerPlayer self = bot.player();
		if (self == null || player.level() != self.level() || player.distanceTo(self) > WATCH_RANGE) {
			return;
		}
		Watch w = watches.computeIfAbsent(player.getUUID(), id -> new Watch());
		w.damage += amount;
		w.damageFrom = source;
		w.damageAt = System.currentTimeMillis();
	}

	private void flushActivity(Watch w, String name) {
		List<String> parts = new ArrayList<>();
		double weight = 0.15;
		if (!w.broken.isEmpty()) {
			parts.add("broke " + list(w.broken));
			for (String block : w.broken.keySet()) {
				weight += valuable(block);
			}
		}
		Map<String, Integer> gained = new TreeMap<>();
		Map<String, Integer> lost = new TreeMap<>();
		w.change.forEach((item, delta) -> {
			if (delta > 0) {
				gained.put(item, delta);
			} else if (delta < 0) {
				lost.put(item, -delta);
			}
		});
		if (!gained.isEmpty()) {
			parts.add("got " + list(gained));
			for (Map.Entry<String, Integer> entry : gained.entrySet()) {
				weight += valuable(entry.getKey()) + (isGear(entry.getKey()) ? 0.3 : 0);
			}
		}
		if (!lost.isEmpty()) {
			parts.add("used up or got rid of " + list(lost));
			for (int count : lost.values()) {
				weight += count >= 16 ? 0.3 : 0;
			}
		}
		w.broken.clear();
		w.change.clear();
		w.lastActivity = 0;
		if (!parts.isEmpty()) {
			post(BrainEvent.Type.PLAYER_ACTIVITY, name + " " + String.join("; ", parts) + ".", Math.min(1.2, weight));
		}
	}

	private static String list(Map<String, Integer> counts) {
		List<String> items = new ArrayList<>();
		counts.forEach((item, count) -> items.add(count + " " + item));
		if (items.size() > 8) {
			int more = items.size() - 8;
			items.subList(8, items.size()).clear();
			items.add(more + " other kinds");
		}
		return String.join(", ", items);
	}

	/** Extra attention for valuable finds. */
	private static double valuable(String name) {
		if (name.contains("diamond") || name.contains("emerald") || name.contains("netherite") || name.equals("ancient_debris")) {
			return 1;
		}
		if (name.contains("gold") || name.contains("iron") || name.contains("redstone") || name.contains("lapis")) {
			return 0.2;
		}
		if (name.contains("coal") || name.contains("copper")) {
			return 0.05;
		}
		return 0;
	}

	private static boolean isGear(String name) {
		return name.endsWith("_pickaxe") || name.endsWith("_axe") || name.endsWith("_sword") || name.endsWith("_shovel")
				|| name.endsWith("_helmet") || name.endsWith("_chestplate") || name.endsWith("_leggings") || name.endsWith("_boots")
				|| name.equals("bow") || name.equals("shield") || name.equals("crafting_table") || name.equals("furnace");
	}

	// ---- What comes into view ----

	/** Another mob of a type reported this recently is only news if it's close. */
	private static final long HOSTILE_REPEAT_MS = 60_000;
	private static final double HOSTILE_CLOSE = 12;
	private final Map<String, Long> hostileTypes = new HashMap<>();

	private void scanEntities(ClankerPlayer self, long now) {
		List<String> hostile = new ArrayList<>();
		List<String> other = new ArrayList<>();
		double weight = 0;
		for (Entity entity : self.level().getEntities(self, self.getBoundingBox().inflate(ENTITY_RANGE),
				e -> e instanceof LivingEntity && e.isAlive() && !(e instanceof Player))) {
			boolean known = seenEntities.containsKey(entity.getUUID());
			seenEntities.put(entity.getUUID(), now);
			if (known || !primed) {
				continue;
			}
			String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
			String where = Perception.blocks(entity.distanceTo(self)) + " " + Perception.direction(self.position(), entity.position());
			if (entity instanceof Enemy) {
				// A swamp full of slimes is one piece of news, not forty; a new one close by is still worth a mention.
				Long last = hostileTypes.get(type);
				if (last != null && now - last < HOSTILE_REPEAT_MS && entity.distanceTo(self) > HOSTILE_CLOSE) {
					continue;
				}
				hostileTypes.put(type, now);
				hostile.add(Perception.entityName(entity) + " " + where);
				weight = Math.max(weight, switch (type) {
					case "creeper", "warden", "witch", "ravager", "evoker" -> 0.9;
					case "enderman" -> 0.4;
					default -> 0.6;
				});
			} else if (firstInAWhile("entity:" + type, now)) {
				other.add(Perception.entityName(entity) + " " + where);
				weight = Math.max(weight, switch (type) {
					case "villager", "wandering_trader" -> 0.7;
					case "iron_golem", "horse", "wolf", "cat", "fox", "panda", "parrot", "allay", "sniffer", "camel" -> 0.35;
					default -> 0.12;
				});
			}
		}
		List<String> all = new ArrayList<>(hostile);
		all.addAll(other);
		if (!all.isEmpty()) {
			post(BrainEvent.Type.SAW, "Came into view: " + String.join("; ", all.subList(0, Math.min(6, all.size()))) + ".",
					Math.min(1, weight + 0.05 * (all.size() - 1)));
		}
	}

	private void scanBlocks(ClankerPlayer self, long now) {
		ServerLevel level = self.level();
		List<String> seen = new ArrayList<>();
		double weight = 0;
		for (Perception.BlockSighting sighting : Perception.sightings(self)) {
			double value = blockWeight(sighting.name());
			BlockPos pos = sighting.nearestPos();
			if (value <= 0 || !exposed(level, pos)) {
				continue;
			}
			// One notice per kind per stretch of land, so a big ore vein or a base full of chests doesn't repeat itself.
			String key = "block:" + sighting.name() + "@" + (pos.getX() >> 5) + "," + (pos.getY() >> 4) + "," + (pos.getZ() >> 5);
			if (!firstInAWhile(key, now) || !primed) {
				continue;
			}
			seen.add(sighting.name() + " " + Perception.blocks(sighting.nearest()) + " "
					+ Perception.direction(self.position(), Vec3.atCenterOf(pos)) + " at " + Perception.coords(pos));
			weight = Math.max(weight, value);
		}
		if (!seen.isEmpty()) {
			post(BrainEvent.Type.SAW, "You can now see: " + String.join("; ", seen) + ".", Math.min(1, weight + 0.05 * (seen.size() - 1)));
		}
	}

	/** Whether a block is in the open (next to air or liquid), as opposed to hidden inside a wall. */
	private static boolean exposed(ServerLevel level, BlockPos pos) {
		for (Direction direction : Direction.values()) {
			BlockPos next = pos.relative(direction);
			if (level.getBlockState(next).isAir() || !level.getFluidState(next).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private static double blockWeight(String name) {
		if (name.startsWith("diamond") || name.startsWith("deepslate_diamond") || name.contains("emerald_ore") || name.equals("ancient_debris")) {
			return 1;
		}
		if (name.equals("spawner")) {
			return 0.9;
		}
		if (name.equals("bell")) {
			return 0.7;
		}
		if (name.contains("chest") || name.endsWith("portal") || name.endsWith("shulker_box")) {
			return 0.6;
		}
		if (name.equals("lava")) {
			return 0.4;
		}
		if (name.contains("gold_ore") || name.equals("enchanting_table") || name.equals("anvil")) {
			return 0.4;
		}
		if (name.contains("iron_ore") || name.contains("redstone_ore") || name.contains("lapis_ore")) {
			return 0.3;
		}
		if (name.contains("copper_ore") || name.contains("quartz_ore") || name.equals("obsidian") || name.contains("bed")) {
			return 0.15;
		}
		if (name.contains("coal_ore")) {
			return 0.12;
		}
		return 0; // logs, water, crafting tables and furnaces are too common to be news
	}

	private void scanBiome(ClankerPlayer self, long now) {
		String biome = Perception.biome(self.level(), self.blockPosition());
		if (firstInAWhile("biome:" + biome, now) && primed) {
			post(BrainEvent.Type.SAW, "You're in " + biome.replace('_', ' ') + " now.", 0.3);
		}
	}

	private void scanLandscape(ClankerPlayer self, long now) {
		Landscape.View view = Landscape.look(self.level(), self.blockPosition());
		for (Map.Entry<String, String> feature : view.features().entrySet()) {
			if (firstInAWhile("view:" + feature.getKey(), now) && primed) {
				double weight = switch (feature.getKey()) {
					case "lava" -> 0.5;
					case "buildings", "paths" -> 0.6;
					default -> 0.2;
				};
				post(BrainEvent.Type.SAW, Landscape.featureName(feature.getKey()) + " is in view to the " + feature.getValue() + ".", weight);
			}
		}
	}

	/** True if this hasn't been noticed recently, and remembers it as noticed now. */
	private boolean firstInAWhile(String key, long now) {
		Long last = noticed.put(key, now);
		return last == null;
	}

	private void post(BrainEvent.Type type, String text, double weight) {
		bot.brain().post(BrainEvent.of(type, text, weight));
	}
}
