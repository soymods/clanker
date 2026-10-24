package io.github.soymods.clanker.network;

import io.github.soymods.clanker.server.HostSettingsHandler;
import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerBots;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import io.github.soymods.clanker.server.brain.BrainEvent;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class ClankerNetworking {
	private ClankerNetworking() {
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(SpeechPayload.TYPE, SpeechPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ThinkingPayload.TYPE, ThinkingPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SettingsPayload.TYPE, SettingsPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SignalPayload.OpenSettings.TYPE, SignalPayload.OpenSettings.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(UtterancePayload.TYPE, UtterancePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SettingsPayload.Update.TYPE, SettingsPayload.Update.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SignalPayload.RequestSettings.TYPE, SignalPayload.RequestSettings.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SignalPayload.Stop.TYPE, SignalPayload.Stop.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(UtterancePayload.TYPE, (payload, context) -> {
			if (context.player() instanceof ClankerPlayer) {
				return;
			}
			for (ClankerBot bot : ClankerBots.all()) {
				bot.brain().hear(context.player(), payload.text(), true, payload.cutOff());
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(SignalPayload.RequestSettings.TYPE, (payload, context) ->
				context.responseSender().sendPacket(new SettingsPayload(HostSettingsHandler.snapshot(context.player(), "").toJson())));

		ServerPlayNetworking.registerGlobalReceiver(SettingsPayload.Update.TYPE, (payload, context) -> {
			String status;
			try {
				status = HostSettingsHandler.apply(context.player(), HostSettings.fromJson(payload.json()));
			} catch (RuntimeException e) {
				status = "Couldn't read those settings.";
			}
			context.responseSender().sendPacket(new SettingsPayload(HostSettingsHandler.snapshot(context.player(), status).toJson()));
		});

		ServerPlayNetworking.registerGlobalReceiver(SignalPayload.Stop.TYPE, (payload, context) -> {
			for (ClankerBot bot : ClankerBots.all()) {
				if (bot.tasks().stop()) {
					bot.brain().post(BrainEvent.of(BrainEvent.Type.NOTE,
							"Your current task was cancelled because " + context.player().getName().getString() + " pressed their stop key."));
				}
			}
		});
	}
}
