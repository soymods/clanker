package io.github.soymods.clanker.server.pathing;

import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.pathing.util.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Turns a movement goal into per-tick player input. Long trips go through the Baritone port ({@link PathingBehavior}),
 * which can walk, sprint, jump, swim, parkour, dig through and pillar or bridge over things; the last stretch, and
 * short hops to a target in plain view (chasing a mob, grabbing a drop), are steered directly.
 */
public class MovementController {
	/** Without any progress for this long, tasks are told movement is stuck. */
	private static final int STUCK_TICKS = 100;
	/** Searches that find nothing at all before giving up on pathing and walking straight at it. */
	private static final int MAX_FAILURES = 3;
	/** A moving goal (a player, a mob) is only handed to the path search again once it has moved this far. */
	private static final double REPATH_GOAL_MOVED = 1.5;
	/** Targets this close and in plain view are walked to directly. */
	private static final double DIRECT_RANGE = 3.5;

	private final BotContext pathing;

	private Goal goal;
	private Goal searchGoal;
	private boolean sprint;
	private Entity lookEntity;
	private Vec3 lookPos;

	private double bestDistance = Double.MAX_VALUE;
	private int ticksWithoutProgress;
	private int lastPathPosition = -1;
	private boolean arrived;
	private boolean jumpQueued;

	public MovementController(ClankerBot bot) {
		this.pathing = new BotContext(bot);
	}

	public BotContext pathing() {
		return pathing;
	}

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
		searchGoal = null;
		arrived = false;
		ticksWithoutProgress = 0;
		bestDistance = Double.MAX_VALUE;
		pathing.getPathingBehavior().setGoal(null);
	}

	/** Drops any path at once, e.g. when the player entity is replaced after a respawn. */
	public void reset() {
		stop();
		pathing.getPathingBehavior().forceCancel();
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
		PathingBehavior behavior = pathing.getPathingBehavior();
		boolean steerDirectly = false;
		if (goal != null) {
			Vec3 feet = player.position();
			if (arrived && !goal.reached(feet, 1)) {
				// a moving target got away again
				arrived = false;
				bestDistance = Double.MAX_VALUE;
				ticksWithoutProgress = 0;
			} else if (goal.reached(feet, 0)) {
				arrived = true;
			}
			if (!arrived) {
				steerDirectly = shouldSteerDirectly(player, behavior);
				updateSearchGoal(behavior, steerDirectly ? null : goal);
				trackProgress(player, behavior);
			}
		}
		if (goal == null || arrived) {
			updateSearchGoal(behavior, null);
		}

		pathing.sprintAllowed = sprint;
		behavior.tick(player);
		if (behavior.isPathing() || pathing.getInputOverrideHandler().inControl()) {
			jumpQueued = false;
			return;
		}

		boolean jumpNow = jumpQueued && player.onGround();
		if (jumpNow) {
			jumpQueued = false;
		}
		player.setJumping(jumpNow);
		if (steerDirectly) {
			walkStraight(player, jumpNow);
			return;
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

	/**
	 * Direct steering for the last stretch (the path search works in whole blocks, the goal may not), for targets close
	 * by in plain view, and as a fallback when the search keeps finding nothing.
	 */
	private boolean shouldSteerDirectly(ServerPlayer player, PathingBehavior behavior) {
		if (behavior.isPathing() && !behavior.isSafeToCancel()) {
			return false; // halfway through a pillar jump or a bridge; let it finish
		}
		BlockPos feet = pathing.playerFeet();
		if (goal.isInGoal(feet) || behavior.failures() >= MAX_FAILURES) {
			return true;
		}
		Vec3 center = goal.center();
		Vec3 position = player.position();
		double flat = Math.sqrt(Math.pow(center.x - position.x, 2) + Math.pow(center.z - position.z, 2));
		if (flat > DIRECT_RANGE || Math.abs(center.y - position.y) > 0.6 || !player.onGround()) {
			return false;
		}
		Vec3 from = position.add(0, 0.5, 0);
		Vec3 to = new Vec3(center.x, position.y + 0.5, center.z);
		return player.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS
				&& !player.level().getBlockState(BlockPos.containing(center.x, position.y - 0.5, center.z)).isAir();
	}

	private void updateSearchGoal(PathingBehavior behavior, Goal wanted) {
		if (wanted == null) {
			if (searchGoal != null) {
				searchGoal = null;
				behavior.setGoal(null);
			}
			return;
		}
		if (searchGoal == null || searchGoal.center().distanceTo(wanted.center()) > REPATH_GOAL_MOVED || searchGoal.radius() != wanted.radius()) {
			searchGoal = wanted;
			behavior.setGoal(wanted);
		}
	}

	private void trackProgress(ServerPlayer player, PathingBehavior behavior) {
		double remaining = player.position().distanceTo(goal.center());
		PathExecutor current = behavior.getCurrent();
		int pathPosition = current == null ? -1 : current.getPosition();
		boolean working = pathing.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)
				|| pathing.getInputOverrideHandler().isInputForcedDown(Input.CLICK_RIGHT);
		if (remaining < bestDistance - 0.3) {
			bestDistance = remaining;
			ticksWithoutProgress = 0;
		} else if (pathPosition != lastPathPosition || working) {
			// digging, building, or moving along a path that winds away from the goal for a while
			ticksWithoutProgress = 0;
		} else {
			ticksWithoutProgress++;
		}
		lastPathPosition = pathPosition;
	}

	private void walkStraight(ServerPlayer player, boolean jumpNow) {
		Vec3 feet = player.position();
		Vec3 aim = goal.center();
		double dx = aim.x - feet.x;
		double dz = aim.z - feet.z;
		float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
		face(player, yaw, 0f);
		double flat = Math.sqrt(dx * dx + dz * dz);
		// ease off right at the target so it doesn't overshoot small goals like a dropped item
		player.zza = flat < 0.5 ? 0.4f : 1f;
		player.setSprinting(sprint && flat > 6 && player.getFoodData().getFoodLevel() > 6);
		boolean blocked = player.horizontalCollision && player.onGround();
		boolean swimming = player.isInWater() || player.isInLava();
		player.setJumping(blocked || swimming || jumpNow);
	}

	private static void face(ServerPlayer player, float yaw, float pitch) {
		player.setYRot(yaw);
		player.setYHeadRot(yaw);
		player.setYBodyRot(yaw);
		player.setXRot(Mth.clamp(pitch, -90f, 90f));
	}
}
