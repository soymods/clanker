package io.github.soymods.clanker.server.skills;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Loose name matching for what the model asks for ("logs", "iron", "oak planks", "minecraft:stone"), and small
 * inventory helpers.
 */
public final class Matching {
	private static final List<String> WOOD_WORDS = List.of("log", "wood", "tree", "trunk");

	private Matching() {
	}

	/** Lowercase registry-style words: "Oak Logs" becomes [oak, log]. */
	public static List<String> words(String query) {
		String clean = query.toLowerCase(Locale.ROOT).strip().replace("minecraft:", "").replace(' ', '_').replace('-', '_');
		return Arrays.stream(clean.split("_+")).filter(w -> !w.isEmpty()).map(Matching::singular).toList();
	}

	private static String singular(String word) {
		if (word.endsWith("ies") && word.length() > 4) {
			return word.substring(0, word.length() - 3) + "y";
		}
		if (word.endsWith("s") && !word.endsWith("ss") && word.length() > 3) {
			return word.substring(0, word.length() - 1);
		}
		return word;
	}

	/** True when every query word appears among the id's words, e.g. [iron] matches deepslate_iron_ore. */
	public static boolean idMatches(String id, List<String> words) {
		List<String> idWords = Arrays.stream(id.split("_")).map(Matching::singular).toList();
		return !words.isEmpty() && idWords.containsAll(words);
	}

	public static Predicate<BlockState> block(String query) {
		List<String> words = words(query);
		if (words.size() == 1 && WOOD_WORDS.contains(words.getFirst())) {
			return state -> state.is(BlockTags.LOGS);
		}
		// A wood type, alone or with "tree"/"log"/"wood" ("acacia", "dark oak tree"), means its logs rather than
		// every block with that name (leaves, planks, saplings...).
		List<String> type = words.stream().filter(w -> !WOOD_WORDS.contains(w)).toList();
		if (!type.isEmpty() && isWoodType(String.join("_", type))) {
			return state -> state.is(BlockTags.LOGS) && idMatches(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(), type);
		}
		return state -> idMatches(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(), words);
	}

	private static boolean isWoodType(String type) {
		return BuiltInRegistries.BLOCK.containsKey(net.minecraft.resources.Identifier.withDefaultNamespace(type + "_log"))
				|| BuiltInRegistries.BLOCK.containsKey(net.minecraft.resources.Identifier.withDefaultNamespace(type + "_stem"));
	}

	public static Predicate<ItemStack> item(String query) {
		List<String> words = words(query);
		if (words.size() == 1 && WOOD_WORDS.contains(words.getFirst())) {
			return stack -> stack.is(ItemTags.LOGS);
		}
		return stack -> !stack.isEmpty() && idMatches(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(), words);
	}

	public static String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
	}

	public static int count(Inventory inventory, Predicate<ItemStack> matcher) {
		int total = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty() && matcher.test(stack)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/** First main-inventory slot (hotbar + backpack) holding a matching item, or -1. */
	public static int find(Inventory inventory, Predicate<ItemStack> matcher) {
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty() && matcher.test(stack)) {
				return i;
			}
		}
		return -1;
	}

	/** Puts the item in {@code slot} in the main hand, the way a player would pick it from the hotbar or swap it in. */
	public static void hold(Inventory inventory, int slot) {
		if (slot < 0) {
			return;
		}
		if (slot < Inventory.SELECTION_SIZE) {
			inventory.setSelectedSlot(slot);
			return;
		}
		int selected = inventory.getSelectedSlot();
		ItemStack moving = inventory.getItem(slot);
		inventory.setItem(slot, inventory.getItem(selected));
		inventory.setItem(selected, moving);
	}
}
