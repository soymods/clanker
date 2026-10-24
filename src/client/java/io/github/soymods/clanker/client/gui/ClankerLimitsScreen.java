package io.github.soymods.clanker.client.gui;

import io.github.soymods.clanker.network.HostSettings;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * What Clanker may do and how much it may spend. Edits the settings object the main screen holds; the main screen's
 * Save sends everything together.
 */
public class ClankerLimitsScreen extends Screen {
	private static final int WIDTH = 150;
	private static final int GAP = 10;

	private final ClankerSettingsScreen parent;
	private final HostSettings settings;
	private EditBox sessionCap;
	private EditBox dailyCap;
	private String error = "";

	public ClankerLimitsScreen(ClankerSettingsScreen parent, HostSettings settings) {
		super(Component.literal("Clanker Limits"));
		this.parent = parent;
		this.settings = settings;
	}

	@Override
	protected void init() {
		int left = width / 2 - WIDTH - GAP / 2;
		int right = width / 2 + GAP / 2;
		int y = 28;
		centered(title, 10);

		toggle(left, y, "Break blocks", settings.canBreakBlocks, v -> settings.canBreakBlocks = v);
		toggle(right, y, "Place blocks", settings.canPlaceBlocks, v -> settings.canPlaceBlocks = v);
		toggle(left, y + 24, "Attack mobs", settings.canAttackMobs, v -> settings.canAttackMobs = v);
		toggle(right, y + 24, "Attack players", settings.canAttackPlayers, v -> settings.canAttackPlayers = v);
		toggle(left, y + 48, "Use chests", settings.canUseContainers, v -> settings.canUseContainers = v);

		int capsY = y + 82;
		sessionCap = field(left, capsY, "Session spend cap (USD)", settings.sessionSpendCap);
		dailyCap = field(right, capsY, "Daily spend cap (USD)", settings.dailySpendCap);

		String spent = String.format(Locale.ROOT, "Spent about $%.2f this session, $%.2f today%s", settings.spentSession, settings.spentToday,
				settings.priceKnown ? "." : " (rough: Clanker doesn't know this model's price).");
		centered(Component.literal(spent), capsY + 40);
		centered(Component.literal(error.isEmpty() ? "A cap of 0 means no cap. Changes apply when you press Save." : error), capsY + 54);

		Button done = addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
				.bounds(width / 2 - 50, capsY + 72, 100, 20).build());
		if (!settings.canEdit) {
			children().forEach(child -> {
				if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget && widget != done) {
					widget.active = false;
				}
			});
		}
	}

	private void toggle(int x, int y, String label, boolean value, java.util.function.Consumer<Boolean> setter) {
		addRenderableWidget(CycleButton.onOffBuilder(value).create(x, y, WIDTH, 20, Component.literal(label), (button, v) -> setter.accept(v)));
	}

	private EditBox field(int x, int y, String label, double value) {
		addRenderableWidget(new StringWidget(x, y, WIDTH, 10, Component.literal(label), font));
		EditBox box = new EditBox(font, x, y + 11, WIDTH, 20, Component.literal(label));
		box.setMaxLength(8);
		box.setValue(String.format(Locale.ROOT, "%.2f", value));
		return addRenderableWidget(box);
	}

	private void centered(Component text, int y) {
		int textWidth = font.width(text);
		addRenderableWidget(new StringWidget((width - textWidth) / 2, y, textWidth, 12, text, font));
	}

	/** Reads the cap fields into the settings. Returns false (and shows why) if one isn't a number. */
	private boolean commit() {
		try {
			settings.sessionSpendCap = Math.max(0, Double.parseDouble(sessionCap.getValue().strip().replace("$", "")));
			settings.dailySpendCap = Math.max(0, Double.parseDouble(dailyCap.getValue().strip().replace("$", "")));
			return true;
		} catch (NumberFormatException e) {
			error = "Spend caps must be numbers, like 2 or 0.50.";
			rebuildWidgets();
			return false;
		}
	}

	@Override
	public void onClose() {
		if (!settings.canEdit || commit()) {
			minecraft.gui.setScreen(parent);
		}
	}
}
