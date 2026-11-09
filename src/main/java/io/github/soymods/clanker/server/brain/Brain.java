package io.github.soymods.clanker.server.brain;

import io.github.soymods.clanker.Clanker;
import io.github.soymods.clanker.config.ServerConfig;
import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import io.github.soymods.clanker.server.brain.llm.ChatMessage;
import io.github.soymods.clanker.server.brain.llm.LlmException;
import io.github.soymods.clanker.server.brain.llm.LlmProvider;
import io.github.soymods.clanker.server.brain.llm.LlmRequest;
import io.github.soymods.clanker.server.brain.llm.LlmResponse;
import io.github.soymods.clanker.server.brain.llm.OpenAiProvider;
import io.github.soymods.clanker.server.brain.llm.ToolCall;
import io.github.soymods.clanker.server.perception.Perception;
import io.github.soymods.clanker.server.safety.ClankerPermissions;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

/**
 * Decides when Clanker gets a turn, sends the model what happened and what it perceives, and carries out its reply.
 * Runs on the server thread; model requests are asynchronous and their results are applied back on the server thread.
 */
public class Brain {
	public static final String DEFAULT_OPENAI_MODEL = "gpt-5-mini";
	public static final String DEFAULT_XAI_MODEL = "grok-4.20-0309-non-reasoning";

	/** Past this many messages, the oldest are folded into the running summary. */
	private static final int MAX_HISTORY = 60;
	private static final int SUMMARIZE_AT_LEAST = 24;
	/** Dropped outright past this, e.g. while a summary request is still out. */
	private static final int HARD_MAX_HISTORY = 100;
	/** Small noticed things fade from attention with this half-life. */
	private static final long ATTENTION_HALF_LIFE_MS = 45_000;
	/** Small things this old are dropped instead of being brought up in a later turn. */
	private static final long STALE_SMALL_EVENT_MS = 120_000;
	private static final double LOOK_AT_SPEAKER_RANGE = 16;
	private static final int MAX_PENDING = 30;
	private static final int MAX_TOOL_ROUNDS = 3;
	private static final long SPEECH_COOLDOWN_MS = 300;
	private static final long EVENT_COOLDOWN_MS = 4000;
	private static final long CONVERSATION_WINDOW_MS = 30_000;
	private static final long NOTICE_INTERVAL_MS = 60_000;
	private static final long MAX_RETRY_DELAY_MS = 300_000;
	/** A request with no reply by now is dropped, even if the connection never says it failed. */
	private static final long REQUEST_GIVE_UP_MS = 150_000;
	private static final Pattern STOP_WORD = Pattern.compile("\\b(stop|halt|freeze)\\b", Pattern.CASE_INSENSITIVE);

	private final ClankerBot bot;
	private final Pattern namePattern;
	private final List<ChatMessage> history = new ArrayList<>();
	private final List<BrainEvent> pending = new ArrayList<>();
	private final Map<UUID, Long> conversation = new HashMap<>();
	private LlmProvider providerOverride;
	private LlmProvider summaryProviderOverride;
	private ServerConfig capConfig;

	private boolean inFlight;
	private boolean shutdown;
	private int generation;
	private int toolRounds;
	private long lastTurnEnd;
	/** When a turn that small things started last ended. */
	private long lastSmallTurnEnd;
	private boolean turnIsSmall;
	/** Weight of small things noticed since the last turn, fading over time. */
	private double attention;
	private long attentionAt;
	private long lastNotice;
	private String summary = "";
	private boolean summarizing;
	/** Who last talked to Clanker, to face while answering. */
	private UUID lastSpeaker;
	private List<BrainEvent> turnEvents = List.of();
	private int turnStartHistorySize;
	private int errorStreak;
	private long retryAt;
	private SentenceSplitter splitter = new SentenceSplitter();
	private final StringBuilder streamed = new StringBuilder();
	/** What this request has already said out loud while its reply was still arriving. */
	private final StringBuilder spokenThisRequest = new StringBuilder();
	private long requestStartedAt;
	private long firstTextAt;
	/** When someone last spoke to Clanker and it hasn't answered yet, for the timing log. */
	private long heardAt;
	private String heardFrom = "";
	private String lastPhase;
	private String lastWeather;

