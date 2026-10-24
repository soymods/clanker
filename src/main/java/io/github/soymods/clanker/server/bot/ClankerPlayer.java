package io.github.soymods.clanker.server.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Clanker's body: a real server player with no client behind it.
 * All state that must survive death lives in {@link ClankerBot}, since respawning creates a new instance.
 */
public class ClankerPlayer extends ServerPlayer {
	public ClankerPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info) {
		super(server, level, profile, info);
		// Show every skin layer (hat, jacket, sleeves...).
		entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7f);
	}

	/** Players normally move on their client; Clanker's movement has to be simulated by the server. */
	@Override
	public boolean isClientAuthoritative() {
		return false;
	}

	@Override
	public void tick() {
		ClankerBot bot = ClankerBots.get(getUUID());
		if (bot != null) {
			bot.beforePlayerTick(this);
		}
		if (level().getServer().getTickCount() % 10 == 0) {
			connection.resetPosition();
			level().getChunkSource().move(this);
		}
		super.tick();
		// Real players get this from their movement packets.
		doTick();
	}

	@Override
	public String getIpAddress() {
		return "127.0.0.1";
	}
}
