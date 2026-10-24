package io.github.soymods.clanker.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.authlib.GameProfile;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import io.github.soymods.clanker.server.bot.FakeGamePacketListener;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gives Clanker's fake player its own packet listener, and keeps it a {@link ClankerPlayer} across respawns.
 * Adapted from Carpet mod's fake-player mixins (MIT).
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
	@WrapOperation(method = "placeNewPlayer", at = @At(value = "NEW", target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"))
	private ServerGamePacketListenerImpl clanker$fakeListener(MinecraftServer server, Connection connection, ServerPlayer player, CommonListenerCookie cookie, Operation<ServerGamePacketListenerImpl> original) {
		if (player instanceof ClankerPlayer) {
			return new FakeGamePacketListener(server, connection, player, cookie);
		}
		return original.call(server, connection, player, cookie);
	}

	@WrapOperation(method = "respawn", at = @At(value = "NEW", target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/level/ClientInformation;)Lnet/minecraft/server/level/ServerPlayer;"))
	private ServerPlayer clanker$respawnFake(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info, Operation<ServerPlayer> original, ServerPlayer oldPlayer, boolean keepAllPlayerData, Entity.RemovalReason reason) {
		if (oldPlayer instanceof ClankerPlayer) {
			return new ClankerPlayer(server, level, profile, info);
		}
		return original.call(server, level, profile, info);
	}
}
