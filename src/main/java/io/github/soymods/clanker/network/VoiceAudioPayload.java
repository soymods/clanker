package io.github.soymods.clanker.network;

import io.github.soymods.clanker.Clanker;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: a piece of a line Clanker is saying, voiced on the host. {@code pcm} is 16-bit little-endian mono
 * at {@link #SAMPLE_RATE}. {@code end} marks the last piece; {@code failed} means the host couldn't voice the line, so
 * the client should say it with its own local voice instead.
 */
public record VoiceAudioPayload(int lineId, byte[] pcm, boolean end, boolean failed) implements CustomPacketPayload {
	public static final int SAMPLE_RATE = 24_000;
	public static final Type<VoiceAudioPayload> TYPE = new Type<>(Clanker.id("voice_audio"));
	public static final StreamCodec<ByteBuf, VoiceAudioPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, VoiceAudioPayload::lineId,
			ByteBufCodecs.byteArray(65_536), VoiceAudioPayload::pcm,
			ByteBufCodecs.BOOL, VoiceAudioPayload::end,
			ByteBufCodecs.BOOL, VoiceAudioPayload::failed,
			VoiceAudioPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
