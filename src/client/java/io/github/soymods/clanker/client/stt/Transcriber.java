package io.github.soymods.clanker.client.stt;

import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;
import io.github.givimad.whisperjni.WhisperSamplingStrategy;
import io.github.soymods.clanker.Clanker;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Speech to text with whisper.cpp on one low-priority background thread, capped to a few cores so the game keeps
 * its frame rate.
 */
public final class Transcriber {
	public static final int SAMPLE_RATE = 16_000;
	/** Whisper's markers for non-speech, e.g. "[BLANK_AUDIO]", "(wind blowing)", "*coughs*". */
	private static final Pattern NON_SPEECH = Pattern.compile("\\[[^\\]]*\\]|\\([^)]*\\)|\\*[^*]*\\*|♪");

	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Clanker Whisper");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY + 1);
		return thread;
	});

	private static WhisperJNI whisper;
	private static WhisperContext context;
	private static Path loadedModel;

	private Transcriber() {
	}

	/**
	 * Transcribes 16 kHz mono samples in [-1, 1]. {@code prompt} nudges spelling (names, Minecraft words).
	 * Completes with an empty string when nothing was said.
	 */
	public static CompletableFuture<String> transcribe(Path model, float[] samples, String language, String prompt) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				WhisperContext ctx = context(model);
				WhisperFullParams params = new WhisperFullParams(WhisperSamplingStrategy.GREEDY);
				params.nThreads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
				params.language = language.isBlank() ? "en" : language;
				params.detectLanguage = params.language.equals("auto");
				params.noTimestamps = true;
				params.suppressBlank = true;
				params.suppressNonSpeechTokens = true;
				params.printProgress = false;
				params.printRealtime = false;
				params.printSpecial = false;
				params.printTimestamps = false;
				params.initialPrompt = prompt;
				int result = whisper.full(ctx, params, samples, samples.length);
				if (result != 0) {
					throw new IllegalStateException("whisper returned " + result);
				}
				StringBuilder text = new StringBuilder();
				int segments = whisper.fullNSegments(ctx);
				for (int i = 0; i < segments; i++) {
					text.append(whisper.fullGetSegmentText(ctx, i));
				}
				return clean(text.toString());
			} catch (IOException e) {
				throw new IllegalStateException("Couldn't load the speech model: " + e.getMessage(), e);
			}
		}, EXECUTOR);
	}

	public static String clean(String text) {
		return NON_SPEECH.matcher(text).replaceAll(" ").replaceAll("\\s+", " ").strip();
	}

	/** Frees the model, e.g. after switching to a different size. Runs after any transcription in progress. */
	public static void unload() {
		EXECUTOR.execute(Transcriber::free);
	}

	/** Frees native memory before the game exits, so whisper.cpp isn't torn down mid-use by process exit. */
	public static void shutdown() {
		try {
			EXECUTOR.submit(Transcriber::free).get(5, java.util.concurrent.TimeUnit.SECONDS);
		} catch (Exception e) {
			Clanker.LOGGER.debug("Whisper shutdown", e);
		}
		EXECUTOR.shutdownNow();
	}

	private static void free() {
		if (context != null) {
			context.close();
			context = null;
			loadedModel = null;
		}
	}

	private static WhisperContext context(Path model) throws IOException {
		if (whisper == null) {
			WhisperJNI.loadLibrary(message -> Clanker.LOGGER.debug("whisper-jni: {}", message));
			WhisperJNI.setLibraryLogger(message -> Clanker.LOGGER.debug("whisper: {}", message));
			whisper = new WhisperJNI();
		}
		if (context != null && !model.equals(loadedModel)) {
			context.close();
			context = null;
		}
		if (context == null) {
			long start = System.currentTimeMillis();
			context = whisper.init(model);
			loadedModel = model;
			Clanker.LOGGER.info("Loaded speech model {} in {} ms", model.getFileName(), System.currentTimeMillis() - start);
		}
		return context;
	}
}
