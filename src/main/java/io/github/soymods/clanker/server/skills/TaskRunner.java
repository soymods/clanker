package io.github.soymods.clanker.server.skills;

import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerPlayer;
import io.github.soymods.clanker.server.brain.BrainEvent;

/**
 * Runs one task at a time and reports how it ended to the brain.
 */
public class TaskRunner {
	private final ClankerBot bot;
	private Task current;

	public TaskRunner(ClankerBot bot) {
		this.bot = bot;
	}

	public Task current() {
		return current;
	}

	public void start(Task task) {
		bot.movement().stop();
		current = task;
	}

	/** Cancels the current task without reporting it. Returns whether anything was running. */
	public boolean stop() {
		boolean wasRunning = current != null;
		current = null;
		bot.movement().stop();
		bot.movement().clearLook();
		return wasRunning;
	}

	public void tick(ClankerPlayer player) {
		if (current == null || player.isDeadOrDying()) {
			return;
		}
		Task task = current;
		Task.Result result = task.tick(bot, player);
		if (result.status() == Task.Status.RUNNING || current != task) {
			return;
		}
		current = null;
		bot.movement().stop();
		BrainEvent.Type type = result.status() == Task.Status.SUCCESS ? BrainEvent.Type.TASK_DONE : BrainEvent.Type.TASK_FAILED;
		String verb = result.status() == Task.Status.SUCCESS ? "finished" : "failed";
		bot.brain().post(BrainEvent.of(type, "Task " + task.name() + " " + verb + ": " + result.message()));
	}
}
