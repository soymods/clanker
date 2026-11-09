package io.github.soymods.clanker.test;

import io.github.soymods.clanker.config.Personalities;
import io.github.soymods.clanker.config.ServerConfig;
import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerBots;
import io.github.soymods.clanker.server.brain.BrainEvent;
import io.github.soymods.clanker.server.brain.Prompts;
import io.github.soymods.clanker.server.brain.llm.LlmRequest;
import io.github.soymods.clanker.server.voice.VoiceCues;
import io.github.soymods.clanker.server.voice.VoiceEngine;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Clanker as a companion: how it decides to speak up, what it notices, how its voice cues and host voice work, and how
 * it remembers older conversation. Nothing here reaches a real API.
 * <p>
 * Ticks run unthrottled, so anything that waits on wall-clock time uses a deadline instead of maxTicks.
 */
@SuppressWarnings("removal")
public class ClankerCompanionTests {
	private static final String ARENA = "clanker-test:arena";

	static {
		TestSetup.isolate();
	}

	@GameTest
	public void voiceCuesTranslate(GameTestHelper helper) {
		String line = "[laugh] Oh no. <whisper>Don't look down.</whisper> [gasp] [long-pause] Okay.";
		helper.assertValueEqual(VoiceCues.forDisplay(line), "Oh no. Don't look down. Okay.", "display text");
		helper.assertValueEqual(VoiceCues.forXai(line), "[laugh] Oh no. <whisper>Don't look down.</whisper> [inhale] [long-pause] Okay.", "xAI");
		helper.assertValueEqual(VoiceCues.forElevenLabs(line), "[laughs] Oh no. [whispers] Don't look down. [gasps] … … Okay.", "ElevenLabs");
		helper.assertValueEqual(VoiceCues.forXai("[mutters] fine <shout>NO</shout>"), "fine NO", "xAI drops what it doesn't know");
		helper.assertValueEqual(VoiceCues.forElevenLabs("[mutters] fine"), "[mutters] fine", "ElevenLabs keeps free-form directions");
		helper.assertTrue(VoiceCues.hasCues("[laugh]") && VoiceCues.forDisplay("[laugh]").isEmpty(), "a line can be just a sound");
		helper.assertValueEqual(VoiceCues.forDisplay("Look, [chuckle] a creeper , right there!"), "Look, a creeper, right there!", "spacing");
		helper.succeed();
	}

	@GameTest
	public void personalityPresets(GameTestHelper helper) {
		String sarcastic = Personalities.PRESETS.get("Sarcastic");
		helper.assertValueEqual(Personalities.resolve(""), Personalities.PRESETS.get(Personalities.DEFAULT), "blank uses the default");
		helper.assertValueEqual(Personalities.presetName(sarcastic), "Sarcastic", "preset name");
		helper.assertValueEqual(Personalities.presetName("loves cake"), "Custom", "custom text");
		String prompt = Prompts.system("Clanker", "loves cake", "", "You and Ryder built a hut.");
		helper.assertTrue(prompt.contains("loves cake") && prompt.contains("You and Ryder built a hut."), "prompt is missing personality or summary");
		helper.assertTrue(prompt.contains("[laugh]") && prompt.contains("<whisper>"), "prompt doesn't explain voice cues");
		helper.succeed();
	}

	/** No timers: with nothing happening Clanker never gets a turn; small things count only once they add up. */
	@GameTest(structure = ARENA, maxTicks = Integer.MAX_VALUE)
	public void smallThingsAddUpWithoutATimer(GameTestHelper helper) {
		FakeProvider provider = FakeProvider.silent();
		ClankerBot bot = ClankerGameTests.spawn(helper, 2.5, 2.5, provider);
		ClankerGameTests.mockPlayer(helper, 6.5, 6.5);
		ServerConfig.get().talkativeness = ServerConfig.Talkativeness.NORMAL;
		long start = System.currentTimeMillis();
		long[] mark = {0};
		int[] before = {0};
		helper.startSequence()
				// Joining the world is big enough for a turn of its own; let it pass first.
				.thenWaitUntil(() -> helper.assertTrue(!provider.requests.isEmpty() && !bot.brain().isThinking(), "waiting for the spawn turn"))
				.thenIdle(5)
				.thenExecute(() -> {
					before[0] = provider.requests.size();
					bot.brain().post(BrainEvent.of(BrainEvent.Type.SAW, "Came into view: sheep 8 blocks east.", 0.3));
					mark[0] = System.currentTimeMillis();
				})
				// A small thing alone isn't worth a turn, and no amount of quiet waiting changes that.
				.thenWaitUntil(() -> {
					if (provider.requests.size() > before[0]) {
						throw new IllegalStateException("got a turn for one small thing, or for nothing: "
								+ provider.requests.getLast().messages().getLast().content());
					}
					helper.assertTrue(System.currentTimeMillis() - mark[0] > 1500, "waiting");
				})
				.thenExecute(() -> {
					bot.brain().post(BrainEvent.of(BrainEvent.Type.PLAYER_ACTIVITY, "Steve dropped about 9 blocks into water.", 0.6));
					bot.brain().post(BrainEvent.of(BrainEvent.Type.SAW, "You can now see: iron_ore 5 blocks east.", 0.3));
				})
				.thenWaitUntil(() -> {
					if (System.currentTimeMillis() - start > 30_000) {
						throw new IllegalStateException("small things never added up to a turn");
					}
					helper.assertTrue(provider.requests.size() > before[0], "waiting for the turn");
				})
				.thenExecute(() -> {
					String turn = provider.requests.get(before[0]).messages().getLast().content();
					if (!(turn.contains("sheep") && turn.contains("dropped about 9 blocks") && turn.contains("iron_ore"))) {
						throw new IllegalStateException("the turn should carry everything noticed: " + turn);
					}
					if (bot.brain().attention() >= 0.01) {
						throw new IllegalStateException("attention wasn't spent by the turn: " + bot.brain().attention());
					}
				})
				.thenExecute(() -> ClankerGameTests.finish(helper, bot));
	}

