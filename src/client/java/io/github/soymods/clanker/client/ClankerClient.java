package io.github.soymods.clanker.client;

import io.github.soymods.clanker.Clanker;
import io.github.soymods.clanker.client.config.ClientConfig;
import io.github.soymods.clanker.client.gui.ClankerSettingsScreen;
import io.github.soymods.clanker.network.HostSettings;
import io.github.soymods.clanker.network.SettingsPayload;
import io.github.soymods.clanker.network.SignalPayload;
import io.github.soymods.clanker.network.SpeechPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public class ClankerClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientConfig.load();
		ClankerKeys.register();

		ClientPlayNetworking.registerGlobalReceiver(SpeechPayload.TYPE, (payload, context) -> {
			// TODO(0.4): synthesize with Piper and play at the entity's position; show subtitle
			Clanker.LOGGER.debug("Speech from entity {}: {}", payload.entityId(), payload.text());
		});

		ClientPlayNetworking.registerGlobalReceiver(SignalPayload.OpenSettings.TYPE, (payload, context) ->
				context.client().gui.setScreen(new ClankerSettingsScreen(context.client().gui.screen())));

		ClientPlayNetworking.registerGlobalReceiver(SettingsPayload.TYPE, (payload, context) -> {
			if (context.client().gui.screen() instanceof ClankerSettingsScreen screen) {
				screen.receive(HostSettings.fromJson(payload.json()));
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(ClankerClient::handleKeys);
	}

	private static void handleKeys(Minecraft client) {
		while (ClankerKeys.openSettings.consumeClick()) {
			if (client.gui.screen() == null) {
				client.gui.setScreen(new ClankerSettingsScreen(null));
			}
		}
		while (ClankerKeys.stop.consumeClick()) {
			if (ClientPlayNetworking.canSend(SignalPayload.Stop.TYPE)) {
				ClientPlayNetworking.send(SignalPayload.Stop.INSTANCE);
			}
		}
	}
}
