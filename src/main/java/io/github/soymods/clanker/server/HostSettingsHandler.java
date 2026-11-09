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
		settings.talkativeness = config.talkativeness.name();
		settings.voiceProvider = config.voiceProvider.name();
		settings.xaiVoice = config.xaiVoice;
		settings.elevenLabsVoice = config.elevenLabsVoice;
		settings.elevenLabsModel = config.elevenLabsModel;
		settings.voiceSpeed = config.voiceSpeed;
		settings.canBreakBlocks = config.canBreakBlocks;
		settings.canPlaceBlocks = config.canPlaceBlocks;
		settings.canAttackMobs = config.canAttackMobs;
		settings.canAttackPlayers = config.canAttackPlayers;
		settings.canUseContainers = config.canUseContainers;
		settings.sessionSpendCap = config.sessionSpendCap;
		settings.dailySpendCap = config.dailySpendCap;
		settings.spentSession = Spend.session();
		settings.spentToday = Spend.today();
		settings.priceKnown = Spend.isKnown(config, Brain.currentModel(config));
		settings.canEdit = ClankerPermissions.canManage(player);
		settings.keySet = !config.resolveApiKey().isBlank();
		settings.keySource = config.apiKeySource();
		for (ServerConfig.KeyKind kind : ServerConfig.KeyKind.values()) {
			settings.keySources.put(kind.name(), config.keySource(kind));
		}
		settings.defaultModel = Brain.currentModel(config);
		settings.defaultModels.put(ServerConfig.Provider.OPENAI.name(), Brain.DEFAULT_OPENAI_MODEL);
		settings.defaultModels.put(ServerConfig.Provider.XAI.name(), Brain.DEFAULT_XAI_MODEL);
		settings.voiceStatus = ClankerBots.all().stream().findFirst().map(bot -> bot.voice().status()).orElse("");
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
			config.talkativeness = ServerConfig.Talkativeness.valueOf(update.talkativeness);
			config.voiceProvider = ServerConfig.VoiceProvider.valueOf(update.voiceProvider);
		} catch (IllegalArgumentException | NullPointerException e) {
			return "Unknown provider, listen mode, talkativeness or voice.";
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
		config.xaiVoice = clean(update.xaiVoice).isEmpty() ? "rex" : clean(update.xaiVoice);
		config.elevenLabsVoice = clean(update.elevenLabsVoice);
		config.elevenLabsModel = clean(update.elevenLabsModel);
		config.voiceSpeed = Math.max(0.7, Math.min(1.5, update.voiceSpeed));
		config.canBreakBlocks = update.canBreakBlocks;
		config.canPlaceBlocks = update.canPlaceBlocks;
		config.canAttackMobs = update.canAttackMobs;
		config.canAttackPlayers = update.canAttackPlayers;
		config.canUseContainers = update.canUseContainers;
		config.sessionSpendCap = Math.max(0, Math.min(1000, update.sessionSpendCap));
		config.dailySpendCap = Math.max(0, Math.min(1000, update.dailySpendCap));
		if (update.clearApiKey) {
			config.setKey(config.brainKey(), "");
		} else if (update.newApiKey != null && !update.newApiKey.isBlank()) {
			config.setKey(config.brainKey(), update.newApiKey.strip());
		}
		ServerConfig.KeyKind voiceKey = ServerConfig.voiceKey(config.voiceProvider);
		if (voiceKey != null) {
			if (update.clearVoiceKey) {
				config.setKey(voiceKey, "");
			} else if (update.newVoiceKey != null && !update.newVoiceKey.isBlank()) {
				config.setKey(voiceKey, update.newVoiceKey.strip());
			}
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