	private int requests;
	private long inputTokens;
	private long outputTokens;
	private long cachedTokens;
	private double spent;
	private String lastError = "";

	public Brain(ClankerBot bot) {
		this.bot = bot;
		this.namePattern = Pattern.compile("\\b" + Pattern.quote(bot.name()) + "\\b", Pattern.CASE_INSENSITIVE);
	}

	/** Checks spend caps against this config instead of the server's, for tests. */
	public void setCapConfigOverride(ServerConfig config) {
		this.capConfig = config;
	}

	/** Replaces the configured provider, for tests. */
	public void setProviderOverride(LlmProvider provider) {
		this.providerOverride = provider;
	}

	public void post(BrainEvent event) {
		if (shutdown) {
			return;
		}
		pending.add(event);
		if (pending.size() > MAX_PENDING) {
			pending.removeIf(e -> e != event && e.type() != BrainEvent.Type.SPEECH && pending.size() > MAX_PENDING);
		}
		fadeAttention(event.timeMillis());
		attention += event.weight();
	}

	private void fadeAttention(long now) {
		if (attentionAt > 0 && now > attentionAt) {
			attention *= Math.pow(0.5, (double) (now - attentionAt) / ATTENTION_HALF_LIFE_MS);
		}
		attentionAt = Math.max(attentionAt, now);
	}

	/** How much small stuff has caught Clanker's attention since its last turn, for tests and the debug HUD. */
	public double attention() {
		fadeAttention(System.currentTimeMillis());
		return attention;
	}

	/** A player said something, typed or spoken. */
	public void hear(ServerPlayer speaker, String text, boolean voice) {
		hear(speaker, text, voice, false);
	}

	/**
	 * A player said something, typed or spoken. {@code cutOff} means they started talking while Clanker's voice was
	 * still playing for them, and their client stopped it.
	 */
	public void hear(ServerPlayer speaker, String text, boolean voice, boolean cutOff) {
		if (shutdown || text.isBlank()) {
			return;
		}
		long now = System.currentTimeMillis();
		String who = speaker.getName().getString();
		boolean addressed = isAddressed(speaker, text, now);
		if (addressed && heardAt == 0) {
			heardAt = now;
			heardFrom = who;
		}
		if (addressed) {
			// Being spoken to mid-thought: drop the request and think again with everything that's been said.
			abandonRequest();
			lastSpeaker = speaker.getUUID();
			face(speaker);
		}
		// Stopping never waits for the model.
		if (addressed && STOP_WORD.matcher(text).find() && bot.tasks().stop()) {
			post(BrainEvent.of(BrainEvent.Type.NOTE, "Your current task was cancelled because " + who + " said stop."));
		}
		if (cutOff) {
			bot.voice().cancel();
			post(BrainEvent.of(BrainEvent.Type.NOTE, who + " started talking while you were still speaking, so they didn't hear the rest of what you said."));
		}
		post(BrainEvent.of(addressed ? BrainEvent.Type.SPEECH : BrainEvent.Type.OVERHEARD,
				who + (voice ? " said (voice): \"" : " said: \"") + text.strip() + "\""));
		if (addressed) {
			conversation.put(speaker.getUUID(), now);
		}
	}

	private void abandonRequest() {
		if (!inFlight) {
			return;
		}
		generation++;
		inFlight = false;
		keepSpokenOrUndo();
	}

	/**
	 * For a request that ends early: keeps what it already said out loud in the history, so Clanker knows it said it,
	 * or else takes the empty turn back out.
	 */
	private void keepSpokenOrUndo() {
		if (!spokenThisRequest.isEmpty()) {
			history.add(ChatMessage.assistant(spokenThisRequest.toString().strip(), List.of()));
			spokenThisRequest.setLength(0);
		} else {
			undoEmptyTurn();
		}
	}

	/** If the turn hasn't done anything yet, takes it back out of the history and keeps its events for the next one. */
	private void undoEmptyTurn() {
		if (toolRounds == 0 && history.size() > turnStartHistorySize) {
			while (history.size() > turnStartHistorySize) {
				history.removeLast();
			}
			pending.addAll(0, turnEvents);
		}
	}

