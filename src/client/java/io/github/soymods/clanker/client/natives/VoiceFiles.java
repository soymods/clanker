package io.github.soymods.clanker.client.natives;

import io.github.soymods.clanker.client.config.ClientConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the voice files live ({@code <game dir>/clanker/models}) and where they come from.
 */
public final class VoiceFiles {
	public static final Path DIR = FabricLoader.getInstance().getGameDir().resolve("clanker").resolve("models");

	/** Piper's natives are too big to ship in the mod jar, so they're fetched from Maven Central like the voices. */
	public static final String PIPER_VERSION = "1.4.1";
	private static final String PIPER_SHA256 = "5b85bcb408eaa9a10e405fe25da1ba5cc0e0064e270822ac4b4b8f1542ca4de9";
	private static final long PIPER_SIZE = 79_910_599;
	private static final long VOICE_SIZE = 63_201_294;

	public static final String DEFAULT_VOICE = "en_US-lessac-medium";

	/** Piper voices offered in the settings screen. Any other voice id from rhasspy/piper-voices also works. */
	public static final List<String> VOICES = List.of(
			"en_US-lessac-medium", "en_US-ryan-medium", "en_US-amy-medium", "en_US-joe-medium",
			"en_US-hfc_female-medium", "en_US-hfc_male-medium", "en_GB-alan-medium", "en_GB-northern_english_male-medium");

	private VoiceFiles() {
	}

	// Speech to text

	public static String whisperFileName(ClientConfig config) {
		boolean english = config.language.isBlank() || config.language.equalsIgnoreCase("en");
		return "ggml-" + config.whisperModel.name().toLowerCase() + (english ? ".en" : "") + ".bin";
	}

	public static Path whisperModel(ClientConfig config) {
		return DIR.resolve(whisperFileName(config));
	}

	public static long whisperSize(ClientConfig.WhisperModel model) {
		return switch (model) {
			case TINY -> 77_700_000;
			case BASE -> 147_960_000;
			case SMALL -> 487_610_000;
		};
	}

	public static Downloads.Item whisperItem(ClientConfig config) {
		String name = whisperFileName(config);
		return new Downloads.Item("speech model (" + name + ")", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/" + name,
				whisperModel(config), whisperSize(config.whisperModel), null);
	}

	// Text to speech

	public static Path piperRuntime() {
		return DIR.resolve("piper-jni-" + PIPER_VERSION + ".jar");
	}

	public static String voiceId(ClientConfig config) {
		return config.ttsVoice.isBlank() ? DEFAULT_VOICE : config.ttsVoice.strip();
	}

	public static Path voiceModel(String id) {
		return DIR.resolve(id + ".onnx");
	}

	public static Path voiceConfig(String id) {
		return DIR.resolve(id + ".onnx.json");
	}

	/** "en_US-lessac-medium" lives at en/en_US/lessac/medium/ in the voices repository. */
	private static String voiceUrl(String id, String extension) {
		String[] parts = id.split("-");
		if (parts.length != 3) {
			throw new IllegalArgumentException("Voice ids look like en_US-lessac-medium, not " + id);
		}
		String locale = parts[0];
		String language = locale.split("_")[0];
		return "https://huggingface.co/rhasspy/piper-voices/resolve/main/" + language + "/" + locale + "/" + parts[1] + "/" + parts[2]
				+ "/" + id + extension;
	}

	// What's missing

	public static boolean sttReady(ClientConfig config) {
		return Files.isRegularFile(whisperModel(config));
	}

	public static boolean ttsReady(ClientConfig config) {
		String voice = voiceId(config);
		return Files.isRegularFile(piperRuntime()) && Files.isRegularFile(voiceModel(voice)) && Files.isRegularFile(voiceConfig(voice));
	}

	public static List<Downloads.Item> missing(ClientConfig config) {
		List<Downloads.Item> items = new ArrayList<>();
		if (config.voiceMode != ClientConfig.VoiceMode.OFF && !sttReady(config)) {
			items.add(whisperItem(config));
		}
		if (config.ttsEnabled) {
			if (!Files.isRegularFile(piperRuntime())) {
				items.add(new Downloads.Item("voice engine (Piper)",
						"https://repo1.maven.org/maven2/io/github/givimad/piper-jni/" + PIPER_VERSION + "/piper-jni-" + PIPER_VERSION + ".jar",
						piperRuntime(), PIPER_SIZE, PIPER_SHA256));
			}
			String voice = voiceId(config);
			if (!Files.isRegularFile(voiceConfig(voice))) {
				items.add(new Downloads.Item("voice settings (" + voice + ")", voiceUrl(voice, ".onnx.json"), voiceConfig(voice), 5_000, null));
			}
			if (!Files.isRegularFile(voiceModel(voice))) {
				items.add(new Downloads.Item("voice (" + voice + ")", voiceUrl(voice, ".onnx"), voiceModel(voice), VOICE_SIZE, null));
			}
		}
		return items;
	}

	public static long size(List<Downloads.Item> items) {
		return items.stream().mapToLong(Downloads.Item::size).sum();
	}
}
