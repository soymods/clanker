package io.github.soymods.clanker.network;

import com.google.gson.Gson;

import java.util.HashMap;
import java.util.Map;

/**
 * Host settings as exchanged with the settings screen. The API key only ever travels client to server
 * ({@link #newApiKey}); the server only reports whether one is set.
 */
public class HostSettings {
	private static final Gson GSON = new Gson();

	public String provider = "";
	public String model = "";
	public String endpoint = "";
	public String reasoningEffort = "";
	public String botName = "";
	public String personality = "";
	public String listenMode = "";
	public String talkativeness = "";
	public String voiceProvider = "";
	public String xaiVoice = "";
	public String elevenLabsVoice = "";
	public String elevenLabsModel = "";
	public double voiceSpeed = 1;
	public boolean canBreakBlocks;
	public boolean canPlaceBlocks;
	public boolean canAttackMobs;
	public boolean canAttackPlayers;
	public boolean canUseContainers;
	public double sessionSpendCap;
	public double dailySpendCap;

	// Server to client
	public boolean canEdit;
	public boolean keySet;
	public String keySource = "";
	/** Where each key comes from ("" if not set), by {@code ServerConfig.KeyKind} name. Never the keys themselves. */
	public Map<String, String> keySources = new HashMap<>();
	public String defaultModel = "";
	/** Default model per brain provider. */
	public Map<String, String> defaultModels = new HashMap<>();
	public String voiceStatus = "";
	public String status = "";
	public double spentSession;
	public double spentToday;
	public boolean priceKnown = true;

	// Client to server
	public String newApiKey = "";
	public boolean clearApiKey;
	/** A key for the voice provider chosen in {@link #voiceProvider}. */
	public String newVoiceKey = "";
	public boolean clearVoiceKey;

	public String toJson() {
		return GSON.toJson(this);
	}

	public static HostSettings fromJson(String json) {
		HostSettings settings = GSON.fromJson(json, HostSettings.class);
		return settings == null ? new HostSettings() : settings;
	}
}