	public boolean isAddressed(ServerPlayer speaker, String text, long now) {
		if (namePattern.matcher(text).find()) {
			return true;
		}
		ClankerPlayer self = bot.player();
		if (self == null) {
			return false;
		}
		ServerConfig config = ServerConfig.get();
		boolean near = speaker.level() == self.level() && speaker.distanceTo(self) <= config.listenRadius;
		return switch (config.listenMode) {
			case EVERYONE -> true;
			case NEARBY -> near;
			case NAME_ONLY -> near && now - conversation.getOrDefault(speaker.getUUID(), 0L) < CONVERSATION_WINDOW_MS;
		};
	}

	/** After Clanker speaks, nearby players can answer without saying its name. */
	public void onSpoke() {
		ClankerPlayer self = bot.player();
		if (self == null) {
			return;
		}
		long now = System.currentTimeMillis();
		int radius = ServerConfig.get().listenRadius;
		for (ServerPlayer player : self.level().players()) {
			if (player != self && player.distanceTo(self) <= radius) {
				conversation.put(player.getUUID(), now);
			}
		}
	}

	public void tick() {
		ClankerPlayer self = bot.player();
		if (shutdown || self == null) {
			return;
		}
		if (bot.server().getTickCount() % 20 == 0) {
			watchWorld(self);
		}
		long now = System.currentTimeMillis();
		if (inFlight) {
			if (now - requestStartedAt > REQUEST_GIVE_UP_MS) {
				generation++;
				inFlight = false;
				lastTurnEnd = now;
				onError(new LlmException(0, "no reply after " + REQUEST_GIVE_UP_MS / 1000 + " seconds"));
			}
			return;
		}
		// Small things that were never worth a turn don't get brought up minutes later.
		pending.removeIf(e -> !e.triggersTurn() && e.type() != BrainEvent.Type.SPEECH && now - e.timeMillis() > STALE_SMALL_EVENT_MS);
		boolean speech = false;
		boolean big = false;
		for (BrainEvent event : pending) {
			speech |= event.type() == BrainEvent.Type.SPEECH;
			big |= event.triggersTurn();
		}
		// No timers: a turn happens because something did. Small things only count once enough of them pile up.
		ServerConfig.Talkativeness talk = ServerConfig.get().talkativeness;
		fadeAttention(now);
		boolean small = !big && !pending.isEmpty() && attention >= talk.threshold
				&& now - lastSmallTurnEnd >= talk.smallTalkGapMs && humansOnline();
		if (!(big || small) || now - lastTurnEnd < (speech ? SPEECH_COOLDOWN_MS : EVENT_COOLDOWN_MS) || now < retryAt) {
			return;
		}
		turnIsSmall = !big;
		startTurn(self, now);
	}

	private void watchWorld(ClankerPlayer self) {
		if (self.level().dimension() != Level.OVERWORLD) {
			return;
		}
		String time = Perception.timeOfDay(self.level().getOverworldClockTime());
		String phase = time.substring(time.indexOf('(') + 1, time.indexOf(')'));
		if (lastPhase != null && !phase.equals(lastPhase)) {
			post(BrainEvent.of(BrainEvent.Type.TIME_OF_DAY, "It's now " + phase + "."));
		}
		lastPhase = phase;

		String weather = self.level().isThundering() ? "thunderstorm" : self.level().isRaining() ? "rain" : "clear";
		if (lastWeather != null && !weather.equals(lastWeather)) {
			post(BrainEvent.of(BrainEvent.Type.WEATHER, "The weather changed from " + lastWeather + " to " + weather + "."));
		}
		lastWeather = weather;
	}

	private void startTurn(ClankerPlayer self, long now) {
		String cap = Spend.capReached(capConfig != null ? capConfig : ServerConfig.get());
		if (cap != null) {
			pending.removeIf(e -> e.type() != BrainEvent.Type.SPEECH);
			lastTurnEnd = now;
			lastError = "reached " + cap;
			notice(Component.literal("[Clanker] " + bot.name() + " reached " + cap + " and won't make model requests until it's raised in /clanker config."), true);
			return;
		}
		LlmProvider provider = provider();
		if (provider == null) {
			pending.clear();
			attention = 0;
			lastTurnEnd = now;
			notice(Component.literal("[Clanker] No API key is set, so " + bot.name() + " can't think yet. Add one with /clanker config."), false);
			return;
		}
		trimHistory(provider);
		turnStartHistorySize = history.size();
		turnEvents = List.copyOf(pending);
		history.add(ChatMessage.user(formatEvents(now), Perception.snapshot(bot, self)));
		pending.clear();
		attention = 0;
		toolRounds = 0;
		send(provider);
	}

