package io.github.soymods.clanker.test;

import io.github.soymods.clanker.client.audio.VoiceInput;
import io.github.soymods.clanker.client.audio.VoicePlayer;
import io.github.soymods.clanker.client.config.ClientConfig;
import io.github.soymods.clanker.client.natives.Downloads;
import io.github.soymods.clanker.client.natives.VoiceFiles;
import io.github.soymods.clanker.client.stt.Transcriber;
import io.github.soymods.clanker.client.tts.Synthesizer;
import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerBots;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The whole voice loop with real models: Piper says a line, Whisper hears it, the text reaches Clanker's brain, and
 * Clanker's own line plays from its position with a subtitle. Skipped unless CLANKER_VOICE_TEST=1, since the first run
 * downloads about 290 MB into the test game directory.
 */
public class ClankerVoiceGameTests implements FabricClientGameTest {
	private static final boolean ENABLED = "1".equals(System.getenv("CLANKER_VOICE_TEST"));
	private static final String LINE = "Clanker, come over here and help me chop some wood.";

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!ENABLED) {
			return;
		}
		ClientConfig config = ClientConfig.get();
		config.voiceMode = ClientConfig.VoiceMode.PUSH_TO_TALK;
		config.ttsEnabled = true;
		config.language = "en";

		// The test game directory is wiped between runs; keep the downloads in build/voice-cache.
		Path cache = FabricLoader.getInstance().getGameDir().toAbsolutePath().getParent().getParent().resolve("voice-cache");
		copyAll(cache, VoiceFiles.DIR);
		var missing = VoiceFiles.missing(config);
		if (!missing.isEmpty()) {
			Downloads.Job job = Downloads.start(missing);
			context.waitFor(client -> job.finished(), 20 * 60 * 15);
			if (job.error() != null) {
				throw new AssertionError("download failed: " + job.error());
			}
			copyAll(VoiceFiles.DIR, cache);
		}
		if (!VoiceFiles.sttReady(config) || !VoiceFiles.ttsReady(config)) {
			throw new AssertionError("voice files missing after download");
		}

		// Text to speech to text, off the game thread like in play.
		Synthesizer.Audio audio = join(Synthesizer.speak(VoiceFiles.voiceId(config), 1.0f, LINE));
		if (audio.seconds() < 1.5) {
			throw new AssertionError("speech too short: " + audio.seconds() + " s");
		}
		long start = System.currentTimeMillis();
		String heard = join(Transcriber.transcribe(VoiceFiles.whisperModel(config), resample(audio, 16_000), "en", "Clanker. Minecraft."));
		System.out.println("[voice test] Piper " + String.format(Locale.ROOT, "%.1f", audio.seconds()) + " s -> Whisper (" + (System.currentTimeMillis() - start)
				+ " ms): " + heard);
		String lower = VoiceInput.fixNames(heard, java.util.List.of("Clanker")).toLowerCase(Locale.ROOT);
		if (!lower.contains("clanker") || !lower.contains("wood")) {
			throw new AssertionError("Whisper heard: " + heard);
		}

		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			FakeProvider provider = FakeProvider.silent();
			int botId = singleplayer.getServer().computeOnServer(server -> {
				ServerPlayer host = server.getPlayerList().getPlayers().getFirst();
				Vec3 pos = host.position().add(host.getLookAngle().multiply(1, 0, 1).normalize().scale(4));
				try {
					ClankerBot bot = ClankerBots.spawn(server, host.level(), pos, host.getYRot() + 180, 0, "Clanker");
					bot.brain().setProviderOverride(provider);
					return bot.player().getId();
				} catch (ClankerBots.SpawnException e) {
					throw new AssertionError(e.getMessage());
				}
			});
			context.waitTicks(40);

			// What the player "said" goes to the server as text.
			context.runOnClient(client -> VoiceInput.deliver(client, heard));
			singleplayer.getServer().waitFor(server -> provider.anyRequestContains("said (voice): \"Clanker"), 20 * 30);

			// Clanker's own echo is dropped.
			context.runOnClient(client -> VoicePlayer.onSpeech(botId, "Sure, I'll grab my axe and meet you by the trees."));
			context.waitFor(client -> VoicePlayer.current() != null, 20 * 30);
			context.waitTicks(10);
			context.takeScreenshot("clanker-voice-subtitle");
			if (VoicePlayer.isEcho("") || !VoicePlayer.isEcho("sure I'll grab my axe and meet you by the trees")) {
				throw new AssertionError("echo filter");
			}
			context.waitFor(client -> VoicePlayer.current() == null, 20 * 30);

			singleplayer.getServer().runOnServer(server -> ClankerBots.first().ifPresent(ClankerBots::despawn));
		}
	}

	/** Linear resampling, good enough to feed Piper's 22 kHz output to Whisper's 16 kHz input. */
	private static float[] resample(Synthesizer.Audio audio, int rate) {
		short[] in = audio.samples();
		int length = (int) ((long) in.length * rate / audio.sampleRate());
		float[] out = new float[length];
		double step = (double) audio.sampleRate() / rate;
		for (int i = 0; i < length; i++) {
			double position = i * step;
			int index = (int) position;
			double fraction = position - index;
			double a = in[Math.min(index, in.length - 1)];
			double b = in[Math.min(index + 1, in.length - 1)];
			out[i] = (float) ((a + (b - a) * fraction) / 32768.0);
		}
		return out;
	}

	private static void copyAll(Path from, Path to) {
		if (!Files.isDirectory(from)) {
			return;
		}
		try (var files = Files.list(from)) {
			Files.createDirectories(to);
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				Path target = to.resolve(file.getFileName());
				if (!Files.exists(target)) {
					Files.copy(file, target);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static <T> T join(java.util.concurrent.CompletableFuture<T> future) {
		try {
			return future.get(5, TimeUnit.MINUTES);
		} catch (Exception e) {
			throw new AssertionError("voice call failed", e);
		}
	}
}
