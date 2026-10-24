package io.github.soymods.clanker.server.brain.llm;

import java.util.concurrent.CompletableFuture;

/**
 * A chat model with tool calling. Implementations must not block the calling thread.
 */
public interface LlmProvider {
	CompletableFuture<LlmResponse> complete(LlmRequest request);
}
