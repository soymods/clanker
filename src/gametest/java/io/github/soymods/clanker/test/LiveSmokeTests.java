package io.github.soymods.clanker.test;

import io.github.soymods.clanker.Clanker;
import io.github.soymods.clanker.config.ServerConfig;
import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerBots;
import io.github.soymods.clanker.server.brain.llm.ChatMessage;
import io.github.soymods.clanker.server.brain.llm.OpenAiProvider;
import io.github.soymods.clanker.server.brain.llm.ToolCall;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Talks to the real configured model. Skipped unless CLANKER_LIVE_TEST=1, since it spends API credit;
 * CLANKER_LIVE_MODEL overrides the model.
 * The API key is resolved by the mod itself (env, config, or ~/.clanker/api-key.txt).
 */
@SuppressWarnings("removal")
public class LiveSmokeTests {
	private static final boolean LIVE = "1".equals(System.getenv("CLANKER_LIVE_TEST"));
	private static final long TIMEOUT_MS = 120_000;

	@GameTest
	public void liveListModels(GameTestHelper helper) {
		if (LIVE) {
			List<String> models = new OpenAiProvider(OpenAiProvider.OPENAI_BASE_URL, ServerConfig.get().resolveApiKey(), "", "")
					.listModels().join();
			Clanker.LOGGER.info("[live] models: {}", models.stream().filter(id -> id.startsWith("gpt") || id.matches("o\\d.*"))
					.sorted().collect(Collectors.joining(", ")));
			helper.assertFalse(models.isEmpty(), "no models listed");
		}
		helper.succeed();
	}

	// Ticks run unthrottled here, so the wait is bounded by wall-clock time instead of maxTicks.
	@GameTest(structure = "clanker-test:arena", maxTicks = Integer.MAX_VALUE)
	public void liveComeHere(GameTestHelper helper) {
		if (!LIVE) {
			helper.succeed();
			return;
		}
		String model = System.getenv("CLANKER_LIVE_MODEL");
		if (model != null && !model.isBlank()) {
			ServerConfig.get().model = model;
		}
		ClankerBot bot;
		try {
			bot = ClankerBots.spawn(helper.getLevel().getServer(), helper.getLevel(), helper.absoluteVec(new Vec3(2.5, 1, 2.5)), 0, 0, "Clanker");
		} catch (ClankerBots.SpawnException e) {
			throw new AssertionError(e.getMessage());
		}
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		Vec3 pos = helper.absoluteVec(new Vec3(12.5, 1, 12.5));
		player.teleportTo(helper.getLevel(), pos.x, pos.y, pos.z, Set.of(), 0, 0, true);
		long start = System.currentTimeMillis();
		AtomicInteger spokenBefore = new AtomicInteger();

		helper.startSequence()
				.thenIdle(5)
				.thenExecute(() -> bot.brain().hear(player, "Clanker, come over here", false))
				.thenWaitUntil(() -> {
					if (System.currentTimeMillis() - start > TIMEOUT_MS) {
						log(bot);
						ClankerBots.despawn(bot);
						helper.fail("no movement tool call within " + TIMEOUT_MS / 1000 + "s; " + bot.brain().status());
					}
					helper.assertTrue(movedForRequest(bot), "waiting for the model");
				})
				.thenWaitUntil(() -> helper.assertFalse(bot.brain().isThinking(), "waiting for the turn to end"))
				.thenExecute(() -> {
					spokenBefore.set(bot.spokenCount());
					bot.brain().hear(player, "Clanker, what do you see around here?", false);
				})
				.thenWaitUntil(() -> {
					if (System.currentTimeMillis() - start > TIMEOUT_MS) {
						log(bot);
						ClankerBots.despawn(bot);
						helper.fail("no spoken answer within " + TIMEOUT_MS / 1000 + "s; " + bot.brain().status());
					}
					helper.assertTrue(bot.spokenCount() > spokenBefore.get() && !bot.brain().isThinking(), "waiting for an answer");
				})
				.thenExecute(() -> {
					log(bot);
					ClankerBots.despawn(bot);
				})
				.thenSucceed();
	}

	private static boolean movedForRequest(ClankerBot bot) {
		return bot.brain().history().stream()
				.filter(m -> m.role() == ChatMessage.Role.ASSISTANT)
				.flatMap(m -> m.toolCalls().stream())
				.map(ToolCall::name)
				.anyMatch(name -> name.equals("come_to") || name.equals("follow_player") || name.equals("go_to"));
	}

	private static void log(ClankerBot bot) {
		for (ChatMessage message : bot.brain().history()) {
			String calls = message.toolCalls().stream().map(c -> c.name() + c.arguments()).collect(Collectors.joining(" "));
			Clanker.LOGGER.info("[live] {}: {} {}", message.role(), message.content(), calls);
		}
		Clanker.LOGGER.info("[live] spoken: {} | {}", bot.lastSpoken(), bot.brain().status());
	}
}
