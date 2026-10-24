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
// Adapted from Baritone's InputOverrideHandler, BlockBreakHelper and BlockPlaceHelper (branch 26.2, 9fadf7cf)
// for Clanker: drives a server-side player through the server game mode instead of the client's controls.

package io.github.soymods.clanker.server.pathing;

import io.github.soymods.clanker.config.ServerConfig;
import io.github.soymods.clanker.server.pathing.util.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.EnumSet;

/**
 * Baritone's InputOverrideHandler, BlockBreakHelper and BlockPlaceHelper for a server-side player: held "keys" become
 * movement input on the player, and clicks go through the same server game-mode calls a real client's packets reach,
 * so survival break times, reach, spawn protection and the host's permissions all apply.
 */
public final class Inputs {
	private final BotContext ctx;
	private final EnumSet<Input> forced = EnumSet.noneOf(Input.class);
	private final BlockBreakHelper breaker = new BlockBreakHelper();
	private int rightClickTimer;
	private int sequence;
	private boolean wasControlling;

	Inputs(BotContext ctx) {
		this.ctx = ctx;
	}

	public boolean isInputForcedDown(Input input) {
		return input != null && forced.contains(input);
	}

	public void setInputForceState(Input input, boolean force) {
		if (force) {
			forced.add(input);
		} else {
			forced.remove(input);
		}
	}

	public void clearAllKeys() {
		forced.clear();
	}

	public BlockBreakHelper getBlockBreakHelper() {
		return breaker;
	}

	/** True while any movement key is held, i.e. the path executor is steering. */
	public boolean inControl() {
		for (Input input : new Input[]{Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.SNEAK, Input.JUMP}) {
			if (forced.contains(input)) {
				return true;
			}
		}
		return false;
	}

	/** Runs the clicks and writes the held keys to the player; called just before the player ticks. */
	void tick(ServerPlayer player) {
		if (forced.contains(Input.CLICK_LEFT)) {
			forced.remove(Input.CLICK_RIGHT);
		}
		breaker.tick(player, forced.contains(Input.CLICK_LEFT));
		tickRightClick(player, forced.contains(Input.CLICK_RIGHT));

		// Only touch the player's movement while steering (and once more to let go), so a crouch or a jump from
		// somewhere else isn't undone every tick.
		boolean controlling = !forced.isEmpty();
		if (!controlling && !wasControlling) {
			return;
		}
		wasControlling = controlling;
		boolean forward = forced.contains(Input.MOVE_FORWARD);
		boolean back = forced.contains(Input.MOVE_BACK);
		boolean left = forced.contains(Input.MOVE_LEFT);
		boolean right = forced.contains(Input.MOVE_RIGHT);
		boolean jump = forced.contains(Input.JUMP);
		boolean sneak = forced.contains(Input.SNEAK);
		float zza = (forward ? 1 : 0) - (back ? 1 : 0);
		float xxa = (left ? 1 : 0) - (right ? 1 : 0);
		if (sneak) {
			// the client applies the sneaking slowdown to its input before sending it, so do the same here
			zza *= 0.3f;
			xxa *= 0.3f;
		}
		player.zza = zza;
		player.xxa = xxa;
		player.setJumping(jump);
		player.setShiftKeyDown(sneak);
		boolean sprint = forced.contains(Input.SPRINT) && zza > 0 && !sneak && player.getFoodData().getFoodLevel() > 6 && !player.isUsingItem();
		player.setSprinting(sprint);
		player.setLastClientInput(new net.minecraft.world.entity.player.Input(forward, back, left, right, jump, sneak, sprint));
	}

	private void tickRightClick(ServerPlayer player, boolean requested) {
		if (rightClickTimer > 0) {
			rightClickTimer--;
			return;
		}
		HitResult mouseOver = ctx.objectMouseOver();
		if (!requested || player.isUsingItem() || mouseOver == null || mouseOver.getType() != HitResult.Type.BLOCK) {
			return;
		}
		ServerLevel level = player.level();
		BlockHitResult hit = (BlockHitResult) mouseOver;
		if (!ServerConfig.get().canPlaceBlocks || !level.mayInteract(player, hit.getBlockPos())) {
			return;
		}
		rightClickTimer = PathingSettings.get().rightClickSpeed.value - 1;
		for (InteractionHand hand : InteractionHand.values()) {
			if (player.gameMode.useItemOn(player, level, player.getItemInHand(hand), hand, hit).consumesAction()) {
				player.swing(hand, true);
				return;
			}
			if (!player.getItemInHand(hand).isEmpty() && player.gameMode.useItem(player, level, player.getItemInHand(hand), hand).consumesAction()) {
				return;
			}
		}
	}

	/**
	 * Holds left click on whatever the player is looking at, the way the client does: start, accumulate progress each
	 * tick, then finish once it reaches 1 (the server checks the elapsed time itself and shows the cracks).
	 */
	public final class BlockBreakHelper {
		private BlockPos breaking;
		private Direction face;
		private float progress;
		private int breakDelay;

		public void stopBreakingBlock() {
			ServerPlayer player = ctx.player();
			if (player != null && breaking != null) {
				send(player, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, breaking, face);
			}
			breaking = null;
			progress = 0;
		}

		void tick(ServerPlayer player, boolean leftClick) {
			if (breakDelay > 0) {
				breakDelay--;
				return;
			}
			HitResult trace = ctx.objectMouseOver();
			if (!leftClick || trace == null || trace.getType() != HitResult.Type.BLOCK || !ServerConfig.get().canBreakBlocks) {
				stopBreakingBlock();
				return;
			}
			BlockHitResult hit = (BlockHitResult) trace;
			BlockPos pos = hit.getBlockPos();
			ServerLevel level = player.level();
			BlockState state = level.getBlockState(pos);
			if (state.isAir()) {
				stopBreakingBlock();
				return;
			}
			if (!pos.equals(breaking)) {
				stopBreakingBlock();
				send(player, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, hit.getDirection());
				player.swing(InteractionHand.MAIN_HAND, true);
				if (state.getDestroyProgress(player, level, pos) < 1 && !level.getBlockState(pos).isAir()) {
					// not an instant break: keep hitting it on the following ticks
					breaking = pos.immutable();
					face = hit.getDirection();
					progress = 0;
				}
				return;
			}
			progress += state.getDestroyProgress(player, level, pos);
			player.swing(InteractionHand.MAIN_HAND, true);
			if (progress >= 1) {
				send(player, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face);
				breaking = null;
				progress = 0;
				// the delay only applies after multi-tick breaks, like vanilla
				breakDelay = PathingSettings.get().blockBreakSpeed.value - 1;
			}
		}

		private void send(ServerPlayer player, ServerboundPlayerActionPacket.Action action, BlockPos pos, Direction direction) {
			player.gameMode.handleBlockBreakAction(pos, action, direction, player.level().getMaxY(), sequence++);
		}
	}
}
