package io.github.soymods.clanker.server.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.soymods.clanker.config.ServerConfig;
import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import io.github.soymods.clanker.server.brain.llm.ToolCall;
import io.github.soymods.clanker.server.brain.llm.ToolSpec;
import io.github.soymods.clanker.server.perception.Perception;
import io.github.soymods.clanker.server.skills.AttackTask;
import io.github.soymods.clanker.server.skills.CollectTask;
import io.github.soymods.clanker.server.skills.ComeToTask;
import io.github.soymods.clanker.server.skills.ContainerTask;
import io.github.soymods.clanker.server.skills.CraftTask;
import io.github.soymods.clanker.server.skills.EatTask;
import io.github.soymods.clanker.server.skills.FollowTask;
import io.github.soymods.clanker.server.skills.GiveTask;
import io.github.soymods.clanker.server.skills.GoToTask;
import io.github.soymods.clanker.server.skills.Matching;
import io.github.soymods.clanker.server.skills.MineTask;
import io.github.soymods.clanker.server.skills.PlaceTask;
import io.github.soymods.clanker.server.skills.SleepTask;
import io.github.soymods.clanker.server.skills.SmeltTask;
import io.github.soymods.clanker.server.skills.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The tools the model can call. Results are short factual strings.
 */
public final class Tools {
	private static final double MAX_TRIP = 256;

	private interface Handler {
		String run(ClankerBot bot, ClankerPlayer player, JsonObject args);
	}

	private record Tool(ToolSpec spec, Handler handler) {
	}

	private static final Map<String, Tool> TOOLS = new LinkedHashMap<>();

