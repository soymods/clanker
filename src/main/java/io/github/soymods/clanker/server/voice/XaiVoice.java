package io.github.soymods.clanker.server.voice;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** xAI's text to speech ({@code POST /v1/tts}), which reads inline sound tags and wrapping tags. */
public class XaiVoice implements VoiceEngine {
	/** Built-in voices, from {@code GET /v1/tts/voices} (Nov 2026). Custom voice ids work too. */
	public static final List<String> VOICES = List.of("altair", "ara", "atlas", "aurora", "carina", "castor", "celeste", "cosmo", "eve",
			"helios", "helix", "iris", "kepler", "leo", "liora", "lumen", "luna", "lux", "naksh", "orion", "perseus", "rex", "rigel",
			"sal", "sirius", "ursa", "zagan", "zenith");
	private static final String URL = "https://api.x.ai/v1/tts";

	private final String apiKey;
	private final String voice;
	private final double speed;

	public XaiVoice(String apiKey, String voice, double speed) {
		this.apiKey = apiKey;
		this.voice = voice.isBlank() ? "rex" : voice.strip();
		this.speed = Math.max(0.7, Math.min(1.5, speed));
	}

	@Override
	public String prepare(String text) {
		return VoiceCues.forXai(text);
	}

	@Override
	public void speak(String text, Consumer<byte[]> onAudio, BooleanSupplier cancelled) throws IOException, InterruptedException {
		JsonObject format = new JsonObject();
		format.addProperty("codec", "pcm");
		format.addProperty("sample_rate", SAMPLE_RATE);
		JsonObject body = new JsonObject();
		body.addProperty("text", text);
		body.addProperty("voice_id", voice);
		body.addProperty("language", "en");
		body.add("output_format", format);
		body.addProperty("speed", speed);
		body.addProperty("optimize_streaming_latency", 1);
		HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
				.timeout(Duration.ofSeconds(30))
				.header("Authorization", "Bearer " + apiKey)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		VoiceEngine.stream(request, "xAI", onAudio, cancelled);
	}

	@Override
	public String model() {
		return "xai-tts";
	}
}
