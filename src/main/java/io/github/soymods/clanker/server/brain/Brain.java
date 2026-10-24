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

	private static final int MAX_HISTORY = 40;
	private static final int MAX_PENDING = 30;
	private static final int MAX_TOOL_ROUNDS = 3;
	private static final long SPEECH_COOLDOWN_MS = 300;
	private static final long EVENT_COOLDOWN_MS = 4000;
	private static final long CONVERSATION_WINDOW_MS = 30_000;
	private static final long NOTICE_INTERVAL_MS = 60_000;
	private static final long MAX_RETRY_DELAY_MS = 300_000;
	private static final Pattern STOP_WORD = Pattern.compile("\\b(stop|halt|freeze)\\b", Pattern.CASE_INSENSITIVE);

	private final ClankerBot bot;
	private final Pattern namePattern;
	private final List<ChatMessage> history = new ArrayList<>();
	private final List<BrainEvent> pending = new ArrayList<>();
	private final Map<UUID, Long> conversation = new HashMap<>();
	private LlmProvider providerOverride;
	private ServerConfig capConfig;

	private boolean inFlight;
	private boolean shutdown;
	private int generation;
	private int toolRounds;
	private long lastTurnEnd;
	private long lastActivity = System.currentTimeMillis();
	private long lastNotice;
	private List<BrainEvent> turnEvents = List.of();
	private int turnStartHistorySize;
	private int errorStreak;
	private long retryAt;
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
		if (event.type().triggersTurn) {
			lastActivity = event.timeMillis();
		}
	}

	/** A player said something, typed or spoken. */
	public void hear(ServerPlayer speaker, String text, boolean voice) {
		if (shutdown || text.isBlank()) {
			return;
		}
		long now = System.currentTimeMillis();
		String who = speaker.getName().getString();
		boolean addressed = isAddressed(speaker, text, now);
		// Stopping never waits for the model.
		if (addressed && STOP_WORD.matcher(text).find() && bot.tasks().stop()) {
			post(BrainEvent.of(BrainEvent.Type.NOTE, "Your current task was cancelled because " + who + " said stop."));
		}
		post(BrainEvent.of(addressed ? BrainEvent.Type.SPEECH : BrainEvent.Type.OVERHEARD,
				who + (voice ? " said (voice): \"" : " said: \"") + text.strip() + "\""));
		if (addressed) {
			conversation.put(speaker.getUUID(), now);
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
		if (inFlight) {
			return;
		}
		long now = System.currentTimeMillis();
		int idleSeconds = ServerConfig.get().idleSeconds;
		if (idleSeconds > 0 && pending.isEmpty() && humansOnline() && now - lastActivity > idleSeconds * 1000L) {
			post(BrainEvent.of(BrainEvent.Type.IDLE, "Nothing new has happened for " + (now - lastActivity) / 1000 + " seconds."));
		}
		boolean speech = false;
		boolean trigger = false;
		for (BrainEvent event : pending) {
			speech |= event.type() == BrainEvent.Type.SPEECH;
			trigger |= event.type().triggersTurn;
		}
		if (!trigger || now - lastTurnEnd < (speech ? SPEECH_COOLDOWN_MS : EVENT_COOLDOWN_MS) || now < retryAt) {
			return;
		}
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
			lastTurnEnd = now;
			lastActivity = now;
			notice(Component.literal("[Clanker] No API key is set, so " + bot.name() + " can't think yet. Add one with /clanker config."), false);
			return;
		}
		turnStartHistorySize = history.size();
		turnEvents = List.copyOf(pending);
		history.add(ChatMessage.user(formatEvents(now), Perception.snapshot(bot, self)));
		pending.clear();
		trimHistory();
		toolRounds = 0;
		send(provider);
	}

	private void send(LlmProvider provider) {
		inFlight = true;
		int requestGeneration = generation;
		ServerConfig config = ServerConfig.get();
		LlmRequest request = new LlmRequest(Prompts.system(bot.name(), config.personality, bot.memory().promptSection()), List.copyOf(history), Tools.specs(config));
		provider.complete(request).whenComplete((response, error) -> bot.server().execute(() -> {
			if (requestGeneration == generation) {
				onResponse(provider, response, error);
			}
		}));
	}

	private void onResponse(LlmProvider provider, LlmResponse response, Throwable error) {
		inFlight = false;
		long now = System.currentTimeMillis();
		lastTurnEnd = now;
		lastActivity = now;
		if (error != null) {
			onError(error instanceof CompletionException && error.getCause() != null ? error.getCause() : error);
			return;
		}
		lastError = "";
		errorStreak = 0;
		retryAt = 0;
		requests++;
		inputTokens += response.usage().inputTokens();
		outputTokens += response.usage().outputTokens();
		cachedTokens += response.usage().cachedInputTokens();
		if (providerOverride == null) {
			double usd = Spend.cost(Spend.price(ServerConfig.get(), currentModel()), response.usage());
			spent += usd;
			Spend.record(usd);
		}

		history.add(ChatMessage.assistant(response.text(), response.toolCalls()));
		if (!response.text().isBlank()) {
			bot.say(response.text());
		}
		if (response.toolCalls().isEmpty()) {
			return;
		}
		for (ToolCall call : response.toolCalls()) {
			String result = Tools.execute(bot, call);
			Clanker.LOGGER.debug("{} {} -> {}", call.name(), call.arguments(), result);
			history.add(ChatMessage.toolResult(call.id(), result));
		}
		if (++toolRounds < MAX_TOOL_ROUNDS && !shutdown) {
			send(provider);
		}
	}

	private void onError(Throwable error) {
		if (toolRounds == 0 && history.size() > turnStartHistorySize) {
			// Nothing happened this turn: undo it and keep its events for the retry, so failures don't pile up.
			while (history.size() > turnStartHistorySize) {
				history.removeLast();
			}
			pending.addAll(0, turnEvents);
		}
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

	private void trimHistory() {
		while (history.size() > MAX_HISTORY) {
			history.removeFirst();
			// Never start the history with an orphaned assistant reply or tool result.
			while (!history.isEmpty() && history.getFirst().role() != ChatMessage.Role.USER) {
				history.removeFirst();
			}
		}
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
		ServerConfig config = ServerConfig.get();
		return config.model.isBlank() && config.provider == ServerConfig.Provider.OPENAI ? DEFAULT_OPENAI_MODEL : config.model;
	}

	private LlmProvider provider() {
		if (providerOverride != null) {
			return providerOverride;
		}
		ServerConfig config = ServerConfig.get();
		String key = config.resolveApiKey();
		return switch (config.provider) {
			case OPENAI -> key.isBlank() ? null
					: new OpenAiProvider(OpenAiProvider.OPENAI_BASE_URL, key, config.model.isBlank() ? DEFAULT_OPENAI_MODEL : config.model, config.reasoningEffort);
			case OPENAI_COMPATIBLE -> config.endpoint.isBlank() || config.model.isBlank() ? null
					: new OpenAiProvider(config.endpoint, key, config.model, config.reasoningEffort);
		};
	}

	/** Try again right away, e.g. after the host changes the API key or model. */
	public void resetBackoff() {
		errorStreak = 0;
		retryAt = 0;
	}

	/** Forget the conversation (not long-term memory, which arrives in 0.5). */
	public void clearConversation() {
		generation++;
		inFlight = false;
		history.clear();
		pending.clear();
	}

	public void shutdown() {
		shutdown = true;
		generation++;
		inFlight = false;
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
