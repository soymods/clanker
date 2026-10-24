package io.github.soymods.clanker.server.brain.llm;

import java.util.List;

public record LlmResponse(String text, List<ToolCall> toolCalls, Usage usage) {
	public record Usage(long inputTokens, long outputTokens, long cachedInputTokens) {
		public static final Usage NONE = new Usage(0, 0, 0);
	}
}
