package io.github.soymods.clanker.client.audio;

import io.github.soymods.clanker.Clanker;
import io.github.soymods.clanker.client.ClankerKeys;
import io.github.soymods.clanker.client.config.ClientConfig;
import io.github.soymods.clanker.client.natives.VoiceFiles;
import io.github.soymods.clanker.client.stt.Transcriber;
import io.github.soymods.clanker.network.UtterancePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Push-to-talk and open mic: records what the player says, transcribes it locally, and sends only the text to the
 * server. While Clanker is talking, open mic doesn't listen, and anything that sounds like Clanker's own words is
 * dropped.
 */
public final class VoiceInput {
	private static final int MAX_FRAMES = 50 * 30;
	private static final int MIN_FRAMES = 50 * 2 / 5;
	private static final int PREROLL_FRAMES = 15;
	private static final int START_FRAMES = 3;
	private static final int END_SILENCE_FRAMES = 40;
	private static final int OPEN_MIC_MAX_FRAMES = 50 * 15;

	private static Microphone microphone;
	private static String microphoneDevice;
	private static final Object lock = new Object();
	private static final List<float[]> recording = new ArrayList<>();
	private static final Deque<float[]> preroll = new ArrayDeque<>();
	private static volatile boolean recordingActive;
	private static volatile boolean openMicListening;
	private static volatile boolean suppressed;
	private static volatile float threshold;
	private static int loudFrames;
	private static int quietFrames;
	private static volatile boolean utteranceReady;

	private static boolean pttWasDown;
	private static long cutOffAt;
	private static int transcribing;
	private static boolean warnedSilent;
	private static long retryOpenAt;

	private VoiceInput() {
	}

	/** Listening right now (push-to-talk held, or open mic hearing speech). */
	public static boolean listening() {
		return recordingActive;
	}

	public static boolean transcribing() {
		return transcribing > 0;
	}

	public static void tick(Minecraft client) {
		ClientConfig config = ClientConfig.get();
		boolean inWorld = client.player != null && ClientPlayNetworking.canSend(UtterancePayload.TYPE);
		suppressed = VoicePlayer.speaking();
		threshold = Microphone.threshold(config.openMicSensitivity);

		switch (inWorld ? config.voiceMode : ClientConfig.VoiceMode.OFF) {
			case PUSH_TO_TALK -> pushToTalk(client, config);
			case OPEN_MIC -> {
				// The push-to-talk key still cuts Clanker off; open mic itself doesn't listen while it talks.
				boolean down = ClankerKeys.pushToTalk.isDown();
				if (down && !pttWasDown) {
					interrupt(client);
				}
				pttWasDown = down;
				openMic(client, config);
			}
			case OFF -> {
				closeMicrophone();
				pttWasDown = false;
			}
		}
		if (utteranceReady) {
			utteranceReady = false;
			finish(client, config);
		}
	}

	/** How to get to the voice download, for anyone who hasn't yet. */
	public static final String SETUP_HINT = "Voice isn't set up yet: type /clanker config, press Voice…, then Download (about 290 MB, once). "
			+ "Then hold V to talk, and Clanker talks back out loud.";

	private static void pushToTalk(Minecraft client, ClientConfig config) {
		boolean down = ClankerKeys.pushToTalk.isDown();
		if (down && !pttWasDown) {
			if (!VoiceFiles.sttReady(config)) {
				hint(client, SETUP_HINT);
			} else if (openMicrophone(client, config)) {
				interrupt(client);
				synchronized (lock) {
					recording.clear();
				}
				recordingActive = true;
			}
		}
		if (recordingActive && (!down || recordingSize() >= MAX_FRAMES)) {
			recordingActive = false;
			utteranceReady = true;
		}
		if (!down && !recordingActive) {
			// Only hold the microphone while the key is down.
			closeMicrophone();
		}
		pttWasDown = down;
	}

	private static void interrupt(Minecraft client) {
		if (VoicePlayer.interrupt(client)) {
			cutOffAt = System.currentTimeMillis();
		}
	}

	private static void openMic(Minecraft client, ClientConfig config) {
		if (!VoiceFiles.sttReady(config)) {
			closeMicrophone();
			return;
		}
		if (microphone == null && System.currentTimeMillis() >= retryOpenAt) {
			openMicListening = openMicrophone(client, config);
		} else if (microphone != null && !config.microphone.equals(microphoneDevice)) {
			closeMicrophone();
		}
	}

	private static boolean openMicrophone(Minecraft client, ClientConfig config) {
		if (microphone != null && config.microphone.equals(microphoneDevice)) {
			return true;
		}
		closeMicrophone();
		try {
			microphone = Microphone.open(config.microphone, VoiceInput::onFrame);
			microphoneDevice = config.microphone;
			return true;
		} catch (Exception e) {
			Clanker.LOGGER.warn("Couldn't open the microphone", e);
			hint(client, "Couldn't open the microphone: " + e.getMessage());
			retryOpenAt = System.currentTimeMillis() + 10_000;
			return false;
		}
	}

	private static void closeMicrophone() {
		if (microphone != null) {
			microphone.close();
			microphone = null;
			microphoneDevice = null;
		}
		openMicListening = false;
		recordingActive = false;
	}

