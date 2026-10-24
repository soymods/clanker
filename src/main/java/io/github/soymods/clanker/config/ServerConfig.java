package io.github.soymods.clanker.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.soymods.clanker.Clanker;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Host-side settings, stored in {@code config/clanker-server.json}.
 * Holds the API key, so it is never sent to clients.
 */
public class ServerConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("clanker-server.json");

	public enum Provider { OPENAI, OPENAI_COMPATIBLE }

	public enum ListenMode { NAME_ONLY, NEARBY, EVERYONE }

	// Brain
	public Provider provider = Provider.OPENAI;
	public String model = "";
	public String apiKey = "";
	/** Base URL for {@link Provider#OPENAI_COMPATIBLE} (e.g. Ollama or OpenRouter). */
	public String endpoint = "";
	/** Passed as {@code reasoning_effort}. Lower effort answers faster, which matters more in a game than deep reasoning. Blank uses the model's default. */
	public String reasoningEffort = "low";
	/** Temperament description. Shapes how Clanker decides, never what it says. */
	public String personality = "";

	// Bot
	public String botName = "Clanker";

	// Behavior
	public int idleSeconds = 30;
	public ListenMode listenMode = ListenMode.NAME_ONLY;
	public int listenRadius = 24;

	// What Clanker is allowed to do
	public boolean canBreakBlocks = true;
	public boolean canPlaceBlocks = true;
	public boolean canAttackMobs = true;
	public boolean canAttackPlayers = false;
	public boolean canUseContainers = true;

	// Spending, in USD; 0 means no cap
	public double sessionSpendCap = 2.0;
	public double dailySpendCap = 5.0;
	/** Custom prices per million tokens for models Clanker doesn't know; 0 uses the built-in table. */
	public double priceInputPerMillion = 0;
	public double priceOutputPerMillion = 0;

	/** Fallback key file outside any game directory, so dev runs and tests share it without it being in the repo. */
	private static final Path KEY_FILE = Path.of(System.getProperty("user.home"), ".clanker", "api-key.txt");

	private static ServerConfig instance;

	/**
	 * The API key to use: {@code CLANKER_API_KEY} env var, then {@link #apiKey}, then {@code ~/.clanker/api-key.txt}.
	 * Returns an empty string if none is set.
	 */
	public String resolveApiKey() {
		String env = System.getenv("CLANKER_API_KEY");
		if (env != null && !env.isBlank()) {
			return env.strip();
		}
		if (!apiKey.isBlank()) {
			return apiKey.strip();
		}
		if (Files.isRegularFile(KEY_FILE)) {
			try {
				return Files.readString(KEY_FILE).strip();
			} catch (IOException e) {
				Clanker.LOGGER.error("Failed to read {}", KEY_FILE, e);
			}
		}
		return "";
	}

	/** Where {@link #resolveApiKey()} found the key, for display. Never includes the key itself. */
	public String apiKeySource() {
		String env = System.getenv("CLANKER_API_KEY");
		if (env != null && !env.isBlank()) {
			return "CLANKER_API_KEY environment variable";
		}
		if (!apiKey.isBlank()) {
			return "settings";
		}
		if (Files.isRegularFile(KEY_FILE)) {
			return "~/.clanker/api-key.txt";
		}
		return "";
	}

	public static ServerConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	public static ServerConfig load() {
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				ServerConfig config = GSON.fromJson(reader, ServerConfig.class);
				if (config != null) {
					instance = config;
					return config;
				}
			} catch (IOException | RuntimeException e) {
				Clanker.LOGGER.error("Failed to read {}, using defaults", PATH, e);
			}
		}
		instance = new ServerConfig();
		instance.save();
		return instance;
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			Clanker.LOGGER.error("Failed to write {}", PATH, e);
		}
	}
}
