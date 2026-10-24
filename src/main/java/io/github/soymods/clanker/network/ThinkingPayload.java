package io.github.soymods.clanker.network;

import io.github.soymods.clanker.Clanker;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: Clanker started or stopped waiting on the model, for the "thinking" indicator.
 */
public record ThinkingPayload(int entityId, boolean thinking) implements CustomPacketPayload {
	public static final Type<ThinkingPayload> TYPE = new Type<>(Clanker.id("thinking"));
	public static final StreamCodec<ByteBuf, ThinkingPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ThinkingPayload::entityId,
			ByteBufCodecs.BOOL, ThinkingPayload::thinking,
			ThinkingPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
