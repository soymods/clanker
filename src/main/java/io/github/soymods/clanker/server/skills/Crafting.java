package io.github.soymods.clanker.server.skills;

import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Crafting-grid recipes: finding ones that make an item, checking what's missing, and crafting from an inventory. */
final class Crafting {
	private Crafting() {
	}

	record Plan(CraftingRecipe recipe, int width, int height, List<Optional<Ingredient>> grid, ItemStack result) {
		boolean needsTable() {
			return width > 2 || height > 2;
		}
	}

	/** Every shaped or shapeless recipe whose output matches. */
	static List<Plan> plansFor(ServerLevel level, Predicate<ItemStack> wanted) {
		List<Plan> plans = new ArrayList<>();
		for (RecipeHolder<?> holder : level.getServer().getRecipeManager().getRecipes()) {
			Plan plan = switch (holder.value()) {
				case ShapedRecipe shaped -> plan(shaped, shaped.getWidth(), shaped.getHeight(), shaped.getIngredients());
				case ShapelessRecipe shapeless -> {
					List<Optional<Ingredient>> grid = shapeless.placementInfo().ingredients().stream().map(Optional::of).collect(Collectors.toCollection(ArrayList::new));
					int size = grid.size() <= 4 ? 2 : 3;
					while (grid.size() < size * size) {
						grid.add(Optional.empty());
					}
					yield plan(shapeless, size, size, grid);
				}
				default -> null;
			};
			if (plan != null && wanted.test(plan.result())) {
				plans.add(plan);
			}
		}
		return plans;
	}

	private static Plan plan(CraftingRecipe recipe, int width, int height, List<Optional<Ingredient>> grid) {
		List<ItemStack> sample = new ArrayList<>();
		for (Optional<Ingredient> cell : grid) {
			sample.add(cell.flatMap(i -> i.items().findFirst()).map(h -> new ItemStack(h)).orElse(ItemStack.EMPTY));
		}
		if (grid.size() != width * height) {
			return null;
		}
		ItemStack result = recipe.assemble(CraftingInput.of(width, height, sample));
		return result.isEmpty() ? null : new Plan(recipe, width, height, grid, result);
	}

	/** Inventory slot for each grid cell (-1 for empty cells), or null if something is missing. */
	static int[] allocate(Plan plan, Inventory inventory) {
		int[] used = new int[inventory.getContainerSize()];
		int[] slots = new int[plan.grid().size()];
		for (int cell = 0; cell < plan.grid().size(); cell++) {
			Optional<Ingredient> ingredient = plan.grid().get(cell);
			if (ingredient.isEmpty()) {
				slots[cell] = -1;
				continue;
			}
			int found = -1;
			for (int i = 0; i < 36; i++) {
				ItemStack stack = inventory.getItem(i);
				if (!stack.isEmpty() && stack.getCount() > used[i] && ingredient.get().test(stack)) {
					found = i;
					break;
				}
			}
			if (found < 0) {
				return null;
			}
			used[found]++;
			slots[cell] = found;
		}
		return slots;
	}

	/** What's missing for one craft, e.g. "2 stick, 1 iron_ingot". */
	static String missing(Plan plan, Inventory inventory) {
		int[] used = new int[inventory.getContainerSize()];
		Map<String, Integer> missing = new LinkedHashMap<>();
		for (Optional<Ingredient> cell : plan.grid()) {
			if (cell.isEmpty()) {
				continue;
			}
			boolean found = false;
			for (int i = 0; i < 36; i++) {
				ItemStack stack = inventory.getItem(i);
				if (!stack.isEmpty() && stack.getCount() > used[i] && cell.get().test(stack)) {
					used[i]++;
					found = true;
					break;
				}
			}
			if (!found) {
				missing.merge(describe(cell.get()), 1, Integer::sum);
			}
		}
		return missing.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "));
	}

	private static String describe(Ingredient ingredient) {
		List<Holder<Item>> items = ingredient.items().toList();
		if (items.isEmpty()) {
			return "?";
		}
		String first = Matching.itemId(new ItemStack(items.getFirst()));
		return items.size() == 1 ? first : first + " (or similar)";
	}

	/** The plan that needs the fewest extra items; ties go to ones that don't need a table. */
	static Plan bestPlan(List<Plan> plans, Inventory inventory) {
		return plans.stream()
				.min(Comparator.<Plan>comparingInt(p -> allocate(p, inventory) != null ? 0 : 1)
						.thenComparingInt(p -> p.needsTable() ? 1 : 0))
				.orElse(null);
	}

	/** Crafts once if the inventory has everything. Returns the result (already added or dropped), or empty. */
	static ItemStack craftOnce(ServerLevel level, Plan plan, net.minecraft.world.entity.player.Player player) {
		Inventory inventory = player.getInventory();
		int[] slots = allocate(plan, inventory);
		if (slots == null) {
			return ItemStack.EMPTY;
		}
		List<ItemStack> stacks = new ArrayList<>();
		for (int slot : slots) {
			stacks.add(slot < 0 ? ItemStack.EMPTY : inventory.getItem(slot).copyWithCount(1));
		}
		CraftingInput input = CraftingInput.of(plan.width(), plan.height(), stacks);
		if (!plan.recipe().matches(input, level)) {
			return ItemStack.EMPTY;
		}
		ItemStack result = plan.recipe().assemble(input);
		NonNullList<ItemStack> leftovers = plan.recipe().getRemainingItems(input);
		for (int slot : slots) {
			if (slot >= 0) {
				inventory.removeItem(slot, 1);
			}
		}
		result.onCraftedBy(player, result.getCount());
		ItemStack made = result.copy();
		give(player, result);
		for (ItemStack leftover : leftovers) {
			give(player, leftover);
		}
		return made;
	}

	static void give(net.minecraft.world.entity.player.Player player, ItemStack stack) {
		if (!stack.isEmpty() && !player.getInventory().add(stack)) {
			player.drop(stack, false);
		}
	}
}
