package io.github.soymods.clanker.server.brain;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts reply text that arrives in pieces into sentences, so each one can be spoken as soon as it's complete instead of
 * waiting for the whole reply.
 */
final class SentenceSplitter {
	/** Shorter bits ("Hm. ", "e.g. ") wait to be joined with what follows. */
	private static final int MIN_LENGTH = 8;

	private final StringBuilder buffer = new StringBuilder();

	/** Adds a piece and returns any sentences it completed. */
	List<String> add(String piece) {
		buffer.append(piece);
		List<String> sentences = new ArrayList<>();
		int start = 0;
		// A sentence ends at . ! ? or … followed by whitespace (so "1.5" and "..." mid-word don't split), or at a newline.
		for (int i = 0; i < buffer.length() - 1; i++) {
			char c = buffer.charAt(i);
			boolean end = c == '\n' || (".!?…".indexOf(c) >= 0 && Character.isWhitespace(buffer.charAt(i + 1)));
			if (end && i + 1 - start >= MIN_LENGTH) {
				String sentence = buffer.substring(start, i + 1).strip();
				if (!sentence.isEmpty()) {
					sentences.add(sentence);
				}
				start = i + 1;
			}
		}
		buffer.delete(0, start);
		return sentences;
	}

	/** Whatever is left once the reply is complete. */
	String flush() {
		String rest = buffer.toString().strip();
		buffer.setLength(0);
		return rest;
	}
}
