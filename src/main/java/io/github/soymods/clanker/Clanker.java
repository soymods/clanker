package io.github.soymods.clanker;

import io.github.soymods.clanker.config.ServerConfig;
import io.github.soymods.clanker.network.ClankerNetworking;
import io.github.soymods.clanker.server.bot.ClankerBots;
import io.github.soymods.clanker.server.command.ClankerCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Clanker implements ModInitializer {
	public static final String MOD_ID = "clanker";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ServerConfig.load();
		ClankerNetworking.register();
		ClankerBots.register();
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				ClankerCommand.register(dispatcher));
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
