package io.github.soymods.clanker.server.brain.llm;

import java.util.List;

/**
 * One entry in the conversation. {@code perception} is kept apart from {@code content} so older turns can be sent
 * without their (large, stale) world snapshot.
 */
public record ChatMessage(Role role, String content, String perception, List<ToolCall> toolCalls, String toolCallId) {
	public enum Role { USER, ASSISTANT, TOOL }

	public static ChatMessage user(String content, String perception) {
		return new ChatMessage(Role.USER, content, perception, List.of(), null);
	}

	public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
		return new ChatMessage(Role.ASSISTANT, content, null, List.copyOf(toolCalls), null);
	}

	public static ChatMessage toolResult(String toolCallId, String content) {
		return new ChatMessage(Role.TOOL, content, null, List.of(), toolCallId);
	}
}
