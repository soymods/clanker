package io.github.soymods.clanker.server.brain.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * OpenAI Chat Completions, which also covers OpenAI-compatible servers (Ollama, OpenRouter, LM Studio…). Replies are
 * streamed so the text can be spoken while the rest is still being written.
 */
public class OpenAiProvider implements LlmProvider {
	public static final String OPENAI_BASE_URL = "https://api.openai.com/v1";

	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);
	/** Reasoning efforts from least to most; a model that rejects one gets the lowest it says it supports. */
	private static final List<String> EFFORTS = List.of("none", "minimal", "low", "medium", "high", "xhigh");
	/**
	 * Endpoint + model pairs that rejected the configured {@code reasoning_effort}, and what to send instead ("" for
	 * none at all: non-reasoning models, some compatible servers).
	 */
	private static final Map<String, String> EFFORT_FALLBACK = new ConcurrentHashMap<>();
	/** Endpoint + model pairs that rejected {@code stream_options}. */
	private static final Set<String> NO_STREAM_OPTIONS = ConcurrentHashMap.newKeySet();
	/** Reading a stream blocks, so it happens here rather than on the HTTP client's own threads. */
	private static final ExecutorService READERS = Executors.newCachedThreadPool(runnable -> {
		Thread thread = new Thread(runnable, "Clanker model stream");
		thread.setDaemon(true);
		return thread;
	});

	private final String baseUrl;
	private final String apiKey;
	private final String model;
	private final String reasoningEffort;

	public OpenAiProvider(String baseUrl, String apiKey, String model, String reasoningEffort) {
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.apiKey = apiKey;
		this.model = model;
		this.reasoningEffort = reasoningEffort;
	}

	@Override
	public CompletableFuture<LlmResponse> complete(LlmRequest request) {
		return complete(request, text -> {
		});
	}

	@Override
	public CompletableFuture<LlmResponse> complete(LlmRequest request, Consumer<String> onText) {
		return send(request, onText, 0);
	}

	/** Sends, and on a 400 about an optional parameter, adjusts it and tries again (a couple of times at most). */
	private CompletableFuture<LlmResponse> send(LlmRequest request, Consumer<String> onText, int retries) {
		String effort = effort();
		boolean streamOptions = !NO_STREAM_OPTIONS.contains(key());
		return stream(request, onText).exceptionallyCompose(error -> {
			Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
			if (retries < 2 && cause instanceof LlmException e && e.status() == 400) {
				String message = e.getMessage().toLowerCase(Locale.ROOT);
				if (streamOptions && message.contains("stream_options")) {
					NO_STREAM_OPTIONS.add(key());
					return send(request, onText, retries + 1);
				}
				if (!effort.isEmpty() && message.contains("reasoning")) {
					EFFORT_FALLBACK.put(key(), lowestSupported(message, effort));
					return send(request, onText, retries + 1);
				}
			}
			return CompletableFuture.failedFuture(cause);
		});
	}

	private String key() {
		return baseUrl + "|" + model;
	}

	/** The {@code reasoning_effort} to send, or "" for none. */
	private String effort() {
		return EFFORT_FALLBACK.getOrDefault(key(), reasoningEffort.strip());
	}

	/**
	 * After a model rejects {@code rejected}: the lowest effort its error lists as supported ("Supported values are:
	 * 'low', 'medium', and 'high'"), or "" to leave the parameter out.
	 */
	public static String lowestSupported(String message, String rejected) {
		int list = message.indexOf("supported values");
		if (list >= 0) {
			String supported = message.substring(list);
			for (String effort : EFFORTS) {
				if (!effort.equals(rejected) && supported.contains("'" + effort + "'")) {
					return effort;
				}
			}
		}
		return "";
	}

	private CompletableFuture<LlmResponse> stream(LlmRequest request, Consumer<String> onText) {
		HttpRequest.Builder http = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
				.timeout(REQUEST_TIMEOUT)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(buildBody(request))));
		if (!apiKey.isBlank()) {
			http.header("Authorization", "Bearer " + apiKey);
		}
		return HTTP.sendAsync(http.build(), HttpResponse.BodyHandlers.ofLines())
				.handleAsync((response, error) -> {
					if (error != null) {
						Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
						throw new LlmException(0, cause.getClass().getSimpleName() + ": " + cause.getMessage());
					}
					try (Stream<String> lines = response.body()) {
						if (response.statusCode() / 100 != 2) {
							throw new LlmException(response.statusCode(), errorMessage(lines.collect(Collectors.joining("\n"))));
						}
						// The request timeout only covers the wait for headers; give up on a stream that stalls.
						AtomicBoolean done = new AtomicBoolean();
						AtomicBoolean timedOut = new AtomicBoolean();
						CompletableFuture.delayedExecutor(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS).execute(() -> {
							if (!done.get()) {
								timedOut.set(true);
								lines.close();
							}
						});
						try {
							return readStream(lines, onText);
						} catch (UncheckedIOException e) {
							throw new LlmException(0, timedOut.get() ? "the reply stopped arriving" : "IOException: " + e.getMessage());
						} finally {
							done.set(true);
						}
					}
				}, READERS);
	}

	/** Lists model IDs from {@code GET /models}. */
	public CompletableFuture<List<String>> listModels() {
		HttpRequest.Builder http = HttpRequest.newBuilder(URI.create(baseUrl + "/models")).timeout(REQUEST_TIMEOUT).GET();
		if (!apiKey.isBlank()) {
			http.header("Authorization", "Bearer " + apiKey);
		}
		return HTTP.sendAsync(http.build(), HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			if (response.statusCode() / 100 != 2) {
				throw new LlmException(response.statusCode(), errorMessage(response.body()));
			}
			List<String> ids = new ArrayList<>();
			for (JsonElement model : GSON.fromJson(response.body(), JsonObject.class).getAsJsonArray("data")) {
				ids.add(model.getAsJsonObject().get("id").getAsString());
			}
			return ids;
		});
	}

	public JsonObject buildBody(LlmRequest request) {
		JsonObject body = new JsonObject();
		body.addProperty("model", model);

		JsonArray messages = new JsonArray();
		JsonObject system = new JsonObject();
		system.addProperty("role", "system");
		system.addProperty("content", request.system());
		messages.add(system);

		int lastUser = -1;
		for (int i = 0; i < request.messages().size(); i++) {
			if (request.messages().get(i).role() == ChatMessage.Role.USER) {
				lastUser = i;
			}
		}
		for (int i = 0; i < request.messages().size(); i++) {
			messages.add(toJson(request.messages().get(i), i == lastUser));
		}
		body.add("messages", messages);

		if (!request.tools().isEmpty()) {
			JsonArray tools = new JsonArray();
			for (ToolSpec spec : request.tools()) {
				JsonObject function = new JsonObject();
				function.addProperty("name", spec.name());
				function.addProperty("description", spec.description());
				function.add("parameters", spec.parameters());
				JsonObject tool = new JsonObject();
				tool.addProperty("type", "function");
				tool.add("function", function);
				tools.add(tool);
			}
			body.add("tools", tools);
		}
		String effort = effort();
		if (!effort.isEmpty()) {
			body.addProperty("reasoning_effort", effort);
		}
		body.addProperty("stream", true);
		if (!NO_STREAM_OPTIONS.contains(key())) {
			JsonObject options = new JsonObject();
			options.addProperty("include_usage", true);
			body.add("stream_options", options);
		}
		return body;
	}

	private static JsonObject toJson(ChatMessage message, boolean includePerception) {
		JsonObject json = new JsonObject();
		switch (message.role()) {
			case USER -> {
				json.addProperty("role", "user");
				String content = message.content();
				if (includePerception && message.perception() != null) {
					content = content + "\n\n[What you perceive right now]\n" + message.perception();
				}
				json.addProperty("content", content);
			}
			case ASSISTANT -> {
				json.addProperty("role", "assistant");
				if (message.content() != null && !message.content().isEmpty()) {
					json.addProperty("content", message.content());
				} else {
					json.add("content", null);
				}
				if (!message.toolCalls().isEmpty()) {
					JsonArray calls = new JsonArray();
					for (ToolCall call : message.toolCalls()) {
						JsonObject function = new JsonObject();
						function.addProperty("name", call.name());
						function.addProperty("arguments", call.arguments());
						JsonObject callJson = new JsonObject();
						callJson.addProperty("id", call.id());
						callJson.addProperty("type", "function");
						callJson.add("function", function);
						calls.add(callJson);
					}
					json.add("tool_calls", calls);
				}
			}
			case TOOL -> {
				json.addProperty("role", "tool");
				json.addProperty("tool_call_id", message.toolCallId());
				json.addProperty("content", message.content());
			}
		}
		return json;
	}

	public static LlmResponse parse(String body) {
		JsonObject json = GSON.fromJson(body, JsonObject.class);
		JsonArray choices = json.getAsJsonArray("choices");
		if (choices == null || choices.isEmpty()) {
			throw new LlmException(200, "response had no choices");
		}
		JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
		String text = message.has("content") && !message.get("content").isJsonNull() ? message.get("content").getAsString() : "";

		List<ToolCall> calls = new ArrayList<>();
		if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
			for (JsonElement element : message.getAsJsonArray("tool_calls")) {
				JsonObject call = element.getAsJsonObject();
				JsonObject function = call.getAsJsonObject("function");
				JsonElement args = function.get("arguments");
				calls.add(new ToolCall(call.get("id").getAsString(), function.get("name").getAsString(),
						args == null || args.isJsonNull() ? "{}" : args.isJsonPrimitive() ? args.getAsString() : args.toString()));
			}
		}

		LlmResponse.Usage usage = json.has("usage") && json.get("usage").isJsonObject() ? usage(json.getAsJsonObject("usage")) : LlmResponse.Usage.NONE;
		return new LlmResponse(text, calls, usage);
	}

	private static LlmResponse.Usage usage(JsonObject u) {
		long cached = 0;
		if (u.has("prompt_tokens_details") && u.get("prompt_tokens_details").isJsonObject()) {
			JsonObject details = u.getAsJsonObject("prompt_tokens_details");
			cached = details.has("cached_tokens") && !details.get("cached_tokens").isJsonNull() ? details.get("cached_tokens").getAsLong() : 0;
		}
		return new LlmResponse.Usage(
				u.has("prompt_tokens") ? u.get("prompt_tokens").getAsLong() : 0,
				u.has("completion_tokens") ? u.get("completion_tokens").getAsLong() : 0,
				cached);
	}

	private static final class PartialCall {
		String id;
		String name = "";
		final StringBuilder arguments = new StringBuilder();
	}

	/**
	 * Reads a streamed reply ({@code data: {...}} lines ending in {@code data: [DONE]}), handing each piece of text to
	 * {@code onText} as it arrives and putting tool calls back together from their fragments. A server that ignored
	 * {@code stream} and sent a plain JSON body is handled too.
	 */
	public static LlmResponse readStream(Stream<String> lines, Consumer<String> onText) {
		StringBuilder text = new StringBuilder();
		Map<Integer, PartialCall> calls = new TreeMap<>();
		LlmResponse.Usage usage = LlmResponse.Usage.NONE;
		StringBuilder plain = new StringBuilder();
		boolean streamed = false;
		for (String line : (Iterable<String>) lines::iterator) {
			if (!line.startsWith("data:")) {
				plain.append(line).append('\n');
				continue;
			}
			streamed = true;
			String data = line.substring(5).strip();
			if (data.equals("[DONE]")) {
				break;
			}
			if (data.isEmpty()) {
				continue;
			}
			JsonObject chunk = GSON.fromJson(data, JsonObject.class);
			if (chunk.has("error")) {
				throw new LlmException(0, errorMessage(data));
			}
			if (chunk.has("usage") && chunk.get("usage").isJsonObject()) {
				usage = usage(chunk.getAsJsonObject("usage"));
			}
			JsonArray choices = chunk.has("choices") && chunk.get("choices").isJsonArray() ? chunk.getAsJsonArray("choices") : null;
			if (choices == null || choices.isEmpty() || !choices.get(0).getAsJsonObject().has("delta")) {
				continue;
			}
			JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
			if (delta.has("content") && !delta.get("content").isJsonNull()) {
				String piece = delta.get("content").getAsString();
				if (!piece.isEmpty()) {
					text.append(piece);
					onText.accept(piece);
				}
			}
			if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
				for (JsonElement element : delta.getAsJsonArray("tool_calls")) {
					JsonObject fragment = element.getAsJsonObject();
					int index = fragment.has("index") ? fragment.get("index").getAsInt() : calls.size();
					PartialCall call = calls.computeIfAbsent(index, i -> new PartialCall());
					if (fragment.has("id") && !fragment.get("id").isJsonNull()) {
						call.id = fragment.get("id").getAsString();
					}
					if (fragment.has("function") && fragment.get("function").isJsonObject()) {
						JsonObject function = fragment.getAsJsonObject("function");
						if (function.has("name") && !function.get("name").isJsonNull()) {
							call.name = function.get("name").getAsString();
						}
						if (function.has("arguments") && !function.get("arguments").isJsonNull()) {
							call.arguments.append(function.get("arguments").getAsString());
						}
					}
				}
			}
		}
		if (!streamed) {
			if (plain.isEmpty()) {
				throw new LlmException(0, "the reply was empty");
			}
			return parse(plain.toString());
		}
		List<ToolCall> toolCalls = new ArrayList<>();
		calls.forEach((index, call) -> toolCalls.add(new ToolCall(call.id != null ? call.id : "call_" + index, call.name,
				call.arguments.isEmpty() ? "{}" : call.arguments.toString())));
		return new LlmResponse(text.toString(), toolCalls, usage);
	}

	private static String errorMessage(String body) {
		try {
			JsonObject json = GSON.fromJson(body, JsonObject.class);
			if (json != null && json.has("error")) {
				JsonElement error = json.get("error");
				if (error.isJsonObject() && error.getAsJsonObject().has("message")) {
					return error.getAsJsonObject().get("message").getAsString();
				}
				return error.toString();
			}
		} catch (RuntimeException ignored) {
			// not JSON
		}
		return body.length() > 300 ? body.substring(0, 300) : body;
	}
}