	@GameTest(structure = ARENA, maxTicks = Integer.MAX_VALUE)
	public void noticesWhatPlayersDo(GameTestHelper helper) {
		FakeProvider provider = FakeProvider.silent();
		ClankerBot bot = ClankerGameTests.spawn(helper, 2.5, 2.5, provider);
		ServerPlayer player = ClankerGameTests.mockPlayer(helper, 6.5, 6.5);
		player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
		String name = player.getName().getString();
		long start = System.currentTimeMillis();
		helper.startSequence()
				.thenExecute(() -> player.getInventory().add(new ItemStack(Items.COBBLESTONE, 32)))
				.thenIdle(60) // the observer has a baseline inventory
				.thenExecute(() -> {
					player.getInventory().clearContent();
					player.hurtServer(helper.getLevel(), helper.getLevel().damageSources().fall(), 5);
				})
				.thenWaitUntil(() -> {
					if (System.currentTimeMillis() - start > 30_000) {
						throw new IllegalStateException("never noticed: " + bot.brain().pending() + " / " + provider.requests.size() + " requests");
					}
					helper.assertTrue(ClankerGameTests.knows(bot, provider, name + " took 5 damage from fall"), "waiting for the damage");
					helper.assertTrue(ClankerGameTests.knows(bot, provider, name + " used up or got rid of 32 cobblestone"), "waiting for the cobblestone");
				})
				.thenExecute(() -> ClankerGameTests.finish(helper, bot));
	}

	/** A stand-in cloud voice: records what it was asked to say and hands back a little silence. */
	private static final class FakeVoice implements VoiceEngine {
		final List<String> said = new CopyOnWriteArrayList<>();
		final boolean fail;

		FakeVoice(boolean fail) {
			this.fail = fail;
		}

		@Override
		public String prepare(String text) {
			return VoiceCues.forXai(text);
		}

		@Override
		public void speak(String text, Consumer<byte[]> onAudio, BooleanSupplier cancelled) throws java.io.IOException {
			said.add(text);
			if (fail) {
				throw new VoiceException(500, "test voice is down");
			}
			for (int i = 0; i < 3 && !cancelled.getAsBoolean(); i++) {
				onAudio.accept(new byte[CHUNK_BYTES]);
			}
		}

		@Override
		public String model() {
			return "test";
		}
	}

	@GameTest(structure = ARENA, maxTicks = Integer.MAX_VALUE)
	public void hostVoiceSpeaksLines(GameTestHelper helper) {
		ClankerBot bot = ClankerGameTests.spawn(helper, 2.5, 2.5, FakeProvider.silent());
		FakeVoice voice = new FakeVoice(false);
		bot.voice().setEngineOverride(voice);
		long start = System.currentTimeMillis();
		helper.startSequence()
				.thenExecute(() -> {
					bot.say("[laugh] You fell in <emphasis>again</emphasis>.");
					bot.say("[sigh]");
				})
				.thenWaitUntil(() -> {
					if (System.currentTimeMillis() - start > 10_000) {
						throw new IllegalStateException("voice never finished: " + bot.voice().status());
					}
					helper.assertTrue(bot.voice().status().contains("2 lines"), "waiting for the voice");
				})
				.thenExecute(() -> {
					helper.assertValueEqual(voice.said, List.of("[laugh] You fell in <emphasis>again</emphasis>.", "[sigh]"), "voiced text");
					helper.assertValueEqual(bot.lastSpoken(), "[sigh]", "a sound-only line still counts as speech");
					helper.assertValueEqual(bot.spokenCount(), 2, "lines");
				})
				.thenExecute(() -> ClankerGameTests.finish(helper, bot));
	}

