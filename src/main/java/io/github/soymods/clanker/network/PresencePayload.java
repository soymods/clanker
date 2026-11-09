package io.github.soymods.clanker.network;

import io.github.soymods.clanker.Clanker;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: whether any Clanker is in the world, so the client only uses the microphone when there's someone
 * to talk to. Sent on joining and whenever it changes.
 */
public record PresencePayload(boolean present) implements CustomPacketPayload {
	public static final Type<PresencePayload> TYPE = new Type<>(Clanker.id("presence"));
	public static final StreamCodec<ByteBuf, PresencePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, PresencePayload::present,
			PresencePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
