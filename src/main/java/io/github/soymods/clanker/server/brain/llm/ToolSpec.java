package io.github.soymods.clanker.server.brain.llm;

import com.google.gson.JsonObject;

/** A tool definition sent to the model; {@code parameters} is a JSON Schema object. */
public record ToolSpec(String name, String description, JsonObject parameters) {
}
