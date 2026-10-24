package io.github.soymods.clanker.server.skills;

import io.github.soymods.clanker.Clanker;
import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import io.github.soymods.clanker.server.brain.BrainEvent;
import net.minecraft.world.phys.Vec3;

/**
 * Runs one task at a time and reports how it ended to the brain.
 */
public class TaskRunner {
	private final ClankerBot bot;
	private Task current;
	private Vec3 progressPos;
	private int progressInventory;
	private String progressDescription;
	private int ticksWithoutProgress;

	public TaskRunner(ClankerBot bot) {
		this.bot = bot;
	}

	public Task current() {
		return current;
	}

	public void start(Task task) {
		end();
		bot.movement().stop();
		current = task;
		resetProgress();
		Clanker.LOGGER.info("{} started {}", bot.name(), task.name());
	}

	/**
	 * Starts a task and runs its first tick right away, so one that's over at once (placing a block within reach) can
	 * be reported straight back instead of as a later event. Returns that result, or null if it's still running.
	 */
	public Task.Result startNow(Task task, ClankerPlayer player) {
		start(task);
		Task.Result result = task.tick(bot, player);
		if (result.status() == Task.Status.RUNNING || current != task) {
			return null;
		}
		end();
		bot.movement().stop();
		Clanker.LOGGER.info("{} {} {}: {}", bot.name(), result.status() == Task.Status.SUCCESS ? "finished" : "failed", task.name(), result.message());
		return result;
	}

	private void resetProgress() {
		progressPos = null;
		progressInventory = 0;
		progressDescription = null;
		ticksWithoutProgress = 0;
	}

	/** Watches for any sign the task is getting somewhere, so a task that's silently stuck ends and gets reported. */
	private boolean stalled(Task task, ClankerPlayer player) {
		if (task.stallTicks() <= 0) {
			return false;
		}
		int inventory = 1;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			var stack = player.getInventory().getItem(i);
			inventory = 31 * inventory + (stack.isEmpty() ? 0 : stack.getItem().hashCode() * 64 + stack.getCount());
		}
		String description = task.describe(bot, player);
		if (progressPos == null || player.position().distanceTo(progressPos) > 1.5 || inventory != progressInventory
				|| !description.equals(progressDescription)) {
			progressPos = player.position();
			progressInventory = inventory;
			progressDescription = description;
			ticksWithoutProgress = 0;
			return false;
		}
		return ++ticksWithoutProgress > task.stallTicks();
	}

	/** Cancels the current task without reporting it. Returns whether anything was running. */
	public boolean stop() {
		boolean wasRunning = current != null;
		end();
		bot.movement().stop();
		bot.movement().clearLook();
		return wasRunning;
	}

	private void end() {
		Task task = current;
		current = null;
		if (task != null && bot.player() != null) {
			task.end(bot, bot.player());
		}
	}

	public void tick(ClankerPlayer player) {
		if (current == null || player.isDeadOrDying()) {
			return;
		}
		Task task = current;
		Task.Result result = task.tick(bot, player);
		if (result.status() == Task.Status.RUNNING && current == task && stalled(task, player)) {
			result = Task.Result.failed("made no progress for " + task.stallTicks() / 20 + " seconds while " + task.describe(bot, player));
		}
		if (result.status() == Task.Status.RUNNING || current != task) {
			return;
		}
		end();
		bot.movement().stop();
		Clanker.LOGGER.info("{} {} {}: {}", bot.name(), result.status() == Task.Status.SUCCESS ? "finished" : "failed", task.name(), result.message());
		BrainEvent.Type type = result.status() == Task.Status.SUCCESS ? BrainEvent.Type.TASK_DONE : BrainEvent.Type.TASK_FAILED;
		String verb = result.status() == Task.Status.SUCCESS ? "finished" : "failed";
		bot.brain().post(BrainEvent.of(type, "Task " + task.name() + " " + verb + ": " + result.message()));
	}
}
