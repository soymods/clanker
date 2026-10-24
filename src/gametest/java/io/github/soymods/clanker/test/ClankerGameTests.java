package io.github.soymods.clanker.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.soymods.clanker.config.ServerConfig;
import io.github.soymods.clanker.network.HostSettings;
import io.github.soymods.clanker.server.HostSettingsHandler;
import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerBots;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import io.github.soymods.clanker.server.brain.Tools;
import io.github.soymods.clanker.server.brain.llm.ChatMessage;
import io.github.soymods.clanker.server.brain.llm.LlmRequest;
import io.github.soymods.clanker.server.brain.llm.LlmResponse;
import io.github.soymods.clanker.server.brain.llm.OpenAiProvider;
import io.github.soymods.clanker.server.brain.llm.ToolCall;
import io.github.soymods.clanker.server.perception.Perception;
import io.github.soymods.clanker.server.skills.ComeToTask;
import io.github.soymods.clanker.server.skills.FollowTask;
import io.github.soymods.clanker.server.skills.GoToTask;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("removal") // makeMockServerPlayerInLevel is the only way to get a ServerPlayer in the test level
public class ClankerGameTests {
	private static final String ARENA = "clanker-test:arena";
	private static final AtomicInteger COUNTER = new AtomicInteger();

	/** Spawns a uniquely named bot in the arena, wired to the given fake model so nothing reaches a real API. */
	static ClankerBot spawn(GameTestHelper helper, double x, double z, FakeProvider provider) {
		Vec3 pos = helper.absoluteVec(new Vec3(x, 1, z));
		try {
			ClankerBot bot = ClankerBots.spawn(helper.getLevel().getServer(), helper.getLevel(), pos, 0, 0,
					"Clank_t" + COUNTER.incrementAndGet());
			bot.brain().setProviderOverride(provider);
			return bot;
		} catch (ClankerBots.SpawnException e) {
			throw new AssertionError("spawn failed: " + e.getMessage());
		}
	}

