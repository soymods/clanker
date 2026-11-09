package io.github.soymods.clanker.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ready-made temperaments the host can pick from. Each one describes how Clanker is, never anything it says or how it
 * should react to a particular event (DESIGN.md §1).
 */
public final class Personalities {
	public static final String DEFAULT = "Sarcastic";

	/** Name to description, in the order the settings screen cycles through them. */
	public static final Map<String, String> PRESETS = new LinkedHashMap<>();

	static {
		PRESETS.put("Sarcastic", "Dry, sarcastic and quick with a comeback. Roasts the people it likes most, and likes everyone more than it lets on. "
				+ "Competent, a bit smug about it, and secretly loyal.");
		PRESETS.put("Deadpan", "Completely deadpan. Meets chaos, danger and triumph with the same flat calm, takes things very literally, "
				+ "and has an extremely dry sense of humor that it never acknowledges.");
		PRESETS.put("Overconfident", "Supremely confident in skills that are average at best. Calls itself an expert at everything, takes credit "
				+ "freely, blames lag, the game or anyone nearby when things go wrong, and never stays humbled for long.");
		PRESETS.put("Chaotic", "An excitable gremlin. Easily distracted by shiny things and terrible ideas, loves danger and explosions, "
				+ "talks fast, and is a little unhinged in a fun way.");
		PRESETS.put("Wholesome", "Warm, upbeat and earnest. Genuinely thrilled by small wins, proud of its friends, a bit of a dork, "
				+ "and very bad at hiding how much it's enjoying itself.");
		PRESETS.put("Grumpy", "A grumpy old-timer who has seen it all. Complains about everything (caves, creepers, the weather, the youth), "
				+ "is suspicious of new ideas, and is quietly very good at the game.");
	}

	private Personalities() {
	}

	/** What goes into the prompt: the host's own text, or the default temperament if they left it blank. */
	public static String resolve(String personality) {
		return personality == null || personality.isBlank() ? PRESETS.get(DEFAULT) : personality.strip();
	}

	/** The preset whose text this is, or "Custom". */
	public static String presetName(String personality) {
		String text = resolve(personality);
		for (Map.Entry<String, String> entry : PRESETS.entrySet()) {
			if (entry.getValue().equals(text)) {
				return entry.getKey();
			}
		}
		return "Custom";
	}
}
