package io.github.soymods.clanker.network;

import io.github.soymods.clanker.Clanker;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Host settings as JSON ({@link HostSettings}). Server to client: current settings. Client to server: changes.
 */
public record SettingsPayload(String json) implements CustomPacketPayload {
	public static final Type<SettingsPayload> TYPE = new Type<>(Clanker.id("settings"));
	public static final StreamCodec<ByteBuf, SettingsPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(16384), SettingsPayload::json,
			SettingsPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	/** Same shape, sent the other way. */
	public record Update(String json) implements CustomPacketPayload {
		public static final Type<Update> TYPE = new Type<>(Clanker.id("update_settings"));
		public static final StreamCodec<ByteBuf, Update> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(16384), Update::json,
				Update::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
