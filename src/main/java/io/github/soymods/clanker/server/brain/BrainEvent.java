package io.github.soymods.clanker.server.brain;

/**
 * Something that happened, described as a neutral fact. Never an instruction about how to react.
 * <p>
 * {@code weight} is how much it draws Clanker's attention: 1 or more gives it a turn right away, smaller amounts add up
 * (and fade) until together they're worth a turn. It decides only <em>whether</em> the model gets to look at what
 * happened, never what it says about it.
 */
public record BrainEvent(Type type, String text, long timeMillis, double weight) {
	public enum Type {
		SPAWNED(1),
		SPEECH(1),
		OVERHEARD(0.35),
		DAMAGE(1),
		DEATH(1),
		RESPAWN(1),
		PLAYER_SLEEPING(1),
		TASK_DONE(1),
		TASK_FAILED(1),
		PLAYER_JOINED(1),
		PLAYER_LEFT(0.6),
		TIME_OF_DAY(0.45),
		WEATHER(0.45),
		/** Something new came into view or reach: a mob, an ore, a structure, a different biome. */
		SAW(0.3),
		/** What another player did: got hurt, fell, went swimming, mined, picked things up or used them up. */
		PLAYER_ACTIVITY(0.3),
		NOTE(0);

		public final double weight;

		Type(double weight) {
			this.weight = weight;
		}
	}

	public static BrainEvent of(Type type, String text) {
		return new BrainEvent(type, text, System.currentTimeMillis(), type.weight);
	}

	public static BrainEvent of(Type type, String text, double weight) {
		return new BrainEvent(type, text, System.currentTimeMillis(), weight);
	}

	/** Whether this event alone is reason to give the model a turn. */
	public boolean triggersTurn() {
		return weight >= 1;
	}
}
