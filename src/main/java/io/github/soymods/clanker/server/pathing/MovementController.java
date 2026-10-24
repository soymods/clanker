package io.github.soymods.clanker.server.pathing;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Turns a movement goal into per-tick player input (rotation, forward, jump, sprint).
 * 0.1 steers in a straight line and jumps over single-block obstacles; the Baritone port replaces the goal
 * with a path to follow.
 */
public class MovementController {
	private static final int STUCK_TICKS = 60;

	private Vec3 target;
	private double arriveDistance;
	private boolean sprint;
	private Entity lookEntity;
	private Vec3 lookPos;

	private double bestDistance = Double.MAX_VALUE;
	private int ticksWithoutProgress;
	private boolean arrived;
	private boolean jumpQueued;

	public void moveTo(Vec3 target, double arriveDistance, boolean sprint) {
		if (this.target == null || this.target.distanceToSqr(target) > 4) {
			bestDistance = Double.MAX_VALUE;
			ticksWithoutProgress = 0;
		}
		if (this.target == null || this.target.distanceToSqr(target) > arriveDistance * arriveDistance) {
			arrived = false;
		}
		this.target = target;
		this.arriveDistance = arriveDistance;
		this.sprint = sprint;
	}

	public void stop() {
		target = null;
		arrived = false;
		ticksWithoutProgress = 0;
		bestDistance = Double.MAX_VALUE;
	}

	public void lookAt(Entity entity) {
		lookEntity = entity;
		lookPos = null;
	}

	public void lookAt(Vec3 pos) {
		lookPos = pos;
		lookEntity = null;
	}

	/** Jump once on the next tick the player is on the ground. */
	public void jumpOnce() {
		jumpQueued = true;
	}

	public void clearLook() {
		lookEntity = null;
		lookPos = null;
	}

	public boolean isMoving() {
		return target != null && !arrived;
	}

	public boolean hasArrived() {
		return arrived;
	}

	public boolean isStuck() {
		return ticksWithoutProgress > STUCK_TICKS;
	}

	public void apply(ServerPlayer player) {
		player.zza = 0;
		player.xxa = 0;
		boolean jumpNow = jumpQueued && player.onGround();
		if (jumpNow) {
			jumpQueued = false;
		}
		player.setJumping(jumpNow);

		if (target != null) {
			Vec3 pos = player.position();
			double dx = target.x - pos.x;
			double dz = target.z - pos.z;
			double horizontal = Math.sqrt(dx * dx + dz * dz);
			double dy = target.y - pos.y;

			if (arrived && (horizontal > arriveDistance + 1 || Math.abs(dy) >= 2.5)) {
				// a moving target got away again
				arrived = false;
				bestDistance = Double.MAX_VALUE;
				ticksWithoutProgress = 0;
			} else if (horizontal <= arriveDistance && Math.abs(dy) < 2.5) {
				arrived = true;
			}
			if (!arrived) {
				float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
				face(player, yaw, 0f);
				player.zza = 1f;
				boolean canSprint = sprint && horizontal > 6 && player.getFoodData().getFoodLevel() > 6;
				player.setSprinting(canSprint);

				boolean blocked = player.horizontalCollision && player.onGround();
				boolean swimming = player.isInWater() || player.isInLava();
				player.setJumping(blocked || swimming || jumpNow);

				double distance = horizontal + Math.abs(dy);
				if (distance < bestDistance - 0.3) {
					bestDistance = distance;
					ticksWithoutProgress = 0;
				} else {
					ticksWithoutProgress++;
				}
				return;
			}
		}

		player.setSprinting(false);
		Vec3 look = lookEntity != null && lookEntity.isAlive() ? lookEntity.getEyePosition() : lookPos;
		if (look != null) {
			Vec3 eye = player.getEyePosition();
			double dx = look.x - eye.x;
			double dy = look.y - eye.y;
			double dz = look.z - eye.z;
			float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
			float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
			face(player, yaw, pitch);
		}
	}

	private static void face(ServerPlayer player, float yaw, float pitch) {
		player.setYRot(yaw);
		player.setYHeadRot(yaw);
		player.setYBodyRot(yaw);
		player.setXRot(Mth.clamp(pitch, -90f, 90f));
	}
}
