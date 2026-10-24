package io.github.soymods.clanker.server.brain.llm;

/** A tool the model asked to run. {@code arguments} is the raw JSON object string. */
public record ToolCall(String id, String name, String arguments) {
}
