package io.github.soymods.clanker.client;

import io.github.soymods.clanker.client.audio.VoiceInput;
import io.github.soymods.clanker.client.audio.VoicePlayer;
import io.github.soymods.clanker.client.config.ClientConfig;
import io.github.soymods.clanker.client.gui.ClankerHud;
import io.github.soymods.clanker.client.stt.Transcriber;
import io.github.soymods.clanker.client.tts.Synthesizer;
import io.github.soymods.clanker.client.gui.ClankerSettingsScreen;
import io.github.soymods.clanker.network.HostSettings;
import io.github.soymods.clanker.network.SettingsPayload;
import io.github.soymods.clanker.network.SignalPayload;
import io.github.soymods.clanker.network.SpeechPayload;
import io.github.soymods.clanker.network.ThinkingPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public class ClankerClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientConfig.load();
		ClankerKeys.register();

		ClankerHud.register();

		ClientPlayNetworking.registerGlobalReceiver(SpeechPayload.TYPE, (payload, context) ->
				VoicePlayer.onSpeech(payload.entityId(), payload.text()));
		ClientPlayNetworking.registerGlobalReceiver(ThinkingPayload.TYPE, (payload, context) ->
				ClankerHud.setThinking(payload.entityId(), payload.thinking()));

		ClientPlayNetworking.registerGlobalReceiver(SignalPayload.OpenSettings.TYPE, (payload, context) ->
				context.client().gui.setScreen(new ClankerSettingsScreen(context.client().gui.screen())));

		ClientPlayNetworking.registerGlobalReceiver(SettingsPayload.TYPE, (payload, context) -> {
			if (context.client().gui.screen() instanceof ClankerSettingsScreen screen) {
				screen.receive(HostSettings.fromJson(payload.json()));
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			handleKeys(client);
			VoiceInput.tick(client);
			VoicePlayer.tick(client);
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			VoiceInput.stop();
			Transcriber.shutdown();
			Synthesizer.shutdown();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			VoiceInput.stop();
			VoicePlayer.clear(client);
			ClankerHud.clear();
		}));
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
