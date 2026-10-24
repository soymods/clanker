package io.github.soymods.clanker.server.brain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.soymods.clanker.Clanker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Clanker's long-term notes for one world, written and removed by the model itself through tools
 * (DESIGN.md §5: memory is the model's, not hardcoded rules). Stored in {@code <world>/clanker/memory-<name>.json}.
 */
public class Memory {
	public static final int MAX_NOTES = 150;
	public static final int MAX_NOTE_LENGTH = 300;
	private static final int MAX_PROMPT_CHARS = 6000;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public record Note(int id, String text, String when) {
	}

	private static final class Data {
		int nextId = 1;
		List<Note> notes = new ArrayList<>();
	}

	private final Path path;
	private Data data = new Data();

	private Memory(Path path) {
		this.path = path;
	}

	public static Memory load(MinecraftServer server, String botName) {
		Memory memory = new Memory(server.getWorldPath(LevelResource.ROOT).resolve("clanker").resolve("memory-" + botName.toLowerCase() + ".json"));
		if (Files.isRegularFile(memory.path)) {
			try {
				Data loaded = GSON.fromJson(Files.readString(memory.path), Data.class);
				if (loaded != null && loaded.notes != null) {
					memory.data = loaded;
				}
			} catch (IOException | RuntimeException e) {
				Clanker.LOGGER.error("Couldn't read Clanker's memory from {}", memory.path, e);
			}
		}
		return memory;
	}

	public List<Note> notes() {
		return List.copyOf(data.notes);
	}

	/** Adds a note and returns its id; the oldest note is dropped once the notebook is full. */
	public int add(String text, String when) {
		String clean = text.replaceAll("\\s+", " ").strip();
		if (clean.length() > MAX_NOTE_LENGTH) {
			clean = clean.substring(0, MAX_NOTE_LENGTH);
		}
		int id = data.nextId++;
		data.notes.add(new Note(id, clean, when));
		while (data.notes.size() > MAX_NOTES) {
			data.notes.removeFirst();
		}
		save();
		return id;
	}

	public boolean remove(int id) {
		boolean removed = data.notes.removeIf(note -> note.id() == id);
		if (removed) {
			save();
		}
		return removed;
	}

	public void clear() {
		data.notes.clear();
		save();
	}

	/** The notes for the system prompt, newest kept when they don't all fit. Empty when there are none. */
	public String promptSection() {
		if (data.notes.isEmpty()) {
			return "";
		}
		List<String> lines = new ArrayList<>();
		int chars = 0;
		for (int i = data.notes.size() - 1; i >= 0; i--) {
			Note note = data.notes.get(i);
			String line = "#" + note.id() + " (" + note.when() + ") " + note.text();
			if (chars + line.length() > MAX_PROMPT_CHARS) {
				break;
			}
			lines.addFirst(line);
			chars += line.length();
		}
		return String.join("\n", lines);
	}

	private void save() {
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(data));
		} catch (IOException e) {
			Clanker.LOGGER.error("Couldn't save Clanker's memory to {}", path, e);
		}
	}
}