	static {
		register("follow_player", "Keep following a player around until you stop or start something else.",
				schema(prop("player", "string", "The player's name"),
						optional(prop("distance", "number", "How close to stay, in blocks (default 3)"))),
				Tools::followPlayer);
		register("come_to", "Walk to where a player is right now, then stop.",
				schema(prop("player", "string", "The player's name")),
				Tools::comeTo);
		register("go_to", "Walk to block coordinates. Like any of your walking, this finds a way: around things, jumping gaps, "
				+ "digging through and building up or across with junk blocks (dirt, cobblestone...) when the host allows breaking and placing.",
				schema(prop("x", "integer", null), prop("y", "integer", null), prop("z", "integer", null)),
				Tools::goTo);
		register("stop", "Stop whatever you're doing.",
				schema(),
				(bot, player, args) -> bot.tasks().stop() ? "Stopped." : "You weren't doing anything.");
		register("look_at", "Turn to look at a player.",
				schema(prop("player", "string", "The player's name")),
				Tools::lookAt);
		register("emote", "Do a quick physical gesture.",
				schema(enumProp("action", "jump, crouch, stand up from crouching, or swing your arm", "jump", "crouch", "stand", "swing")),
				Tools::emote);
		register("explore", "Head off in a direction to see what's there.",
				schema(optional(enumProp("direction", "Which way (default: a random one)", "north", "south", "east", "west", "random")),
						optional(prop("distance", "integer", "How far, in blocks (default 32, max 128)"))),
				Tools::explore);
		register("mine", "Find blocks of a kind nearby (within " + MineTask.SEARCH_RADIUS + " blocks), walk over, break them with your best tool and pick up the drops. "
						+ "Works for anything you can break by hand: trees/logs, stone, ores, dirt, sand, plants…",
				schema(prop("block", "string", "Block name, e.g. \"oak_log\", \"logs\", \"stone\", \"iron_ore\", \"sand\""),
						optional(prop("count", "integer", "How many blocks to break (default 1, max 256)"))),
				Tools::mine);
		register("pick_up_items", "Walk over dropped items nearby to pick them up.",
				schema(optional(prop("item", "string", "Only this kind of item (default: everything)"))),
				Tools::pickUp);
		register("give_item", "Walk to a player and toss them items from your inventory.",
				schema(prop("player", "string", "The player's name"), prop("item", "string", "Item name, e.g. \"oak_log\" or \"bread\""),
						optional(prop("count", "integer", "How many (default: all you have)"))),
				Tools::giveItem);
		register("drop_item", "Drop items from your inventory where you stand.",
				schema(prop("item", "string", "Item name"), optional(prop("count", "integer", "How many (default: all)"))),
				Tools::dropItem);
		register("hold_item", "Put an item from your inventory in your hand.",
				schema(prop("item", "string", "Item name")),
				Tools::holdItem);
		register("equip", "Put on armor (or a shield in your offhand) from your inventory.",
				schema(prop("item", "string", "Item name, e.g. \"iron_chestplate\"")),
				Tools::equip);
		register("eat", "Eat food from your inventory.",
				schema(optional(prop("item", "string", "Which food (default: whatever you have)"))),
				Tools::eat);
		register("attack", "Fight a mob or player until it dies or gets away. Uses your best weapon.",
				schema(prop("target", "string", "A player's name or a mob type, e.g. \"zombie\"; the nearest match within 24 blocks")),
				Tools::attack);
		register("craft", "Craft an item from your inventory. Bigger recipes need a crafting table within " + 24 + " blocks; you'll walk to it.",
				schema(prop("item", "string", "What to make, e.g. \"oak_planks\", \"stick\", \"crafting_table\", \"stone_pickaxe\""),
						optional(prop("count", "integer", "How many items you want (default 1)"))),
				Tools::craft);
		register("smelt", "Cook or smelt items in the nearest furnace, using fuel from your inventory, and collect the results.",
				schema(prop("item", "string", "What to put in, e.g. \"raw_iron\", \"beef\", \"sand\""),
						optional(prop("count", "integer", "How many (default: all you have, max 64)"))),
				Tools::smelt);
		register("open_container", "Walk to the nearest chest or barrel (or the one at given coordinates) and see what's inside.",
				schema(optional(prop("x", "integer", null)), optional(prop("y", "integer", null)), optional(prop("z", "integer", null))),
				(bot, player, args) -> container(bot, args, ContainerTask.Mode.LOOK));
		register("store_items", "Put items into the nearest chest or barrel (or the one at given coordinates).",
				schema(optional(prop("item", "string", "Which item (default: everything except tools, armor, weapons and food)")),
						optional(prop("count", "integer", "How many (default: all)")),
						optional(prop("x", "integer", null)), optional(prop("y", "integer", null)), optional(prop("z", "integer", null))),
				(bot, player, args) -> container(bot, args, ContainerTask.Mode.STORE));
		register("take_items", "Take items out of the nearest chest or barrel (or the one at given coordinates).",
				schema(prop("item", "string", "Which item, or \"everything\""),
						optional(prop("count", "integer", "How many (default: all)")),
						optional(prop("x", "integer", null)), optional(prop("y", "integer", null)), optional(prop("z", "integer", null))),
				(bot, player, args) -> container(bot, args, ContainerTask.Mode.TAKE));
		register("place_block", "Place a block from your inventory at coordinates, or right next to you if none are given.",
				schema(prop("item", "string", "Block item, e.g. \"cobblestone\", \"crafting_table\", \"torch\""),
						optional(prop("x", "integer", null)), optional(prop("y", "integer", null)), optional(prop("z", "integer", null))),
				Tools::placeBlock);
		register("remember", "Write a note in your notebook so you remember it in later sessions: places, things about people, plans, anything.",
				schema(prop("note", "string", "What to remember, in a sentence, with coordinates if it's about a place")),
				Tools::remember);
		register("forget_note", "Cross out a note in your notebook that's wrong or no longer matters.",
				schema(prop("id", "integer", "The note's number")),
				(bot, player, args) -> bot.memory().remove(integer(args, "id")) ? "Crossed out note #" + integer(args, "id") + "." : "There's no note #" + integer(args, "id") + ".");
		register("sleep", "Walk to the nearest bed and sleep in it (only works at night or during thunderstorms).",
				schema(),
				(bot, player, args) -> {
					bot.tasks().start(new SleepTask());
					return "Heading to a bed.";
				});
	}

