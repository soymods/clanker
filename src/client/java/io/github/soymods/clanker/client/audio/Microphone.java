package io.github.soymods.clanker.client.audio;

import io.github.soymods.clanker.Clanker;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Captures 16 kHz mono audio (what Whisper wants) on its own thread and hands it out in 20 ms frames.
 */
public final class Microphone implements AutoCloseable {
	public static final int SAMPLE_RATE = 16_000;
	public static final int FRAME_SAMPLES = SAMPLE_RATE / 50;
	private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);

	private final TargetDataLine line;
	private final Thread thread;
	private final Consumer<float[]> frames;
	private volatile boolean running = true;
	private volatile float level;

	private Microphone(TargetDataLine line, Consumer<float[]> frames) {
		this.line = line;
		this.frames = frames;
		this.thread = new Thread(this::run, "Clanker Microphone");
		this.thread.setDaemon(true);
	}

	/** Capture devices by name. The first entry, "", is the system default. */
	public static List<String> devices() {
		List<String> names = new ArrayList<>();
		names.add("");
		DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
		for (Mixer.Info mixer : AudioSystem.getMixerInfo()) {
			try {
				if (AudioSystem.getMixer(mixer).isLineSupported(info)) {
					names.add(mixer.getName());
				}
			} catch (RuntimeException ignored) {
			}
		}
		return names;
	}

	/** Opens the named device (or the default one) and starts delivering frames to {@code frames}. */
	public static Microphone open(String device, Consumer<float[]> frames) throws LineUnavailableException {
		DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
		TargetDataLine line = null;
		if (!device.isBlank()) {
			for (Mixer.Info mixer : AudioSystem.getMixerInfo()) {
				if (mixer.getName().equals(device) && AudioSystem.getMixer(mixer).isLineSupported(info)) {
					line = (TargetDataLine) AudioSystem.getMixer(mixer).getLine(info);
					break;
				}
			}
			if (line == null) {
				Clanker.LOGGER.warn("Microphone '{}' not found, using the default one", device);
			}
		}
		if (line == null) {
			line = (TargetDataLine) AudioSystem.getLine(info);
		}
		line.open(FORMAT, FRAME_SAMPLES * 2 * 8);
		line.start();
		Microphone microphone = new Microphone(line, frames);
		microphone.thread.start();
		return microphone;
	}

	/** Loudness of the last frame, 0..1 (RMS). */
	public float level() {
		return level;
	}

	private void run() {
		byte[] bytes = new byte[FRAME_SAMPLES * 2];
		while (running) {
			int read = 0;
			while (read < bytes.length && running) {
				int n = line.read(bytes, read, bytes.length - read);
				if (n <= 0) {
					break;
				}
				read += n;
			}
			if (read < bytes.length) {
				continue;
			}
			float[] frame = new float[FRAME_SAMPLES];
			double sum = 0;
			for (int i = 0; i < FRAME_SAMPLES; i++) {
				short sample = (short) ((bytes[i * 2] & 0xff) | (bytes[i * 2 + 1] << 8));
				frame[i] = sample / 32768f;
				sum += frame[i] * frame[i];
			}
			level = (float) Math.sqrt(sum / FRAME_SAMPLES);
			try {
				frames.accept(frame);
			} catch (RuntimeException e) {
				Clanker.LOGGER.warn("Microphone frame handler failed", e);
			}
		}
	}

	@Override
	public void close() {
		running = false;
		line.stop();
		line.close();
	}

	/** Frame loudness above which open mic counts it as speech. Sensitivity 0..1; higher hears quieter voices. */
	public static float threshold(float sensitivity) {
		return (float) Math.pow(10, -1 - 2 * Math.clamp(sensitivity, 0f, 1f));
	}
}
