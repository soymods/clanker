package io.github.soymods.clanker.server.brain.llm;

/** A failed model request. {@code status} is the HTTP status, or 0 for network errors. */
public class LlmException extends RuntimeException {
	private final int status;

	public LlmException(int status, String message) {
		super(message);
		this.status = status;
	}

	public int status() {
		return status;
	}
}