	private Tools() {
	}

	/** All tools, including ones the host has turned off (for tests and docs). */
	public static List<ToolSpec> specs() {
		return TOOLS.values().stream().map(Tool::spec).toList();
	}

	/** The tools the host allows. Turned-off tools aren't offered at all. */
	public static List<ToolSpec> specs(ServerConfig config) {
		return TOOLS.values().stream().map(Tool::spec).filter(spec -> allowed(config, spec.name())).toList();
	}

	static boolean allowed(ServerConfig config, String tool) {
		return switch (tool) {
			case "mine" -> config.canBreakBlocks;
			case "place_block" -> config.canPlaceBlocks;
			case "attack" -> config.canAttackMobs || config.canAttackPlayers;
			case "open_container", "store_items", "take_items" -> config.canUseContainers;
			default -> true;
		};
	}

	public static String execute(ClankerBot bot, ToolCall call) {
		Tool tool = TOOLS.get(call.name());
		if (tool == null) {
			return "Error: there is no tool named " + call.name() + ".";
		}
		if (!allowed(ServerConfig.get(), call.name())) {
			return "Error: the host has turned off " + call.name() + ".";
		}
		ClankerPlayer player = bot.player();
		if (player == null || player.isDeadOrDying()) {
			return "Error: you're dead and waiting to respawn.";
		}
		JsonObject args;
		try {
			JsonElement parsed = JsonParser.parseString(call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments());
			args = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
		} catch (RuntimeException e) {
			return "Error: arguments were not valid JSON.";
		}
		try {
			return tool.handler().run(bot, player, args);
		} catch (MissingArgument e) {
			return "Error: missing or invalid argument \"" + e.getMessage() + "\".";
		} catch (RuntimeException e) {
			return "Error: " + e.getMessage();
		}
	}

	private static String followPlayer(ClankerBot bot, ClankerPlayer player, JsonObject args) {
		ServerPlayer target = findPlayer(bot, string(args, "player"));
		if (target == null) {
			return noSuchPlayer(bot, string(args, "player"));
		}
		if (target.level() != player.level()) {
			return target.getName().getString() + " is in another dimension.";
		}
		double distance = args.has("distance") ? Math.max(1.5, Math.min(16, args.get("distance").getAsDouble())) : 3;
		bot.tasks().start(new FollowTask(target, distance));
		return "Following " + target.getName().getString() + " (" + Perception.blocks(player.distanceTo(target)) + " away).";
	}

	private static String comeTo(ClankerBot bot, ClankerPlayer player, JsonObject args) {
		ServerPlayer target = findPlayer(bot, string(args, "player"));
		if (target == null) {
			return noSuchPlayer(bot, string(args, "player"));
		}
		if (target.level() != player.level()) {
			return target.getName().getString() + " is in another dimension.";
		}
		bot.tasks().start(new ComeToTask(target));
		return "Walking to " + target.getName().getString() + " (" + Perception.blocks(player.distanceTo(target)) + " away).";
	}

	private static String goTo(ClankerBot bot, ClankerPlayer player, JsonObject args) {
		Vec3 target = new Vec3(integer(args, "x") + 0.5, integer(args, "y"), integer(args, "z") + 0.5);
		double distance = player.position().distanceTo(target);
		if (distance > MAX_TRIP) {
			return "That's " + Perception.blocks(distance) + " away; one trip can be at most " + (int) MAX_TRIP + " blocks.";
		}
		bot.tasks().start(new GoToTask(target));
		return "Walking to " + Perception.coords(target) + ", " + Perception.blocks(distance) + " away.";
	}

	private static String lookAt(ClankerBot bot, ClankerPlayer player, JsonObject args) {
		ServerPlayer target = findPlayer(bot, string(args, "player"));
		if (target == null) {
			return noSuchPlayer(bot, string(args, "player"));
		}
		bot.movement().lookAt(target);
		return "Looking at " + target.getName().getString() + ".";
	}

