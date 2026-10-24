package io.github.soymods.clanker.test;

import io.github.soymods.clanker.client.gui.ClankerLimitsScreen;
import io.github.soymods.clanker.client.gui.ClankerSettingsScreen;
import io.github.soymods.clanker.config.ServerConfig;
import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerBots;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Runs in a real client: the bot shows up in the world, and the settings screen sends a key to the server without
 * ever getting it back.
 */
public class ClankerClientGameTests implements FabricClientGameTest {
	private static final String TEST_KEY = "sk-test-not-a-real-key";

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();

			// A visible bot in front of the player, with the model replaced so nothing reaches a real API.
			BlockPos botPos = singleplayer.getServer().computeOnServer(server -> {
				ServerPlayer host = server.getPlayerList().getPlayers().getFirst();
				Vec3 pos = host.position().add(host.getLookAngle().multiply(1, 0, 1).normalize().scale(4));
				try {
					ClankerBot bot = ClankerBots.spawn(server, host.level(), pos, host.getYRot() + 180, 0, "Clanker");
					bot.brain().setProviderOverride(FakeProvider.silent());
					return bot.player().blockPosition();
				} catch (ClankerBots.SpawnException e) {
					throw new AssertionError(e.getMessage());
				}
			});
			context.waitTicks(40);
			context.getInput().lookAt(botPos.above());
			context.waitTicks(5);
			context.takeScreenshot("clanker-in-world");

			context.setScreen(() -> new ClankerSettingsScreen(null));
			context.waitFor(client -> apiKeyField(client.gui.screen()) != null);
			context.runOnClient(client -> {
				EditBox apiKey = apiKeyField(client.gui.screen());
				client.gui.screen().setFocused(apiKey);
			});
			context.getInput().typeChars(TEST_KEY);
			context.takeScreenshot("clanker-settings-typed");
			context.clickScreenButton("Save");

			singleplayer.getServer().waitFor(server -> TEST_KEY.equals(ServerConfig.get().apiKey));
			// The screen refreshes from the server: it only learns that a key is set.
			context.waitFor(client -> {
				EditBox box = apiKeyField(client.gui.screen());
				return box != null && box.getValue().isEmpty();
			});
			context.takeScreenshot("clanker-settings-saved");

			// Limits: flipping one permission and saving changes only that one, and keeps the key.
			context.clickScreenButton("Limits…");
			context.waitFor(client -> client.gui.screen() instanceof ClankerLimitsScreen);
			context.takeScreenshot("clanker-limits");
			clickWidget(context, "Attack players");
			context.clickScreenButton("Back");
			context.clickScreenButton("Save");
			singleplayer.getServer().waitFor(server -> ServerConfig.get().canAttackPlayers);
			singleplayer.getServer().runOnServer(server -> {
				ServerConfig config = ServerConfig.get();
				if (!config.canBreakBlocks || !config.canPlaceBlocks || config.sessionSpendCap <= 0 || !TEST_KEY.equals(config.apiKey)) {
					throw new AssertionError("saving the limits changed other settings");
				}
			});
			context.waitFor(client -> apiKeyField(client.gui.screen()) != null);
			context.clickScreenButton("Limits…");
			clickWidget(context, "Attack players");
			context.clickScreenButton("Back");

			context.clickScreenButton("Voice…");
			context.waitFor(client -> client.gui.screen() instanceof io.github.soymods.clanker.client.gui.ClankerVoiceScreen);
			context.takeScreenshot("clanker-voice-settings");
			context.clickScreenButton("Done");
			context.waitFor(client -> apiKeyField(client.gui.screen()) != null);

			context.clickScreenButton("Remove key");
			context.clickScreenButton("Save");
			singleplayer.getServer().waitFor(server -> ServerConfig.get().apiKey.isEmpty() && !ServerConfig.get().canAttackPlayers);
			context.clickScreenButton("Done");

			singleplayer.getServer().runOnServer(server -> ClankerBots.first().ifPresent(ClankerBots::despawn));
		}
	}

	/** Clicks a widget whose label starts with {@code label}, the way a player would. CycleButtons aren't Buttons. */
	private static void clickWidget(ClientGameTestContext context, String label) {
		double[] pos = context.computeOnClient(client -> {
			for (var child : client.gui.screen().children()) {
				if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget && widget.getMessage().getString().startsWith(label)) {
					double scale = client.getWindow().getGuiScale();
					return new double[] {(widget.getX() + widget.getWidth() / 2.0) * scale, (widget.getY() + widget.getHeight() / 2.0) * scale};
				}
			}
			throw new AssertionError("no widget labelled " + label);
		});
		context.getInput().setCursorPos(pos[0], pos[1]);
		context.getInput().pressMouse(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT);
	}

	private static EditBox apiKeyField(net.minecraft.client.gui.screens.Screen screen) {
		if (!(screen instanceof ClankerSettingsScreen settings)) {
			return null;
		}
		for (var child : settings.children()) {
			if (child instanceof EditBox box && box.getMessage().getString().equals("API key")) {
				return box;
			}
		}
		return null;
	}
}
