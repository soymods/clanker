package io.github.soymods.clanker.network;

import io.github.soymods.clanker.Clanker;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: something Clanker said, as display text (no voice cues). With {@code lineId} 0 each client voices
 * it locally; otherwise the host is voicing it and the audio follows as {@link VoiceAudioPayload}s with that id. The
 * text can be empty for a line that's only a sound, like a laugh.
 */
public record SpeechPayload(int entityId, String text, int lineId) implements CustomPacketPayload {
	public static final Type<SpeechPayload> TYPE = new Type<>(Clanker.id("speech"));
	public static final StreamCodec<ByteBuf, SpeechPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, SpeechPayload::entityId,
			ByteBufCodecs.stringUtf8(4096), SpeechPayload::text,
			ByteBufCodecs.VAR_INT, SpeechPayload::lineId,
			SpeechPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
