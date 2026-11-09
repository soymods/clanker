package io.github.soymods.clanker.server.voice;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A cloud text-to-speech service the host uses for Clanker's voice. Audio is always 16-bit little-endian mono PCM at
 * {@link #SAMPLE_RATE}. Calls block, and run on the bot's voice thread.
 */
public interface VoiceEngine {
	int SAMPLE_RATE = 24_000;
	/** About 100 ms of audio per chunk handed on. */
	int CHUNK_BYTES = SAMPLE_RATE / 10 * 2;
	HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	/** The text to send, with cues converted to this service's dialect. */
	String prepare(String text);

	/** Synthesizes {@code text} (already prepared), handing over audio as it arrives, until done or {@code cancelled}. */
	void speak(String text, Consumer<byte[]> onAudio, BooleanSupplier cancelled) throws IOException, InterruptedException;

	/** For pricing. */
	String model();

	/** Sends a request and streams the audio body to {@code onAudio} in whole-sample chunks. */
	static void stream(HttpRequest request, String service, Consumer<byte[]> onAudio, BooleanSupplier cancelled)
			throws IOException, InterruptedException {
		HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream body = response.body()) {
			if (response.statusCode() != 200) {
				String message = new String(body.readNBytes(2000), StandardCharsets.UTF_8).replaceAll("\\s+", " ").strip();
				throw new VoiceException(response.statusCode(), service + " voice failed (" + response.statusCode() + "): " + message);
			}
			byte[] buffer = new byte[CHUNK_BYTES];
			int filled = 0;
			while (!cancelled.getAsBoolean()) {
				int read = body.read(buffer, filled, buffer.length - filled);
				if (read < 0) {
					break;
				}
				filled += read;
				if (filled == buffer.length) {
					onAudio.accept(buffer.clone());
					filled = 0;
				}
			}
			if (filled > 1 && !cancelled.getAsBoolean()) {
				byte[] rest = new byte[filled & ~1];
				System.arraycopy(buffer, 0, rest, 0, rest.length);
				onAudio.accept(rest);
			}
		}
	}

	class VoiceException extends IOException {
		private final int status;

		public VoiceException(int status, String message) {
			super(message);
			this.status = status;
		}

		public int status() {
			return status;
		}
	}
}