	private static String emote(ClankerBot bot, ClankerPlayer player, JsonObject args) {
		String action = string(args, "action");
		switch (action) {
			case "jump" -> bot.movement().jumpOnce();
			case "crouch" -> player.setShiftKeyDown(true);
			case "stand" -> player.setShiftKeyDown(false);
			case "swing" -> player.swing(InteractionHand.MAIN_HAND, true);
			default -> {
				return "Error: unknown action \"" + action + "\".";
			}
		}
		return "Done: " + action + ".";
	}

	private static String explore(ClankerBot bot, ClankerPlayer player, JsonObject args) {
		String direction = args.has("direction") ? args.get("direction").getAsString() : "random";
		int distance = args.has("distance") ? Math.clamp(integer(args, "distance"), 8, 128) : 32;
		double angle = switch (direction) {
			case "north" -> Math.PI;
			case "south" -> 0;
			case "east" -> -Math.PI / 2;
			case "west" -> Math.PI / 2;
			default -> player.getRandom().nextDouble() * Math.PI * 2;
		};
		int x = (int) Math.floor(player.getX() - Math.sin(angle) * distance);
		int z = (int) Math.floor(player.getZ() + Math.cos(angle) * distance);
		int y = player.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		Vec3 target = new Vec3(x + 0.5, y, z + 0.5);
		bot.tasks().start(new GoToTask(target, 3, "explore"));
		return "Heading " + (direction.equals("random") ? Perception.direction(player.position(), target) : direction)
				+ " toward " + Perception.coords(target) + ".";
	}

	private static String remember(ClankerBot bot, ClankerPlayer player, JsonObject args) {
		String note = string(args, "note");
		String when = "day " + (player.level().getOverworldClockTime() / 24000 + 1);
		int id = bot.memory().add(note, when);
		return "Wrote note #" + id + ".";
	}

	private static String mine(ClankerBot bot, ClankerPlayer player, JsonObject args) {
		String block = string(args, "block");
		int count = args.has("count") ? Math.clamp(integer(args, "count"), 1, 256) : 1;
		bot.tasks().start(new MineTask(block, Matching.block(block), count));
		return "Started mining " + count + " " + block + ".";
	}

	private static String pickUp(ClankerBot bot, ClankerPlayer player, JsonObject args) {
		String item = args.has("item") ? string(args, "item") : null;
		bot.tasks().start(new CollectTask(item == null ? "items" : item, item == null ? stack -> true : Matching.item(item)));
		return "Picking up " + (item == null ? "items" : item) + " nearby.";
	}

	private static String giveItem(ClankerBot bot, ClankerPlayer player, JsonObject args) {
		ServerPlayer target = findPlayer(bot, string(args, "player"));
		if (target == null) {
			return noSuchPlayer(bot, string(args, "player"));
		}
		String item = string(args, "item");
		var matcher = Matching.item(item);
		int have = Matching.count(player.getInventory(), matcher);
		if (have == 0) {
			return "You have no " + item + ".";
		}
		int count = args.has("count") ? Math.clamp(integer(args, "count"), 1, have) : have;
		bot.tasks().start(new GiveTask(target, item, matcher, count));
		return "Bringing " + count + " " + item + " to " + target.getName().getString() + ".";
	}

	private static String dropItem(ClankerBot bot, ClankerPlayer player, JsonObject args) {
		String item = string(args, "item");
		var matcher = Matching.item(item);
		int have = Matching.count(player.getInventory(), matcher);
		if (have == 0) {
			return "You have no " + item + ".";
		}
		int count = args.has("count") ? Math.clamp(integer(args, "count"), 1, have) : have;
		return "Dropped " + GiveTask.give(player, matcher, count) + " " + item + ".";
	}

