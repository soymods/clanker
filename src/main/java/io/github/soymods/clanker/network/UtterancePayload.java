package io.github.soymods.clanker.network;

import io.github.soymods.clanker.Clanker;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: a player's speech, already transcribed on their machine. {@code cutOff} is true if they started
 * talking over Clanker and their client stopped its voice.
 */
public record UtterancePayload(String text, boolean cutOff) implements CustomPacketPayload {
	public static final Type<UtterancePayload> TYPE = new Type<>(Clanker.id("utterance"));
	public static final StreamCodec<ByteBuf, UtterancePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(1024), UtterancePayload::text,
			ByteBufCodecs.BOOL, UtterancePayload::cutOff,
			UtterancePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
