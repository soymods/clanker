package io.github.soymods.clanker.client.audio;

import io.github.soymods.clanker.Clanker;
import io.github.soymods.clanker.client.tts.Synthesizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;

/**
 * One spoken line, played through Minecraft's own sound engine as a mono stream so it comes from Clanker's position,
 * fades with distance, and follows the Voice/Speech volume slider. Uses Fabric's custom-stream hook; the
 * {@code clanker:voice} sound event in sounds.json points at Fabric's placeholder sound.
 */
public class VoiceSoundInstance extends AbstractTickableSoundInstance {
	public static final SoundEvent VOICE = SoundEvent.createVariableRangeEvent(Clanker.id("voice"));

	private final int entityId;
	private final Synthesizer.Audio audio;

	public VoiceSoundInstance(int entityId, Synthesizer.Audio audio, float volume) {
		super(VOICE, SoundSource.VOICE, SoundInstance.createUnseededRandom());
		this.entityId = entityId;
		this.audio = audio;
		this.volume = volume;
		this.attenuation = Attenuation.LINEAR;
		follow();
	}

	@Override
	public void tick() {
		follow();
	}

	private void follow() {
		Minecraft client = Minecraft.getInstance();
		Entity entity = client.level == null ? null : client.level.getEntity(entityId);
		if (entity != null) {
			x = entity.getX();
			y = entity.getEyeY();
			z = entity.getZ();
		}
	}

	@Override
	public boolean canStartSilent() {
		return true;
	}

	@Override
	public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, Identifier id, boolean repeatInstantly) {
		return CompletableFuture.completedFuture(new PcmStream(audio));
	}

	/** Hands the synthesized samples to OpenAL in chunks, then signals the end with null. */
	private static final class PcmStream implements AudioStream {
		private final short[] samples;
		private final AudioFormat format;
		private int position;

		PcmStream(Synthesizer.Audio audio) {
			this.samples = audio.samples();
			this.format = new AudioFormat(audio.sampleRate(), 16, 1, true, false);
		}

		@Override
		public AudioFormat getFormat() {
			return format;
		}

		@Override
		public ByteBuffer read(int size) {
			if (position >= samples.length) {
				return null;
			}
			int count = Math.min(samples.length - position, Math.max(1, size / 2));
			ByteBuffer buffer = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < count; i++) {
				buffer.putShort(samples[position + i]);
			}
			position += count;
			return buffer.flip();
		}

		@Override
		public void close() {
		}
	}
}
