package io.github.soymods.clanker.server.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Turns a movement goal into per-tick player input (rotation, forward, jump, sprint). Plans a path with
 * {@link PathFinder} and walks it waypoint by waypoint, re-planning when the goal moves or progress stalls.
 */
public class MovementController {
	private static final int STUCK_TICKS = 80;
	private static final int REPATH_COOLDOWN = 10;
	private static final double REPATH_GOAL_MOVED = 2.5;

	private Goal goal;
	private boolean sprint;
	private Entity lookEntity;
	private Vec3 lookPos;

	private List<BlockPos> path;
	private int index;
	private Vec3 pathPlannedFor;
	private int repathCooldown;
	private boolean repathRequested;

	private double bestDistance = Double.MAX_VALUE;
	private int ticksWithoutProgress;
	private boolean arrived;
	private boolean jumpQueued;

	public void moveTo(Vec3 target, double arriveDistance, boolean sprint) {
		moveTo(Goal.near(target, arriveDistance), sprint);
	}

	public void moveTo(Goal goal, boolean sprint) {
		double moved = this.goal == null ? Double.MAX_VALUE : this.goal.center().distanceTo(goal.center());
		if (moved > 1) {
			bestDistance = Double.MAX_VALUE;
			ticksWithoutProgress = 0;
		}
		if (moved > goal.radius()) {
			arrived = false;
		}
		this.goal = goal;
		this.sprint = sprint;
	}

	public void stop() {
		goal = null;
		path = null;
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
		return goal != null && !arrived;
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
		if (repathCooldown > 0) {
			repathCooldown--;
		}

		if (goal != null) {
			Vec3 feet = player.position();
			if (arrived && !goal.reached(feet, 1)) {
				// a moving target got away again
				arrived = false;
				bestDistance = Double.MAX_VALUE;
				ticksWithoutProgress = 0;
			} else if (goal.reached(feet, 0)) {
				arrived = true;
				path = null;
			}
			if (!arrived) {
				walk(player, feet, jumpNow);
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

	private void walk(ServerPlayer player, Vec3 feet, boolean jumpNow) {
		boolean goalMoved = pathPlannedFor != null && pathPlannedFor.distanceTo(goal.center()) > REPATH_GOAL_MOVED;
		boolean pathDone = path != null && index >= path.size();
		if ((path == null || goalMoved || pathDone || repathRequested) && repathCooldown == 0 && player.level() instanceof ServerLevel level) {
			path = PathFinder.find(level, BlockPos.containing(feet.x, feet.y + 0.2, feet.z), goal);
			index = 0;
			pathPlannedFor = goal.center();
			repathCooldown = REPATH_COOLDOWN;
			repathRequested = false;
		}

		boolean advanced = false;
		if (path != null) {
			// Skip every waypoint we're already at, including ones further along (shortcuts, falling past them).
			for (int i = path.size() - 1; i >= index; i--) {
				if (at(feet, path.get(i))) {
					index = i + 1;
					advanced = true;
					break;
				}
			}
		}

		Vec3 aim;
		boolean climb = false;
		if (path != null && index < path.size()) {
			BlockPos waypoint = path.get(index);
			aim = Vec3.atBottomCenterOf(waypoint);
			climb = waypoint.getY() > feet.y + 0.3;
		} else {
			aim = goal.center();
		}

		double dx = aim.x - feet.x;
		double dz = aim.z - feet.z;
		float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
		face(player, yaw, 0f);
		player.zza = 1f;

		double remaining = feet.distanceTo(goal.center());
		boolean straight = path == null || index + 2 >= path.size() || isStraight(feet, path.get(index), path.get(index + 2));
		player.setSprinting(sprint && remaining > 6 && straight && !climb && player.getFoodData().getFoodLevel() > 6);

		boolean blocked = player.horizontalCollision && player.onGround();
		boolean swimming = player.isInWater() || player.isInLava();
		boolean stepUp = climb && player.onGround() && Math.sqrt(dx * dx + dz * dz) < 1.6;
		player.setJumping(blocked || swimming || stepUp || jumpNow);

		if (remaining < bestDistance - 0.3) {
			bestDistance = remaining;
			ticksWithoutProgress = 0;
		} else if (advanced) {
			ticksWithoutProgress = 0;
		} else {
			ticksWithoutProgress++;
			if (ticksWithoutProgress % 30 == 0) {
				repathRequested = true;
			}
		}
	}

	private static boolean at(Vec3 feet, BlockPos waypoint) {
		double dx = waypoint.getX() + 0.5 - feet.x;
		double dz = waypoint.getZ() + 0.5 - feet.z;
		return dx * dx + dz * dz < 0.35 * 0.35 && feet.y > waypoint.getY() - 0.5 && feet.y < waypoint.getY() + 1.2;
	}

	private static boolean isStraight(Vec3 feet, BlockPos next, BlockPos later) {
		Vec3 a = Vec3.atBottomCenterOf(next).subtract(feet).multiply(1, 0, 1);
		Vec3 b = Vec3.atBottomCenterOf(later).subtract(Vec3.atBottomCenterOf(next)).multiply(1, 0, 1);
		return a.lengthSqr() < 0.01 || b.lengthSqr() < 0.01 || a.normalize().dot(b.normalize()) > 0.9;
	}

	private static void face(ServerPlayer player, float yaw, float pitch) {
		player.setYRot(yaw);
		player.setYHeadRot(yaw);
		player.setYBodyRot(yaw);
		player.setXRot(Mth.clamp(pitch, -90f, 90f));
	}
}
