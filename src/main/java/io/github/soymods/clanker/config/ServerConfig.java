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
 * Holds the API keys, so it is never sent to clients.
 */
public class ServerConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("clanker-server.json");
	/** Fallback key files outside any game directory, so dev runs and tests share them without them being in the repo. */
	private static final Path KEY_DIR = Path.of(System.getProperty("user.home"), ".clanker");

	public enum Provider { OPENAI, XAI, OPENAI_COMPATIBLE }

	public enum ListenMode { NAME_ONLY, NEARBY, EVERYONE }

	/**
	 * How readily small things Clanker notices add up to a turn. Speech and big events always get one; nothing ever
	 * runs on a timer.
	 */
	public enum Talkativeness {
		QUIET(2.0, 40_000), NORMAL(1.0, 15_000), CHATTY(0.6, 6_000);

		/** How much noticed weight it takes before Clanker gets a turn to react to small things. */
		public final double threshold;
		/** After a turn about small things, how long before small things alone can start another. */
		public final long smallTalkGapMs;

		Talkativeness(double threshold, long smallTalkGapMs) {
			this.threshold = threshold;
			this.smallTalkGapMs = smallTalkGapMs;
		}
	}

	/** Where Clanker's voice is made. LOCAL is each player's own Piper voice; the others run on the host. */
	public enum VoiceProvider { LOCAL, XAI, ELEVENLABS }

	/** Which secret: the brain's providers, plus ElevenLabs for the voice. */
	public enum KeyKind {
		OPENAI("CLANKER_API_KEY", "api-key.txt"),
		XAI("CLANKER_XAI_API_KEY", "xai-key.txt"),
		ELEVENLABS("CLANKER_ELEVENLABS_API_KEY", "elevenlabs-key.txt");

		final String env;
		final String file;

		KeyKind(String env, String file) {
			this.env = env;
			this.file = file;
		}
	}

	// Brain
	public Provider provider = Provider.XAI;
	public String model = "";
	/** The OpenAI key, also used for {@link Provider#OPENAI_COMPATIBLE} endpoints that need one. */
	public String apiKey = "";
	public String xaiApiKey = "";
	/** Base URL for {@link Provider#OPENAI_COMPATIBLE} (e.g. Ollama or OpenRouter). */
	public String endpoint = "";
	/**
	 * Passed as {@code reasoning_effort} ("none", "minimal", "low", …). Lower effort answers faster, which matters more
	 * in a game than deep reasoning. Blank uses the model's default. A model that rejects the value gets the lowest one
	 * it supports instead.
	 */
	public String reasoningEffort = "low";
	/** Temperament description; blank uses {@link Personalities#DEFAULT}. Shapes how Clanker decides, never what it says. */
	public String personality = "";

	// Voice
	public VoiceProvider voiceProvider = VoiceProvider.XAI;
	public String xaiVoice = "rex";
	public String elevenLabsApiKey = "";
	/** An ElevenLabs voice id from their voice library. */
	public String elevenLabsVoice = "";
	public String elevenLabsModel = "eleven_v3";
	/** 0.7 to 1.5. */
	public double voiceSpeed = 1.0;

	// Bot
	public String botName = "Clanker";

	// Behavior
	public Talkativeness talkativeness = Talkativeness.NORMAL;
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

	private static ServerConfig instance;

	/** Which key the current brain provider uses. */
	public KeyKind brainKey() {
		return provider == Provider.XAI ? KeyKind.XAI : KeyKind.OPENAI;
	}

	/** The key the brain uses. Returns an empty string if none is set. */
	public String resolveApiKey() {
		return resolveKey(brainKey());
	}

	/** Where {@link #resolveApiKey()} found the key, for display. Never includes the key itself. */
	public String apiKeySource() {
		return keySource(brainKey());
	}

	/**
	 * A key: its env var ({@code CLANKER_API_KEY}, {@code CLANKER_XAI_API_KEY}, {@code CLANKER_ELEVENLABS_API_KEY}),
	 * then the setting, then {@code ~/.clanker/<file>}. Returns an empty string if none is set.
	 */
	public String resolveKey(KeyKind kind) {
		String env = System.getenv(kind.env);
		if (env != null && !env.isBlank()) {
			return env.strip();
		}
		String setting = setting(kind);
		if (!setting.isBlank()) {
			return setting.strip();
		}
		Path file = KEY_DIR.resolve(kind.file);
		if (Files.isRegularFile(file)) {
			try {
				return Files.readString(file).strip();
			} catch (IOException e) {
				Clanker.LOGGER.error("Failed to read {}", file, e);
			}
		}
		return "";
	}

	/** Where {@link #resolveKey} found a key, for display. Never includes the key itself. */
	public String keySource(KeyKind kind) {
		String env = System.getenv(kind.env);
		if (env != null && !env.isBlank()) {
			return kind.env + " environment variable";
		}
		if (!setting(kind).isBlank()) {
			return "settings";
		}
		if (Files.isRegularFile(KEY_DIR.resolve(kind.file))) {
			return "~/.clanker/" + kind.file;
		}
		return "";
	}

	public void setKey(KeyKind kind, String key) {
		switch (kind) {
			case OPENAI -> apiKey = key;
			case XAI -> xaiApiKey = key;
			case ELEVENLABS -> elevenLabsApiKey = key;
		}
	}

	private String setting(KeyKind kind) {
		String value = switch (kind) {
			case OPENAI -> apiKey;
			case XAI -> xaiApiKey;
			case ELEVENLABS -> elevenLabsApiKey;
		};
		return value == null ? "" : value;
	}

	/** Which key a host voice provider needs, or null for the local voice. */
	public static KeyKind voiceKey(VoiceProvider voice) {
		return switch (voice) {
			case LOCAL -> null;
			case XAI -> KeyKind.XAI;
			case ELEVENLABS -> KeyKind.ELEVENLABS;
		};
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
					config.fillDefaults();
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

	/** Settings added after a config file was written come back as null from Gson. */
	private void fillDefaults() {
		ServerConfig defaults = new ServerConfig();
		if (provider == null) provider = defaults.provider;
		if (listenMode == null) listenMode = defaults.listenMode;
		if (talkativeness == null) talkativeness = defaults.talkativeness;
		if (voiceProvider == null) voiceProvider = defaults.voiceProvider;
		if (xaiVoice == null) xaiVoice = defaults.xaiVoice;
		if (xaiApiKey == null) xaiApiKey = "";
		if (elevenLabsApiKey == null) elevenLabsApiKey = "";
		if (elevenLabsVoice == null) elevenLabsVoice = "";
		if (elevenLabsModel == null) elevenLabsModel = defaults.elevenLabsModel;
		if (voiceSpeed <= 0) voiceSpeed = 1.0;
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