	private static String holdItem(ClankerBot bot, ClankerPlayer player, JsonObject args) {
		String item = string(args, "item");
		int slot = Matching.find(player.getInventory(), Matching.item(item));
		if (slot < 0) {
			return "You have no " + item + ".";
		}
		Matching.hold(player.getInventory(), slot);
		return "Now holding " + Perception.itemName(player.getMainHandItem()) + ".";
	}

	private static String equip(ClankerBot bot, ClankerPlayer player, JsonObject args) {
		String item = string(args, "item");
		int slot = Matching.find(player.getInventory(), stack -> Matching.item(item).test(stack) && stack.has(DataComponents.EQUIPPABLE));
		if (slot < 0) {
			return "You have no " + item + " you can wear.";
		}
		ItemStack stack = player.getInventory().getItem(slot);
		EquipmentSlot equipmentSlot = stack.get(DataComponents.EQUIPPABLE).slot();
		ItemStack previous = player.getItemBySlot(equipmentSlot);
		player.setItemSlot(equipmentSlot, stack.copy());
		player.getInventory().setItem(slot, previous);
		return "Put on " + Matching.itemId(stack) + (previous.isEmpty() ? "" : ", took off " + Matching.itemId(previous)) + ".";
	}

	private static String eat(ClankerBot bot, ClankerPlayer player, JsonObject args) {
		String item = args.has("item") ? string(args, "item") : null;
		int slot = Matching.find(player.getInventory(), stack -> EatTask.isFood(stack) && (item == null || Matching.item(item).test(stack)));
		if (slot < 0) {
			return item == null ? "You have no food." : "You have no " + item + " to eat.";
		}
		String label = Matching.itemId(player.getInventory().getItem(slot));
		bot.tasks().start(new EatTask(label, slot));
		return "Eating " + label + ".";
	}

	private static String attack(ClankerBot bot, ClankerPlayer player, JsonObject args) {
		String name = string(args, "target");
		ServerConfig config = ServerConfig.get();
		ServerPlayer targetPlayer = findPlayer(bot, name);
		Entity target = targetPlayer != null && targetPlayer.level() == player.level() && targetPlayer.distanceTo(player) < 24 ? targetPlayer : null;
		if (target != null && !config.canAttackPlayers) {
			return "The host doesn't allow you to attack players.";
		}
		if (target == null && !config.canAttackMobs) {
			return "The host doesn't allow you to attack mobs.";
		}
		if (target == null) {
			List<String> words = Matching.words(name);
			target = player.level().getEntities(player, player.getBoundingBox().inflate(24),
							e -> e instanceof LivingEntity && e.isAlive() && !(e instanceof ClankerPlayer) && !(e instanceof ServerPlayer)
									&& Matching.idMatches(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(), words)).stream()
					.min(Comparator.comparingDouble(e -> e.distanceToSqr(player)))
					.orElse(null);
		}
		if (target == null) {
			return "There's no " + name + " within 24 blocks.";
		}
		bot.tasks().start(new AttackTask(target));
		return "Attacking " + Perception.entityName(target) + " (" + Perception.blocks(player.distanceTo(target)) + " away).";
	}

	private static String craft(ClankerBot bot, ClankerPlayer player, JsonObject args) {
		String item = string(args, "item");
		int count = args.has("count") ? Math.clamp(integer(args, "count"), 1, 256) : 1;
		bot.tasks().start(new CraftTask(item, Matching.item(item), count));
		return "Crafting " + count + " " + item + ".";
	}

	private static String smelt(ClankerBot bot, ClankerPlayer player, JsonObject args) {
		String item = string(args, "item");
		var matcher = Matching.item(item);
		int have = Matching.count(player.getInventory(), matcher);
		if (have == 0) {
			return "You have no " + item + ".";
		}
		int count = args.has("count") ? Math.clamp(integer(args, "count"), 1, Math.min(have, 64)) : Math.min(have, 64);
		bot.tasks().start(new SmeltTask(item, matcher, count));
		return "Taking " + count + " " + item + " to a furnace.";
	}

