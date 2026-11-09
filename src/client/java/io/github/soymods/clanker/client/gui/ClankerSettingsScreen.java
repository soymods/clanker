package io.github.soymods.clanker.client.gui;

import io.github.soymods.clanker.config.Personalities;
import io.github.soymods.clanker.network.HostSettings;
import io.github.soymods.clanker.network.SettingsPayload;
import io.github.soymods.clanker.network.SignalPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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

/**
 * Host settings: provider, model, API key, name, personality, listening. Changes go to the server, which checks that
 * the player may edit them. The current API key is never shown or sent back; the screen only knows whether one is set.
 */
public class ClankerSettingsScreen extends Screen {
	private static final int COLUMN_WIDTH = 150;
	private static final int GAP = 10;
	private static final int ROW = 32; // fits the 854x480 minimum window at GUI scale 2
	private static final List<String> PROVIDERS = List.of("OPENAI", "XAI", "OPENAI_COMPATIBLE");
	private static final List<String> TALKATIVENESS = List.of("QUIET", "NORMAL", "CHATTY");
	private static final List<String> LISTEN_MODES = List.of("NAME_ONLY", "NEARBY", "EVERYONE");
	private static final List<String> EFFORTS = List.of("", "none", "minimal", "low", "medium", "high");

	private final Screen parent;
	private HostSettings settings;
	private String status = "";
	private boolean clearKeyRequested;
	private String pendingKey = "";

	private CycleButton<String> provider;
	private EditBox model;
	private EditBox endpoint;
	private CycleButton<String> effort;
	private EditBox apiKey;
	private EditBox botName;
	private CycleButton<String> listenMode;
	private CycleButton<String> talkativeness;
	private CycleButton<String> preset;
	private EditBox personality;

	public ClankerSettingsScreen(Screen parent) {
		super(Component.literal("Clanker Settings"));
		this.parent = parent;
	}

	/** Called when the server sends settings, either on open or after saving. */
	public void receive(HostSettings settings) {
		this.settings = settings;
		this.status = settings.status;
		this.clearKeyRequested = false;
		this.pendingKey = "";
		rebuildWidgets();
	}

	@Override
	protected void init() {
		int left = width / 2 - COLUMN_WIDTH - GAP / 2;
		int right = width / 2 + GAP / 2;
		int top = 24;
		centered(title, 8);

		if (!ClientPlayNetworking.canSend(SignalPayload.RequestSettings.TYPE)) {
			centered(Component.literal("Join a world or server that has Clanker to change its settings."), height / 2 - 20);
			addRenderableWidget(Button.builder(Component.literal("Voice…"), b -> minecraft.gui.setScreen(new ClankerVoiceScreen(this)))
					.bounds(width / 2 - 155, height / 2, 150, 20).build());
			addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
					.bounds(width / 2 + 5, height / 2, 150, 20).build());
			return;
		}
		if (settings == null) {
			centered(Component.literal("Loading…"), height / 2 - 6);
			ClientPlayNetworking.send(SignalPayload.RequestSettings.INSTANCE);
			return;
		}

		provider = addRenderableWidget(CycleButton.builder(ClankerSettingsScreen::providerLabel, PROVIDERS.contains(settings.provider) ? settings.provider : "OPENAI")
				.withValues(PROVIDERS)
				.create(left, top + 12, COLUMN_WIDTH, 20, Component.literal("Provider"), (button, value) -> updateEndpointState()));
		model = field(left, top + ROW, "Model", settings.model, 100);
		endpoint = field(left, top + ROW * 2, "Endpoint (OpenAI-compatible)", settings.endpoint, 300);
		endpoint.setHint(Component.literal("http://localhost:11434/v1"));
		effort = addRenderableWidget(CycleButton.builder(ClankerSettingsScreen::effortLabel, EFFORTS.contains(settings.reasoningEffort) ? settings.reasoningEffort : "")
				.withValues(EFFORTS)
				.create(left, top + ROW * 3 + 12, COLUMN_WIDTH, 20, Component.literal("Reasoning")));

