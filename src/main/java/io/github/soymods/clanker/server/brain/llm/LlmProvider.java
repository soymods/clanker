package io.github.soymods.clanker.server.brain.llm;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A chat model with tool calling. Implementations must not block the calling thread.
 */
public interface LlmProvider {
	CompletableFuture<LlmResponse> complete(LlmRequest request);

	/**
	 * Like {@link #complete(LlmRequest)}, but hands over pieces of the reply text as they arrive, on any thread, so it
	 * can be spoken early. Providers that can't stream just call it never.
	 */
	default CompletableFuture<LlmResponse> complete(LlmRequest request, Consumer<String> onText) {
		return complete(request);
	}
}
