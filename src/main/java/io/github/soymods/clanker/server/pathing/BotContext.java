/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */
// Adapted from Baritone's IBaritone and IPlayerContext (branch 26.2, 9fadf7cf) for Clanker: one per
// Clanker, wrapping its server-side player instead of the client's local player.

package io.github.soymods.clanker.server.pathing;

import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.pathing.movement.MovementHelper;
import io.github.soymods.clanker.server.pathing.util.BetterBlockPos;
import io.github.soymods.clanker.server.pathing.util.BlockStateInterface;
import io.github.soymods.clanker.server.pathing.util.RayTraceUtils;
import io.github.soymods.clanker.server.pathing.util.Rotation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Stands in for Baritone's {@code IBaritone} and {@code IPlayerContext} for one Clanker: the ported pathing code asks
 * it for the player, the world, what the player is looking at, and the look/input/inventory/pathing behaviors.
 * Everything here runs on the server thread except what {@link BlockStateInterface} snapshots for searches.
 */
public final class BotContext {
	private final ClankerBot bot;
	private final Inputs inputs = new Inputs(this);
	private final Look look = new Look();
	private final Throwaway inventory = new Throwaway(this);
	private final PathingBehavior pathing = new PathingBehavior(this);
	/** Whether the current task wants to sprint; read when a search or the sprint check builds its context. */
	public boolean sprintAllowed = true;

	public BotContext(ClankerBot bot) {
		this.bot = bot;
	}

	public ClankerBot bot() {
		return bot;
	}

	// IBaritone

	public BotContext getPlayerContext() {
		return this;
	}

	public Inputs getInputOverrideHandler() {
		return inputs;
	}

	public Look getLookBehavior() {
		return look;
	}

	public Throwaway getInventoryBehavior() {
		return inventory;
	}

	public PathingBehavior getPathingBehavior() {
		return pathing;
	}

	/** A block view of the live world, for use on the server thread. */
	public BlockStateInterface bsi() {
		return new BlockStateInterface(this);
	}

	// IPlayerContext

	/** The current player entity (replaced on respawn, so never cache it). */
	public ServerPlayer player() {
		return bot.player();
	}

	public ServerLevel world() {
		return player().level();
	}

	public PlayerController playerController() {
		return new PlayerController(player().blockInteractionRange());
	}

	public record PlayerController(double getBlockReachDistance) {
		public double getBlockReachDistance() {
			return getBlockReachDistance;
		}
	}

	/** What the crosshair would be on: a block raytrace along the player's current rotation. */
	public HitResult objectMouseOver() {
		return RayTraceUtils.rayTraceTowards(player(), playerRotations(), playerController().getBlockReachDistance());
	}

	public BetterBlockPos playerFeet() {
		// same soul sand / slab handling as Baritone's IPlayerContext
		Vec3 position = player().position();
		BetterBlockPos feet = new BetterBlockPos(position.x, position.y + 0.1251, position.z);
		if (world().getBlockState(feet).getBlock() instanceof SlabBlock) {
			return feet.above();
		}
		return feet;
	}

	public Vec3 playerFeetAsVec() {
		return player().position();
	}

	public Vec3 playerHead() {
		Vec3 position = player().position();
		return new Vec3(position.x, position.y + player().getEyeHeight(), position.z);
	}

	public Vec3 playerMotion() {
		return player().getDeltaMovement();
	}

	public Rotation playerRotations() {
		return new Rotation(player().getYRot(), player().getXRot());
	}

	public Optional<BlockPos> getSelectedBlock() {
		HitResult result = objectMouseOver();
		if (result != null && result.getType() == HitResult.Type.BLOCK) {
			return Optional.of(((BlockHitResult) result).getBlockPos());
		}
		return Optional.empty();
	}

	public boolean isLookingAt(BlockPos pos) {
		return getSelectedBlock().equals(Optional.of(pos));
	}

	/** Baritone's PathingBehavior.pathStart(), used by movements to check where a segment began. */
	public BetterBlockPos pathStart() {
		return pathing.pathStart();
	}

	/**
	 * Baritone's LookBehavior, minus the client camera: a forced or movement rotation is applied to the player
	 * directly before it moves.
	 */
	public static final class Look {
		private Rotation target;

		public void updateTarget(Rotation rotation, boolean blockInteract) {
			this.target = rotation;
		}

		public AimProcessor getAimProcessor() {
			return AimProcessor.INSTANCE;
		}

		/** The rotation to apply this tick, then forgotten. */
		Rotation take() {
			Rotation rotation = target;
			target = null;
			return rotation;
		}
	}

	/** Baritone smooths and randomizes aim here; Clanker aims exactly. */
	public static final class AimProcessor {
		static final AimProcessor INSTANCE = new AimProcessor();

		public Rotation peekRotation(Rotation rotation) {
			return rotation;
		}
	}

	/** True if the bot could stand on this block, checked against the live world. */
	public boolean canStandOn(BlockPos pos) {
		return MovementHelper.canWalkOn(this, pos);
	}
}
