package io.github.soymods.clanker.server.safety;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

public final class ClankerPermissions {
	private ClankerPermissions() {
	}

	/** Ops, the console, and the host of a singleplayer/LAN world (even with cheats off) can manage Clanker. */
	public static boolean canManage(CommandSourceStack source) {
		if (Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source)) {
			return true;
		}
		ServerPlayer player = source.getPlayer();
		return player != null && source.getServer().isSingleplayerOwner(player.nameAndId());
	}

	public static boolean canManage(ServerPlayer player) {
		return canManage(player.createCommandSourceStack());
	}
}