	@GameTest(structure = ARENA, maxTicks = Integer.MAX_VALUE)
	public void failingHostVoiceFallsBackToLocal(GameTestHelper helper) {
		ClankerBot bot = ClankerGameTests.spawn(helper, 2.5, 2.5, FakeProvider.silent());
		FakeVoice voice = new FakeVoice(true);
		bot.voice().setEngineOverride(voice);
		long start = System.currentTimeMillis();
		helper.startSequence()
				.thenExecute(() -> bot.say("Hello there."))
				.thenWaitUntil(() -> {
					if (System.currentTimeMillis() - start > 10_000) {
						throw new IllegalStateException("failure never registered: " + bot.voice().status());
					}
					helper.assertTrue(bot.voice().status().contains("test voice is down"), "waiting for the failure");
				})
				.thenExecute(() -> {
					// While it's failing, lines go to players' local voices instead of the host.
					helper.assertValueEqual(bot.voice().submit("Still here."), 0, "line id while failing");
					bot.say("[laugh]");
					helper.assertValueEqual(bot.spokenCount(), 1, "a lone sound can't be said by a local voice, so it's skipped");
					bot.voice().resetBackoff();
					helper.assertTrue(bot.voice().submit("Back.") > 0, "trying the host voice again after a reset");
				})
				.thenExecute(() -> ClankerGameTests.finish(helper, bot));
	}

	/** Old conversation is folded into a summary instead of being forgotten outright. */
	@GameTest(structure = ARENA, maxTicks = Integer.MAX_VALUE)
	public void summarizesOlderConversation(GameTestHelper helper) {
		AtomicInteger turns = new AtomicInteger();
		FakeProvider provider = new FakeProvider(request -> FakeProvider.reply("Reply " + turns.incrementAndGet() + "."));
		FakeProvider summarizer = new FakeProvider(request -> FakeProvider.reply("You promised Steve a diamond."));
		ClankerBot bot = ClankerGameTests.spawn(helper, 2.5, 2.5, provider);
		bot.brain().setSummaryProviderOverride(summarizer);
		ServerPlayer player = ClankerGameTests.mockPlayer(helper, 5.5, 5.5);
		long start = System.currentTimeMillis();
		int[] asked = {0};
		helper.startSequence()
				.thenWaitUntil(() -> {
					if (System.currentTimeMillis() - start > 60_000) {
						throw new IllegalStateException("no summary after " + asked[0] + " questions; history " + bot.brain().history().size());
					}
					if (!bot.brain().isThinking() && bot.brain().pending().isEmpty() && turns.get() >= asked[0]) {
						asked[0]++;
						bot.brain().hear(player, bot.name() + ", question " + asked[0], false);
					}
					helper.assertFalse(bot.brain().summary().isEmpty(), "waiting for a summary");
				})
				.thenExecute(() -> {
					LlmRequest request = summarizer.requests.getFirst();
					helper.assertTrue(request.tools().isEmpty(), "summaries don't need tools");
					String input = request.messages().getFirst().content();
					helper.assertTrue(input.contains("question 1") && input.contains("You said: Reply 1."), "summary input: " + input);
					helper.assertTrue(bot.brain().history().size() <= 60, "history wasn't trimmed: " + bot.brain().history().size());
					bot.brain().hear(player, bot.name() + ", one more", false);
				})
				.thenWaitUntil(() -> helper.assertTrue(provider.requests.getLast().system().contains("You promised Steve a diamond."),
						"the summary isn't in the prompt"))
				.thenExecute(() -> ClankerGameTests.finish(helper, bot));
	}

	@GameTest(structure = ARENA)
	public void spawnedBotsLookAtWhoTalksToThem(GameTestHelper helper) {
		ClankerBot bot = ClankerGameTests.spawn(helper, 2.5, 2.5, FakeProvider.silent());
		ServerPlayer player = ClankerGameTests.mockPlayer(helper, 2.5, 8.5);
		float before = bot.player().getYRot();
		helper.startSequence()
				.thenExecute(() -> bot.brain().hear(player, bot.name() + ", over here", false))
				.thenIdle(10)
				.thenExecute(() -> {
					// The player is due south (+z), which is yaw 0.
					float yaw = net.minecraft.util.Mth.wrapDegrees(bot.player().getYRot());
					helper.assertTrue(Math.abs(yaw) < 20, "bot didn't turn to the speaker: yaw " + yaw + " (was " + before + ")");
				})
				.thenExecute(() -> ClankerBots.despawn(bot))
				.thenSucceed();
	}
}