		apiKey = field(right, top, "API key", pendingKey, 500);
		apiKey.addFormatter((text, offset) -> FormattedCharSequence.forward("•".repeat(text.length()), Style.EMPTY));
		botName = field(right, top + ROW, "Bot name", settings.botName, 16);
		listenMode = addRenderableWidget(CycleButton.builder(ClankerSettingsScreen::listenLabel, settings.listenMode)
				.withValues(LISTEN_MODES)
				.create(right, top + ROW * 2 + 12, COLUMN_WIDTH, 20, Component.literal("Listens to")));
		talkativeness = addRenderableWidget(CycleButton.builder(ClankerSettingsScreen::talkLabel,
						TALKATIVENESS.contains(settings.talkativeness) ? settings.talkativeness : "NORMAL")
				.withValues(TALKATIVENESS)
				.create(right, top + ROW * 3 + 12, COLUMN_WIDTH, 20, Component.literal("Chattiness")));

		List<String> presets = new ArrayList<>(Personalities.PRESETS.keySet());
		presets.add("Custom");
		preset = addRenderableWidget(CycleButton.builder(Component::literal, Personalities.presetName(settings.personality))
				.withValues(presets)
				.create(left, top + ROW * 4 + 12, COLUMN_WIDTH, 20, Component.literal("Personality"), (button, value) -> {
					if (!value.equals("Custom")) {
						personality.setValue(Personalities.PRESETS.get(value));
						personality.moveCursorToStart(false);
					}
				}));
		personality = field(right, top + ROW * 4, "Personality description", Personalities.resolve(settings.personality), 1000);
		personality.moveCursorToStart(false);
		personality.setHint(Component.literal("e.g. sarcastic but loyal, scared of caves"));
		personality.setResponder(text -> {
			String name = Personalities.presetName(text);
			if (preset != null && !name.equals(preset.getValue())) {
				preset.setValue(name);
			}
		});

		int bottom = top + ROW * 5 + 4;
		Component statusText = Component.literal(!settings.canEdit ? "Only operators or the world's host can change these."
				: clearKeyRequested ? "The key will be removed when you save." : status);
		centered(statusText, bottom);

