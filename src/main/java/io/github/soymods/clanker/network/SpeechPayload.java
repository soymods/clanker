package io.github.soymods.clanker.network;

import io.github.soymods.clanker.Clanker;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: something Clanker said. Each client synthesizes the audio locally
 * and plays it at the speaking entity's position.
 */
public record SpeechPayload(int entityId, String text) implements CustomPacketPayload {
	public static final Type<SpeechPayload> TYPE = new Type<>(Clanker.id("speech"));
	public static final StreamCodec<ByteBuf, SpeechPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, SpeechPayload::entityId,
			ByteBufCodecs.stringUtf8(4096), SpeechPayload::text,
			SpeechPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
