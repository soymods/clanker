package io.github.soymods.clanker.server.perception;

import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import io.github.soymods.clanker.server.skills.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the text snapshot of Clanker's surroundings that the model sees each turn. Facts only.
 */
public final class Perception {
	private static final int BLOCK_RADIUS = 16;
	private static final int BLOCK_HEIGHT = 8;
	private static final double ENTITY_RADIUS = 24;
	private static final double PLAYER_RADIUS = 64;
	private static final int MAX_ENTITIES = 12;
	private static final int MAX_BLOCK_KINDS = 14;

	private Perception() {
	}

	public static String snapshot(ClankerBot bot, ClankerPlayer self) {
		ServerLevel level = self.level();
		StringBuilder out = new StringBuilder();

		out.append("You: ").append(bot.name()).append(" at ").append(coords(self.position()))
				.append(", facing ").append(compass(self.getYRot()))
				.append(", in ").append(level.dimension().identifier().getPath())
				.append(" (").append(biome(level, self.blockPosition())).append(")\n");
		out.append("Health ").append(Math.round(self.getHealth())).append("/20, food ")
				.append(self.getFoodData().getFoodLevel()).append("/20, armor ").append(self.getArmorValue());
		if (self.isInWater()) {
			out.append(", in water");
		}
		if (self.isOnFire()) {
			out.append(", on fire");
		}
		out.append('\n');

		long time = level.getOverworldClockTime();
		out.append("Time: day ").append(time / 24000 + 1).append(", ").append(timeOfDay(time))
				.append("; weather ").append(level.isThundering() ? "thunderstorm" : level.isRaining() ? "rain" : "clear").append('\n');

		out.append("Holding: ").append(itemName(self.getMainHandItem())).append('\n');
		out.append("Inventory: ").append(inventory(self.getInventory())).append('\n');

		Task task = bot.tasks().current();
		out.append("Current task: ").append(task == null ? "none" : task.describe(bot, self)).append('\n');

		appendPlayers(out, self);
		appendEntities(out, self);
		appendBlocks(out, self);
		return out.toString().strip();
	}

	private static void appendPlayers(StringBuilder out, ClankerPlayer self) {
		List<String> lines = new ArrayList<>();
		for (ServerPlayer other : self.level().players()) {
			if (other == self || other.isSpectator()) {
				continue;
			}
			double distance = other.distanceTo(self);
			if (distance > PLAYER_RADIUS) {
				continue;
			}
			StringBuilder line = new StringBuilder("- ").append(other.getName().getString()).append(": ")
					.append(blocks(distance)).append(' ').append(direction(self.position(), other.position()))
					.append(", holding ").append(itemName(other.getMainHandItem()))
					.append(", health ").append(Math.round(other.getHealth())).append("/20");
			String lookingAt = lookingAt(other, self);
			if (lookingAt != null) {
				line.append(", looking at ").append(lookingAt);
			}
			lines.add(line.toString());
		}
		out.append("Players nearby:").append(lines.isEmpty() ? " none\n" : "\n" + String.join("\n", lines) + "\n");
	}