	static ServerPlayer mockPlayer(GameTestHelper helper, double x, double z) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		Vec3 pos = helper.absoluteVec(new Vec3(x, 1, z));
		player.teleportTo(helper.getLevel(), pos.x, pos.y, pos.z, Set.of(), 0, 0, true);
		return player;
	}

	/** Whether the model saw this, or will on its next turn (event cooldowns are wall-clock and tests run fast). */
	static boolean knows(ClankerBot bot, FakeProvider provider, String text) {
		return provider.anyRequestContains(text) || bot.brain().pending().stream().anyMatch(e -> e.text().contains(text));
	}

	static void finish(GameTestHelper helper, ClankerBot bot) {
		ClankerBots.despawn(bot);
		helper.succeed();
	}

	@GameTest
	public void commandRegistered(GameTestHelper helper) {
		var root = helper.getLevel().getServer().getCommands().getDispatcher().getRoot();
		helper.assertTrue(root.getChild("clanker") != null, "/clanker command is not registered");
		helper.succeed();
	}

	@GameTest(structure = ARENA)
	public void spawnsAndDespawns(GameTestHelper helper) {
		ClankerBot bot = spawn(helper, 2.5, 2.5, FakeProvider.silent());
		ClankerPlayer player = bot.player();
		helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayer(bot.id()) == player, "bot is not in the player list");
		helper.assertTrue(player.isAlive(), "bot is not alive");
		helper.startSequence()
				.thenIdle(5)
				.thenExecute(() -> {
					ClankerBots.despawn(bot);
					helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayer(bot.id()) == null, "bot still in player list");
					helper.assertTrue(ClankerBots.get(bot.id()) == null, "bot still registered");
				})
				.thenSucceed();
	}

	@GameTest(structure = ARENA, maxTicks = 300)
	public void walksToTarget(GameTestHelper helper) {
		ClankerBot bot = spawn(helper, 2.5, 2.5, FakeProvider.silent());
		Vec3 target = helper.absoluteVec(new Vec3(12.5, 1, 12.5));
		bot.tasks().start(new GoToTask(target));
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null && bot.player().position().distanceTo(target) < 1.5,
						"bot is " + bot.player().position().distanceTo(target) + " blocks from target"))
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA, maxTicks = 300)
	public void jumpsOverWall(GameTestHelper helper) {
		for (int z = 0; z < 16; z++) {
			helper.setBlock(7, 1, z, Blocks.STONE);
		}
		ClankerBot bot = spawn(helper, 2.5, 8.5, FakeProvider.silent());
		Vec3 target = helper.absoluteVec(new Vec3(12.5, 1, 8.5));
		bot.tasks().start(new GoToTask(target));
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(bot.player().position().distanceTo(target) < 1.5,
						"bot didn't get over the wall; at " + Perception.coords(helper.relativeVec(bot.player().position()))))
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA, maxTicks = 300)
	public void comesToPlayer(GameTestHelper helper) {
		ClankerBot bot = spawn(helper, 2.5, 2.5, FakeProvider.silent());
		ServerPlayer player = mockPlayer(helper, 12.5, 12.5);
		bot.tasks().start(new ComeToTask(player));
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null && bot.player().distanceTo(player) < 3,
						"bot is " + bot.player().distanceTo(player) + " from the player"))
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void followsPlayer(GameTestHelper helper) {
		ClankerBot bot = spawn(helper, 2.5, 2.5, FakeProvider.silent());
		ServerPlayer player = mockPlayer(helper, 5.5, 5.5);
		bot.tasks().start(new FollowTask(player, 2.5));
		Vec3 moved = helper.absoluteVec(new Vec3(13.5, 1, 13.5));
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(bot.player().distanceTo(player) < 4.5, "didn't reach the player"))
				.thenExecute(() -> player.teleportTo(helper.getLevel(), moved.x, moved.y, moved.z, Set.of(), 0, 0, true))
				.thenWaitUntil(() -> helper.assertTrue(bot.player().distanceTo(player) < 4.5, "didn't follow the player"))
				.thenExecute(() -> helper.assertTrue(bot.tasks().current() instanceof FollowTask, "follow ended on its own"))
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA, maxTicks = 200)
	public void brainUsesToolsAndSpeaks(GameTestHelper helper) {
		AtomicReference<String> target = new AtomicReference<>("");
		FakeProvider provider = new FakeProvider(request -> {
			ChatMessage last = request.messages().getLast();
			if (last.role() == ChatMessage.Role.USER && last.content().contains("follow me")) {
				return new LlmResponse("", List.of(new ToolCall("call_1", "follow_player",
						"{\"player\":\"" + target.get() + "\"}")), LlmResponse.Usage.NONE);
			}
			if (last.role() == ChatMessage.Role.TOOL) {
				return FakeProvider.reply("Right behind you.");
			}
			return FakeProvider.reply("");
		});
		ClankerBot bot = spawn(helper, 2.5, 2.5, provider);
		ServerPlayer player = mockPlayer(helper, 6.5, 6.5);
		target.set(player.getName().getString());

		helper.startSequence()
				.thenIdle(10)
				.thenExecute(() -> bot.brain().hear(player, bot.name() + ", follow me", false))
				.thenWaitUntil(() -> helper.assertTrue(bot.spokenCount() > 0, "bot never spoke"))
				.thenExecute(() -> {
					helper.assertTrue(bot.tasks().current() instanceof FollowTask, "follow_player didn't start a follow task");
					helper.assertValueEqual(bot.lastSpoken(), "Right behind you.", "spoken text");
					LlmRequest first = provider.requests.stream()
							.filter(r -> r.messages().getLast().content() != null && r.messages().getLast().content().contains("follow me"))
							.findFirst().orElseThrow(() -> new AssertionError("model never saw the request"));
					helper.assertTrue(first.system().contains(bot.name()), "system prompt is missing the bot's name");
					helper.assertValueEqual(first.tools().size(), Tools.specs().size(), "tool count");
					String perception = first.messages().getLast().perception();
					helper.assertTrue(perception.contains("Players nearby") && perception.contains(target.get()),
							"perception doesn't list the nearby player:\n" + perception);
					helper.assertTrue(bot.brain().history().stream().anyMatch(m -> m.role() == ChatMessage.Role.TOOL
							&& m.content().startsWith("Following")), "tool result missing from history");
				})
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA)
	public void perceptionSeesSurroundings(GameTestHelper helper) {
		helper.setBlock(5, 1, 5, Blocks.CHEST);
		helper.setBlock(9, 1, 3, Blocks.OAK_LOG);
		helper.setBlock(3, 1, 9, Blocks.CRAFTING_TABLE);
		helper.spawn(EntityTypes.PIG, new BlockPos(10, 1, 10));
		ClankerBot bot = spawn(helper, 2.5, 2.5, FakeProvider.silent());
		helper.startSequence()
				.thenIdle(2)
				.thenExecute(() -> {
					String snapshot = Perception.snapshot(bot, bot.player());
					for (String expected : List.of("chest", "oak_log", "crafting_table", "pig", "Health 20/20", "Current task: none")) {
						helper.assertTrue(snapshot.contains(expected), "snapshot is missing \"" + expected + "\":\n" + snapshot);
					}
				})
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void diesAndRespawns(GameTestHelper helper) {
		FakeProvider provider = FakeProvider.silent();
		ClankerBot bot = spawn(helper, 2.5, 2.5, provider);
		ClankerPlayer original = bot.player();
		helper.startSequence()
				.thenIdle(5)
				.thenExecute(() -> {
					original.setGameMode(GameType.SURVIVAL); // the test server's default is creative
					original.kill(helper.getLevel());
					helper.assertTrue(original.isDeadOrDying(), "kill didn't kill the bot (game mode " + original.gameMode() + ")");
				})
				.thenWaitUntil(() -> helper.assertTrue(bot.player() != original && bot.player().isAlive(), "bot didn't respawn"))
				.thenWaitUntil(() -> helper.assertTrue(knows(bot, provider, "You died") && knows(bot, provider, "You respawned"),
						"death and respawn weren't reported to the brain"))
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA)
	public void stopWordCancelsImmediately(GameTestHelper helper) {
		ClankerBot bot = spawn(helper, 2.5, 2.5, FakeProvider.silent());
		ServerPlayer player = mockPlayer(helper, 10.5, 10.5);
		bot.tasks().start(new FollowTask(player, 3));
		bot.brain().hear(player, bot.name() + " stop!", false);
		helper.assertTrue(bot.tasks().current() == null, "task kept running after stop");
		finish(helper, bot);
	}

	@GameTest(structure = ARENA)
	public void listensWhenAddressed(GameTestHelper helper) {
		ClankerBot bot = spawn(helper, 2.5, 2.5, FakeProvider.silent());
		ServerPlayer player = mockPlayer(helper, 6.5, 6.5);
		long now = System.currentTimeMillis();
		helper.assertTrue(bot.brain().isAddressed(player, "hey " + bot.name().toLowerCase() + " what's up", now), "name mention not addressed");
		helper.assertFalse(bot.brain().isAddressed(player, "anyone got food?", now), "unaddressed chat counted as addressed");
		bot.say("Who, me?");
		helper.assertTrue(bot.brain().isAddressed(player, "yeah you", System.currentTimeMillis()), "reply after Clanker spoke not addressed");
		finish(helper, bot);
	}

	@GameTest
	public void toolErrorsAreFacts(GameTestHelper helper) {
		ClankerBot bot = spawn(helper, 2.5, 2.5, FakeProvider.silent());
		helper.assertTrue(Tools.execute(bot, new ToolCall("1", "follow_player", "{\"player\":\"Nobody123\"}")).startsWith("No player named Nobody123"),
				"unknown player should be reported");
		helper.assertTrue(Tools.execute(bot, new ToolCall("2", "fly", "{}")).startsWith("Error: there is no tool"), "unknown tool");
		helper.assertTrue(Tools.execute(bot, new ToolCall("3", "go_to", "{\"x\":1}")).contains("missing or invalid argument"), "missing argument");
		helper.assertTrue(Tools.execute(bot, new ToolCall("4", "go_to", "not json")).contains("not valid JSON"), "bad JSON");
		finish(helper, bot);
	}

	@GameTest
	public void settingsNeverExposeKeyAndCheckPermission(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		ServerConfig config = ServerConfig.get();
		String previousKey = config.apiKey;
		config.apiKey = "sk-test-not-a-real-key";
		try {
			String json = HostSettingsHandler.snapshot(player, "").toJson();
			helper.assertFalse(json.contains("sk-test-not-a-real-key"), "settings snapshot leaked the key");
			helper.assertTrue(HostSettings.fromJson(json).keySet, "keySet should be true");

			HostSettings update = HostSettingsHandler.snapshot(player, "");
			update.botName = "Hacker";
			String status = HostSettingsHandler.apply(player, update);
			helper.assertTrue(status.startsWith("Only operators"), "non-op was allowed to change settings: " + status);
			helper.assertValueEqual(config.botName.equals("Hacker"), false, "bot name changed by non-op");
		} finally {
			config.apiKey = previousKey;
		}
		helper.succeed();
	}

	@GameTest
	public void openAiRequestAndResponseShape(GameTestHelper helper) {
		OpenAiProvider provider = new OpenAiProvider(OpenAiProvider.OPENAI_BASE_URL, "", "test-model", "low");
		List<ChatMessage> history = List.of(
				ChatMessage.user("first", "OLD SNAPSHOT"),
				ChatMessage.assistant("", List.of(new ToolCall("c1", "stop", "{}"))),
				ChatMessage.toolResult("c1", "Stopped."),
				ChatMessage.user("second", "NEW SNAPSHOT"));
		JsonObject body = provider.buildBody(new LlmRequest("sys", history, Tools.specs()));
		String json = body.toString();
		helper.assertFalse(json.contains("OLD SNAPSHOT"), "stale perception was sent");
		helper.assertTrue(json.contains("NEW SNAPSHOT"), "current perception missing");
		helper.assertValueEqual(body.get("reasoning_effort").getAsString(), "low", "reasoning effort");
		JsonArray messages = body.getAsJsonArray("messages");
		helper.assertValueEqual(messages.get(0).getAsJsonObject().get("role").getAsString(), "system", "first role");
		helper.assertValueEqual(messages.get(3).getAsJsonObject().get("tool_call_id").getAsString(), "c1", "tool call id");

		LlmResponse response = OpenAiProvider.parse("""
				{"choices":[{"message":{"role":"assistant","content":"On it.","tool_calls":[{"id":"call_9","type":"function",
				"function":{"name":"go_to","arguments":"{\\"x\\":1,\\"y\\":2,\\"z\\":3}"}}]}}],
				"usage":{"prompt_tokens":1200,"completion_tokens":40,"prompt_tokens_details":{"cached_tokens":1024}}}""");
		helper.assertValueEqual(response.text(), "On it.", "text");
		helper.assertValueEqual(response.toolCalls().getFirst().name(), "go_to", "tool name");
		helper.assertValueEqual(response.toolCalls().getFirst().arguments(), "{\"x\":1,\"y\":2,\"z\":3}", "tool args");
		helper.assertValueEqual(response.usage().cachedInputTokens(), 1024L, "cached tokens");
		helper.succeed();
	}
}
