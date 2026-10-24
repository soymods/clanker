package io.github.soymods.clanker.server.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.soymods.clanker.config.ServerConfig;
import io.github.soymods.clanker.network.SignalPayload;
import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerBots;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import io.github.soymods.clanker.server.brain.Brain;
import io.github.soymods.clanker.server.perception.Perception;
import io.github.soymods.clanker.server.safety.ClankerPermissions;
import io.github.soymods.clanker.server.skills.ComeToTask;
import io.github.soymods.clanker.server.skills.FollowTask;
import io.github.soymods.clanker.server.skills.GoToTask;
import io.github.soymods.clanker.server.skills.Task;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

public final class ClankerCommand {
	private ClankerCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("clanker")
				.then(Commands.literal("spawn").requires(ClankerPermissions::canManage)
						.executes(ClankerCommand::spawn))
				.then(Commands.literal("despawn").requires(ClankerPermissions::canManage)
						.executes(ClankerCommand::despawn))
				.then(Commands.literal("stop")
						.executes(ClankerCommand::stop))
				.then(Commands.literal("status")
						.executes(ClankerCommand::status))
				.then(Commands.literal("follow")
						.executes(context -> follow(context, context.getSource().getPlayerOrException()))
						.then(Commands.argument("player", EntityArgument.player())
								.executes(context -> follow(context, EntityArgument.getPlayer(context, "player")))))
				.then(Commands.literal("come")
						.executes(ClankerCommand::come))
				.then(Commands.literal("goto")
						.then(Commands.argument("pos", Vec3Argument.vec3())
								.executes(context -> goTo(context, Vec3Argument.getVec3(context, "pos")))))
				.then(Commands.literal("forget").requires(ClankerPermissions::canManage)
						.executes(ClankerCommand::forget))
				.then(Commands.literal("config")
						.executes(ClankerCommand::openConfig)
						.then(Commands.literal("reload").requires(ClankerPermissions::canManage)
								.executes(context -> {
									ServerConfig.load();
									ClankerBots.all().forEach(bot -> bot.brain().resetBackoff());
									context.getSource().sendSuccess(() -> Component.literal("Clanker config reloaded"), true);
									return 1;
								}))));
	}

	private static int spawn(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		try {
			ClankerBot bot = ClankerBots.spawn(source.getServer(), source.getLevel(), source.getPosition(),
					source.getRotation().y, source.getRotation().x);
			source.sendSuccess(() -> Component.literal(bot.name() + " joined at " + Perception.coords(bot.player().position())), true);
			if (ServerConfig.get().resolveApiKey().isBlank() && ServerConfig.get().provider == ServerConfig.Provider.OPENAI) {
				source.sendSystemMessage(Component.literal("No API key is set yet. Open /clanker config to add one."));
			}
			return 1;
		} catch (ClankerBots.SpawnException e) {
			source.sendFailure(Component.literal(e.getMessage()));
			return 0;
		}
	}

	private static int despawn(CommandContext<CommandSourceStack> context) {
		Optional<ClankerBot> bot = ClankerBots.first();
		if (bot.isEmpty()) {
			context.getSource().sendFailure(Component.literal("Clanker isn't in the world"));
			return 0;
		}
		ClankerBots.despawn(bot.get());
		context.getSource().sendSuccess(() -> Component.literal(bot.get().name() + " left"), true);
		return 1;
	}

	private static int stop(CommandContext<CommandSourceStack> context) {
		Optional<ClankerBot> bot = requireBot(context);
		if (bot.isEmpty()) {
			return 0;
		}
		boolean stopped = bot.get().tasks().stop();
		context.getSource().sendSuccess(() -> Component.literal(stopped ? "Stopped" : "Nothing to stop"), false);
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerConfig config = ServerConfig.get();
		String model = config.model.isBlank() && config.provider == ServerConfig.Provider.OPENAI ? Brain.DEFAULT_OPENAI_MODEL : config.model;
		String key = config.resolveApiKey().isBlank() ? "not set" : "set (" + config.apiKeySource() + ")";
		StringBuilder text = new StringBuilder("Provider: " + config.provider + ", model: " + model + ", API key: " + key);
		Optional<ClankerBot> bot = ClankerBots.first();
		if (bot.isEmpty()) {
			text.append("\nClanker isn't in the world.");
		} else {
			ClankerPlayer player = bot.get().player();
			Task task = bot.get().tasks().current();
			text.append("\n").append(bot.get().name()).append(" at ").append(Perception.coords(player.position()))
					.append(", health ").append(Math.round(player.getHealth())).append("/20")
					.append("\nTask: ").append(task == null ? "none" : task.describe(bot.get(), player))
					.append(bot.get().brain().isThinking() ? " (thinking…)" : "")
					.append("\n").append(bot.get().brain().status());
		}
		source.sendSuccess(() -> Component.literal(text.toString()), false);
		return 1;
	}

	private static int follow(CommandContext<CommandSourceStack> context, ServerPlayer target) {
		Optional<ClankerBot> bot = requireBot(context);
		if (bot.isEmpty()) {
			return 0;
		}
		bot.get().tasks().start(new FollowTask(target, 3));
		context.getSource().sendSuccess(() -> Component.literal(bot.get().name() + " is following " + target.getName().getString()), false);
		return 1;
	}

	private static int come(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer target = context.getSource().getPlayerOrException();
		Optional<ClankerBot> bot = requireBot(context);
		if (bot.isEmpty()) {
			return 0;
		}
		bot.get().tasks().start(new ComeToTask(target));
		context.getSource().sendSuccess(() -> Component.literal(bot.get().name() + " is coming to you"), false);
		return 1;
	}

	private static int goTo(CommandContext<CommandSourceStack> context, Vec3 pos) {
		Optional<ClankerBot> bot = requireBot(context);
		if (bot.isEmpty()) {
			return 0;
		}
		bot.get().tasks().start(new GoToTask(pos));
		context.getSource().sendSuccess(() -> Component.literal(bot.get().name() + " is walking to " + Perception.coords(pos)), false);
		return 1;
	}

	private static int forget(CommandContext<CommandSourceStack> context) {
		Optional<ClankerBot> bot = requireBot(context);
		if (bot.isEmpty()) {
			return 0;
		}
		bot.get().brain().clearConversation();
		context.getSource().sendSuccess(() -> Component.literal(bot.get().name() + " forgot the conversation so far"), true);
		return 1;
	}

	private static int openConfig(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		if (!ServerPlayNetworking.canSend(player, SignalPayload.OpenSettings.TYPE)) {
			context.getSource().sendFailure(Component.literal("The settings screen needs Clanker installed on your client. "
					+ "Hosts can also edit config/clanker-server.json and run /clanker config reload."));
			return 0;
		}
		ServerPlayNetworking.send(player, SignalPayload.OpenSettings.INSTANCE);
		return 1;
	}

	private static Optional<ClankerBot> requireBot(CommandContext<CommandSourceStack> context) {
		Optional<ClankerBot> bot = ClankerBots.first();
		if (bot.isEmpty()) {
			context.getSource().sendFailure(Component.literal("Clanker isn't in the world. Use /clanker spawn"));
		}
		return bot;
	}
}
