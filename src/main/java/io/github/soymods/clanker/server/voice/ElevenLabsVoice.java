package io.github.soymods.clanker.server.voice;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * ElevenLabs text to speech, streamed as raw PCM. Its expressive models read free-form bracketed directions
 * ({@code [laughs]}, {@code [whispers]}).
 */
public class ElevenLabsVoice implements VoiceEngine {
	private static final String URL = "https://api.elevenlabs.io/v1/text-to-speech/";

	private final String apiKey;
	private final String voice;
	private final String model;
	private final double speed;

	public ElevenLabsVoice(String apiKey, String voice, String model, double speed) {
		this.apiKey = apiKey;
		this.voice = voice.strip();
		this.model = model.isBlank() ? "eleven_v3" : model.strip();
		// ElevenLabs accepts 0.7 to 1.2.
		this.speed = Math.max(0.7, Math.min(1.2, speed));
	}

	@Override
	public String prepare(String text) {
		return VoiceCues.forElevenLabs(text);
	}

	@Override
	public void speak(String text, Consumer<byte[]> onAudio, BooleanSupplier cancelled) throws IOException, InterruptedException {
		JsonObject settings = new JsonObject();
		settings.addProperty("speed", speed);
		JsonObject body = new JsonObject();
		body.addProperty("text", text);
		body.addProperty("model_id", model);
		body.add("voice_settings", settings);
		HttpRequest request = HttpRequest.newBuilder(URI.create(URL + URLEncoder.encode(voice, StandardCharsets.UTF_8)
						+ "/stream?output_format=pcm_" + SAMPLE_RATE))
				.timeout(Duration.ofSeconds(30))
				.header("xi-api-key", apiKey)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		VoiceEngine.stream(request, "ElevenLabs", onAudio, cancelled);
	}

	@Override
	public String model() {
		return model;
	}
}
