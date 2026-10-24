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

// Ported from Baritone (branch 26.2, 9fadf7cf) for Clanker. Changes: package names; runs per Clanker on the server
// thread instead of as a client Behavior; processes, path events, rendering, and the elytra/auto-jump hooks are gone;
// the goal is set directly (with Baritone's revalidate-goal rule) and every search gets a fresh CalculationContext.

package io.github.soymods.clanker.server.pathing;

import io.github.soymods.clanker.server.pathing.calc.AStarPathFinder;
import io.github.soymods.clanker.server.pathing.calc.AbstractNodeCostSearch;
import io.github.soymods.clanker.server.pathing.calc.Favoring;
import io.github.soymods.clanker.server.pathing.calc.IPath;
import io.github.soymods.clanker.server.pathing.calc.PathCalculationResult;
import io.github.soymods.clanker.server.pathing.movement.CalculationContext;
import io.github.soymods.clanker.server.pathing.movement.MovementHelper;
import io.github.soymods.clanker.server.pathing.util.BetterBlockPos;
import io.github.soymods.clanker.server.pathing.util.Helper;
import io.github.soymods.clanker.server.pathing.util.Rotation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PathingBehavior implements Helper {
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "Clanker pathing");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    /** After a search finds nothing, wait this many ticks before searching again for the same goal. */
    private static final int FAILURE_COOLDOWN_TICKS = 20;

    final BotContext baritone;
    final BotContext ctx;

    private PathExecutor current;
    private PathExecutor next;

    private Goal goal;
    private CalculationContext context;

    private boolean safeToCancel;
    private boolean cancelRequested;
    private boolean cancelWhenSafe;
    private volatile boolean calcFailed;
    private int failures;
    private int failureCooldown;

    private volatile AbstractNodeCostSearch inProgress;
    private final Object pathCalcLock = new Object();

    private final Object pathPlanLock = new Object();

    private BetterBlockPos expectedSegmentStart;

    public PathingBehavior(BotContext baritone) {
        this.baritone = baritone;
        this.ctx = baritone;
    }

    /**
     * Runs one tick of path following and writes the resulting keys and rotation to the player. Called on the server
     * thread just before the player itself ticks.
     */
    public void tick(ServerPlayer player) {
        if (calcFailed) {
            calcFailed = false;
            failures++;
            failureCooldown = FAILURE_COOLDOWN_TICKS;
        }
        if (failureCooldown > 0) {
            failureCooldown--;
        }
        expectedSegmentStart = pathStart();
        if (goal != null && failureCooldown == 0) {
            startIfIdle();
        }
        tickPath();
        if (cancelWhenSafe && (current == null || safeToCancel)) {
            cancelWhenSafe = false;
            secretInternalSegmentCancel();
        }
        // turn first, so the clicks below hit what the movement is aiming at
        Rotation rotation = baritone.getLookBehavior().take();
        if (rotation != null) {
            float yaw = rotation.getYaw();
            player.setYRot(yaw);
            player.setYHeadRot(yaw);
            player.setYBodyRot(yaw);
            player.setXRot(Mth.clamp(rotation.getPitch(), -90f, 90f));
        }
        baritone.getInputOverrideHandler().tick(player);
    }

    private void tickPath() {
        if (cancelRequested) {
            cancelRequested = false;
            baritone.getInputOverrideHandler().clearAllKeys();
        }
        synchronized (pathPlanLock) {
            synchronized (pathCalcLock) {
                if (inProgress != null) {
                    // we are calculating
                    // are we calculating the right thing though? 🤔
                    BetterBlockPos calcFrom = inProgress.getStart();
                    Optional<IPath> currentBest = inProgress.bestPathSoFar();
                    if ((current == null || !current.getPath().getDest().equals(calcFrom)) // if current ends in inProgress's start, then we're ok
                            && !calcFrom.equals(ctx.playerFeet()) && !calcFrom.equals(expectedSegmentStart) // if current starts in our playerFeet or pathStart, then we're ok
                            && (!currentBest.isPresent() || (!currentBest.get().positions().contains(ctx.playerFeet()) && !currentBest.get().positions().contains(expectedSegmentStart))) // if
                    ) {
                        // when it was *just* started, currentBest will be empty so we need to also check calcFrom since that's always present
                        inProgress.cancel(); // cancellation doesn't dispatch any events
                    }
                }
            }
            if (current == null) {
                return;
            }
            safeToCancel = current.onTick();
            if (current.failed() || current.finished()) {
                current = null;
                if (goal == null || goal.isInGoal(ctx.playerFeet())) {
                    logDebug("All done. At " + goal);
                    next = null;
                    return;
                }
                if (next != null && !next.getPath().positions().contains(ctx.playerFeet()) && !next.getPath().positions().contains(expectedSegmentStart)) { // can contain either one
                    // if the current path failed, we may not actually be on the next one, so make sure
                    logDebug("Discarding next path as it does not contain current position");
                    // for example if we had a nicely planned ahead path that starts where current ends
                    // that's all fine and good
                    // but if we fail in the middle of current
                    // we're nowhere close to our planned ahead path
                    // so need to discard it sadly.
                    next = null;
                }
                if (next != null) {
                    logDebug("Continuing on to planned next path");
                    current = next;
                    next = null;
                    current.onTick(); // don't waste a tick doing nothing, get started right away
                    return;
                }
                // at this point, current just ended, but we aren't in the goal and have no plan for the future
                synchronized (pathCalcLock) {
                    if (inProgress != null) {
                        return;
                    }
                    // we aren't calculating
                    findPathInNewThread(expectedSegmentStart, true);
                }
                return;
            }
            // at this point, we know current is in progress
            if (safeToCancel && next != null && next.snipsnapifpossible()) {
                // a movement just ended; jump directly onto the next path
                logDebug("Splicing into planned next path early...");
                current = next;
                next = null;
                current.onTick();
                return;
            }
            if (PathingSettings.get().splicePath.value) {
                current = current.trySplice(next);
            }
            if (next != null && current.getPath().getDest().equals(next.getPath().getDest())) {
                next = null;
            }
            synchronized (pathCalcLock) {
                if (inProgress != null) {
                    // if we aren't calculating right now
                    return;
                }
                if (next != null) {
                    // and we have no plan for what to do next
                    return;
                }
                if (goal == null || goal.isInGoal(current.getPath().getDest())) {
                    // and this path doesn't get us all the way there
                    return;
                }
                if (ticksRemainingInSegment(false).get() < PathingSettings.get().planningTickLookahead.value) {
                    // and this path has 7.5 seconds or less left
                    // don't include the current movement so a very long last movement (e.g. descend) doesn't trip it up
                    // if we actually included current, it wouldn't start planning ahead until the last movement was done, if the last movement took more than 7.5 seconds on its own
                    logDebug("Path almost over. Planning ahead...");
                    findPathInNewThread(current.getPath().getDest(), false);
                }
            }
        }
    }

    /**
     * Heads for {@code goal} from now on (null to stop). Like Baritone's REVALIDATE_GOAL_AND_PATH: a path in progress is
     * kept unless it was going to end in the old goal and that end isn't in the new one.
     */
    public void setGoal(Goal goal) {
        Goal previous = this.goal;
        this.goal = goal;
        if (goal == null) {
            if (previous != null) {
                cancelSegmentWhenSafe();
            }
            return;
        }
        if (previous != goal) {
            failures = 0;
            failureCooldown = 0;
        }
        if (previous != null && previous != goal && revalidateGoal(goal)) {
            softCancelIfSafe();
        }
    }

    private boolean revalidateGoal(Goal newGoal) {
        for (PathExecutor executor : new PathExecutor[]{current, next}) {
            if (executor != null) {
                Goal intended = executor.getPath().getGoal();
                BlockPos end = executor.getPath().getDest();
                if (intended.isInGoal(end) && !newGoal.isInGoal(end)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void startIfIdle() {
        if (goal.isInGoal(ctx.playerFeet())) {
            return;
        }
        synchronized (pathPlanLock) {
            if (current != null) {
                return;
            }
            synchronized (pathCalcLock) {
                if (inProgress != null) {
                    return;
                }
                findPathInNewThread(expectedSegmentStart, true);
            }
        }
    }

    public Goal getGoal() {
        return goal;
    }

    public boolean hasPath() {
        return current != null;
    }

    public boolean isPathing() {
        return hasPath();
    }

    /** Searches in a row that found no path at all toward the current goal. */
    public int failures() {
        return failures;
    }

    public PathExecutor getCurrent() {
        return current;
    }

    public PathExecutor getNext() {
        return next;
    }

    public Optional<AbstractNodeCostSearch> getInProgress() {
        return Optional.ofNullable(inProgress);
    }

    public boolean isSafeToCancel() {
        if (current == null) {
            return true;
        }
        return safeToCancel;
    }

    public void softCancelIfSafe() {
        synchronized (pathPlanLock) {
            getInProgress().ifPresent(AbstractNodeCostSearch::cancel); // only cancel ours
            if (!isSafeToCancel()) {
                return;
            }
            current = null;
            next = null;
        }
        cancelRequested = true;
        // do everything BUT clear keys
    }

    /** Stops after the current movement if it can't safely be abandoned (e.g. halfway through a pillar jump). */
    public void cancelSegmentWhenSafe() {
        if (isSafeToCancel()) {
            secretInternalSegmentCancel();
        } else {
            getInProgress().ifPresent(AbstractNodeCostSearch::cancel);
            cancelWhenSafe = true;
        }
    }

    // just cancel the current path
    public void secretInternalSegmentCancel() {
        synchronized (pathPlanLock) {
            getInProgress().ifPresent(AbstractNodeCostSearch::cancel);
            current = null;
            next = null;
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
        }
    }

    /** Drops everything at once, safe or not (e.g. on death or despawn). */
    public void forceCancel() {
        goal = null;
        cancelWhenSafe = false;
        secretInternalSegmentCancel();
        synchronized (pathCalcLock) {
            inProgress = null;
        }
    }

    public CalculationContext secretInternalGetCalculationContext() {
        return context;
    }

    public Optional<Double> ticksRemainingInSegment(boolean includeCurrentMovement) {
        PathExecutor current = getCurrent();
        if (current == null) {
            return Optional.empty();
        }
        int start = includeCurrentMovement ? current.getPosition() : current.getPosition() + 1;
        return Optional.of(current.getPath().ticksRemainingFrom(start));
    }

    /**
     * See issue #209
     *
     * @return The starting {@link BlockPos} for a new path
     */
    public BetterBlockPos pathStart() { // TODO move to a helper or util class
        BetterBlockPos feet = ctx.playerFeet();
        if (!MovementHelper.canWalkOn(ctx, feet.below())) {
            if (ctx.player().onGround()) {
                double playerX = ctx.player().position().x;
                double playerZ = ctx.player().position().z;
                ArrayList<BetterBlockPos> closest = new ArrayList<>();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        closest.add(new BetterBlockPos(feet.x + dx, feet.y, feet.z + dz));
                    }
                }
                closest.sort(Comparator.comparingDouble(pos -> ((pos.x + 0.5D) - playerX) * ((pos.x + 0.5D) - playerX) + ((pos.z + 0.5D) - playerZ) * ((pos.z + 0.5D) - playerZ)));
                for (int i = 0; i < 4; i++) {
                    BetterBlockPos possibleSupport = closest.get(i);
                    double xDist = Math.abs((possibleSupport.x + 0.5D) - playerX);
                    double zDist = Math.abs((possibleSupport.z + 0.5D) - playerZ);
                    if (xDist > 0.8 && zDist > 0.8) {
                        // can't possibly be sneaking off of this one, we're too far away
                        continue;
                    }
                    if (MovementHelper.canWalkOn(ctx, possibleSupport.below()) && MovementHelper.canWalkThrough(ctx, possibleSupport) && MovementHelper.canWalkThrough(ctx, possibleSupport.above())) {
                        // this is plausible
                        //logDebug("Faking path start assuming player is standing off the edge of a block");
                        return possibleSupport;
                    }
                }

            } else {
                // !onGround
                // we're in the middle of a jump
                if (MovementHelper.canWalkOn(ctx, feet.below().below())) {
                    //logDebug("Faking path start assuming player is midair and falling");
                    return feet.below();
                }
            }
        }
        return feet;
    }

    /**
     * In a new thread, pathfind to target blockpos
     *
     * @param start
     * @param talkAboutIt
     */
    private void findPathInNewThread(final BlockPos start, final boolean talkAboutIt) {
        // this must be called with synchronization on pathCalcLock!
        // actually, we can check this, muahaha
        if (!Thread.holdsLock(pathCalcLock)) {
            throw new IllegalStateException("Must be called with synchronization on pathCalcLock");
            // why do it this way? it's already indented so much that putting the whole thing in a synchronized(pathCalcLock) was just too much lol
        }
        if (inProgress != null) {
            throw new IllegalStateException("Already doing it"); // should have been checked by caller
        }
        Goal goal = this.goal;
        if (goal == null) {
            logDebug("no goal"); // TODO should this be an exception too? definitely should be checked by caller
            return;
        }
        // Clanker: a fresh context per search, capturing the loaded chunks and the host's current permissions
        CalculationContext context = new CalculationContext(baritone, true);
        this.context = context;
        long primaryTimeout;
        long failureTimeout;
        if (current == null) {
            primaryTimeout = PathingSettings.get().primaryTimeoutMS.value;
            failureTimeout = PathingSettings.get().failureTimeoutMS.value;
        } else {
            primaryTimeout = PathingSettings.get().planAheadPrimaryTimeoutMS.value;
            failureTimeout = PathingSettings.get().planAheadFailureTimeoutMS.value;
        }
        AbstractNodeCostSearch pathfinder = createPathfinder(start, goal, current == null ? null : current.getPath(), context);
        inProgress = pathfinder;
        EXECUTOR.execute(() -> {
            if (talkAboutIt) {
                logDebug("Starting to search for path from " + start + " to " + goal);
            }

            PathCalculationResult calcResult = pathfinder.calculate(primaryTimeout, failureTimeout);
            synchronized (pathPlanLock) {
                Optional<PathExecutor> executor = calcResult.getPath().map(p -> new PathExecutor(PathingBehavior.this, p));
                if (current == null) {
                    if (executor.isPresent()) {
                        if (executor.get().getPath().positions().contains(expectedSegmentStart)) {
                            current = executor.get();
                            failures = 0;
                        } else {
                            logDebug("Warning: discarding orphan path segment with incorrect start");
                        }
                    } else {
                        if (calcResult.getType() != PathCalculationResult.Type.CANCELLATION && calcResult.getType() != PathCalculationResult.Type.EXCEPTION) {
                            calcFailed = true;
                        }
                    }
                } else {
                    if (next == null) {
                        if (executor.isPresent()) {
                            if (executor.get().getPath().getSrc().equals(current.getPath().getDest())) {
                                next = executor.get();
                            } else {
                                logDebug("Warning: discarding orphan next segment with incorrect start");
                            }
                        }
                    } else {
                        //throw new IllegalStateException("I have no idea what to do with this path");
                        // no point in throwing an exception here, and it gets it stuck with inProgress being not null
                        logDirect("Warning: PathingBehaivor illegal state! Discarding invalid path!");
                    }
                }
                if (talkAboutIt && current != null && current.getPath() != null) {
                    if (goal.isInGoal(current.getPath().getDest())) {
                        logDebug("Finished finding a path from " + start + " to " + goal + ". " + current.getPath().getNumNodesConsidered() + " nodes considered");
                    } else {
                        logDebug("Found path segment from " + start + " towards " + goal + ". " + current.getPath().getNumNodesConsidered() + " nodes considered");
                    }
                }
                synchronized (pathCalcLock) {
                    inProgress = null;
                }
            }
        });
    }

    private AbstractNodeCostSearch createPathfinder(BlockPos start, Goal goal, IPath previous, CalculationContext context) {
        Favoring favoring = new Favoring(previous, context);
        BetterBlockPos feet = ctx.playerFeet();
        var realStart = new BetterBlockPos(start);
        var sub = feet.subtract(realStart);
        if (feet.getY() == realStart.getY() && Math.abs(sub.getX()) <= 1 && Math.abs(sub.getZ()) <= 1) {
            realStart = feet;
        }
        return new AStarPathFinder(realStart, start.getX(), start.getY(), start.getZ(), goal, favoring, context);

    }
}