		int buttonsY = bottom + 14;
		int buttonWidth = (COLUMN_WIDTH * 2 + GAP - 4 * 4) / 5;
		int step = buttonWidth + 4;
		Button clearKey = addRenderableWidget(Button.builder(Component.literal("Remove key"), b -> {
			clearKeyRequested = true;
			apiKey.setValue("");
			rebuildWidgets();
		}).bounds(left, buttonsY, buttonWidth, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Limits…"), b -> {
			keepEdits();
			minecraft.gui.setScreen(new ClankerLimitsScreen(this, settings));
		}).bounds(left + step, buttonsY, buttonWidth, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Voice…"), b -> {
			keepEdits();
			minecraft.gui.setScreen(new ClankerHostVoiceScreen(this, settings));
		}).bounds(left + step * 2, buttonsY, buttonWidth, 20).build());
		Button save = addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
				.bounds(left + step * 3, buttonsY, buttonWidth, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
				.bounds(left + step * 4, buttonsY, buttonWidth, 20).build());

		if (!settings.canEdit) {
			for (var widget : List.of(provider, model, endpoint, effort, apiKey, botName, listenMode, talkativeness, preset, personality, clearKey, save)) {
				widget.active = false;
			}
		}
		clearKey.active &= settings.keySet && !clearKeyRequested;
		updateEndpointState();
	}

	private void centered(Component text, int y) {
		int textWidth = font.width(text);
		addRenderableWidget(new StringWidget((width - textWidth) / 2, y, textWidth, 12, text, font));
	}

	private EditBox field(int x, int y, String label, String value, int maxLength) {
		addRenderableWidget(new StringWidget(x, y, COLUMN_WIDTH, 10, Component.literal(label), font));
		EditBox box = new EditBox(font, x, y + 11, COLUMN_WIDTH, 20, Component.literal(label));
		box.setMaxLength(maxLength);
		box.setValue(value);
		return addRenderableWidget(box);
	}

	private void updateEndpointState() {
		if (endpoint != null && provider != null) {
			String chosen = provider.getValue();
			endpoint.setEditable(settings.canEdit && "OPENAI_COMPATIBLE".equals(chosen));
			String defaultModel = settings.defaultModels.getOrDefault(chosen, "");
			model.setHint(Component.literal(defaultModel.isEmpty() ? "model name" : "default: " + defaultModel));
			String source = settings.keySources.getOrDefault("XAI".equals(chosen) ? "XAI" : "OPENAI", "");
			apiKey.setHint(Component.literal(clearKeyRequested ? "will be removed" : !source.isEmpty() ? "set (" + source + ")"
					: "XAI".equals(chosen) ? "paste an xAI key" : "paste your key"));
		}
	}

	/** Remembers what's typed so far, so visiting the limits page doesn't lose it. The key is never kept. */
	private void keepEdits() {
		settings.provider = provider.getValue();
		settings.model = model.getValue();
		settings.endpoint = endpoint.getValue();
		settings.reasoningEffort = effort.getValue();
		settings.botName = botName.getValue();
		settings.personality = personality.getValue();
		settings.listenMode = listenMode.getValue();
		settings.talkativeness = talkativeness.getValue();
		pendingKey = apiKey.getValue();
	}

	private void save() {
		HostSettings update = new HostSettings();
		update.canBreakBlocks = settings.canBreakBlocks;
		update.canPlaceBlocks = settings.canPlaceBlocks;
		update.canAttackMobs = settings.canAttackMobs;
		update.canAttackPlayers = settings.canAttackPlayers;
		update.canUseContainers = settings.canUseContainers;
		update.sessionSpendCap = settings.sessionSpendCap;
		update.dailySpendCap = settings.dailySpendCap;
		update.provider = provider.getValue();
		update.model = model.getValue();
		update.endpoint = endpoint.getValue();
		update.reasoningEffort = effort.getValue();
		update.botName = botName.getValue();
		update.personality = personality.getValue();
		update.listenMode = listenMode.getValue();
		update.talkativeness = talkativeness.getValue();
		update.voiceProvider = settings.voiceProvider;
		update.xaiVoice = settings.xaiVoice;
		update.elevenLabsVoice = settings.elevenLabsVoice;
		update.elevenLabsModel = settings.elevenLabsModel;
		update.voiceSpeed = settings.voiceSpeed;
		update.newVoiceKey = settings.newVoiceKey;
		update.clearVoiceKey = settings.clearVoiceKey;
		settings.newVoiceKey = "";
		settings.clearVoiceKey = false;
		update.newApiKey = apiKey.getValue();
		update.clearApiKey = clearKeyRequested;
		apiKey.setValue("");
		pendingKey = "";
		status = "Saving…";
		ClientPlayNetworking.send(new SettingsPayload.Update(update.toJson()));
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	private static Component providerLabel(String value) {
		return Component.literal(switch (value) {
			case "OPENAI" -> "OpenAI";
			case "XAI" -> "xAI (Grok)";
			case "OPENAI_COMPATIBLE" -> "OpenAI-compatible";
			default -> value;
		});
	}

	private static Component listenLabel(String value) {
		return Component.literal(switch (value) {
			case "NAME_ONLY" -> "Its name";
			case "NEARBY" -> "Anyone nearby";
			case "EVERYONE" -> "Everyone";
			default -> value;
		});
	}

	private static Component talkLabel(String value) {
		return Component.literal(switch (value) {
			case "QUIET" -> "Quiet";
			case "CHATTY" -> "Chatty";
			default -> "Normal";
		});
	}

	private static Component effortLabel(String value) {
		return Component.literal(value.isEmpty() ? "Default" : value.equals("none") ? "none (fastest)" : value);
	}
}
