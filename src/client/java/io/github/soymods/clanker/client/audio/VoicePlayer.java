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

	public record Line(int entityId, String speaker, String text, Synthesizer.Audio audio) {
	}

	private record Spoken(String text, long at) {
	}

	private static final Deque<Line> queue = new ArrayDeque<>();
	private static final List<Spoken> recent = new ArrayList<>();
	private static final Set<String> speakerNames = new LinkedHashSet<>(List.of("Clanker"));
	private static int synthesizing;
	private static Line current;
	private static VoiceSoundInstance playing;
	private static long quietSince;
	private static long startedAt;
	private static String lastError = "";

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
		if (!config.ttsEnabled || !VoiceFiles.ttsReady(config)) {
			return;
		}
		synthesizing++;
		Synthesizer.speak(VoiceFiles.voiceId(config), config.ttsSpeed, text).whenComplete((audio, error) -> client.execute(() -> {
			synthesizing--;
			if (error != null) {
				lastError = error.getCause() != null ? error.getCause().getMessage() : error.getMessage();
				Clanker.LOGGER.warn("Couldn't speak a line", error);
				return;
			}
			lastError = "";
			queue.add(new Line(entityId, speaker, text, audio));
		}));
	}

	public static void tick(Minecraft client) {
		if (playing != null && !client.getSoundManager().isActive(playing) && !justStarted()) {
			playing = null;
			current = null;
			quietSince = System.currentTimeMillis();
		}
		if (playing == null && !queue.isEmpty()) {
			current = queue.poll();
			playing = new VoiceSoundInstance(current.entityId(), current.audio(), ClientConfig.get().ttsVolume);
			startedAt = System.currentTimeMillis();
			client.getSoundManager().play(playing);
		}
		long now = System.currentTimeMillis();
		recent.removeIf(spoken -> now - spoken.at() > ECHO_WINDOW_MS);
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
