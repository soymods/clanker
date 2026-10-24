package io.github.soymods.clanker.server;

import io.github.soymods.clanker.config.ServerConfig;
import io.github.soymods.clanker.network.HostSettings;
import io.github.soymods.clanker.server.bot.ClankerBots;
import io.github.soymods.clanker.server.brain.Brain;
import io.github.soymods.clanker.server.brain.Spend;
import io.github.soymods.clanker.server.safety.ClankerPermissions;
import net.minecraft.server.level.ServerPlayer;

import java.util.regex.Pattern;

/**
 * Applies changes from the settings screen to {@link ServerConfig}.
 */
public final class HostSettingsHandler {
	private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
	private static final int MAX_FIELD = 2000;

	private HostSettingsHandler() {
	}

	/** Current settings for this player's screen. Never includes the key. */
	public static HostSettings snapshot(ServerPlayer player, String status) {
		ServerConfig config = ServerConfig.get();
		HostSettings settings = new HostSettings();
		settings.provider = config.provider.name();
		settings.model = config.model;
		settings.endpoint = config.endpoint;
		settings.reasoningEffort = config.reasoningEffort;
		settings.botName = config.botName;
		settings.personality = config.personality;
		settings.listenMode = config.listenMode.name();
		settings.idleSeconds = config.idleSeconds;
		settings.canBreakBlocks = config.canBreakBlocks;
		settings.canPlaceBlocks = config.canPlaceBlocks;
		settings.canAttackMobs = config.canAttackMobs;
		settings.canAttackPlayers = config.canAttackPlayers;
		settings.canUseContainers = config.canUseContainers;
		settings.sessionSpendCap = config.sessionSpendCap;
		settings.dailySpendCap = config.dailySpendCap;
		settings.spentSession = Spend.session();
		settings.spentToday = Spend.today();
		String model = config.model.isBlank() && config.provider == ServerConfig.Provider.OPENAI ? Brain.DEFAULT_OPENAI_MODEL : config.model;
		settings.priceKnown = Spend.isKnown(config, model);
		settings.canEdit = ClankerPermissions.canManage(player);
		settings.keySet = !config.resolveApiKey().isBlank();
		settings.keySource = config.apiKeySource();
		settings.defaultModel = Brain.DEFAULT_OPENAI_MODEL;
		settings.status = status;
		return settings;
	}

	/** Applies an update and returns the status message to show. */
	public static String apply(ServerPlayer player, HostSettings update) {
		if (!ClankerPermissions.canManage(player)) {
			return "Only operators or the world's host can change Clanker's settings.";
		}
		ServerConfig config = ServerConfig.get();
		try {
			config.provider = ServerConfig.Provider.valueOf(update.provider);
			config.listenMode = ServerConfig.ListenMode.valueOf(update.listenMode);
		} catch (IllegalArgumentException | NullPointerException e) {
			return "Unknown provider or listen mode.";
		}
		String name = clean(update.botName);
		if (!VALID_NAME.matcher(name).matches()) {
			return "Bot name must be 3-16 letters, numbers or underscores.";
		}
		boolean renamed = !name.equals(config.botName);
		config.botName = name;
		config.model = clean(update.model);
		config.endpoint = clean(update.endpoint);
		config.reasoningEffort = clean(update.reasoningEffort);
		config.personality = clean(update.personality);
		config.idleSeconds = Math.max(0, Math.min(3600, update.idleSeconds));
		config.canBreakBlocks = update.canBreakBlocks;
		config.canPlaceBlocks = update.canPlaceBlocks;
		config.canAttackMobs = update.canAttackMobs;
		config.canAttackPlayers = update.canAttackPlayers;
		config.canUseContainers = update.canUseContainers;
		config.sessionSpendCap = Math.max(0, Math.min(1000, update.sessionSpendCap));
		config.dailySpendCap = Math.max(0, Math.min(1000, update.dailySpendCap));
		if (update.clearApiKey) {
			config.apiKey = "";
		} else if (update.newApiKey != null && !update.newApiKey.isBlank()) {
			config.apiKey = update.newApiKey.strip();
		}
		config.save();
		ClankerBots.all().forEach(bot -> bot.brain().resetBackoff());
		return renamed ? "Saved. The new name applies the next time Clanker spawns." : "Saved.";
	}

	private static String clean(String value) {
		if (value == null) {
			return "";
		}
		String stripped = value.strip();
		return stripped.length() > MAX_FIELD ? stripped.substring(0, MAX_FIELD) : stripped;
	}
}
