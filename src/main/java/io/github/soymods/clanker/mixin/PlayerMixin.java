package io.github.soymods.clanker.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * When a player is hit by another player, vanilla sends the knockback to the victim's client and then puts the old
 * velocity back on the server, since the client moves itself. Clanker has no client, so keep the knockback.
 * Same idea as Carpet mod's fake-player fix (MIT).
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
	@WrapOperation(method = "causeExtraKnockback", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
	private void clanker$keepKnockback(Entity target, Vec3 oldMovement, Operation<Void> original) {
		if (!(target instanceof ClankerPlayer)) {
			original.call(target, oldMovement);
		}
	}
}
