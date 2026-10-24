package io.github.soymods.clanker.server.brain;

/**
 * Something that happened, described as a neutral fact. Never an instruction about how to react.
 */
public record BrainEvent(Type type, String text, long timeMillis) {
	public enum Type {
		SPAWNED(true),
		SPEECH(true),
		OVERHEARD(false),
		DAMAGE(true),
		DEATH(true),
		RESPAWN(true),
		TASK_DONE(true),
		TASK_FAILED(true),
		PLAYER_JOINED(true),
		PLAYER_LEFT(true),
		TIME_OF_DAY(true),
		WEATHER(true),
		IDLE(true),
		NOTE(false);

		/** Whether this event alone is reason to give the model a turn. */
		public final boolean triggersTurn;

		Type(boolean triggersTurn) {
			this.triggersTurn = triggersTurn;
		}
	}

	public static BrainEvent of(Type type, String text) {
		return new BrainEvent(type, text, System.currentTimeMillis());
	}
}
