package io.github.soymods.clanker.client.gui;

import io.github.soymods.clanker.Clanker;
import io.github.soymods.clanker.client.audio.Microphone;
import io.github.soymods.clanker.client.config.ClientConfig;
import io.github.soymods.clanker.client.natives.Downloads;
import io.github.soymods.clanker.client.natives.VoiceFiles;
import io.github.soymods.clanker.client.stt.Transcriber;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * This player's voice settings: how they talk to Clanker, how Clanker sounds to them, and the voice files. Nothing is
 * downloaded until the player presses Download. Stored in config/clanker-client.json.
 */
public class ClankerVoiceScreen extends Screen {
	private static final int WIDTH = 150;
	private static final int GAP = 10;
	private static final int ROW = 24;
	private static final List<String> LANGUAGES = List.of("en", "auto", "de", "es", "fr", "it", "pt", "nl", "pl", "ru", "ja", "ko", "zh");

	private final Screen parent;
	private final ClientConfig config = ClientConfig.get();
	private Microphone testMicrophone;
	private Button download;
	private int meterY;
	private int statusY;

	public ClankerVoiceScreen(Screen parent) {
		super(Component.literal("Clanker Voice"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int left = width / 2 - WIDTH - GAP / 2;
		int right = width / 2 + GAP / 2;
		int top = 22;

		// Talking to Clanker
		addRenderableWidget(CycleButton.<ClientConfig.VoiceMode>builder(mode -> Component.literal(switch (mode) {
					case PUSH_TO_TALK -> "Push to talk";
					case OPEN_MIC -> "Open mic";
					case OFF -> "Off";
				}), config.voiceMode)
				.withValues(ClientConfig.VoiceMode.values())
				.create(left, top, WIDTH, 20, Component.literal("Your voice"), (b, v) -> change(() -> config.voiceMode = v)));
		List<String> devices = Microphone.devices();
		String device = devices.contains(config.microphone) ? config.microphone : "";
		addRenderableWidget(CycleButton.<String>builder(name -> Component.literal(name.isEmpty() ? "Default" : name), device)
				.withValues(devices)
				.create(left, top + ROW, WIDTH, 20, Component.literal("Mic"), (b, v) -> change(() -> config.microphone = v)));
		addRenderableWidget(slider(left, top + ROW * 2, config.openMicSensitivity,
				v -> "Open mic sensitivity: " + Math.round(v * 100) + "%", v -> config.openMicSensitivity = (float) v));
		addRenderableWidget(CycleButton.<ClientConfig.WhisperModel>builder(model -> Component.literal(
						model.name().charAt(0) + model.name().substring(1).toLowerCase(Locale.ROOT) + " (" + mb(VoiceFiles.whisperSize(model)) + ")"),
						config.whisperModel)
				.withValues(ClientConfig.WhisperModel.values())
				.create(left, top + ROW * 3, WIDTH, 20, Component.literal("Speech model"), (b, v) -> change(() -> {
					config.whisperModel = v;
					Transcriber.unload();
				})));
		String language = LANGUAGES.contains(config.language) ? config.language : "en";
		addRenderableWidget(CycleButton.<String>builder(code -> Component.literal(code.equals("auto") ? "Detect" : code), language)
				.withValues(LANGUAGES)
				.create(left, top + ROW * 4, WIDTH, 20, Component.literal("Language"), (b, v) -> change(() -> {
					config.language = v;
					Transcriber.unload();
				})));
		addRenderableWidget(CycleButton.onOffBuilder(testMicrophone != null)
				.create(left, top + ROW * 5, WIDTH, 20, Component.literal("Test mic"), (b, v) -> toggleTest(v)));

		// How Clanker sounds
		addRenderableWidget(CycleButton.onOffBuilder(config.ttsEnabled)
				.create(right, top, WIDTH, 20, Component.literal("Clanker speaks"), (b, v) -> change(() -> config.ttsEnabled = v)));
		String voice = VoiceFiles.voiceId(config);
		List<String> voices = VoiceFiles.VOICES.contains(voice) ? VoiceFiles.VOICES
				: java.util.stream.Stream.concat(java.util.stream.Stream.of(voice), VoiceFiles.VOICES.stream()).toList();
		addRenderableWidget(CycleButton.<String>builder(id -> Component.literal(voiceLabel(id)), voice)
				.withValues(voices)
				.create(right, top + ROW, WIDTH, 20, Component.literal("Voice"), (b, v) -> change(() -> config.ttsVoice = v)));
		addRenderableWidget(slider(right, top + ROW * 2, config.ttsVolume, v -> "Volume: " + Math.round(v * 100) + "%",
				v -> config.ttsVolume = (float) v));
		addRenderableWidget(slider(right, top + ROW * 3, (config.ttsSpeed - 0.5) / 1.5,
				v -> String.format(Locale.ROOT, "Speed: %.1fx", 0.5 + v * 1.5), v -> config.ttsSpeed = (float) (Math.round((0.5 + v * 1.5) * 10) / 10.0)));
		addRenderableWidget(CycleButton.onOffBuilder(config.subtitles)
				.create(right, top + ROW * 4, WIDTH, 20, Component.literal("Subtitles"), (b, v) -> change(() -> config.subtitles = v)));
		addRenderableWidget(CycleButton.onOffBuilder(config.thinkingIndicator)
				.create(right, top + ROW * 5, WIDTH, 20, Component.literal("Thinking indicator"), (b, v) -> change(() -> config.thinkingIndicator = v)));

		meterY = top + ROW * 6 + 2;
		statusY = meterY + 12;
		int buttonsY = statusY + 14;
		download = addRenderableWidget(Button.builder(Component.literal("Download"), b -> startDownload())
				.bounds(left, buttonsY, WIDTH, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
				.bounds(right, buttonsY, WIDTH, 20).build());
		updateDownloadButton();
	}

	private void change(Runnable edit) {
		edit.run();
		config.save();
		updateDownloadButton();
	}

	private void updateDownloadButton() {
		if (download == null) {
			return;
		}
		List<Downloads.Item> missing = VoiceFiles.missing(config);
		download.active = !missing.isEmpty() && !Downloads.running();
		download.setMessage(Component.literal(missing.isEmpty() ? "All downloaded" : "Download (" + mb(VoiceFiles.size(missing)) + ")"));
	}

	private void startDownload() {
		List<Downloads.Item> missing = VoiceFiles.missing(config);
		if (!missing.isEmpty()) {
			Downloads.start(missing);
		}
		updateDownloadButton();
	}

	private void toggleTest(boolean on) {
		if (testMicrophone != null) {
			testMicrophone.close();
			testMicrophone = null;
		}
		if (on) {
			try {
				testMicrophone = Microphone.open(config.microphone, frame -> {
				});
			} catch (Exception e) {
				Clanker.LOGGER.warn("Couldn't open the microphone", e);
			}
		}
	}

	@Override
	public void tick() {
		updateDownloadButton();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
		int left = width / 2 - WIDTH - GAP / 2;
		int full = WIDTH * 2 + GAP;

		if (testMicrophone != null) {
			// Level meter, with a tick where open mic starts listening.
			float level = Math.min(1, testMicrophone.level() * 4);
			int threshold = (int) (Math.min(1, Microphone.threshold(config.openMicSensitivity) * 4) * full);
			graphics.fill(left, meterY, left + full, meterY + 6, 0xFF202020);
			graphics.fill(left, meterY, left + (int) (level * full), meterY + 6, level * full > threshold ? 0xFF55FF55 : 0xFF3A7A3A);
			graphics.fill(left + threshold, meterY - 1, left + threshold + 1, meterY + 7, 0xFFFFFF55);
		}
		graphics.centeredText(font, status(), width / 2, statusY, 0xFFCCCCCC);
	}

	private Component status() {
		Downloads.Job job = Downloads.job();
		if (job != null && !job.finished()) {
			return Component.literal(String.format(Locale.ROOT, "Downloading %s… %d%%", job.current(), Math.round(job.progress() * 100)));
		}
		if (job != null && job.error() != null) {
			return Component.literal("Download failed: " + job.error());
		}
		boolean stt = config.voiceMode == ClientConfig.VoiceMode.OFF || VoiceFiles.sttReady(config);
		boolean tts = !config.ttsEnabled || VoiceFiles.ttsReady(config);
		if (stt && tts) {
			return Component.literal(config.voiceMode == ClientConfig.VoiceMode.PUSH_TO_TALK
					? "Ready. Hold " + io.github.soymods.clanker.client.ClankerKeys.pushToTalk.getTranslatedKeyMessage().getString() + " to talk."
					: "Ready. Everything runs on your computer; only text is sent.");
		}
		return Component.literal("Voice files aren't downloaded yet. Everything runs on your computer.");
	}

	@Override
	public void removed() {
		toggleTest(false);
		config.save();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	private static String voiceLabel(String id) {
		String[] parts = id.split("-");
		if (parts.length != 3) {
			return id;
		}
		String name = parts[1].replace('_', ' ');
		String region = parts[0].endsWith("GB") ? "UK" : parts[0].endsWith("US") ? "US" : parts[0];
		return Character.toUpperCase(name.charAt(0)) + name.substring(1) + " (" + region + ")";
	}

	private static String mb(long bytes) {
		return Math.max(1, Math.round(bytes / 1_000_000.0)) + " MB";
	}

	private static AbstractSliderButton slider(int x, int y, double value, DoubleFunction<String> label, DoubleConsumer setter) {
		return new AbstractSliderButton(x, y, WIDTH, 20, Component.literal(label.apply(value)), Math.clamp(value, 0, 1)) {
			@Override
			protected void updateMessage() {
				setMessage(Component.literal(label.apply(this.value)));
			}

			@Override
			protected void applyValue() {
				setter.accept(this.value);
			}
		};
	}
}
