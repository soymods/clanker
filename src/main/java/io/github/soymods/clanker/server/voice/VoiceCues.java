package io.github.soymods.clanker.server.voice;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The delivery cues the model can put in what it says: sounds in square brackets ({@code [laugh]}, {@code [sigh]},
 * {@code [pause]}) and wrapping tags that change how words are said ({@code <whisper>…</whisper>}). Each voice engine
 * gets them in its own dialect, and text shown on screen gets none.
 */
public final class VoiceCues {
	private static final Pattern SOUND = Pattern.compile("\\[([A-Za-z][A-Za-z \\-]{0,23})]");
	private static final Pattern WRAP = Pattern.compile("</?([A-Za-z][A-Za-z\\-]{1,19})>");

	/** Sounds xAI's voices understand as inline tags. */
	private static final Set<String> XAI_SOUNDS = Set.of("laugh", "chuckle", "giggle", "sigh", "breath", "inhale", "exhale",
			"pause", "long-pause", "tsk", "cry", "hum-tune", "tongue-click", "lip-smack");
	private static final Map<String, String> XAI_SOUND_ALIASES = Map.of("gasp", "inhale", "laughs", "laugh", "sighs", "sigh",
			"chuckles", "chuckle", "giggles", "giggle", "long pause", "long-pause");
	/** Wrapping tags xAI's voices understand. */
	private static final Set<String> XAI_WRAPS = Set.of("whisper", "soft", "loud", "slow", "fast", "singing", "sing-song", "emphasis",
			"higher-pitch", "lower-pitch", "build-intensity", "decrease-intensity");

	private static final Map<String, String> ELEVENLABS_SOUNDS = Map.ofEntries(
			Map.entry("laugh", "[laughs]"), Map.entry("chuckle", "[chuckles]"), Map.entry("giggle", "[giggles]"),
			Map.entry("sigh", "[sighs]"), Map.entry("breath", "[breathes]"), Map.entry("inhale", "[inhales]"),
			Map.entry("exhale", "[exhales]"), Map.entry("gasp", "[gasps]"), Map.entry("tsk", "[tsks]"), Map.entry("cry", "[crying]"),
			Map.entry("pause", "…"), Map.entry("long-pause", "… …"), Map.entry("long pause", "… …"));
	private static final Map<String, String> ELEVENLABS_WRAPS = Map.of("whisper", "[whispers]", "soft", "[softly]", "loud", "[shouting]",
			"slow", "[slowly]", "fast", "[quickly]", "singing", "[sings]", "sing-song", "[sing-song]");

	private VoiceCues() {
	}

	/** What's shown in chat and subtitles: the words only. */
	public static String forDisplay(String text) {
		String plain = WRAP.matcher(SOUND.matcher(text).replaceAll(" ")).replaceAll("");
		return tidy(plain);
	}

	/** True if the text has any cue at all (a line can be just a laugh). */
	public static boolean hasCues(String text) {
		return SOUND.matcher(text).find() || WRAP.matcher(text).find();
	}

	public static String forXai(String text) {
		StringBuilder out = new StringBuilder();
		Matcher sound = SOUND.matcher(text);
		while (sound.find()) {
			String name = name(sound.group(1));
			name = XAI_SOUND_ALIASES.getOrDefault(name, name);
			sound.appendReplacement(out, XAI_SOUNDS.contains(name) ? Matcher.quoteReplacement("[" + name + "]") : " ");
		}
		sound.appendTail(out);
		Matcher wrap = WRAP.matcher(out.toString());
		StringBuilder wrapped = new StringBuilder();
		while (wrap.find()) {
			String name = name(wrap.group(1));
			String tag = wrap.group().startsWith("</") ? "</" + name + ">" : "<" + name + ">";
			wrap.appendReplacement(wrapped, XAI_WRAPS.contains(name) ? Matcher.quoteReplacement(tag) : "");
		}
		wrap.appendTail(wrapped);
		return tidy(wrapped.toString());
	}

	/** ElevenLabs reads free-form bracketed directions, so unknown sounds pass through as they are. */
	public static String forElevenLabs(String text) {
		StringBuilder out = new StringBuilder();
		Matcher sound = SOUND.matcher(text);
		while (sound.find()) {
			String name = name(sound.group(1));
			sound.appendReplacement(out, Matcher.quoteReplacement(ELEVENLABS_SOUNDS.getOrDefault(name, "[" + name + "]")));
		}
		sound.appendTail(out);
		Matcher wrap = WRAP.matcher(out.toString());
		StringBuilder wrapped = new StringBuilder();
		while (wrap.find()) {
			boolean closing = wrap.group().startsWith("</");
			String direction = closing ? "" : ELEVENLABS_WRAPS.getOrDefault(name(wrap.group(1)), "");
			wrap.appendReplacement(wrapped, Matcher.quoteReplacement(direction.isEmpty() ? "" : direction + " "));
		}
		wrap.appendTail(wrapped);
		return tidy(wrapped.toString());
	}

	private static String name(String raw) {
		return raw.strip().toLowerCase(Locale.ROOT);
	}

	private static String tidy(String text) {
		return text.replaceAll("\\s+", " ").replaceAll(" ([,.!?;:])", "$1").strip();
	}
}
