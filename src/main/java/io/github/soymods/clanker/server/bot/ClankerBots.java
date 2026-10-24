package io.github.soymods.clanker.server.bot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.github.soymods.clanker.Clanker;
import io.github.soymods.clanker.config.ServerConfig;
import io.github.soymods.clanker.server.brain.BrainEvent;
import io.github.soymods.clanker.server.brain.Spend;
import io.github.soymods.clanker.server.perception.Perception;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Spawns, tracks, and removes Clankers, and feeds them game events.
 */
public final class ClankerBots {
	private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
	private static final Gson GSON = new Gson();
	private static final Map<UUID, ClankerBot> BOTS = new ConcurrentHashMap<>();
	/** Bots that come back when the world is reopened: the configured Clanker, not test or named spawns. */
	private static final Set<UUID> RESTORED_ON_START = ConcurrentHashMap.newKeySet();
	private static boolean stopping;
	/** Clanker was in the world when it was last saved and comes back when the first player joins. */
	private static boolean restorePending;
	private static int restoreRetryTick = -1;

	private ClankerBots() {
	}

	public static ClankerBot get(UUID id) {
		return BOTS.get(id);
	}

	public static Collection<ClankerBot> all() {
		return BOTS.values();
	}

	/** 0.1 runs a single Clanker. */
	public static Optional<ClankerBot> first() {
		return BOTS.values().stream().findFirst();
	}

	public static class SpawnException extends Exception {
		public SpawnException(String message) {
			super(message);
		}
	}

	/**
	 * Spawns Clanker with the configured name. With a null {@code pos} it appears where it was last saved, or at
	 * world spawn. Only one Clanker is allowed this way.
	 */
	public static ClankerBot spawn(MinecraftServer server, ServerLevel level, Vec3 pos, float yaw, float pitch) throws SpawnException {
		if (!BOTS.isEmpty()) {
			throw new SpawnException("Clanker is already in the world");
		}
		ClankerBot bot = spawn(server, level, pos, yaw, pitch, ServerConfig.get().botName);
		RESTORED_ON_START.add(bot.id());
		saveState(server);
		return bot;
	}

