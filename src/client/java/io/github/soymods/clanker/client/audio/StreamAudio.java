package io.github.soymods.clanker.client.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A line's audio as it arrives: 16-bit mono samples appended from the network (host voice) or all at once (local
 * voice), read by the sound engine's thread while more is still coming.
 */
public final class StreamAudio {
	private final int sampleRate;
	private short[] samples = new short[0];
	private int length;
	private int position;
	private boolean finished;
	private boolean failed;

	public StreamAudio(int sampleRate) {
		this.sampleRate = sampleRate;
	}

	public int sampleRate() {
		return sampleRate;
	}

	/** Little-endian 16-bit samples. */
	public synchronized void append(byte[] pcm) {
		ensure(length + pcm.length / 2);
		ByteBuffer buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
		while (buffer.remaining() >= 2) {
			samples[length++] = buffer.getShort();
		}
	}

	public synchronized void append(short[] more) {
		ensure(length + more.length);
		System.arraycopy(more, 0, samples, length, more.length);
		length += more.length;
	}

	private void ensure(int size) {
		if (size > samples.length) {
			short[] bigger = new short[Math.max(size, samples.length * 2 + sampleRate)];
			System.arraycopy(samples, 0, bigger, 0, length);
			samples = bigger;
		}
	}

	public synchronized void finish() {
		finished = true;
	}

	/** The host couldn't voice it; the caller decides whether to fill it some other way. */
	public synchronized void fail() {
		failed = true;
	}

	public synchronized boolean failed() {
		return failed;
	}

	public synchronized boolean finished() {
		return finished;
	}

	/** Seconds of audio received so far. */
	public synchronized double bufferedSeconds() {
		return (double) length / sampleRate;
	}

	/** Seconds not yet played. */
	public synchronized double aheadSeconds() {
		return (double) (length - position) / sampleRate;
	}

	/** Whether everything has arrived and been played. */
	public synchronized boolean drained() {
		return finished && position >= length;
	}

	/**
	 * Up to {@code max} unplayed samples as a direct buffer for OpenAL; a little silence if more audio is due but hasn't
	 * arrived yet; or null once everything has been played.
	 */
	public synchronized ByteBuffer read(int max) {
		int count = Math.min(length - position, Math.max(1, max));
		if (count <= 0) {
			if (finished) {
				return null;
			}
			count = Math.min(Math.max(1, max), sampleRate / 50); // 20 ms of silence to wait on the network
			ByteBuffer silence = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < count; i++) {
				silence.putShort((short) 0);
			}
			return silence.flip();
		}
		ByteBuffer buffer = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < count; i++) {
			buffer.putShort(samples[position + i]);
		}
		position += count;
		return buffer.flip();
	}
}
