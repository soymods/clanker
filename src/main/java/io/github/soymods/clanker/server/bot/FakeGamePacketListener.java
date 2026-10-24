package io.github.soymods.clanker.server.bot;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;

import java.util.Set;

/**
 * Packet listener for Clanker's fake player. Adapted from Carpet mod's NetHandlerPlayServerFake (MIT).
 */
public class FakeGamePacketListener extends ServerGamePacketListenerImpl {
	public FakeGamePacketListener(MinecraftServer server, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
		super(server, connection, player, cookie);
	}

	@Override
	public void disconnect(DisconnectionDetails details) {
		// Clanker never sends input packets, so the idle timeout would otherwise kick it.
		if (details.reason().getContents() instanceof TranslatableContents text
				&& text.getKey().equals("multiplayer.disconnect.idling")) {
			return;
		}
		onDisconnect(details);
	}

	/** There is no client to finish loading; without this the bot is invulnerable for a while after joining and respawning. */
	@Override
	public boolean hasClientLoaded() {
		return true;
	}

	@Override
	public void teleport(PositionMoveRotation destination, Set<Relative> relatives) {
		super.teleport(destination, relatives);
		// No client will confirm the teleport, so accept it immediately.
		if (player.level().getPlayerByUUID(player.getUUID()) != null) {
			resetPosition();
			player.level().getChunkSource().move(player);
		}
	}
}
