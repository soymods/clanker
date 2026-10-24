package io.github.soymods.clanker.client.config;

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
 * Per-player settings, stored in {@code config/clanker-client.json}.
 */
public class ClientConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("clanker-client.json");

	public enum VoiceMode { PUSH_TO_TALK, OPEN_MIC, OFF }

	public enum WhisperModel { TINY, BASE, SMALL }

	// Voice input
	public VoiceMode voiceMode = VoiceMode.PUSH_TO_TALK;
	/** Mixer name from the system's audio devices. Empty uses the default microphone. */
	public String microphone = "";
	/** 0..1, how loud speech must be before open mic starts listening. */
	public float openMicSensitivity = 0.5f;
	public WhisperModel whisperModel = WhisperModel.BASE;
	/** Whisper language code ("en", "de", …) or "auto". English uses the smaller English-only models. */
	public String language = "en";

	// Voice output
	/** Speak Clanker's lines out loud (needs the voice files). */
	public boolean ttsEnabled = true;
	/** A Piper voice id such as en_US-lessac-medium. Empty uses the default voice. */
	public String ttsVoice = "";
	public float ttsVolume = 1.0f;
	public float ttsSpeed = 1.0f;

	// Display
	/**
	 * Show speech as text too: subtitles, your own transcript, and the chat copy of lines Clanker says out loud. Off
	 * keeps the screen clear; lines that can't be voiced still show in chat.
	 */
	public boolean speechText = false;
	public boolean thinkingIndicator = true;
	public boolean debugHud = false;

	private static ClientConfig instance;

	public static ClientConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	public static ClientConfig load() {
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				ClientConfig config = GSON.fromJson(reader, ClientConfig.class);
				if (config != null) {
					instance = config;
					return config;
				}
			} catch (IOException | RuntimeException e) {
				Clanker.LOGGER.error("Failed to read {}, using defaults", PATH, e);
			}
		}
		instance = new ClientConfig();
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
