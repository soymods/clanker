package io.github.soymods.clanker.server.bot;

import io.github.soymods.clanker.mixin.ConnectionAccessor;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * A connection with nobody on the other end. Outgoing packets are dropped.
 * Adapted from Carpet mod's FakeClientConnection (MIT).
 */
public class FakeConnection extends Connection {
	public FakeConnection() {
		super(PacketFlow.SERVERBOUND);
		// An open channel makes isConnected() true, which some vanilla logic (e.g. ender pearls) checks.
		((ConnectionAccessor) this).clanker$setChannel(new EmbeddedChannel());
	}

	@Override
	public void setReadOnly() {
	}

	@Override
	public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
	}

	@Override
	public void handleDisconnection() {
	}

	@Override
	public void setListenerForServerboundHandshake(PacketListener listener) {
	}

	@Override
	public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T listener) {
	}
}
