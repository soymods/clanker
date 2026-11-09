package io.github.soymods.clanker.server.voice;

import io.github.soymods.clanker.Clanker;
import io.github.soymods.clanker.config.ServerConfig;
import io.github.soymods.clanker.network.VoiceAudioPayload;
import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.brain.Spend;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Voices Clanker's lines on the host with a cloud voice, one line at a time and in order, streaming the audio to nearby
 * players as it arrives. When there's no host voice (local voice chosen, no key, a spend cap reached, the service
 * failing), lines go out as text and each player's own local voice speaks them instead.
 */
public class HostVoice {
	private static final AtomicInteger LINE_IDS = new AtomicInteger();
	private static final long MAX_FAILURE_BACKOFF_MS = 120_000;

	private final ClankerBot bot;
	private final ExecutorService worker;
	private volatile int epoch;
	private VoiceEngine engineOverride;
	private boolean localOnly;
	private int failures;
	private long localUntil;
	private int lines;
	private long characters;
	private double spent;
	private String lastError = "";

	public HostVoice(ClankerBot bot) {
		this.bot = bot;
		this.worker = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "Clanker voice (" + bot.name() + ")");
			thread.setDaemon(true);
			return thread;
		});
	}

	/** Never uses a cloud voice, whatever the settings say. For tests, which must not reach paid services. */
	public void setLocalOnly(boolean localOnly) {
		this.localOnly = localOnly;
	}

	/** Replaces the configured engine, for tests. */
	public void setEngineOverride(VoiceEngine engine) {
		this.engineOverride = engine;
	}

	/**
	 * Starts voicing a line (as the model wrote it, cues included). Returns its line id, or 0 if the host isn't voicing
	 * it and players should use their local voice.
	 */
	public int submit(String text) {
		ServerConfig config = ServerConfig.get();
		VoiceEngine engine = engine(config);
		if (engine == null || System.currentTimeMillis() < localUntil || Spend.capReached(config) != null) {
			return 0;
		}
		String prepared = engine.prepare(text);
		if (prepared.isBlank()) {
			return 0;
		}
		int id = LINE_IDS.incrementAndGet();
		int lineEpoch = epoch;
		ServerConfig.VoiceProvider provider = engineOverride != null ? ServerConfig.VoiceProvider.LOCAL : config.voiceProvider;
		worker.execute(() -> run(id, lineEpoch, engine, prepared, provider));
		return id;
	}

	private void run(int id, int lineEpoch, VoiceEngine engine, String text, ServerConfig.VoiceProvider provider) {
		if (lineEpoch != epoch) {
			return; // cut off before it started
		}
		long start = System.currentTimeMillis();
		long[] firstAudio = {0};
		boolean[] any = {false};
		try {
			engine.speak(text, pcm -> {
				if (firstAudio[0] == 0) {
					firstAudio[0] = System.currentTimeMillis();
				}
				any[0] = true;
				bot.server().execute(() -> bot.sendVoiceAudio(new VoiceAudioPayload(id, pcm, false, false)));
			}, () -> lineEpoch != epoch);
			bot.server().execute(() -> bot.sendVoiceAudio(new VoiceAudioPayload(id, new byte[0], true, false)));
			double usd = Spend.voiceCost(provider, engine.model(), text.length());
			bot.server().execute(() -> {
				failures = 0;
				lastError = "";
				lines++;
				characters += text.length();
				spent += usd;
				Spend.record(usd);
			});
			Clanker.LOGGER.info("{} voiced a {}-character line: first audio after {}s, done after {}s", bot.name(), text.length(),
					seconds(firstAudio[0] == 0 ? 0 : firstAudio[0] - start), seconds(System.currentTimeMillis() - start));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			Clanker.LOGGER.warn("{}: {}", bot.name(), message);
			boolean started = any[0];
			bot.server().execute(() -> {
				lastError = message;
				failures++;
				// Keep using the local voice for a while instead of failing on every line.
				localUntil = System.currentTimeMillis() + Math.min(MAX_FAILURE_BACKOFF_MS, 5_000L << Math.min(failures - 1, 5));
				bot.sendVoiceAudio(new VoiceAudioPayload(id, new byte[0], true, !started));
			});
		}
	}

	/** Stops the line being voiced and drops the queued ones, e.g. when a player talks over Clanker. */
	public void cancel() {
		epoch++;
	}

	public void shutdown() {
		epoch++;
		worker.shutdownNow();
	}

	/** Try the host voice again right away, e.g. after the host changes its settings. */
	public void resetBackoff() {
		failures = 0;
		localUntil = 0;
	}

	private VoiceEngine engine(ServerConfig config) {
		if (engineOverride != null) {
			return engineOverride;
		}
		if (localOnly) {
			return null;
		}
		ServerConfig.KeyKind kind = ServerConfig.voiceKey(config.voiceProvider);
		String key = kind == null ? "" : config.resolveKey(kind);
		if (key.isBlank()) {
			return null;
		}
		return switch (config.voiceProvider) {
			case LOCAL -> null;
			case XAI -> new XaiVoice(key, config.xaiVoice, config.voiceSpeed);
			case ELEVENLABS -> config.elevenLabsVoice.isBlank() ? null
					: new ElevenLabsVoice(key, config.elevenLabsVoice, config.elevenLabsModel, config.voiceSpeed);
		};
	}

	/** Which voice is in use, for status displays. */
	public String describe() {
		ServerConfig config = ServerConfig.get();
		if (engineOverride != null) {
			return "test voice";
		}
		if (engine(config) == null) {
			return config.voiceProvider == ServerConfig.VoiceProvider.LOCAL ? "each player's local voice"
					: "each player's local voice (" + config.voiceProvider.name().toLowerCase(Locale.ROOT) + " isn't set up)";
		}
		String voice = config.voiceProvider == ServerConfig.VoiceProvider.XAI ? "xAI " + config.xaiVoice : "ElevenLabs " + config.elevenLabsVoice;
		return System.currentTimeMillis() < localUntil ? voice + " (failing, using local voices for now)" : voice;
	}

	public String status() {
		return describe() + ": " + lines + " lines, " + characters + " characters, about $"
				+ String.format(Locale.ROOT, "%.4f", spent) + (lastError.isEmpty() ? "" : "; last error: " + lastError);
	}

	private static String seconds(long millis) {
		return String.format(Locale.ROOT, "%.2f", millis / 1000.0);
	}
}
