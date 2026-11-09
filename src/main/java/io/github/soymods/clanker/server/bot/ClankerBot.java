package io.github.soymods.clanker.server.bot;

import io.github.soymods.clanker.network.SpeechPayload;
import io.github.soymods.clanker.network.ThinkingPayload;
import io.github.soymods.clanker.network.VoiceAudioPayload;
import io.github.soymods.clanker.server.brain.Brain;
import io.github.soymods.clanker.server.brain.Memory;
import io.github.soymods.clanker.server.pathing.MovementController;
import io.github.soymods.clanker.server.perception.Observer;
import io.github.soymods.clanker.server.skills.TaskRunner;
import io.github.soymods.clanker.server.voice.HostVoice;
import io.github.soymods.clanker.server.voice.VoiceCues;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Everything about one Clanker that outlives a single {@link ClankerPlayer} instance (which is replaced on respawn).
 */
public class ClankerBot {
	private static final int RESPAWN_DELAY_TICKS = 20;
	/** Clients farther than this don't receive speech audio packets. */
	private static final double SPEECH_RANGE = 96;

	private final MinecraftServer server;
	private final UUID id;
	private final String name;
	private final MovementController movement;
	private final TaskRunner tasks;
	private final Brain brain;
	private final Memory memory;
	private final Observer observer;
	private final HostVoice voice;
	private ClankerPlayer player;
	private int deadTicks;
	private String lastSpoken = "";
	private int spokenCount;
	private boolean thinkingShown;

	ClankerBot(MinecraftServer server, UUID id, String name) {
		this.server = server;
		this.id = id;
		this.name = name;
		this.movement = new MovementController(this);
		this.tasks = new TaskRunner(this);
		this.brain = new Brain(this);
		this.memory = Memory.load(server, name);
		this.observer = new Observer(this);
		this.voice = new HostVoice(this);
	}

	public MinecraftServer server() {
		return server;
	}

	public UUID id() {
		return id;
	}

	public String name() {
		return name;
	}

	public ClankerPlayer player() {
		return player;
	}

	public Memory memory() {
		return memory;
	}

	public MovementController movement() {
		return movement;
	}

	public TaskRunner tasks() {
		return tasks;
	}

	public Brain brain() {
		return brain;
	}

	public Observer observer() {
		return observer;
	}

	public HostVoice voice() {
		return voice;
	}

	public String lastSpoken() {
		return lastSpoken;
	}

	public int spokenCount() {
		return spokenCount;
	}

	void setPlayer(ClankerPlayer player) {
		this.player = player;
		this.deadTicks = 0;
		movement.reset();
	}

	void beforePlayerTick(ClankerPlayer player) {
		if (player != this.player) {
			return;
		}
		tasks.tick(player);
		movement.apply(player);
	}

	void serverTick() {
		if (player == null) {
			return;
		}
		if (player.isDeadOrDying()) {
			if (++deadTicks == RESPAWN_DELAY_TICKS) {
				player.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
			}
		} else {
			deadTicks = 0;
			observer.tick(player);
		}
		brain.tick();
		if (brain.isThinking() != thinkingShown) {
			thinkingShown = !thinkingShown;
			sendNearby(new ThinkingPayload(player.getId(), thinkingShown), ThinkingPayload.TYPE);
		}
	}

	/**
	 * Says something out loud: shown in chat for everyone (without voice cues), and voiced for nearby clients that have
	 * Clanker, by the host's voice if it has one, else by each client's local voice.
	 */
	public void say(String text) {
		String spoken = text.replaceAll("\\s+", " ").strip();
		if (spoken.length() > 600) {
			spoken = spoken.substring(0, 600) + "…";
		}
		String display = VoiceCues.forDisplay(spoken);
		if (player == null || (display.isEmpty() && !VoiceCues.hasCues(spoken))) {
			return;
		}
		int lineId = voice.submit(spoken);
		if (display.isEmpty() && lineId == 0) {
			return; // just a sound, and nothing to make it with
		}
		lastSpoken = display.isEmpty() ? spoken : display;
		spokenCount++;
		// Speech first, so clients that will voice the line know to keep its chat copy off screen.
		sendNearby(new SpeechPayload(player.getId(), display, lineId), SpeechPayload.TYPE);
		if (!display.isEmpty()) {
			server.getPlayerList().broadcastSystemMessage(
					Component.translatable("chat.type.text", player.getDisplayName(), Component.literal(display)), false);
		}
		brain.onSpoke();
	}

	/** A piece of host-voiced audio for players near Clanker. */
	public void sendVoiceAudio(VoiceAudioPayload payload) {
		if (player != null) {
			sendNearby(payload, VoiceAudioPayload.TYPE);
		}
	}

	/** Sends a payload to players near Clanker who have the mod. */
	private void sendNearby(CustomPacketPayload payload, CustomPacketPayload.Type<?> type) {
		for (ServerPlayer listener : server.getPlayerList().getPlayers()) {
			if (listener != player && listener.level() == player.level()
					&& listener.distanceTo(player) <= SPEECH_RANGE
					&& ServerPlayNetworking.canSend(listener, type)) {
				ServerPlayNetworking.send(listener, payload);
			}
		}
	}
}