	private static int recordingSize() {
		synchronized (lock) {
			return recording.size();
		}
	}

	/** Runs on the microphone thread. */
	private static void onFrame(float[] frame) {
		synchronized (lock) {
			if (!openMicListening) {
				if (recordingActive) {
					recording.add(frame);
				}
				return;
			}
			float level = rms(frame);
			if (!recordingActive) {
				preroll.add(frame);
				if (preroll.size() > PREROLL_FRAMES) {
					preroll.poll();
				}
				loudFrames = level > threshold && !suppressed ? loudFrames + 1 : 0;
				if (loudFrames >= START_FRAMES) {
					recording.clear();
					recording.addAll(preroll);
					preroll.clear();
					quietFrames = 0;
					recordingActive = true;
				}
				return;
			}
			recording.add(frame);
			quietFrames = level > threshold ? 0 : quietFrames + 1;
			if (quietFrames >= END_SILENCE_FRAMES || recording.size() >= OPEN_MIC_MAX_FRAMES) {
				recordingActive = false;
				loudFrames = 0;
				utteranceReady = true;
			}
		}
	}

	private static float rms(float[] frame) {
		double sum = 0;
		for (float sample : frame) {
			sum += sample * sample;
		}
		return (float) Math.sqrt(sum / frame.length);
	}

	private static void finish(Minecraft client, ClientConfig config) {
		float[] samples;
		synchronized (lock) {
			if (recording.size() < MIN_FRAMES) {
				recording.clear();
				return;
			}
			samples = new float[recording.size() * Microphone.FRAME_SAMPLES];
			for (int i = 0; i < recording.size(); i++) {
				System.arraycopy(recording.get(i), 0, samples, i * Microphone.FRAME_SAMPLES, Microphone.FRAME_SAMPLES);
			}
			recording.clear();
		}
		float peak = 0;
		for (float sample : samples) {
			peak = Math.max(peak, Math.abs(sample));
		}
		if (peak == 0) {
			if (!warnedSilent) {
				warnedSilent = true;
				hint(client, "Your microphone is sending silence. On macOS the official launcher can't use the mic (Prism Launcher can); "
						+ "on Windows, allow desktop apps to use the microphone in privacy settings.");
			}
			return;
		}
		transcribing++;
		long started = System.currentTimeMillis();
		String prompt = String.join(", ", VoicePlayer.speakerNames()) + ". Minecraft.";
		Transcriber.transcribe(VoiceFiles.whisperModel(config), samples, config.language, prompt)
				.whenComplete((text, error) -> client.execute(() -> {
					transcribing--;
					Clanker.LOGGER.info("Transcribed {}s of speech in {}s", VoicePlayer.seconds(samples.length * 1000L / Microphone.SAMPLE_RATE),
							VoicePlayer.seconds(System.currentTimeMillis() - started));
					if (error != null) {
						Clanker.LOGGER.warn("Transcription failed", error);
						hint(client, "Speech recognition failed: " + (error.getCause() != null ? error.getCause().getMessage() : error.getMessage()));
						return;
					}
					deliver(client, text);
				}));
	}

	/** Sends a transcript to the server, unless it's empty or Clanker hearing itself. */
	public static void deliver(Minecraft client, String text) {
		String clean = fixNames(Transcriber.clean(text), VoicePlayer.speakerNames());
		if (clean.isEmpty() || VoicePlayer.isEcho(clean) || !ClientPlayNetworking.canSend(UtterancePayload.TYPE)) {
			return;
		}
		if (clean.length() > 1000) {
			clean = clean.substring(0, 1000);
		}
		boolean cutOff = System.currentTimeMillis() - cutOffAt < 60_000;
		cutOffAt = 0;
		ClientPlayNetworking.send(new UtterancePayload(clean, cutOff));
		if (ClientConfig.get().speechText) {
			client.gui.hud.setOverlayMessage(Component.literal("You: " + clean), false);
		}
	}

	/**
	 * Whisper often mishears an unusual name by a letter ("Planker", "Clinker"). Words one or two edits away from a
	 * known bot name become that name, so the server sees who was addressed.
	 */
	public static String fixNames(String text, Iterable<String> names) {
		StringBuilder out = new StringBuilder();
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[\\p{L}']+").matcher(text);
		int last = 0;
		while (matcher.find()) {
			out.append(text, last, matcher.start());
			String word = matcher.group();
			String replacement = word;
			for (String name : names) {
				int allowed = name.length() >= 7 ? 2 : name.length() >= 5 ? 1 : 0;
				int distance = distance(word.toLowerCase(java.util.Locale.ROOT), name.toLowerCase(java.util.Locale.ROOT));
				if (distance > 0 && distance <= allowed) {
					replacement = name;
					break;
				}
			}
			out.append(replacement);
			last = matcher.end();
		}
		return out.append(text.substring(last)).toString();
	}

	private static int distance(String a, String b) {
		int[] previous = new int[b.length() + 1];
		int[] current = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) {
			previous[j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			current[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
			}
			int[] swap = previous;
			previous = current;
			current = swap;
		}
		return previous[b.length()];
	}

	static void hint(Minecraft client, String text) {
		client.gui.hud.getChat().addClientSystemMessage(Component.literal("[Clanker] " + text));
	}

	public static void stop() {
		closeMicrophone();
	}
}
