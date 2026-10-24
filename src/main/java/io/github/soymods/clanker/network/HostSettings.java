package io.github.soymods.clanker.network;

import com.google.gson.Gson;

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
	public int idleSeconds;

	// Server to client
	public boolean canEdit;
	public boolean keySet;
	public String keySource = "";
	public String defaultModel = "";
	public String status = "";

	// Client to server
	public String newApiKey = "";
	public boolean clearApiKey;

	public String toJson() {
		return GSON.toJson(this);
	}

	public static HostSettings fromJson(String json) {
		HostSettings settings = GSON.fromJson(json, HostSettings.class);
		return settings == null ? new HostSettings() : settings;
	}
}