	private void send(LlmProvider provider) {
		inFlight = true;
		int requestGeneration = generation;
		splitter = new SentenceSplitter();
		streamed.setLength(0);
		spokenThisRequest.setLength(0);
		requestStartedAt = System.currentTimeMillis();
		firstTextAt = 0;
		ServerConfig config = ServerConfig.get();
		LlmRequest request = new LlmRequest(Prompts.system(bot.name(), config.personality, bot.memory().promptSection(), summary),
				List.copyOf(history), Tools.specs(config));
		// Text pieces and the finished reply both hop to the server thread in the order they arrived.
		provider.complete(request, piece -> bot.server().execute(() -> {
			if (requestGeneration == generation && !shutdown) {
				onText(piece);
			}
		})).whenComplete((response, error) -> bot.server().execute(() -> {
			if (requestGeneration == generation) {
				onResponse(provider, response, error);
			} else if (response != null) {
				recordUsage(response); // abandoned, but still paid for
			}
		}));
	}

	/** A piece of a reply that's still arriving: speaks each sentence as soon as it's complete. */
	private void onText(String piece) {
		if (firstTextAt == 0) {
			firstTextAt = System.currentTimeMillis();
		}
		streamed.append(piece);
		for (String sentence : splitter.add(piece)) {
			speak(sentence);
		}
	}

	private void speak(String text) {
		if (text.isBlank()) {
			return;
		}
		if (heardAt > 0) {
			Clanker.LOGGER.info("{} started answering {} {}s after hearing them", bot.name(), heardFrom, seconds(System.currentTimeMillis() - heardAt));
			heardAt = 0;
		}
		if (lastSpeaker != null && bot.server().getPlayerList().getPlayer(lastSpeaker) instanceof ServerPlayer listener) {
			face(listener);
		}
		bot.say(text);
		spokenThisRequest.append(text).append(' ');
	}

	private static String seconds(long millis) {
		return String.format(java.util.Locale.ROOT, "%.2f", millis / 1000.0);
	}

	/** Turns to look at someone nearby while talking with them, unless busy with a task that aims somewhere itself. */
	private void face(ServerPlayer player) {
		ClankerPlayer self = bot.player();
		if (self != null && bot.tasks().current() == null && player != self && player.level() == self.level()
				&& player.distanceTo(self) <= LOOK_AT_SPEAKER_RANGE) {
			bot.movement().lookAt(player);
		}
	}

	private void onResponse(LlmProvider provider, LlmResponse response, Throwable error) {
		inFlight = false;
		long now = System.currentTimeMillis();
		lastTurnEnd = now;
		if (turnIsSmall) {
			lastSmallTurnEnd = now;
		}
		if (error != null) {
			Clanker.LOGGER.info("{} model request failed after {}s", bot.name(), seconds(now - requestStartedAt));
			onError(error instanceof CompletionException && error.getCause() != null ? error.getCause() : error);
			return;
		}
		lastError = "";
		errorStreak = 0;
		retryAt = 0;
		recordUsage(response);
		Clanker.LOGGER.info("{} model reply took {}s ({}): {} input tokens ({} cached), {} output, {} tool calls", bot.name(),
				seconds(now - requestStartedAt), firstTextAt > 0 ? "first words after " + seconds(firstTextAt - requestStartedAt) + "s" : "no words",
				response.usage().inputTokens(), response.usage().cachedInputTokens(), response.usage().outputTokens(), response.toolCalls().size());

		history.add(ChatMessage.assistant(response.text(), response.toolCalls()));
		// The rest of the reply: whatever didn't arrive in pieces (all of it, from a provider that doesn't stream).
		String text = response.text();
		String unseen = text.startsWith(streamed.toString()) ? text.substring(streamed.length()) : streamed.isEmpty() ? text : "";
		for (String sentence : splitter.add(unseen)) {
			speak(sentence);
		}
		speak(splitter.flush());
		spokenThisRequest.setLength(0);
		if (response.toolCalls().isEmpty()) {
			heardAt = 0;
			return;
		}
		for (ToolCall call : response.toolCalls()) {
			String result = Tools.execute(bot, call);
			Clanker.LOGGER.info("{} used {} {} -> {}", bot.name(), call.name(), call.arguments(), abbreviate(result));
			history.add(ChatMessage.toolResult(call.id(), result));
		}
		if (++toolRounds < MAX_TOOL_ROUNDS && !shutdown) {
			send(provider);
		}
	}