	/**
	 * Spawns a Clanker with a specific name. Tests use this to run several at once. Bots spawned this way aren't
	 * brought back when the world is reopened.
	 */
	public static ClankerBot spawn(MinecraftServer server, ServerLevel level, Vec3 pos, float yaw, float pitch, String name) throws SpawnException {
		if (!VALID_NAME.matcher(name).matches()) {
			throw new SpawnException("Bot name \"" + name + "\" must be 3-16 letters, numbers or underscores");
		}
		if (server.getPlayerList().getPlayerByName(name) != null) {
			throw new SpawnException("A player named " + name + " is already online");
		}

		GameProfile profile = new GameProfile(botId(name), name);
		ClankerPlayer player = new ClankerPlayer(server, level, profile, ClientInformation.createDefault());
		ClankerBot bot = new ClankerBot(server, profile.id(), name);
		bot.setPlayer(player);
		BOTS.put(bot.id(), bot);

		try {
			server.getPlayerList().placeNewPlayer(new FakeConnection(), player, new CommonListenerCookie(profile, 0, player.clientInformation(), false));
			boolean hadSave = loadPlayerData(player);
			if (pos != null) {
				player.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), yaw, pitch, true);
			} else if (!hadSave) {
				ServerLevel overworld = server.overworld();
				BlockPos spawn = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, overworld.getRespawnData().pos());
				player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, Set.of(), 0, 0, true);
			}
		} catch (RuntimeException e) {
			BOTS.remove(bot.id());
			throw e;
		}
		if (player.hasDisconnected() || player.isRemoved()) {
			// Some mods' join handlers fail on a player without a client and disconnect it before it's fully in.
			BOTS.remove(bot.id());
			bot.brain().shutdown();
			throw new SpawnException("another mod disconnected " + name + " while it was joining (see the log)");
		}

		bot.brain().post(BrainEvent.of(BrainEvent.Type.SPAWNED, "You just joined the world at " + Perception.coords(player.position()) + "."));
		return bot;
	}

	public static void despawn(ClankerBot bot) {
		BOTS.remove(bot.id());
		bot.brain().shutdown();
		ClankerPlayer player = bot.player();
		if (player != null && !player.isRemoved()) {
			player.connection.onDisconnect(new DisconnectionDetails(Component.translatable("multiplayer.disconnect.generic")));
		}
		saveState(bot.server());
		RESTORED_ON_START.remove(bot.id());
	}

	/** Where wide Steve sits in the client's list of default skins, which it picks from with the player's UUID. */
	private static final int STEVE_SKIN_INDEX = 15;
	private static final int DEFAULT_SKIN_COUNT = 18;

	/**
	 * A stable id for a bot name that clients show with the Steve skin (a bot has no skin of its own, so clients pick
	 * a default skin from {@code floorMod(uuid.hashCode(), 18)}).
	 */
	public static UUID botId(String name) {
		UUID id = UUIDUtil.createOfflinePlayerUUID(name);
		for (int salt = 0; Math.floorMod(id.hashCode(), DEFAULT_SKIN_COUNT) != STEVE_SKIN_INDEX; salt++) {
			id = UUID.nameUUIDFromBytes(("Clanker:" + name + ":" + salt).getBytes(StandardCharsets.UTF_8));
		}
		return id;
	}

	private static boolean loadPlayerData(ClankerPlayer player) {
		try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(player.problemPath(), Clanker.LOGGER)) {
			var players = player.level().getServer().getPlayerList();
			String name = player.getGameProfile().name();
			// Before Clanker had a fixed skin, its save was under the plain offline-mode id for its name.
			Optional<ValueInput> data = players.loadPlayerData(player.nameAndId())
					.or(() -> players.loadPlayerData(new NameAndId(UUIDUtil.createOfflinePlayerUUID(name), name)))
					.map(tag -> TagValueInput.create(reporter, player.registryAccess(), tag));
			data.ifPresent(input -> {
				player.load(input);
				player.loadAndSpawnEnderPearls(input);
			});
			return data.isPresent();
		}
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (restoreRetryTick >= 0 && server.getTickCount() >= restoreRetryTick) {
				restoreRetryTick = -1;
				restore(server, 0);
			}
			for (ClankerBot bot : BOTS.values()) {
				bot.serverTick();
			}
		});

		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			if (sender instanceof ClankerPlayer) {
				return;
			}
			for (ClankerBot bot : BOTS.values()) {
				bot.brain().hear(sender, message.signedContent(), false);
			}
		});

		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
			if (entity instanceof ClankerPlayer player && damageTaken > 0) {
				ClankerBot bot = BOTS.get(player.getUUID());
				if (bot != null) {
					bot.brain().post(BrainEvent.of(BrainEvent.Type.DAMAGE, String.format("Took %.1f damage from %s. Health %.0f/20.",
							damageTaken, describe(source), player.getHealth())));
				}
			}
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ClankerPlayer player) {
				ClankerBot bot = BOTS.get(player.getUUID());
				if (bot != null) {
					bot.tasks().stop();
					bot.brain().post(BrainEvent.of(BrainEvent.Type.DEATH, "You died: " + player.getCombatTracker().getDeathMessage().getString()
							+ " at " + Perception.coords(player.position()) + "."));
				}
			}
		});

		EntitySleepEvents.START_SLEEPING.register((entity, pos) -> {
			if (entity instanceof ServerPlayer sleeper && !(sleeper instanceof ClankerPlayer)) {
				for (ClankerBot bot : BOTS.values()) {
					bot.brain().post(BrainEvent.of(BrainEvent.Type.PLAYER_SLEEPING, sleeper.getName().getString() + " got into bed."));
				}
			}
		});

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (newPlayer instanceof ClankerPlayer player) {
				ClankerBot bot = BOTS.get(player.getUUID());
				if (bot != null) {
					bot.setPlayer(player);
					bot.brain().post(BrainEvent.of(BrainEvent.Type.RESPAWN, "You respawned at " + Perception.coords(player.position()) + "."));
				}
			}
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (!(handler.player instanceof ClankerPlayer) && restorePending) {
				// Come back once someone is here to play with, and after mods have set up for the world.
				restorePending = false;
				server.execute(() -> restore(server, 1));
			}
			if (!(handler.player instanceof ClankerPlayer)) {
				for (ClankerBot bot : BOTS.values()) {
					bot.brain().post(BrainEvent.of(BrainEvent.Type.PLAYER_JOINED, handler.player.getName().getString() + " joined the game."));
				}
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (handler.player instanceof ClankerPlayer player) {
				ClankerBot bot = BOTS.get(player.getUUID());
				// Kicked or removed by something other than despawn().
				if (bot != null && !stopping && bot.player() == player) {
					BOTS.remove(bot.id());
					bot.brain().shutdown();
					saveState(server);
					RESTORED_ON_START.remove(bot.id());
				}
				return;
			}
			for (ClankerBot bot : BOTS.values()) {
				bot.brain().post(BrainEvent.of(BrainEvent.Type.PLAYER_LEFT, handler.player.getName().getString() + " left the game."));
			}
		});

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			Spend.resetSession();
			restorePending = readSpawnedState(server);
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			saveState(server);
			stopping = true;
			for (ClankerBot bot : BOTS.values()) {
				bot.brain().shutdown();
			}
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			BOTS.clear();
			RESTORED_ON_START.clear();
			stopping = false;
			restorePending = false;
			restoreRetryTick = -1;
		});
	}

	private static void restore(MinecraftServer server, int retriesLeft) {
		if (!BOTS.isEmpty() || server.isStopped()) {
			return;
		}
		try {
			spawn(server, server.overworld(), null, 0, 0);
		} catch (SpawnException | RuntimeException e) {
			Clanker.LOGGER.warn("Couldn't bring Clanker back: {}", e.getMessage());
			if (retriesLeft > 0) {
				restoreRetryTick = server.getTickCount() + 40;
			}
		}
	}

	private static String describe(DamageSource source) {
		if (source.getEntity() != null) {
			return source.getEntity().getName().getString();
		}
		return source.type().msgId();
	}

	private static Path statePath(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("clanker").resolve("state.json");
	}

	private static void saveState(MinecraftServer server) {
		JsonObject state = new JsonObject();
		state.addProperty("spawned", BOTS.keySet().stream().anyMatch(RESTORED_ON_START::contains));
		try {
			Path path = statePath(server);
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(state));
		} catch (IOException e) {
			Clanker.LOGGER.error("Failed to save Clanker state", e);
		}
	}

	private static boolean readSpawnedState(MinecraftServer server) {
		Path path = statePath(server);
		if (!Files.isRegularFile(path)) {
			return false;
		}
		try {
			JsonObject state = GSON.fromJson(Files.readString(path), JsonObject.class);
			return state != null && state.has("spawned") && state.get("spawned").getAsBoolean();
		} catch (IOException | RuntimeException e) {
			Clanker.LOGGER.error("Failed to read Clanker state", e);
			return false;
		}
	}
}
