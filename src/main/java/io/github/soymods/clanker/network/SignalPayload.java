package io.github.soymods.clanker.network;

import io.github.soymods.clanker.Clanker;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Payloads with no data, where the type is the whole message.
 */
public final class SignalPayload {
	private SignalPayload() {
	}

	/** Server to client: open the Clanker settings screen. */
	public record OpenSettings() implements CustomPacketPayload {
		public static final OpenSettings INSTANCE = new OpenSettings();
		public static final Type<OpenSettings> TYPE = new Type<>(Clanker.id("open_settings"));
		public static final StreamCodec<ByteBuf, OpenSettings> CODEC = StreamCodec.unit(INSTANCE);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client to server: send me the current host settings. */
	public record RequestSettings() implements CustomPacketPayload {
		public static final RequestSettings INSTANCE = new RequestSettings();
		public static final Type<RequestSettings> TYPE = new Type<>(Clanker.id("request_settings"));
		public static final StreamCodec<ByteBuf, RequestSettings> CODEC = StreamCodec.unit(INSTANCE);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client to server: the stop key was pressed. */
	public record Stop() implements CustomPacketPayload {
		public static final Stop INSTANCE = new Stop();
		public static final Type<Stop> TYPE = new Type<>(Clanker.id("stop"));
		public static final StreamCodec<ByteBuf, Stop> CODEC = StreamCodec.unit(INSTANCE);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
