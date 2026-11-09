package io.github.soymods.clanker.client.audio;

import io.github.soymods.clanker.Clanker;
import io.github.soymods.clanker.client.config.ClientConfig;
import io.github.soymods.clanker.client.natives.VoiceFiles;
import io.github.soymods.clanker.client.tts.Synthesizer;
import io.github.soymods.clanker.network.VoiceAudioPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plays what Clanker says, one line at a time and in the order it was said, and remembers recent lines so the
 * microphone can ignore Clanker's own voice coming back through the speakers.
 * <p>
 * A line's audio comes either from the host (streamed in pieces, see {@link VoiceAudioPayload}) or from this client's
 * own Piper voice. Either way the line takes its place in the queue as soon as it's said, and starts playing once
 * enough of its audio is here.
 */
public final class VoicePlayer {
	private static final long ECHO_WINDOW_MS = 20_000;
	/** Host audio buffered before a line starts, to ride out network hiccups. */
	private static final double START_BUFFER_SECONDS = 0.3;
	/** A line whose audio never shows up is skipped after this. */
	private static final long GIVE_UP_MS = 20_000;

	/** {@code receivedAt} is when the server's speech arrived, for the timing log. */
	public record Line(int entityId, String speaker, String text, StreamAudio audio, long receivedAt, int lineId) {
	}

	private record Spoken(String text, long at) {
	}

	private static final Deque<Line> queue = new ArrayDeque<>();
	/** Host-voiced lines waiting for their audio, by line id. */
	private static final Map<Integer, Line> hostLines = new HashMap<>();
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

	/** A line for this client's own voice, as from a host without a voice of its own. */
	public static void onSpeech(int entityId, String text) {
		onSpeech(entityId, text, 0);
	}

	/** Called on the client thread when the server says Clanker spoke. {@code lineId} > 0 means the host voices it. */
	public static void onSpeech(int entityId, String text, int lineId) {
		Minecraft client = Minecraft.getInstance();
		Entity entity = client.level == null ? null : client.level.getEntity(entityId);
		String speaker = entity == null ? "Clanker" : entity.getName().getString();
		speakerNames.add(speaker);
		if (!text.isEmpty()) {
			recent.add(new Spoken(text, System.currentTimeMillis()));
		}

		ClientConfig config = ClientConfig.get();
		boolean hostVoiced = lineId > 0;
		if (!config.ttsEnabled || (!hostVoiced && !VoiceFiles.ttsReady(config))) {
			if (config.ttsEnabled && !setupHintShown) {
				setupHintShown = true;
				VoiceInput.hint(client, VoiceInput.SETUP_HINT);
			}
			return;
		}
		if (!config.speechText && !text.isEmpty()) {
			unshown.add(text);
			while (unshown.size() > 16) {
				unshown.poll();
			}
		}
		StreamAudio audio = new StreamAudio(hostVoiced ? VoiceAudioPayload.SAMPLE_RATE : 22_050);
		Line line = new Line(entityId, speaker, text, audio, System.currentTimeMillis(), lineId);
		queue.add(line);
		if (hostVoiced) {
			hostLines.put(lineId, line);
		} else {
			synthesizeLocally(client, line);
		}
	}

	/** A piece of host-voiced audio. */
	public static void onAudio(int lineId, byte[] pcm, boolean end, boolean failed) {
		Line line = hostLines.get(lineId);
		if (line == null) {
			return; // cut off, or we never got the line itself
		}
		if (failed) {
			hostLines.remove(lineId);
			line.audio().fail();
			if (!line.text().isEmpty() && VoiceFiles.ttsReady(ClientConfig.get())) {
				synthesizeLocally(Minecraft.getInstance(), line);
			} else {
				line.audio().finish();
			}
			return;
		}
		line.audio().append(pcm);
		if (end) {
			hostLines.remove(lineId);
			line.audio().finish();
		}
	}

	private static void synthesizeLocally(Minecraft client, Line line) {
		ClientConfig config = ClientConfig.get();
		synthesizing++;
		int lineEpoch = epoch;
		Synthesizer.speak(VoiceFiles.voiceId(config), config.ttsSpeed, line.text()).whenComplete((audio, error) -> client.execute(() -> {
			synthesizing--;
			if (lineEpoch != epoch) {
				return; // cut off before it was ready
			}
			if (error != null) {
				lastError = error.getCause() != null ? error.getCause().getMessage() : error.getMessage();
				Clanker.LOGGER.warn("Couldn't speak a line", error);
				line.audio().finish();
				return;
			}
			lastError = "";
			Clanker.LOGGER.info("Synthesized a {}-character line in {}s", line.text().length(), seconds(System.currentTimeMillis() - line.receivedAt()));
			if (audio.sampleRate() == line.audio().sampleRate()) {
				line.audio().append(audio.samples());
				line.audio().finish();
			} else {
				// Rare: a voice with a different rate. Swap in a line with the right format, same place in the queue.
				StreamAudio replacement = new StreamAudio(audio.sampleRate());
				replacement.append(audio.samples());
				replacement.finish();
				replace(line, new Line(line.entityId(), line.speaker(), line.text(), replacement, line.receivedAt(), line.lineId()));
			}
		}));
	}

	private static void replace(Line old, Line replacement) {
		List<Line> lines = new ArrayList<>(queue);
		int index = lines.indexOf(old);
		if (index >= 0) {
			lines.set(index, replacement);
			queue.clear();
			queue.addAll(lines);
		}
	}

	public static void tick(Minecraft client) {
		if (playing != null && !client.getSoundManager().isActive(playing) && !justStarted()) {
			playing = null;
			current = null;
			quietSince = System.currentTimeMillis();
		}
		// Wait while the player is talking, instead of talking over them.
		Line next = queue.peek();
		if (playing == null && next != null && !VoiceInput.listening()) {
			StreamAudio audio = next.audio();
			if (audio.drained() || System.currentTimeMillis() - next.receivedAt() > GIVE_UP_MS && audio.bufferedSeconds() == 0) {
				queue.poll(); // nothing to play
				hostLines.remove(next.lineId());
			} else if (audio.finished() || (next.lineId() > 0 && !audio.failed() && audio.aheadSeconds() >= START_BUFFER_SECONDS)) {
				current = queue.poll();
				playing = new VoiceSoundInstance(current.entityId(), audio, ClientConfig.get().ttsVolume);
				startedAt = System.currentTimeMillis();
				client.getSoundManager().play(playing);
				Clanker.LOGGER.info("Started saying a line {}s after it arrived", seconds(startedAt - current.receivedAt()));
			}
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
		hostLines.clear();
		if (playing != null) {
			client.getSoundManager().stop(playing);
		}
		playing = null;
		current = null;
	}

	/**
	 * Stops Clanker's voice because the player started talking: the current line, queued ones and any still being
	 * synthesized or streamed. True if anything was cut off.
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
