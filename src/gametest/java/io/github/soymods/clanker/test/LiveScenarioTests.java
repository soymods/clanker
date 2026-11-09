package io.github.soymods.clanker.test;

import io.github.soymods.clanker.Clanker;
import io.github.soymods.clanker.config.Personalities;
import io.github.soymods.clanker.config.ServerConfig;
import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerBots;
import io.github.soymods.clanker.server.brain.BrainEvent;
import io.github.soymods.clanker.server.brain.llm.ChatMessage;
import io.github.soymods.clanker.server.voice.XaiVoice;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Live checks that spend a little real API credit, for judging how Clanker comes across. Both are skipped unless asked
 * for:
 * <ul>
 * <li>{@code CLANKER_SCENARIOS=1}: plays a short scripted session against the real brain (xAI unless
 * {@code CLANKER_LIVE_PROVIDER} says otherwise; {@code CLANKER_LIVE_MODEL}, {@code CLANKER_LIVE_PERSONALITY} pick the
 * model and a preset) and logs everything Clanker says as {@code [scenario]} lines. Voice stays local.</li>
 * <li>{@code CLANKER_VOICE_SAMPLES=1}: voices one test line with each xAI voice (or those in
 * {@code CLANKER_VOICE_SAMPLES_VOICES}) into {@code build/run/gameTest/build/voice-samples/<voice>.wav}.</li>
 * </ul>
 */
@SuppressWarnings("removal")
public class LiveScenarioTests {
	private static final boolean SCENARIOS = "1".equals(System.getenv("CLANKER_SCENARIOS"));
	private static final boolean SAMPLES = "1".equals(System.getenv("CLANKER_VOICE_SAMPLES"));
	private static final long BEAT_TIMEOUT_MS = 40_000;
	private static final String SAMPLE_LINE = "[laugh] Oh, you're swimming now? Bold strategy. <whisper>I am not coming in after you.</whisper> [sigh] Fine. I'm coming in after you.";

	static {
		TestSetup.isolate();
	}

	@GameTest(structure = "clanker-test:arena", maxTicks = Integer.MAX_VALUE)
	public void liveScenario(GameTestHelper helper) {
		if (!SCENARIOS) {
			helper.succeed();
			return;
		}
		ServerConfig config = ServerConfig.get();
		config.provider = ServerConfig.Provider.valueOf(env("CLANKER_LIVE_PROVIDER", "XAI"));
		config.model = env("CLANKER_LIVE_MODEL", "");
		config.reasoningEffort = env("CLANKER_LIVE_EFFORT", "");
		config.personality = Personalities.PRESETS.getOrDefault(env("CLANKER_LIVE_PERSONALITY", Personalities.DEFAULT), "");
		config.talkativeness = ServerConfig.Talkativeness.NORMAL;

		ClankerBot bot;
		try {
			bot = ClankerBots.spawn(helper.getLevel().getServer(), helper.getLevel(), helper.absoluteVec(new Vec3(4.5, 1, 4.5)), 0, 0, "Clanker");
		} catch (ClankerBots.SpawnException e) {
			throw new AssertionError(e.getMessage());
		}
		bot.voice().setLocalOnly(true);
		bot.player().setGameMode(GameType.SURVIVAL);
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.setGameMode(GameType.SURVIVAL);
		Vec3 pos = helper.absoluteVec(new Vec3(8.5, 1, 8.5));
		player.teleportTo(helper.getLevel(), pos.x, pos.y, pos.z, Set.of(), 0, 0, true);
		String name = player.getName().getString();
		Clanker.LOGGER.info("[scenario] {} / {} / {}", config.provider, config.model.isBlank() ? "default model" : config.model,
				Personalities.presetName(config.personality));

		GameTestSequence sequence = helper.startSequence().thenIdle(120);
		beat(helper, sequence, bot, () -> say(bot, player, "Alright Clanker, are you ready to speedrun this game?"));
		beat(helper, sequence, bot, () -> player.getInventory().add(new ItemStack(Items.COBBLESTONE, 32)));
		sequence.thenIdle(40);
		beat(helper, sequence, bot, () -> {
			// The mock player has no client to move it, so the fall itself is reported the way the observer would.
			bot.brain().post(BrainEvent.of(BrainEvent.Type.PLAYER_ACTIVITY, name + " dropped about 11 blocks into water.", 0.67));
			bot.brain().post(BrainEvent.of(BrainEvent.Type.PLAYER_ACTIVITY, name + " is in the water, under the surface.", 0.2));
			player.getInventory().clearContent();
		});
		beat(helper, sequence, bot, () -> say(bot, player, "I'll come save you. Wait, am I swimming? Okay I'm fine."));
		beat(helper, sequence, bot, () -> {
			helper.setBlock(6, 1, 2, Blocks.DIAMOND_ORE);
			helper.spawn(EntityTypes.ZOMBIE, 12, 1, 3);
		});
		beat(helper, sequence, bot, () -> say(bot, player, "Come on, let's go find a village. Can you find a village for us?"));
		sequence.thenExecute(() -> {
			log(bot);
			ClankerBots.despawn(bot);
		}).thenSucceed();
	}

