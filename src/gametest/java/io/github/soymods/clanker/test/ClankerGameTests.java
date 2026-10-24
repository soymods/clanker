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
import io.github.soymods.clanker.server.skills.AttackTask;
import io.github.soymods.clanker.server.skills.ContainerTask;
import io.github.soymods.clanker.server.skills.CraftTask;
import io.github.soymods.clanker.server.skills.EatTask;
import io.github.soymods.clanker.server.skills.GiveTask;
import io.github.soymods.clanker.server.skills.Matching;
import io.github.soymods.clanker.server.skills.MineTask;
import io.github.soymods.clanker.server.skills.PlaceTask;
import io.github.soymods.clanker.server.skills.SmeltTask;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
	/** Same floor, but tall enough to build and stand on towers. */
	private static final String TALL_ARENA = "clanker-test:tall_arena";
	private static final AtomicInteger COUNTER = new AtomicInteger();
	/** Bots keep their saved inventory by name, so each run uses fresh names. */
	private static final String RUN_ID = "C" + Long.toString(System.currentTimeMillis() % 2_000_000_000L, 36);

	/** Spawns a uniquely named bot in the arena, wired to the given fake model so nothing reaches a real API. */
	static ClankerBot spawn(GameTestHelper helper, double x, double z, FakeProvider provider) {
		Vec3 pos = helper.absoluteVec(new Vec3(x, 1, z));
		try {
			ClankerBot bot = ClankerBots.spawn(helper.getLevel().getServer(), helper.getLevel(), pos, 0, 0,
					RUN_ID + "_" + COUNTER.incrementAndGet());
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

	static ClankerBot survivalBot(GameTestHelper helper, double x, double z) {
		ClankerBot bot = spawn(helper, x, z, FakeProvider.silent());
		bot.player().setGameMode(GameType.SURVIVAL);
		return bot;
	}

	static int count(ClankerBot bot, net.minecraft.world.item.Item item) {
		return bot.player().getInventory().countItem(item);
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void pathsAroundWall(GameTestHelper helper) {
		// A two-high wall too tall to jump, with a gap only at the far end.
		for (int z = 0; z <= 12; z++) {
			helper.setBlock(7, 1, z, Blocks.STONE);
			helper.setBlock(7, 2, z, Blocks.STONE);
		}
		ClankerBot bot = spawn(helper, 2.5, 2.5, FakeProvider.silent());
		Vec3 target = helper.absoluteVec(new Vec3(12.5, 1, 2.5));
		bot.tasks().start(new GoToTask(target));
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null && bot.player().position().distanceTo(target) < 1.5,
						"didn't path around the wall; at " + Perception.coords(helper.relativeVec(bot.player().position()))))
				.thenExecute(() -> finish(helper, bot));
	}

	/** What the path executor is doing, for failure messages. */
	static String pathState(GameTestHelper helper, ClankerBot bot) {
		var behavior = bot.movement().pathing().getPathingBehavior();
		var player = bot.player();
		StringBuilder out = new StringBuilder(" [at " + Perception.coords(helper.relativeVec(player.position())) + " ground=" + player.onGround()
				+ " sneak=" + player.isShiftKeyDown() + " yaw=" + (int) player.getYRot() + " pitch=" + (int) player.getXRot()
				+ " hand=" + player.getMainHandItem() + " failures=" + behavior.failures() + " searching=" + behavior.getInProgress().isPresent());
		var current = behavior.getCurrent();
		if (current != null) {
			var movements = current.getPath().movements();
			int index = Math.min(current.getPosition(), movements.size() - 1);
			var movement = movements.get(index);
			out.append(" movement ").append(index).append("/").append(movements.size()).append(" ").append(movement.getClass().getSimpleName())
					.append(" ").append(helper.relativePos(movement.getSrc())).append("->").append(helper.relativePos(movement.getDest()));
		}
		for (var input : io.github.soymods.clanker.server.pathing.util.Input.values()) {
			if (bot.movement().pathing().getInputOverrideHandler().isInputForcedDown(input)) {
				out.append(" ").append(input);
			}
		}
		return out.append("]").toString();
	}

	static void fill(GameTestHelper helper, int x1, int y1, int z1, int x2, int y2, int z2, net.minecraft.world.level.block.Block block) {
		for (int x = x1; x <= x2; x++) {
			for (int y = y1; y <= y2; y++) {
				for (int z = z1; z <= z2; z++) {
					helper.setBlock(x, y, z, block);
				}
			}
		}
	}

	@GameTest(structure = ARENA, maxTicks = 3000)
	public void digsThroughAWall(GameTestHelper helper) {
		// Sealed in a pocket of dirt with the target in another pocket: the only way is to dig.
		fill(helper, 1, 1, 6, 10, 4, 10, Blocks.DIRT);
		helper.setBlock(2, 1, 8, Blocks.AIR);
		helper.setBlock(2, 2, 8, Blocks.AIR);
		helper.setBlock(8, 1, 8, Blocks.AIR);
		helper.setBlock(8, 2, 8, Blocks.AIR);
		ClankerBot bot = survivalBot(helper, 2.5, 8.5);
		Vec3 target = helper.absoluteVec(new Vec3(8.5, 1, 8.5));
		bot.tasks().start(new GoToTask(target));
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null && bot.player().position().distanceTo(target) < 1.5,
						"didn't dig through; at " + Perception.coords(helper.relativeVec(bot.player().position()))))
				.thenExecute(() -> helper.assertTrue(count(bot, Items.DIRT) > 0, "picked up none of the dirt it dug"))
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = TALL_ARENA, maxTicks = 3000)
	public void pillarsUpOntoALedge(GameTestHelper helper) {
		fill(helper, 10, 1, 7, 12, 3, 9, Blocks.STONE);
		ClankerBot bot = survivalBot(helper, 4.5, 8.5);
		bot.player().getInventory().add(new ItemStack(Items.COBBLESTONE, 16));
		Vec3 target = helper.absoluteVec(new Vec3(11.5, 4, 8.5));
		bot.tasks().start(new GoToTask(target));
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null && bot.player().position().distanceTo(target) < 1.5,
						"didn't get up; at " + Perception.coords(helper.relativeVec(bot.player().position()))))
				.thenExecute(() -> helper.assertTrue(count(bot, Items.COBBLESTONE) < 16, "got up without placing anything?"))
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = TALL_ARENA, maxTicks = 3000)
	public void crossesAGapBetweenTowers(GameTestHelper helper) {
		fill(helper, 0, 1, 6, 4, 3, 10, Blocks.STONE);
		fill(helper, 8, 1, 6, 12, 3, 10, Blocks.STONE);
		ClankerBot bot = spawn(helper, 2.5, 8.5, FakeProvider.silent());
		bot.player().setGameMode(GameType.SURVIVAL);
		bot.player().teleportTo(helper.getLevel(), bot.player().getX(), helper.absoluteVec(new Vec3(0, 4, 0)).y, bot.player().getZ(), Set.of(), 0, 0, true);
		bot.player().getInventory().add(new ItemStack(Items.COBBLESTONE, 16));
		Vec3 target = helper.absoluteVec(new Vec3(10.5, 4, 8.5));
		bot.tasks().start(new GoToTask(target));
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null && bot.player().position().distanceTo(target) < 1.5,
						"didn't cross" + pathState(helper, bot)))
				.thenExecute(() -> helper.assertTrue(bot.player().getHealth() >= bot.player().getMaxHealth(), "got hurt: " + bot.player().getHealth()))
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = TALL_ARENA, maxTicks = 3000)
	public void jumpsAGapWithNothingToBuildWith(GameTestHelper helper) {
		fill(helper, 0, 1, 6, 5, 3, 10, Blocks.STONE);
		fill(helper, 8, 1, 6, 12, 3, 10, Blocks.STONE);
		ClankerBot bot = spawn(helper, 2.5, 8.5, FakeProvider.silent());
		bot.player().setGameMode(GameType.SURVIVAL);
		bot.player().teleportTo(helper.getLevel(), bot.player().getX(), helper.absoluteVec(new Vec3(0, 4, 0)).y, bot.player().getZ(), Set.of(), 0, 0, true);
		Vec3 target = helper.absoluteVec(new Vec3(10.5, 4, 8.5));
		bot.tasks().start(new GoToTask(target));
		double[] lowest = {Double.MAX_VALUE};
		helper.onEachTick(() -> lowest[0] = Math.min(lowest[0], bot.player().getY()));
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null && bot.player().position().distanceTo(target) < 1.5,
						"didn't jump the gap" + pathState(helper, bot)))
				.thenExecute(() -> helper.assertTrue(lowest[0] > helper.absoluteVec(new Vec3(0, 3, 0)).y, "went down instead of jumping"))
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = TALL_ARENA, maxTicks = 3000)
	public void getsDownATallTowerSafely(GameTestHelper helper) {
		// Four blocks is one more than it will drop without water, so it has to dig or step its way down.
		fill(helper, 6, 1, 6, 8, 4, 8, Blocks.DIRT);
		ClankerBot bot = spawn(helper, 7.5, 7.5, FakeProvider.silent());
		bot.player().setGameMode(GameType.SURVIVAL);
		bot.player().teleportTo(helper.getLevel(), bot.player().getX(), helper.absoluteVec(new Vec3(0, 5, 0)).y, bot.player().getZ(), Set.of(), 0, 0, true);
		Vec3 target = helper.absoluteVec(new Vec3(13.5, 1, 13.5));
		bot.tasks().start(new GoToTask(target));
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null && bot.player().position().distanceTo(target) < 1.5,
						"didn't get down" + pathState(helper, bot)))
				.thenExecute(() -> helper.assertTrue(bot.player().getHealth() >= bot.player().getMaxHealth(), "took fall damage: " + bot.player().getHealth()))
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA)
	public void pathingRespectsHostPermissions(GameTestHelper helper) {
		ClankerBot bot = survivalBot(helper, 2.5, 8.5);
		bot.player().getInventory().add(new ItemStack(Items.DIRT, 8));
		ServerConfig config = ServerConfig.get();
		boolean breakBefore = config.canBreakBlocks;
		boolean placeBefore = config.canPlaceBlocks;
		// Config is shared with tests running alongside, so check and restore within this tick.
		try {
			config.canBreakBlocks = false;
			config.canPlaceBlocks = false;
			var context = new io.github.soymods.clanker.server.pathing.movement.CalculationContext(bot.movement().pathing(), true);
			helper.assertFalse(context.allowBreak, "search may break blocks");
			helper.assertFalse(context.hasThrowaway, "search may place blocks");
		} finally {
			config.canBreakBlocks = breakBefore;
			config.canPlaceBlocks = placeBefore;
		}
		var context = new io.github.soymods.clanker.server.pathing.movement.CalculationContext(bot.movement().pathing(), true);
		helper.assertTrue(context.allowBreak && context.hasThrowaway, "permissions not restored");
		finish(helper, bot);
	}

	@GameTest(structure = TALL_ARENA, maxTicks = 6000)
	public void minesARealAcaciaTree(GameTestHelper helper) {
		BlockPos base = new BlockPos(9, 1, 9);
		helper.setBlock(base.below(), Blocks.GRASS_BLOCK);
		var level = helper.getLevel();
		var feature = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.CONFIGURED_FEATURE)
				.getOrThrow(net.minecraft.data.worldgen.features.TreeFeatures.ACACIA).value();
		helper.assertTrue(feature.place(level, level.getChunkSource().getGenerator(), net.minecraft.util.RandomSource.create(7), helper.absolutePos(base)), "tree didn't grow");
		ClankerBot bot = survivalBot(helper, 3.5, 3.5);
		MineTask task = new MineTask("acacia", Matching.block("acacia"), 3);
		bot.tasks().start(task);
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null, "still mining: " + task.describe(bot, bot.player()) + pathState(helper, bot)))
				.thenExecute(() -> helper.assertTrue(count(bot, Items.ACACIA_LOG) >= 3, "acacia logs: " + count(bot, Items.ACACIA_LOG) + pathState(helper, bot)))
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA, maxTicks = 1200)
	public void stalledTasksEndAndAreReported(GameTestHelper helper) {
		FakeProvider provider = FakeProvider.silent();
		ClankerBot bot = spawn(helper, 2.5, 2.5, provider);
		bot.tasks().start(new io.github.soymods.clanker.server.skills.Task() {
			@Override
			public String name() {
				return "wait_forever";
			}

			@Override
			public String describe(ClankerBot b, ClankerPlayer p) {
				return "standing still";
			}

			@Override
			public Result tick(ClankerBot b, ClankerPlayer p) {
				return Result.RUNNING;
			}
		});
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null, "stalled task still running"))
				.thenExecute(() -> helper.assertTrue(knows(bot, provider, "made no progress"), "brain wasn't told"))
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA, maxTicks = 100)
	public void takesKnockbackFromPlayers(GameTestHelper helper) {
		ClankerBot bot = survivalBot(helper, 8.5, 8.5);
		ServerPlayer attacker = mockPlayer(helper, 6.5, 8.5);
		Vec3[] before = new Vec3[1];
		helper.startSequence()
				.thenIdle(5)
				.thenExecute(() -> {
					attacker.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, bot.player().getEyePosition());
					before[0] = bot.player().position();
					attacker.attack(bot.player());
				})
				.thenIdle(10)
				.thenExecute(() -> {
					double moved = bot.player().position().subtract(before[0]).horizontalDistance();
					helper.assertTrue(moved > 0.3, "knockback moved the bot only " + moved + " blocks");
				})
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA, maxTicks = 1200)
	public void minesATree(GameTestHelper helper) {
		for (int y = 1; y <= 3; y++) {
			helper.setBlock(9, y, 9, Blocks.OAK_LOG);
		}
		ClankerBot bot = survivalBot(helper, 2.5, 2.5);
		bot.tasks().start(new MineTask("logs", Matching.block("logs"), 3));
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null, "still mining: " + Perception.snapshot(bot, bot.player())))
				.thenExecute(() -> {
					helper.assertValueEqual(count(bot, Items.OAK_LOG), 3, "oak logs in inventory");
					helper.assertBlockNotPresent(Blocks.OAK_LOG, new BlockPos(9, 3, 9));
				})
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void craftsPlanksSticksAndATable(GameTestHelper helper) {
		ClankerBot bot = spawn(helper, 2.5, 2.5, FakeProvider.silent());
		bot.player().getInventory().add(new ItemStack(Items.OAK_LOG, 2));
		bot.tasks().start(new CraftTask("oak planks", Matching.item("oak planks"), 8));
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null, "still crafting planks"))
				.thenExecute(() -> {
					helper.assertValueEqual(count(bot, Items.OAK_PLANKS), 8, "planks");
					bot.tasks().start(new CraftTask("crafting_table", Matching.item("crafting_table"), 1));
				})
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null, "still crafting the table"))
				.thenExecute(() -> {
					helper.assertValueEqual(count(bot, Items.CRAFTING_TABLE), 1, "crafting tables");
					bot.tasks().start(new CraftTask("sticks", Matching.item("sticks"), 4));
				})
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null, "still crafting sticks"))
				.thenExecute(() -> helper.assertValueEqual(count(bot, Items.STICK), 4, "sticks"))
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void walksToATableForBigRecipes(GameTestHelper helper) {
		helper.setBlock(11, 1, 11, Blocks.CRAFTING_TABLE);
		ClankerBot bot = spawn(helper, 2.5, 2.5, FakeProvider.silent());
		bot.player().getInventory().add(new ItemStack(Items.OAK_PLANKS, 3));
		bot.player().getInventory().add(new ItemStack(Items.STICK, 2));
		bot.tasks().start(new CraftTask("wooden pickaxe", Matching.item("wooden pickaxe"), 1));
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null, "still crafting"))
				.thenExecute(() -> {
					helper.assertValueEqual(count(bot, Items.WOODEN_PICKAXE), 1, "pickaxes");
					helper.assertTrue(bot.player().position().distanceTo(helper.absoluteVec(new Vec3(11.5, 1, 11.5))) < 5, "didn't go to the table");
				})
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA)
	public void craftingReportsWhatsMissing(GameTestHelper helper) {
		ClankerBot bot = spawn(helper, 2.5, 2.5, FakeProvider.silent());
		bot.player().getInventory().add(new ItemStack(Items.STICK, 2));
		String[] message = new String[1];
		var task = new CraftTask("stone pickaxe", Matching.item("stone pickaxe"), 1);
		var result = task.tick(bot, bot.player());
		helper.assertValueEqual(result.status(), io.github.soymods.clanker.server.skills.Task.Status.FAILED, "status");
		helper.assertTrue(result.message().contains("missing 3 cobblestone"), "message: " + result.message());
		finish(helper, bot);
	}

	@GameTest(structure = ARENA, maxTicks = 900)
	public void smeltsInAFurnace(GameTestHelper helper) {
		helper.setBlock(10, 1, 4, Blocks.FURNACE);
		ClankerBot bot = spawn(helper, 2.5, 2.5, FakeProvider.silent());
		bot.player().getInventory().add(new ItemStack(Items.RAW_IRON, 2));
		bot.player().getInventory().add(new ItemStack(Items.COAL, 1));
		bot.tasks().start(new SmeltTask("raw iron", Matching.item("raw iron"), 2));
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null, "still smelting"))
				.thenExecute(() -> helper.assertValueEqual(count(bot, Items.IRON_INGOT), 2, "iron ingots"))
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void storesAndTakesFromAChest(GameTestHelper helper) {
		helper.setBlock(9, 1, 3, Blocks.CHEST);
		ClankerBot bot = spawn(helper, 2.5, 2.5, FakeProvider.silent());
		bot.player().getInventory().add(new ItemStack(Items.COBBLESTONE, 20));
		bot.player().getInventory().add(new ItemStack(Items.IRON_SWORD));
		bot.tasks().start(new ContainerTask(ContainerTask.Mode.STORE, null, null, null, Integer.MAX_VALUE));
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null, "still storing"))
				.thenExecute(() -> {
					helper.assertValueEqual(count(bot, Items.COBBLESTONE), 0, "cobblestone left in inventory");
					helper.assertValueEqual(count(bot, Items.IRON_SWORD), 1, "the sword should stay");
					bot.tasks().start(new ContainerTask(ContainerTask.Mode.TAKE, null, "cobblestone", Matching.item("cobblestone"), 5));
				})
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null, "still taking"))
				.thenExecute(() -> helper.assertValueEqual(count(bot, Items.COBBLESTONE), 5, "cobblestone taken back"))
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA, maxTicks = 300)
	public void placesABlock(GameTestHelper helper) {
		ClankerBot bot = survivalBot(helper, 2.5, 2.5);
		bot.player().getInventory().add(new ItemStack(Items.COBBLESTONE, 4));
		BlockPos target = helper.absolutePos(new BlockPos(8, 1, 8));
		bot.tasks().start(new PlaceTask("cobblestone", Matching.item("cobblestone"), target));
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null, "still placing"))
				.thenExecute(() -> {
					helper.assertBlockPresent(Blocks.COBBLESTONE, new BlockPos(8, 1, 8));
					helper.assertValueEqual(count(bot, Items.COBBLESTONE), 3, "cobblestone left");
				})
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA, maxTicks = 300)
	public void placesOnTheSpotItStandsOn(GameTestHelper helper) {
		ClankerBot bot = survivalBot(helper, 8.5, 8.5);
		bot.player().getInventory().add(new ItemStack(Items.CRAFTING_TABLE));
		bot.tasks().start(new PlaceTask("crafting table", Matching.item("crafting table"), helper.absolutePos(new BlockPos(8, 1, 8))));
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null, "still placing"))
				.thenExecute(() -> helper.assertBlockPresent(Blocks.CRAFTING_TABLE, new BlockPos(8, 1, 8)))
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA, maxTicks = 300)
	public void placesInSnow(GameTestHelper helper) {
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				helper.setBlock(new BlockPos(x, 1, z), Blocks.SNOW.defaultBlockState());
			}
		}
		helper.setBlock(new BlockPos(6, 1, 3), Blocks.SNOW.defaultBlockState().setValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS, 3));
		ClankerBot bot = survivalBot(helper, 2.5, 2.5);
		bot.player().getInventory().add(new ItemStack(Items.CRAFTING_TABLE, 3));
		AtomicReference<String> nextTo = new AtomicReference<>();
		helper.startSequence()
				.thenIdle(5)
				.thenExecute(() -> nextTo.set(Tools.execute(bot, new ToolCall("1", "place_block", "{\"item\":\"crafting_table\"}"))))
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null, "still placing next to itself"))
				.thenExecute(() -> {
					helper.assertTrue(nextTo.get().startsWith("Done: placed"), "next to itself: " + nextTo.get());
					helper.assertValueEqual(count(bot, Items.CRAFTING_TABLE), 2, "tables left after placing next to itself");
					// a single snow layer is replaced, like grass
					bot.tasks().start(new PlaceTask("crafting table", Matching.item("crafting table"), helper.absolutePos(new BlockPos(3, 1, 6))));
				})
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null, "still placing in thin snow"))
				.thenExecute(() -> {
					helper.assertBlockPresent(Blocks.CRAFTING_TABLE, new BlockPos(3, 1, 6));
					// deeper snow can't be built into, so it goes on top
					bot.tasks().start(new PlaceTask("crafting table", Matching.item("crafting table"), helper.absolutePos(new BlockPos(6, 1, 3))));
				})
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null, "still placing on deep snow"))
				.thenExecute(() -> helper.assertBlockPresent(Blocks.CRAFTING_TABLE, new BlockPos(6, 2, 3)))
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest
	public void botsWearTheSteveSkin(GameTestHelper helper) {
		for (String name : List.of("Clanker", "Bolt", "Clanker2", "x")) {
			helper.assertValueEqual(Math.floorMod(ClankerBots.botId(name).hashCode(), 18), 15, name + "'s default skin");
			helper.assertValueEqual(ClankerBots.botId(name), ClankerBots.botId(name), name + "'s id is stable");
		}
		helper.succeed();
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void newSpeechInterruptsThinking(GameTestHelper helper) {
		java.util.concurrent.CompletableFuture<LlmResponse> held = new java.util.concurrent.CompletableFuture<>();
		List<LlmRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
		ClankerBot bot = spawn(helper, 2.5, 2.5, FakeProvider.silent());
		bot.brain().setProviderOverride(request -> {
			requests.add(request);
			return requests.size() == 1 ? held : java.util.concurrent.CompletableFuture.completedFuture(FakeProvider.reply("ok"));
		});
		ServerPlayer player = mockPlayer(helper, 5.5, 5.5);
		helper.startSequence()
				.thenExecute(() -> bot.brain().hear(player, bot.name() + ", tell me a long story", false))
				.thenWaitUntil(() -> helper.assertTrue(bot.brain().isThinking(), "never started thinking"))
				.thenExecute(() -> bot.brain().hear(player, bot.name() + ", actually never mind", true, true))
				.thenWaitUntil(() -> helper.assertValueEqual(requests.size(), 2, "requests"))
				.thenExecute(() -> {
					String turn = requests.get(1).messages().getLast().content();
					helper.assertTrue(turn.contains("long story") && turn.contains("never mind"), "the new turn should have both lines: " + turn);
					helper.assertTrue(turn.contains("didn't hear the rest"), "the cut-off wasn't reported: " + turn);
					long userTurns = requests.get(1).messages().stream().filter(m -> m.role() == ChatMessage.Role.USER).count();
					helper.assertValueEqual(userTurns, 1L, "abandoned turn left in history");
					// the abandoned answer arriving late is ignored
					held.complete(FakeProvider.reply("Once upon a time"));
				})
				.thenIdle(5)
				.thenExecute(() -> helper.assertFalse(bot.brain().history().stream()
						.anyMatch(m -> m.content() != null && m.content().contains("Once upon")), "late answer was used"))
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA, maxTicks = 200)
	public void eatsWhenHungry(GameTestHelper helper) {
		ClankerBot bot = survivalBot(helper, 2.5, 2.5);
		bot.player().getFoodData().setFoodLevel(10);
		bot.player().getInventory().add(new ItemStack(Items.BREAD, 2));
		bot.tasks().start(new EatTask("bread", Matching.find(bot.player().getInventory(), Matching.item("bread"))));
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null, "still eating"))
				.thenExecute(() -> {
					helper.assertTrue(bot.player().getFoodData().getFoodLevel() > 10, "food didn't go up");
					helper.assertValueEqual(count(bot, Items.BREAD), 1, "bread left");
				})
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA, maxTicks = 600)
	public void fightsAMob(GameTestHelper helper) {
		ClankerBot bot = survivalBot(helper, 2.5, 2.5);
		bot.player().getInventory().add(new ItemStack(Items.IRON_SWORD));
		var pig = helper.spawn(EntityTypes.PIG, new BlockPos(10, 1, 10));
		bot.tasks().start(new AttackTask(pig));
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(!pig.isAlive() || pig.isDeadOrDying(), "pig still alive with " + pig.getHealth() + " health"))
				.thenExecute(() -> helper.assertTrue(bot.player().getMainHandItem().is(Items.IRON_SWORD), "didn't use the sword"))
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA, maxTicks = 300)
	public void givesItemsToAPlayer(GameTestHelper helper) {
		ClankerBot bot = spawn(helper, 2.5, 2.5, FakeProvider.silent());
		ServerPlayer player = mockPlayer(helper, 11.5, 11.5);
		bot.player().getInventory().add(new ItemStack(Items.DIRT, 5));
		bot.tasks().start(new GiveTask(player, "dirt", Matching.item("dirt"), 5));
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(bot.tasks().current() == null, "still giving"))
				.thenExecute(() -> {
					helper.assertValueEqual(count(bot, Items.DIRT), 0, "dirt left");
					helper.assertTrue(bot.player().distanceTo(player) < 4, "didn't walk over first");
				})
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest
	public void namesMatchLoosely(GameTestHelper helper) {
		helper.assertTrue(Matching.block("logs").test(Blocks.SPRUCE_LOG.defaultBlockState()), "logs -> spruce_log");
		helper.assertTrue(Matching.block("trees").test(Blocks.OAK_LOG.defaultBlockState()), "trees -> oak_log");
		helper.assertTrue(Matching.block("iron").test(Blocks.DEEPSLATE_IRON_ORE.defaultBlockState()), "iron -> deepslate_iron_ore");
		helper.assertFalse(Matching.block("iron ore").test(Blocks.IRON_BLOCK.defaultBlockState()), "iron ore !-> iron_block");
		helper.assertTrue(Matching.block("acacia").test(Blocks.ACACIA_LOG.defaultBlockState()), "acacia -> acacia_log");
		helper.assertFalse(Matching.block("acacia").test(Blocks.ACACIA_LEAVES.defaultBlockState()), "acacia !-> acacia_leaves");
		helper.assertTrue(Matching.block("dark oak tree").test(Blocks.DARK_OAK_LOG.defaultBlockState()), "dark oak tree -> dark_oak_log");
		helper.assertTrue(Matching.block("acacia leaves").test(Blocks.ACACIA_LEAVES.defaultBlockState()), "acacia leaves");
		helper.assertTrue(Matching.item("Oak Planks").test(new ItemStack(Items.OAK_PLANKS)), "Oak Planks");
		helper.assertTrue(Matching.item("minecraft:sticks").test(new ItemStack(Items.STICK)), "minecraft:sticks");
		helper.assertFalse(Matching.item("pickaxe").test(new ItemStack(Items.IRON_AXE)), "pickaxe !-> iron_axe");
		helper.succeed();
	}

	@GameTest(structure = ARENA, maxTicks = 200)
	public void failedTurnsDontPileUp(GameTestHelper helper) {
		FakeProvider provider = new FakeProvider(request -> {
			throw new io.github.soymods.clanker.server.brain.llm.LlmException(401, "Your API key has been invalidated.");
		});
		ClankerBot bot = spawn(helper, 2.5, 2.5, provider);
		ServerPlayer player = mockPlayer(helper, 5.5, 5.5);
		helper.startSequence()
				.thenIdle(5)
				.thenExecute(() -> bot.brain().hear(player, bot.name() + ", hello?", false))
				.thenIdle(60)
				.thenExecute(() -> {
					helper.assertTrue(bot.brain().history().isEmpty(), "failed turn left " + bot.brain().history().size() + " messages in history");
					helper.assertTrue(bot.brain().pending().stream().anyMatch(e -> e.text().contains("hello?")), "the question was lost instead of kept for a retry");
					helper.assertTrue(provider.requests.size() <= 2, "retried " + provider.requests.size() + " times without backing off");
					helper.assertTrue(bot.brain().status().contains("401"), "error not reported in status");
				})
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA)
	public void remembersNotesAcrossSessions(GameTestHelper helper) {
		ClankerBot bot = spawn(helper, 2.5, 2.5, FakeProvider.silent());
		String result = Tools.execute(bot, new ToolCall("1", "remember", "{\"note\":\"Base is at 100, 64, -20\"}"));
		helper.assertTrue(result.startsWith("Wrote note #"), result);
		int id = bot.memory().notes().getLast().id();
		helper.assertTrue(bot.memory().promptSection().contains("Base is at 100, 64, -20"), "note missing from prompt");
		helper.assertTrue(io.github.soymods.clanker.server.brain.Prompts.system(bot.name(), "", bot.memory().promptSection()).contains("Your notebook:"), "prompt has no notebook");
		// A fresh load (next session) sees the same note.
		var reloaded = io.github.soymods.clanker.server.brain.Memory.load(helper.getLevel().getServer(), bot.name());
		helper.assertTrue(reloaded.notes().stream().anyMatch(n -> n.text().contains("Base is at")), "note didn't persist");
		helper.assertTrue(Tools.execute(bot, new ToolCall("2", "forget_note", "{\"id\":" + id + "}")).startsWith("Crossed out"), "forget_note");
		helper.assertTrue(bot.memory().notes().isEmpty(), "note not removed");
		finish(helper, bot);
	}

	@GameTest
	public void spendMathAndCaps(GameTestHelper helper) {
		ServerConfig config = new ServerConfig();
		var mini = io.github.soymods.clanker.server.brain.Spend.price(config, "gpt-5-mini-2025-08-07");
		helper.assertValueEqual(mini.input(), 0.25, "gpt-5-mini input price via dated name");
		double cost = io.github.soymods.clanker.server.brain.Spend.cost(mini, new LlmResponse.Usage(1_000_000, 1_000_000, 400_000));
		helper.assertTrue(Math.abs(cost - (0.6 * 0.25 + 0.4 * 0.025 + 1.0 * 2)) < 1e-9, "cost was " + cost);
		helper.assertFalse(io.github.soymods.clanker.server.brain.Spend.isKnown(config, "gpt-9-ultra"), "unknown model should use the fallback");
		config.provider = ServerConfig.Provider.OPENAI_COMPATIBLE;
		helper.assertValueEqual(io.github.soymods.clanker.server.brain.Spend.price(config, "llama3").output(), 0.0, "local models are free");

		ServerConfig tight = new ServerConfig();
		tight.sessionSpendCap = 1e-12;
		io.github.soymods.clanker.server.brain.Spend.record(1e-9);
		helper.assertTrue(io.github.soymods.clanker.server.brain.Spend.capReached(tight) != null, "cap not reached");
		tight.sessionSpendCap = 0;
		tight.dailySpendCap = 0;
		helper.assertTrue(io.github.soymods.clanker.server.brain.Spend.capReached(tight) == null, "0 should mean no cap");
		helper.succeed();
	}

	@GameTest(structure = ARENA, maxTicks = 100)
	public void spendCapBlocksRequests(GameTestHelper helper) {
		FakeProvider provider = FakeProvider.silent();
		ClankerBot bot = spawn(helper, 2.5, 2.5, provider);
		ServerConfig tight = new ServerConfig();
		tight.sessionSpendCap = 1e-12;
		io.github.soymods.clanker.server.brain.Spend.record(1e-9);
		bot.brain().setCapConfigOverride(tight);
		ServerPlayer player = mockPlayer(helper, 5.5, 5.5);
		int before = provider.requests.size();
		helper.startSequence()
				.thenExecute(() -> bot.brain().hear(player, bot.name() + ", hi", false))
				.thenIdle(30)
				.thenExecute(() -> {
					helper.assertValueEqual(provider.requests.size(), before, "requests made past the cap");
					helper.assertTrue(bot.brain().status().contains("spend cap"), "status doesn't mention the cap: " + bot.brain().status());
				})
				.thenExecute(() -> finish(helper, bot));
	}

	@GameTest(structure = ARENA)
	public void hostPermissionsRemoveTools(GameTestHelper helper) {
		ClankerBot bot = spawn(helper, 2.5, 2.5, FakeProvider.silent());
		ServerConfig config = ServerConfig.get();
		boolean before = config.canBreakBlocks;
		try {
			config.canBreakBlocks = false;
			helper.assertFalse(Tools.specs(config).stream().anyMatch(t -> t.name().equals("mine")), "mine still offered");
			helper.assertTrue(Tools.execute(bot, new ToolCall("1", "mine", "{\"block\":\"stone\"}")).contains("turned off"), "mine not refused");
		} finally {
			config.canBreakBlocks = before;
		}
		helper.assertTrue(Tools.specs(config).stream().anyMatch(t -> t.name().equals("mine")), "mine missing when allowed");
		helper.assertFalse(config.canAttackPlayers, "attacking players should be off by default");
		finish(helper, bot);
	}
}
