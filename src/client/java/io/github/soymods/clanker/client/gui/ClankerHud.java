package io.github.soymods.clanker.client.gui;

import io.github.soymods.clanker.Clanker;
import io.github.soymods.clanker.client.audio.VoiceInput;
import io.github.soymods.clanker.client.audio.VoicePlayer;
import io.github.soymods.clanker.client.config.ClientConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Subtitles for Clanker's voice, a "thinking" line while it waits on the model, and a listening indicator for the
 * microphone.
 */
public final class ClankerHud {
	private static final Set<Integer> thinking = new HashSet<>();

	private ClankerHud() {
	}

	public static void register() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.SUBTITLES, Clanker.id("voice"), ClankerHud::render);
	}

	public static void setThinking(int entityId, boolean value) {
		if (value) {
			thinking.add(entityId);
		} else {
			thinking.remove(entityId);
		}
	}

	public static void clear() {
		thinking.clear();
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return;
		}
		ClientConfig config = ClientConfig.get();
		Font font = client.font;
		int width = graphics.guiWidth();
		int y = graphics.guiHeight() - 72;

		VoicePlayer.Line line = VoicePlayer.current();
		if (config.subtitles && line != null) {
			List<FormattedCharSequence> rows = font.split(Component.literal(line.speaker() + ": " + line.text()), Math.min(320, width - 40));
			int start = y - rows.size() * 10;
			for (int i = 0; i < rows.size(); i++) {
				int rowWidth = font.width(rows.get(i));
				int x = (width - rowWidth) / 2;
				graphics.fill(x - 2, start + i * 10 - 1, x + rowWidth + 2, start + i * 10 + 9, 0x90000000);
				graphics.text(font, rows.get(i), x, start + i * 10, 0xFFFFFFFF, false);
			}
			y = start - 4;
		}

		if (config.thinkingIndicator && !thinking.isEmpty()) {
			for (int id : thinking) {
				Entity entity = client.level.getEntity(id);
				if (entity != null) {
					String dots = ".".repeat((int) (System.currentTimeMillis() / 400 % 3) + 1);
					graphics.centeredText(font, entity.getName().getString() + " is thinking" + dots, width / 2, y - 10, 0xFFB0B0B0);
					y -= 12;
					break;
				}
			}
		}

		String mic = VoiceInput.listening() ? "● Listening" : VoiceInput.transcribing() ? "… Understanding" : null;
		if (mic != null) {
			int color = VoiceInput.listening() ? 0xFFFF5555 : 0xFFCCCCCC;
			graphics.centeredText(font, mic, width / 2, graphics.guiHeight() / 2 + 14, color);
		}
	}
}