	private static String container(ClankerBot bot, JsonObject args, ContainerTask.Mode mode) {
		String item = args.has("item") ? string(args, "item") : null;
		boolean everything = item == null || item.equalsIgnoreCase("everything") || item.equalsIgnoreCase("all");
		int count = args.has("count") ? Math.max(1, integer(args, "count")) : Integer.MAX_VALUE;
		bot.tasks().start(new ContainerTask(mode, optionalPos(args), everything ? null : item, everything ? null : Matching.item(item), count));
		return "Heading to the container.";
	}

	private static String placeBlock(ClankerBot bot, ClankerPlayer player, JsonObject args) {
		String item = string(args, "item");
		BlockPos pos = optionalPos(args);
		Task.Result result = bot.tasks().startNow(new PlaceTask(item, Matching.item(item), pos), player);
		if (result != null) {
			return (result.status() == Task.Status.SUCCESS ? "Done: " : "Failed: ") + result.message() + ".";
		}
		return "Started placing " + item + (pos == null ? " next to you" : " at " + Perception.coords(pos)) + "; you'll get an event when it's done.";
	}

	private static BlockPos optionalPos(JsonObject args) {
		if (!args.has("x") || !args.has("y") || !args.has("z")) {
			return null;
		}
		return new BlockPos(integer(args, "x"), integer(args, "y"), integer(args, "z"));
	}

	/** Names are unique on a normal server, but test and proxy setups can repeat them, so prefer the closest. */
	private static ServerPlayer findPlayer(ClankerBot bot, String name) {
		ServerPlayer self = bot.player();
		return bot.server().getPlayerList().getPlayers().stream()
				.filter(candidate -> candidate != self && candidate.getName().getString().equalsIgnoreCase(name.strip()))
				.min(Comparator.comparingDouble(candidate -> candidate.level() == self.level() ? candidate.distanceToSqr(self) : Double.MAX_VALUE))
				.orElse(null);
	}

	private static String noSuchPlayer(ClankerBot bot, String name) {
		String online = bot.server().getPlayerList().getPlayers().stream()
				.filter(p -> !(p instanceof ClankerPlayer))
				.map(p -> p.getName().getString())
				.collect(Collectors.joining(", "));
		return "No player named " + name + " is online. Online: " + (online.isEmpty() ? "nobody else" : online) + ".";
	}

	private static class MissingArgument extends RuntimeException {
		MissingArgument(String name) {
			super(name);
		}
	}

	private static String string(JsonObject args, String name) {
		JsonElement value = args.get(name);
		if (value == null || !value.isJsonPrimitive() || value.getAsString().isBlank()) {
			throw new MissingArgument(name);
		}
		return value.getAsString();
	}

	private static int integer(JsonObject args, String name) {
		JsonElement value = args.get(name);
		try {
			return (int) Math.floor(value.getAsDouble());
		} catch (RuntimeException e) {
			throw new MissingArgument(name);
		}
	}

	private static void register(String name, String description, JsonObject parameters, Handler handler) {
		TOOLS.put(name, new Tool(new ToolSpec(name, description, parameters), handler));
	}

	private record Prop(String name, JsonObject schema, boolean required) {
	}

	private static Prop prop(String name, String type, String description) {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", type);
		if (description != null) {
			schema.addProperty("description", description);
		}
		return new Prop(name, schema, true);
	}

	private static Prop enumProp(String name, String description, String... values) {
		Prop prop = prop(name, "string", description);
		JsonArray options = new JsonArray();
		for (String value : values) {
			options.add(value);
		}
		prop.schema().add("enum", options);
		return prop;
	}

	private static Prop optional(Prop prop) {
		return new Prop(prop.name(), prop.schema(), false);
	}

	private static JsonObject schema(Prop... props) {
		JsonObject properties = new JsonObject();
		JsonArray required = new JsonArray();
		for (Prop prop : props) {
			properties.add(prop.name(), prop.schema());
			if (prop.required()) {
				required.add(prop.name());
			}
		}
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		schema.add("required", required);
		schema.addProperty("additionalProperties", false);
		return schema;
	}
}
