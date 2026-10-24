package io.github.soymods.clanker.client.tts;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.soymods.clanker.Clanker;
import io.github.soymods.clanker.client.natives.VoiceFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Text to speech with a Piper voice on one background thread. Lines are synthesized in the order they arrive.
 */
public final class Synthesizer {
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Clanker Piper");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY + 1);
		return thread;
	});

	/** Mono 16-bit samples. */
	public record Audio(short[] samples, int sampleRate) {
		public double seconds() {
			return (double) samples.length / sampleRate;
		}
	}

	private static PiperRuntime runtime;
	private static PiperRuntime.Voice voice;
	private static String loadedKey;

	private Synthesizer() {
	}

	public static CompletableFuture<Audio> speak(String voiceId, float speed, String text) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				PiperRuntime.Voice current = voice(voiceId, speed);
				return new Audio(runtime.speak(current, text), current.sampleRate());
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new IllegalStateException("Piper failed: " + e.getMessage(), e);
			}
		}, EXECUTOR);
	}

	/** Frees the voice and Piper's natives before the game exits. */
	public static void shutdown() {
		try {
			EXECUTOR.submit(() -> {
				if (voice != null) {
					runtime.closeVoice(voice);
					voice = null;
				}
				if (runtime != null) {
					try {
						runtime.close();
					} catch (Exception e) {
						Clanker.LOGGER.debug("Piper shutdown", e);
					}
					runtime = null;
				}
			}).get(5, java.util.concurrent.TimeUnit.SECONDS);
		} catch (Exception e) {
			Clanker.LOGGER.debug("Piper shutdown", e);
		}
		EXECUTOR.shutdownNow();
	}

	private static PiperRuntime.Voice voice(String voiceId, float speed) throws Exception {
		if (runtime == null) {
			long start = System.currentTimeMillis();
			runtime = PiperRuntime.load(VoiceFiles.piperRuntime());
			Clanker.LOGGER.info("Loaded Piper in {} ms", System.currentTimeMillis() - start);
		}
		String key = voiceId + "@" + speed;
		if (voice != null && !key.equals(loadedKey)) {
			runtime.closeVoice(voice);
			voice = null;
		}
		if (voice == null) {
			voice = runtime.loadVoice(VoiceFiles.voiceModel(voiceId), configFor(voiceId, speed));
			loadedKey = key;
		}
		return voice;
	}

	/**
	 * Piper reads its speaking rate from the voice's config ({@code inference.length_scale}), so a different speed is a
	 * copy of the config with that one value changed.
	 */
	private static Path configFor(String voiceId, float speed) throws IOException {
		Path original = VoiceFiles.voiceConfig(voiceId);
		if (Math.abs(speed - 1f) < 0.01f) {
			return original;
		}
		JsonObject config = JsonParser.parseString(Files.readString(original)).getAsJsonObject();
		JsonObject inference = config.has("inference") ? config.getAsJsonObject("inference") : new JsonObject();
		double base = inference.has("length_scale") ? inference.get("length_scale").getAsDouble() : 1.0;
		inference.addProperty("length_scale", base / speed);
		config.add("inference", inference);
		Path copy = VoiceFiles.DIR.resolve("speed").resolve(voiceId + "-" + String.format(Locale.ROOT, "%.2f", speed) + ".onnx.json");
		Files.createDirectories(copy.getParent());
		Files.writeString(copy, config.toString());
		return copy;
	}
}