	private static String lookingAt(ServerPlayer player, ClankerPlayer self) {
		HitResult hit = player.pick(6, 0, false);
		Vec3 eye = player.getEyePosition();
		Vec3 view = player.getViewVector(1f);
		for (Entity entity : player.level().getEntities(player, player.getBoundingBox().inflate(6))) {
			if (entity.getBoundingBox().inflate(0.3).clip(eye, eye.add(view.scale(6))).isPresent()
					&& (hit.getType() == HitResult.Type.MISS || entity.distanceTo(player) < hit.getLocation().distanceTo(eye))) {
				return entity == self ? "you" : entityName(entity);
			}
		}
		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			BlockState state = player.level().getBlockState(blockHit.getBlockPos());
			// Barriers, light blocks and structure voids are invisible to players, so Clanker doesn't see them either.
			return state.getRenderShape() == RenderShape.INVISIBLE ? null : blockName(state) + " at " + coords(blockHit.getBlockPos());
		}
		if (hit instanceof EntityHitResult entityHit) {
			return entityName(entityHit.getEntity());
		}
		return null;
	}

	private static void appendEntities(StringBuilder out, ClankerPlayer self) {
		List<Entity> entities = new ArrayList<>(self.level().getEntities(self, self.getBoundingBox().inflate(ENTITY_RADIUS),
				e -> e.isAlive() && !(e instanceof Player)));
		entities.sort(Comparator.comparingDouble(e -> e.distanceToSqr(self)));
		List<String> lines = new ArrayList<>();
		for (Entity entity : entities) {
			if (lines.size() >= MAX_ENTITIES) {
				lines.add("- …and " + (entities.size() - MAX_ENTITIES) + " more");
				break;
			}
			String kind = entity instanceof Enemy ? " (hostile)" : "";
			lines.add("- " + entityName(entity) + kind + ": " + blocks(entity.distanceTo(self)) + " " + direction(self.position(), entity.position()));
		}
		out.append("Entities nearby:").append(lines.isEmpty() ? " none\n" : "\n" + String.join("\n", lines) + "\n");
	}

	private record BlockSighting(int count, double nearest, BlockPos nearestPos) {
	}

	private static void appendBlocks(StringBuilder out, ClankerPlayer self) {
		ServerLevel level = self.level();
		BlockPos origin = self.blockPosition();
		Map<String, BlockSighting> seen = new TreeMap<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -BLOCK_RADIUS; dx <= BLOCK_RADIUS; dx++) {
			for (int dz = -BLOCK_RADIUS; dz <= BLOCK_RADIUS; dz++) {
				for (int dy = -BLOCK_HEIGHT; dy <= BLOCK_HEIGHT; dy++) {
					pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					if (!level.isLoaded(pos)) {
						continue;
					}
					String name = notableName(level.getBlockState(pos), level.getFluidState(pos));
					if (name == null) {
						continue;
					}
					double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
					BlockSighting previous = seen.get(name);
					if (previous == null) {
						seen.put(name, new BlockSighting(1, distance, pos.immutable()));
					} else if (distance < previous.nearest()) {
						seen.put(name, new BlockSighting(previous.count() + 1, distance, pos.immutable()));
					} else {
						seen.put(name, new BlockSighting(previous.count() + 1, previous.nearest(), previous.nearestPos()));
					}
				}
			}
		}
		List<Map.Entry<String, BlockSighting>> entries = new ArrayList<>(seen.entrySet());
		entries.sort(Comparator.comparingDouble(e -> e.getValue().nearest()));
		List<String> lines = new ArrayList<>();
		for (Map.Entry<String, BlockSighting> entry : entries) {
			if (lines.size() >= MAX_BLOCK_KINDS) {
				break;
			}
			BlockSighting s = entry.getValue();
			lines.add("- " + entry.getKey() + " x" + s.count() + ", nearest " + blocks(s.nearest()) + " "
					+ direction(self.position(), Vec3.atCenterOf(s.nearestPos())) + " at " + coords(s.nearestPos()));
		}
		out.append("Notable blocks within ").append(BLOCK_RADIUS).append(":")
				.append(lines.isEmpty() ? " none\n" : "\n" + String.join("\n", lines) + "\n");
	}

	private static String notableName(BlockState state, FluidState fluid) {
		if (!fluid.isEmpty()) {
			if (!fluid.isSource()) {
				return null;
			}
			return BuiltInRegistries.FLUID.getKey(fluid.getType()).getPath();
		}
		if (state.isAir()) {
			return null;
		}
		String name = blockName(state);
		if (state.is(BlockTags.LOGS) || state.is(BlockTags.BEDS) || name.endsWith("_ore") || name.equals("ancient_debris")
				|| name.contains("chest") || name.equals("barrel") || name.endsWith("shulker_box")
				|| name.equals("crafting_table") || name.endsWith("furnace") || name.equals("smoker")
				|| name.equals("spawner") || name.equals("enchanting_table") || name.equals("anvil")
				|| name.endsWith("portal") || name.equals("obsidian")) {
			return name;
		}
		return null;
	}

	private static String inventory(Inventory inventory) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty()) {
				counts.merge(itemName(stack), stack.getCount(), Integer::sum);
			}
		}
		if (counts.isEmpty()) {
			return "empty";
		}
		List<String> parts = new ArrayList<>();
		counts.forEach((name, count) -> parts.add(count + " " + name));
		return String.join(", ", parts);
	}

	public static String itemName(ItemStack stack) {
		return stack.isEmpty() ? "nothing" : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
	}

	public static String blockName(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
	}

	public static String entityName(Entity entity) {
		if (entity instanceof Player player) {
			return player.getName().getString();
		}
		if (entity instanceof ItemEntity item) {
			return item.getItem().getCount() + " " + itemName(item.getItem()) + " (dropped item)";
		}
		String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
		return entity.hasCustomName() ? type + " named " + entity.getCustomName().getString() : type;
	}

	private static String biome(ServerLevel level, BlockPos pos) {
		return level.getBiome(pos).unwrapKey().map(key -> key.identifier().getPath()).orElse("unknown biome");
	}

	public static String timeOfDay(long time) {
		long t = Math.floorMod(time, 24000L);
		int hours = (int) ((t / 1000 + 6) % 24);
		int minutes = (int) (t % 1000 * 60 / 1000);
		String phase;
		if (t < 6000) {
			phase = "morning";
		} else if (t < 12000) {
			phase = "afternoon";
		} else if (t < 13800) {
			phase = "dusk";
		} else if (t < 22200) {
			phase = "night";
		} else {
			phase = "dawn";
		}
		return String.format(Locale.ROOT, "%02d:%02d (%s)", hours, minutes, phase);
	}

	public static String coords(Vec3 pos) {
		return String.format(Locale.ROOT, "(%d, %d, %d)", (int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));
	}

	public static String coords(BlockPos pos) {
		return String.format(Locale.ROOT, "(%d, %d, %d)", pos.getX(), pos.getY(), pos.getZ());
	}

	public static String blocks(double distance) {
		return distance < 10 ? String.format(Locale.ROOT, "%.1f blocks", distance) : Math.round(distance) + " blocks";
	}

	private static String compass(float yaw) {
		String[] names = {"south", "southwest", "west", "northwest", "north", "northeast", "east", "southeast"};
		return names[Math.floorMod(Math.round(yaw / 45f), 8)];
	}

	public static String direction(Vec3 from, Vec3 to) {
		double dx = to.x - from.x;
		double dz = to.z - from.z;
		double dy = to.y - from.y;
		String horizontal = dx * dx + dz * dz < 1 ? "" : compass((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90));
		String vertical = dy > 2 ? "above" : dy < -2 ? "below" : "";
		if (horizontal.isEmpty()) {
			return vertical.isEmpty() ? "right here" : vertical;
		}
		return vertical.isEmpty() ? horizontal : horizontal + ", " + vertical;
	}
}
