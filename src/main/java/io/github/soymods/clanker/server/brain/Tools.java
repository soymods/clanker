package io.github.soymods.clanker.server.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import io.github.soymods.clanker.server.brain.llm.ToolCall;
import io.github.soymods.clanker.server.brain.llm.ToolSpec;
import io.github.soymods.clanker.server.perception.Perception;
import io.github.soymods.clanker.server.skills.ComeToTask;
import io.github.soymods.clanker.server.skills.FollowTask;
import io.github.soymods.clanker.server.skills.GoToTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
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
		register("go_to", "Walk to block coordinates.",
				schema(prop("x", "integer", null), prop("y", "integer", null), prop("z", "integer", null)),
				Tools::goTo);
		register("stop", "Stop whatever you're doing (moving, following).",
				schema(),
				(bot, player, args) -> bot.tasks().stop() ? "Stopped." : "You weren't doing anything.");
		register("look_at", "Turn to look at a player.",
				schema(prop("player", "string", "The player's name")),
				Tools::lookAt);
		register("emote", "Do a quick physical gesture.",
				schema(enumProp("action", "jump, crouch, stand up from crouching, or swing your arm", "jump", "crouch", "stand", "swing")),
				Tools::emote);
	}

	private Tools() {
	}

	public static List<ToolSpec> specs() {
		return TOOLS.values().stream().map(Tool::spec).toList();
	}

	public static String execute(ClankerBot bot, ToolCall call) {
		Tool tool = TOOLS.get(call.name());
		if (tool == null) {
			return "Error: there is no tool named " + call.name() + ".";
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