	private static void say(ClankerBot bot, ServerPlayer player, String text) {
		Clanker.LOGGER.info("[scenario] {} says: {}", player.getName().getString(), text);
		bot.brain().hear(player, text, true);
	}

	/** Does something, then waits for Clanker's reaction (or a quiet spell) before the next beat. */
	private static void beat(GameTestHelper helper, GameTestSequence sequence, ClankerBot bot, Runnable action) {
		long[] start = {0};
		int[] requestsBefore = {0};
		sequence.thenExecute(() -> {
			start[0] = System.currentTimeMillis();
			requestsBefore[0] = bot.brain().history().size();
			action.run();
		}).thenWaitUntil(() -> {
			long elapsed = System.currentTimeMillis() - start[0];
			boolean answered = bot.brain().history().size() > requestsBefore[0] && !bot.brain().isThinking() && elapsed > 6_000;
			helper.assertTrue(answered || elapsed >= BEAT_TIMEOUT_MS, "waiting for Clanker");
		});
	}

	private static void log(ClankerBot bot) {
		for (ChatMessage message : bot.brain().history()) {
			if (message.role() == ChatMessage.Role.USER) {
				Clanker.LOGGER.info("[scenario] events: {}", message.content().replace('\n', ' '));
			} else if (message.role() == ChatMessage.Role.ASSISTANT) {
				String calls = message.toolCalls().stream().map(c -> c.name() + c.arguments()).collect(Collectors.joining(" "));
				if (message.content() != null && !message.content().isBlank()) {
					Clanker.LOGGER.info("[scenario] Clanker: {}", message.content());
				}
				if (!calls.isEmpty()) {
					Clanker.LOGGER.info("[scenario] Clanker does: {}", calls);
				}
			}
		}
		Clanker.LOGGER.info("[scenario] {}", bot.brain().status());
	}

	@GameTest(maxTicks = Integer.MAX_VALUE)
	public void liveVoiceSamples(GameTestHelper helper) {
		if (!SAMPLES) {
			helper.succeed();
			return;
		}
		String key = ServerConfig.get().resolveKey(ServerConfig.KeyKind.XAI);
		helper.assertFalse(key.isBlank(), "no xAI key");
		String only = env("CLANKER_VOICE_SAMPLES_VOICES", "");
		List<String> voices = only.isBlank() ? XaiVoice.VOICES : Arrays.asList(only.split(","));
		Path dir = Path.of("build", "voice-samples").toAbsolutePath();
		Thread thread = new Thread(() -> {
			for (String voice : voices) {
				XaiVoice engine = new XaiVoice(key, voice.strip(), 1.0);
				ByteArrayOutputStream pcm = new ByteArrayOutputStream();
				try {
					long start = System.currentTimeMillis();
					engine.speak(engine.prepare(SAMPLE_LINE), pcm::writeBytes, () -> false);
					Files.createDirectories(dir);
					Files.write(dir.resolve(voice.strip() + ".wav"), wav(pcm.toByteArray(), 24_000));
					Clanker.LOGGER.info("[samples] {}: {}s of audio in {}s", voice, pcm.size() / 48_000.0, (System.currentTimeMillis() - start) / 1000.0);
				} catch (IOException | InterruptedException e) {
					Clanker.LOGGER.warn("[samples] {} failed: {}", voice, e.getMessage());
				}
			}
		});
		thread.start();
		helper.startSequence().thenWaitUntil(() -> helper.assertFalse(thread.isAlive(), "waiting for samples")).thenSucceed();
	}

	private static byte[] wav(byte[] pcm, int rate) {
		ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
		header.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes())
				.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2)
				.putShort((short) 2).putShort((short) 16)
				.put("data".getBytes()).putInt(pcm.length);
		byte[] out = new byte[44 + pcm.length];
		System.arraycopy(header.array(), 0, out, 0, 44);
		System.arraycopy(pcm, 0, out, 44, pcm.length);
		return out;
	}

	private static String env(String name, String fallback) {
		String value = System.getenv(name);
		return value == null || value.isBlank() ? fallback : value.strip();
	}
}
