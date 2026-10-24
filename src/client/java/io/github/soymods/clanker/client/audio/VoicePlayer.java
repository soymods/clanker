package io.github.soymods.clanker.client.audio;

import io.github.soymods.clanker.Clanker;
import io.github.soymods.clanker.client.config.ClientConfig;
import io.github.soymods.clanker.client.natives.VoiceFiles;
import io.github.soymods.clanker.client.tts.Synthesizer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Plays what Clanker says, one line at a time, and remembers recent lines so the microphone can ignore Clanker's own
 * voice coming back through the speakers.
 */
public final class VoicePlayer {
	private static final long ECHO_WINDOW_MS = 20_000;

	/** {@code receivedAt} is when the server's speech arrived, for the timing log. */
	public record Line(int entityId, String speaker, String text, Synthesizer.Audio audio, long receivedAt) {
	}

	private record Spoken(String text, long at) {
	}

	private static final Deque<Line> queue = new ArrayDeque<>();
	private static final Deque<String> unshown = new ArrayDeque<>();
	private static final List<Spoken> recent = new ArrayList<>();
	private static final Set<String> speakerNames = new LinkedHashSet<>(List.of("Clanker"));
	private static int synthesizing;
	private static int epoch;
	private static Line current;
	private static VoiceSoundInstance playing;
	private static long quietSince;
	private static long startedAt;
	private static String lastError = "";
	private static boolean setupHintShown;

	private VoicePlayer() {
	}

	/** Called on the client thread when the server says Clanker spoke. */
	public static void onSpeech(int entityId, String text) {
		Minecraft client = Minecraft.getInstance();
		Entity entity = client.level == null ? null : client.level.getEntity(entityId);
		String speaker = entity == null ? "Clanker" : entity.getName().getString();
		speakerNames.add(speaker);
		recent.add(new Spoken(text, System.currentTimeMillis()));

		ClientConfig config = ClientConfig.get();
		if (config.ttsEnabled && !VoiceFiles.ttsReady(config) && !setupHintShown) {
			setupHintShown = true;
			VoiceInput.hint(client, VoiceInput.SETUP_HINT);
		}
		if (!config.ttsEnabled || !VoiceFiles.ttsReady(config)) {
			return;
		}
		if (!config.speechText) {
			unshown.add(text);
			while (unshown.size() > 16) {
				unshown.poll();
			}
		}
		synthesizing++;
		int lineEpoch = epoch;
		long receivedAt = System.currentTimeMillis();
		Synthesizer.speak(VoiceFiles.voiceId(config), config.ttsSpeed, text).whenComplete((audio, error) -> client.execute(() -> {
			synthesizing--;
			if (lineEpoch != epoch) {
				return; // cut off before it was ready
			}
			if (error != null) {
				lastError = error.getCause() != null ? error.getCause().getMessage() : error.getMessage();
				Clanker.LOGGER.warn("Couldn't speak a line", error);
				return;
			}
			lastError = "";
			Clanker.LOGGER.info("Synthesized a {}-character line in {}s", text.length(), seconds(System.currentTimeMillis() - receivedAt));
			queue.add(new Line(entityId, speaker, text, audio, receivedAt));
		}));
	}

	public static void tick(Minecraft client) {
		if (playing != null && !client.getSoundManager().isActive(playing) && !justStarted()) {
			playing = null;
			current = null;
			quietSince = System.currentTimeMillis();
		}
		// Wait while the player is talking, instead of talking over them.
		if (playing == null && !queue.isEmpty() && !VoiceInput.listening()) {
			current = queue.poll();
			playing = new VoiceSoundInstance(current.entityId(), current.audio(), ClientConfig.get().ttsVolume);
			startedAt = System.currentTimeMillis();
			client.getSoundManager().play(playing);
			Clanker.LOGGER.info("Started saying a line {}s after it arrived", seconds(startedAt - current.receivedAt()));
		}
		long now = System.currentTimeMillis();
		recent.removeIf(spoken -> now - spoken.at() > ECHO_WINDOW_MS);
	}

	static String seconds(long millis) {
		return String.format(java.util.Locale.ROOT, "%.2f", millis / 1000.0);
	}

	/** The sound engine picks new sounds up on its own tick; don't treat "not active yet" as finished. */
	private static boolean justStarted() {
		return System.currentTimeMillis() - startedAt < 250;
	}

	public static void clear(Minecraft client) {
		queue.clear();
		if (playing != null) {
			client.getSoundManager().stop(playing);
		}
		playing = null;
		current = null;
	}

	/**
	 * Stops Clanker's voice because the player started talking: the current line, queued ones and any still being
	 * synthesized. True if anything was cut off.
	 */
	public static boolean interrupt(Minecraft client) {
		boolean cut = playing != null || !queue.isEmpty() || synthesizing > 0;
		if (cut) {
			epoch++;
			clear(client);
			quietSince = System.currentTimeMillis();
		}
		return cut;
	}

	/**
	 * True (once) for the chat copy of a line being voiced, which stays off screen unless speech text is on. The
	 * server sends the speech just before the chat line.
	 */
	public static boolean takeUnshown(String text) {
		return unshown.remove(text);
	}

	/** Clanker is talking, or about to (counts a short tail for room echo). */
	public static boolean speaking() {
		return playing != null || synthesizing > 0 || !queue.isEmpty() || System.currentTimeMillis() - quietSince < 600;
	}

	/** The line being spoken right now, for subtitles. */
	public static Line current() {
		return current;
	}

	public static String lastError() {
		return lastError;
	}

	/** Names worth priming the speech model with, so "Clanker" isn't heard as "clinker". */
	public static Set<String> speakerNames() {
		return speakerNames;
	}

	/** True if a transcript is mostly words Clanker said in the last few seconds: its own voice, picked up again. */
	public static boolean isEcho(String transcript) {
		List<String> heard = words(transcript);
		if (heard.isEmpty()) {
			return false;
		}
		for (Spoken spoken : recent) {
			List<String> said = words(spoken.text());
			long overlap = heard.stream().filter(said::contains).count();
			if (overlap >= Math.max(2, heard.size() * 0.7)) {
				return true;
			}
		}
		return false;
	}

	private static List<String> words(String text) {
		List<String> words = new ArrayList<>();
		for (String word : text.toLowerCase().split("[^\\p{L}\\p{N}']+")) {
			if (!word.isEmpty()) {
				words.add(word);
			}
		}
		return words;
	}
}