	private void recordUsage(LlmResponse response) {
		requests++;
		inputTokens += response.usage().inputTokens();
		outputTokens += response.usage().outputTokens();
		cachedTokens += response.usage().cachedInputTokens();
		if (providerOverride == null) {
			double usd = Spend.cost(Spend.price(ServerConfig.get(), currentModel()), response.usage());
			spent += usd;
			Spend.record(usd);
		}
	}

	private static String abbreviate(String text) {
		String line = text.replace('\n', ' ');
		return line.length() > 200 ? line.substring(0, 200) + "…" : line;
	}

	private void onError(Throwable error) {
		// Keep the events for the retry, so failures don't pile up.
		keepSpokenOrUndo();
		errorStreak++;
		retryAt = System.currentTimeMillis() + Math.min(MAX_RETRY_DELAY_MS, 5_000L << Math.min(errorStreak - 1, 6));
		String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
		int status = error instanceof LlmException llm ? llm.status() : 0;
		lastError = (status > 0 ? status + ": " : "") + message;
		Clanker.LOGGER.warn("Model request failed: {}", lastError);
		String hint = status == 401 ? " Check the API key in /clanker config." : status == 404 ? " Check the model name in /clanker config." : "";
		notice(Component.literal("[Clanker] Model request failed (" + lastError + ")." + hint), true);
	}

	/** Shows a system notice (not Clanker speaking), rate-limited. */
	private void notice(Component message, boolean managersOnly) {
		long now = System.currentTimeMillis();
		if (now - lastNotice < NOTICE_INTERVAL_MS) {
			return;
		}
		lastNotice = now;
		Component styled = message.copy().withStyle(ChatFormatting.GRAY);
		for (ServerPlayer player : bot.server().getPlayerList().getPlayers()) {
			if (!(player instanceof ClankerPlayer) && (!managersOnly || ClankerPermissions.canManage(player))) {
				player.sendSystemMessage(styled);
			}
		}
	}

	private String formatEvents(long now) {
		StringBuilder out = new StringBuilder("[Events since your last turn]\n");
		for (BrainEvent event : pending) {
			long ago = (now - event.timeMillis()) / 1000;
			out.append("- ");
			if (ago >= 2) {
				out.append('(').append(ago).append("s ago) ");
			}
			out.append(event.text()).append('\n');
		}
		return out.toString().strip();
	}

	/**
	 * Keeps the history short: past {@link #MAX_HISTORY}, the oldest turns are folded into a running summary (written by
	 * the model in the background), so running jokes and plans outlive the raw messages.
	 */
	private void trimHistory(LlmProvider provider) {
		if (history.size() > MAX_HISTORY && !summarizing) {
			int cut = -1;
			for (int i = SUMMARIZE_AT_LEAST; i < history.size(); i++) {
				if (history.get(i).role() == ChatMessage.Role.USER) {
					cut = i;
					break;
				}
			}
			if (cut > 0) {
				List<ChatMessage> old = new ArrayList<>(history.subList(0, cut));
				history.subList(0, cut).clear();
				summarize(summaryProviderOverride != null ? summaryProviderOverride : provider, old);
			}
		}
		while (history.size() > HARD_MAX_HISTORY) {
			history.removeFirst();
			// Never start the history with an orphaned assistant reply or tool result.
			while (!history.isEmpty() && history.getFirst().role() != ChatMessage.Role.USER) {
				history.removeFirst();
			}
		}
	}

