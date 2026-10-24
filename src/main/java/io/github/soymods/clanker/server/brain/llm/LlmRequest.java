package io.github.soymods.clanker.server.brain.llm;

import java.util.List;

public record LlmRequest(String system, List<ChatMessage> messages, List<ToolSpec> tools) {
}
