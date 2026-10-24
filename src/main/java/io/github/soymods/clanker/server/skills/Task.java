package io.github.soymods.clanker.server.skills;

import io.github.soymods.clanker.server.bot.ClankerBot;
import io.github.soymods.clanker.server.bot.ClankerPlayer;

/**
 * Something Clanker does over many ticks. Results are plain facts reported back to the model.
 */
public interface Task {
	/** Tool-style name, e.g. {@code follow_player}. */
	String name();

	/** Current state in a few words, shown to the model each turn. */
	String describe(ClankerBot bot, ClankerPlayer player);

	Result tick(ClankerBot bot, ClankerPlayer player);

	/** Called once when the task ends for any reason (finished, failed, replaced or stopped), to clean up. */
	default void end(ClankerBot bot, ClankerPlayer player) {
	}

	enum Status { RUNNING, SUCCESS, FAILED }

	record Result(Status status, String message) {
		public static final Result RUNNING = new Result(Status.RUNNING, "");

		public static Result success(String message) {
			return new Result(Status.SUCCESS, message);
		}

		public static Result failed(String message) {
			return new Result(Status.FAILED, message);
		}
	}
}
