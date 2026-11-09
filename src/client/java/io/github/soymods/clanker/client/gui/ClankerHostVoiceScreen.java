package io.github.soymods.clanker.client.gui;

import io.github.soymods.clanker.network.HostSettings;
import io.github.soymods.clanker.server.voice.XaiVoice;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Clanker's voice, as the host sets it: a cloud voice made on the host (xAI or ElevenLabs) that everyone hears the
 * same, or each player's own local voice. Edits the settings object the main screen holds; the main screen's Save
 * sends everything together. A voice key typed here only ever travels to the server.
 */
public class ClankerHostVoiceScreen extends Screen {
	private static final int WIDTH = 150;
	private static final int GAP = 10;
	private static final List<String> PROVIDERS = List.of("XAI", "ELEVENLABS", "LOCAL");

	private final ClankerSettingsScreen parent;
	private final HostSettings settings;
	private CycleButton<String> provider;
	private CycleButton<String> xaiVoice;
	private EditBox elevenVoice;
	private EditBox elevenModel;
	private EditBox speed;
	private EditBox key;
	private String error = "";

	public ClankerHostVoiceScreen(ClankerSettingsScreen parent, HostSettings settings) {
		super(Component.literal("Clanker's Voice"));
		this.parent = parent;
		this.settings = settings;
	}

	@Override
	protected void init() {
		int left = width / 2 - WIDTH - GAP / 2;
		int right = width / 2 + GAP / 2;
		int y = 28;
		centered(title, 10);

		provider = addRenderableWidget(CycleButton.builder(ClankerHostVoiceScreen::providerLabel,
						PROVIDERS.contains(settings.voiceProvider) ? settings.voiceProvider : "XAI")
				.withValues(PROVIDERS)
				.create(left, y + 11, WIDTH, 20, Component.literal("Voice"), (b, v) -> {
					keep();
					rebuildWidgets();
				}));
		String chosen = provider.getValue();

		List<String> voices = new ArrayList<>(XaiVoice.VOICES);
		if (!voices.contains(settings.xaiVoice) && !settings.xaiVoice.isBlank()) {
			voices.addFirst(settings.xaiVoice);
		}
		xaiVoice = addRenderableWidget(CycleButton.builder((String id) -> Component.literal(id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1)),
						voices.contains(settings.xaiVoice) ? settings.xaiVoice : "rex")
				.withValues(voices)
				.create(right, y + 11, WIDTH, 20, Component.literal("xAI voice")));

		elevenVoice = field(left, y + 36, "ElevenLabs voice id", settings.elevenLabsVoice, 64);
		elevenVoice.setHint(Component.literal("from their voice library"));
		elevenModel = field(right, y + 36, "ElevenLabs model", settings.elevenLabsModel, 64);
		elevenModel.setHint(Component.literal("eleven_v3"));

		speed = field(left, y + 72, "Speed (0.7 to 1.5)", String.format(Locale.ROOT, "%.2f", settings.voiceSpeed), 5);
		String kind = "ELEVENLABS".equals(chosen) ? "ELEVENLABS" : "XAI";
		String source = settings.keySources.getOrDefault(kind, "");
		key = field(right, y + 72, ("ELEVENLABS".equals(chosen) ? "ElevenLabs" : "xAI") + " key", "", 500);
		key.setHint(Component.literal(settings.clearVoiceKey ? "will be removed" : !source.isEmpty() ? "set (" + source + ")" : "paste a key"));
		key.addFormatter((text, offset) -> FormattedCharSequence.forward("•".repeat(text.length()), Style.EMPTY));
		if (!settings.newVoiceKey.isEmpty()) {
			key.setValue(settings.newVoiceKey);
		}

		String about = switch (chosen) {
			case "XAI" -> "Made on the host and streamed to everyone. About $0.10 per hour of play.";
			case "ELEVENLABS" -> "Made on the host and streamed to everyone. The most expressive; about $0.50 per hour.";
			default -> "Each player hears their own local voice (set under Your mic and speakers). Free.";
		};
		centered(Component.literal(error.isEmpty() ? about : error), y + 108);
		if (!settings.voiceStatus.isEmpty()) {
			centered(Component.literal(settings.voiceStatus), y + 122);
		}

		Button removeKey = addRenderableWidget(Button.builder(Component.literal("Remove key"), b -> {
			keep();
			settings.clearVoiceKey = true;
			settings.newVoiceKey = "";
			rebuildWidgets();
		}).bounds(left, y + 140, 96, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Your mic and speakers…"), b -> {
			if (keep()) {
				minecraft.gui.setScreen(new ClankerVoiceScreen(this));
			}
		}).bounds(left + 100, y + 140, 150, 20).build());
		Button back = addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
				.bounds(left + 254, y + 140, WIDTH * 2 + GAP - 254, 20).build());

		xaiVoice.active = "XAI".equals(chosen);
		elevenVoice.setEditable("ELEVENLABS".equals(chosen));
		elevenModel.setEditable("ELEVENLABS".equals(chosen));
		key.setEditable(!"LOCAL".equals(chosen));
		removeKey.active = !"LOCAL".equals(chosen) && !source.isEmpty() && "settings".equals(source) && !settings.clearVoiceKey;
		if (!settings.canEdit) {
			children().forEach(child -> {
				if (child instanceof AbstractWidget widget && widget != back && !(widget instanceof StringWidget)
						&& !widget.getMessage().getString().startsWith("Your mic")) {
					widget.active = false;
				}
			});
		}
	}

	private EditBox field(int x, int y, String label, String value, int maxLength) {
		addRenderableWidget(new StringWidget(x, y, WIDTH, 10, Component.literal(label), font));
		EditBox box = new EditBox(font, x, y + 11, WIDTH, 20, Component.literal(label));
		box.setMaxLength(maxLength);
		box.setValue(value);
		return addRenderableWidget(box);
	}

	private void centered(Component text, int y) {
		int textWidth = font.width(text);
		addRenderableWidget(new StringWidget((width - textWidth) / 2, y, textWidth, 12, text, font));
	}

	/** Copies the fields into the settings. Returns false (and shows why) if the speed isn't a number. */
	private boolean keep() {
		if (provider == null) {
			return true;
		}
		String previous = settings.voiceProvider;
		settings.voiceProvider = provider.getValue();
		if (!settings.voiceProvider.equals(previous)) {
			// A key typed for one service isn't for another.
			settings.newVoiceKey = "";
			settings.clearVoiceKey = false;
		} else if (!key.getValue().isBlank()) {
			settings.newVoiceKey = key.getValue().strip();
			settings.clearVoiceKey = false;
		}
		settings.xaiVoice = xaiVoice.getValue();
		settings.elevenLabsVoice = elevenVoice.getValue().strip();
		settings.elevenLabsModel = elevenModel.getValue().strip();
		try {
			settings.voiceSpeed = Math.max(0.7, Math.min(1.5, Double.parseDouble(speed.getValue().strip().replace("x", ""))));
			error = "";
			return true;
		} catch (NumberFormatException e) {
			error = "Speed must be a number, like 1 or 1.1.";
			rebuildWidgets();
			return false;
		}
	}

	@Override
	public void onClose() {
		if (!settings.canEdit || keep()) {
			minecraft.gui.setScreen(parent);
		}
	}

	private static Component providerLabel(String value) {
		return Component.literal(switch (value) {
			case "XAI" -> "xAI (host)";
			case "ELEVENLABS" -> "ElevenLabs (host)";
			default -> "Local (each player)";
		});
	}
}
