package io.github.soymods.clanker.server.brain.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenAI Chat Completions, which also covers OpenAI-compatible servers (Ollama, OpenRouter, LM Studio…).
 */
public class OpenAiProvider implements LlmProvider {
	public static final String OPENAI_BASE_URL = "https://api.openai.com/v1";

	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);
	/** Endpoint + model pairs that rejected {@code reasoning_effort} (non-reasoning models, some compatible servers). */
	private static final Set<String> NO_REASONING = ConcurrentHashMap.newKeySet();

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
		boolean sentReasoning = sendsReasoning();
		return send(request).exceptionallyCompose(error -> {
			Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
			if (sentReasoning && cause instanceof LlmException e && e.status() == 400 && e.getMessage().contains("reasoning")) {
				NO_REASONING.add(baseUrl + "|" + model);
				return send(request);
			}
			return CompletableFuture.failedFuture(cause);
		});
	}

	private boolean sendsReasoning() {
		return !reasoningEffort.isBlank() && !NO_REASONING.contains(baseUrl + "|" + model);
	}

	private CompletableFuture<LlmResponse> send(LlmRequest request) {
		HttpRequest.Builder http = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
				.timeout(REQUEST_TIMEOUT)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(buildBody(request))));
		if (!apiKey.isBlank()) {
			http.header("Authorization", "Bearer " + apiKey);
		}
		return HTTP.sendAsync(http.build(), HttpResponse.BodyHandlers.ofString())
				.handle((response, error) -> {
					if (error != null) {
						Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
						throw new LlmException(0, cause.getClass().getSimpleName() + ": " + cause.getMessage());
					}
					if (response.statusCode() / 100 != 2) {
						throw new LlmException(response.statusCode(), errorMessage(response.body()));
					}
					return parse(response.body());
				});
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
		if (sendsReasoning()) {
			body.addProperty("reasoning_effort", reasoningEffort);
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

		LlmResponse.Usage usage = LlmResponse.Usage.NONE;
		if (json.has("usage") && json.get("usage").isJsonObject()) {
			JsonObject u = json.getAsJsonObject("usage");
			long cached = 0;
			if (u.has("prompt_tokens_details") && u.get("prompt_tokens_details").isJsonObject()) {
				JsonObject details = u.getAsJsonObject("prompt_tokens_details");
				cached = details.has("cached_tokens") ? details.get("cached_tokens").getAsLong() : 0;
			}
			usage = new LlmResponse.Usage(
					u.has("prompt_tokens") ? u.get("prompt_tokens").getAsLong() : 0,
					u.has("completion_tokens") ? u.get("completion_tokens").getAsLong() : 0,
					cached);
		}
		return new LlmResponse(text, calls, usage);
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