	private void summarize(LlmProvider provider, List<ChatMessage> old) {
		StringBuilder transcript = new StringBuilder();
		for (ChatMessage message : old) {
			switch (message.role()) {
				case USER -> transcript.append(message.content()).append('\n');
				case ASSISTANT -> {
					if (message.content() != null && !message.content().isBlank()) {
						transcript.append("You said: ").append(message.content().strip()).append('\n');
					}
					for (ToolCall call : message.toolCalls()) {
						transcript.append("You used ").append(call.name()).append(' ').append(call.arguments()).append('\n');
					}
				}
				case TOOL -> transcript.append("Result: ").append(abbreviate(message.content())).append('\n');
			}
		}
		String input = (summary.isBlank() ? "No previous recollection." : "Previous recollection:\n" + summary)
				+ "\n\nOldest part of the conversation:\n" + transcript.toString().strip();
		summarizing = true;
		int requestGeneration = generation;
		provider.complete(new LlmRequest(Prompts.SUMMARIZER, List.of(ChatMessage.user(input, null)), List.of()))
				.whenComplete((response, error) -> bot.server().execute(() -> {
					summarizing = false;
					if (response != null) {
						recordUsage(response);
					}
					if (requestGeneration != generation && history.isEmpty()) {
						return; // the conversation was cleared meanwhile
					}
					if (error != null) {
						Clanker.LOGGER.warn("{} couldn't summarize older conversation: {}", bot.name(), error.getMessage());
					} else if (!response.text().isBlank()) {
						summary = response.text().strip();
						Clanker.LOGGER.info("{} summarized {} older messages", bot.name(), old.size());
					}
				}));
	}

	/** The running summary of conversation that's no longer in the history. */
	public String summary() {
		return summary;
	}

	/** Uses this provider for summaries instead of the turn provider, for tests. */
	public void setSummaryProviderOverride(LlmProvider provider) {
		this.summaryProviderOverride = provider;
	}

	private boolean humansOnline() {
		for (ServerPlayer player : bot.server().getPlayerList().getPlayers()) {
			if (!(player instanceof ClankerPlayer)) {
				return true;
			}
		}
		return false;
	}

	private String currentModel() {
		return currentModel(ServerConfig.get());
	}

	/** The model the config asks for, with the provider's default filled in. */
	public static String currentModel(ServerConfig config) {
		if (!config.model.isBlank()) {
			return config.model;
		}
		return switch (config.provider) {
			case OPENAI -> DEFAULT_OPENAI_MODEL;
			case XAI -> DEFAULT_XAI_MODEL;
			case OPENAI_COMPATIBLE -> "";
		};
	}

	private LlmProvider provider() {
		if (providerOverride != null) {
			return providerOverride;
		}
		ServerConfig config = ServerConfig.get();
		String key = config.resolveApiKey();
		return switch (config.provider) {
			case OPENAI -> key.isBlank() ? null
					: new OpenAiProvider(OpenAiProvider.OPENAI_BASE_URL, key, currentModel(config), config.reasoningEffort);
			case XAI -> key.isBlank() ? null
					: new OpenAiProvider(OpenAiProvider.XAI_BASE_URL, key, currentModel(config), config.reasoningEffort);
			case OPENAI_COMPATIBLE -> config.endpoint.isBlank() || config.model.isBlank() ? null
					: new OpenAiProvider(config.endpoint, key, config.model, config.reasoningEffort);
		};
	}

	/** Try again right away, e.g. after the host changes the API key or model. */
	public void resetBackoff() {
		errorStreak = 0;
		retryAt = 0;
		bot.voice().resetBackoff();
	}

	/** Forget the conversation (not long-term memory, which arrives in 0.5). */
	public void clearConversation() {
		generation++;
		inFlight = false;
		history.clear();
		pending.clear();
		summary = "";
		attention = 0;
	}

	public void shutdown() {
		shutdown = true;
		generation++;
		inFlight = false;
		bot.voice().shutdown();
	}

	public boolean isThinking() {
		return inFlight;
	}

	public String status() {
		ServerConfig config = ServerConfig.get();
		String estimate = Spend.isKnown(config, currentModel()) ? "" : " (rough: unknown model price)";
		return currentModel() + ": " + requests + " model requests, " + inputTokens + " input tokens (" + cachedTokens + " cached), "
				+ outputTokens + " output tokens, about $" + String.format(java.util.Locale.ROOT, "%.4f", spent) + estimate
				+ (lastError.isEmpty() ? "" : "; last error: " + lastError);
	}

	public List<ChatMessage> history() {
		return List.copyOf(history);
	}

	/** Events waiting for the next turn. */
	public List<BrainEvent> pending() {
		return List.copyOf(pending);
	}
}
