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

	// Ticks run unthrottled here, so waits are bounded by wall-clock time instead of maxTicks. Timeouts throw a plain
	// exception because thenWaitUntil treats assertion failures as "not yet".
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
						throw new IllegalStateException("no movement tool call within " + TIMEOUT_MS / 1000 + "s; " + bot.brain().status());
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
						throw new IllegalStateException("no spoken answer within " + TIMEOUT_MS / 1000 + "s; " + bot.brain().status());
					}
					helper.assertTrue(bot.spokenCount() > spokenBefore.get() && !bot.brain().isThinking(), "waiting for an answer");
				})
				.thenExecute(() -> {
					log(bot);
					ClankerBots.despawn(bot);
				})
				.thenSucceed();
	}

	@GameTest(structure = "clanker-test:arena", maxTicks = Integer.MAX_VALUE)
	public void liveGathersWood(GameTestHelper helper) {
		if (!LIVE) {
			helper.succeed();
			return;
		}
		for (int y = 1; y <= 4; y++) {
			helper.setBlock(11, y, 11, net.minecraft.world.level.block.Blocks.OAK_LOG);
		}
		ClankerBot bot;
		try {
			bot = ClankerBots.spawn(helper.getLevel().getServer(), helper.getLevel(), helper.absoluteVec(new Vec3(3.5, 1, 3.5)), 0, 0, "Clanky");
		} catch (ClankerBots.SpawnException e) {
			throw new AssertionError(e.getMessage());
		}
		bot.player().setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		Vec3 pos = helper.absoluteVec(new Vec3(2.5, 1, 6.5));
		player.teleportTo(helper.getLevel(), pos.x, pos.y, pos.z, Set.of(), 0, 0, true);
		long start = System.currentTimeMillis();

		helper.startSequence()
				.thenIdle(5)
				.thenExecute(() -> bot.brain().hear(player, "Clanky, chop down that tree and bring me the wood", false))
				.thenWaitUntil(() -> {
					if (System.currentTimeMillis() - start > 180_000) {
						log(bot);
						ClankerBots.despawn(bot);
						throw new IllegalStateException("didn't gather and hand over wood within 3 minutes; " + bot.brain().status());
					}
					helper.assertTrue(happened(bot, "Task give_item finished"), "waiting for the wood");
				})
				.thenExecute(() -> {
					log(bot);
					ClankerBots.despawn(bot);
				})
				.thenSucceed();
	}

	private static boolean happened(ClankerBot bot, String text) {
		return bot.brain().history().stream().anyMatch(m -> m.content() != null && m.content().contains(text))
				|| bot.brain().pending().stream().anyMatch(e -> e.text().contains(text));
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
