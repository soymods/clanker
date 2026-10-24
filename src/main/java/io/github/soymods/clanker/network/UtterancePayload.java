package io.github.soymods.clanker.network;

import io.github.soymods.clanker.Clanker;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: a player's speech, already transcribed on their machine.
 */
public record UtterancePayload(String text) implements CustomPacketPayload {
	public static final Type<UtterancePayload> TYPE = new Type<>(Clanker.id("utterance"));
	public static final StreamCodec<ByteBuf, UtterancePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(1024), UtterancePayload::text,
			UtterancePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
