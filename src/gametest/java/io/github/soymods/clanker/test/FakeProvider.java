package io.github.soymods.clanker.test;

import io.github.soymods.clanker.server.brain.llm.LlmProvider;
import io.github.soymods.clanker.server.brain.llm.LlmRequest;
import io.github.soymods.clanker.server.brain.llm.LlmResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A model stand-in for tests: records every request and answers with whatever the script returns.
 */
public class FakeProvider implements LlmProvider {
	private final Function<LlmRequest, LlmResponse> script;
	public final List<LlmRequest> requests = new CopyOnWriteArrayList<>();

	public FakeProvider(Function<LlmRequest, LlmResponse> script) {
		this.script = script;
	}

	/** Never says or does anything. */
	public static FakeProvider silent() {
		return new FakeProvider(request -> reply(""));
	}

	public static LlmResponse reply(String text) {
		return new LlmResponse(text, List.of(), LlmResponse.Usage.NONE);
	}

	@Override
	public CompletableFuture<LlmResponse> complete(LlmRequest request) {
		requests.add(request);
		try {
			return CompletableFuture.completedFuture(script.apply(request));
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e); // like a real HTTP failure
		}
	}

	public boolean anyRequestContains(String text) {
		return requests.stream().anyMatch(r -> r.messages().stream()
				.anyMatch(m -> m.content() != null && m.content().contains(text)));
	}
}
