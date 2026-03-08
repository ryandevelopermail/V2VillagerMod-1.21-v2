package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class ButcherCraftingGoal extends AbstractCraftingGoal<ButcherCraftingGoal.Recipe> {

    public ButcherCraftingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    @Override
    protected boolean hasRequiredProfession() {
        return villager.getVillagerData().getProfession() == VillagerProfession.BUTCHER;
    }

    @Override
    protected String getGoalName() {
        return "Butcher";
    }

    @Override
    protected int getDailyCraftLimit(ServerWorld world) {
        return 2;
    }

    @Override
    protected List<Recipe> discoverRecipes(ServerWorld world, Inventory inventory) {
        List<Recipe> recipes = new ArrayList<>();
        for (Recipe recipe : Recipe.values()) {
            if (hasIngredients(inventory, recipe.requirements)) {
                recipes.add(recipe);
            }
        }
        return recipes;
    }

    @Override
    protected boolean canStillCraftRecipe(ServerWorld world, Inventory inventory, Recipe recipe) {
        return hasIngredients(inventory, recipe.requirements);
    }

    @Override
    protected boolean craftRecipe(ServerWorld world, Inventory inventory, Recipe recipe) {
        if (!consumeIngredients(inventory, recipe.requirements)) {
            return false;
        }
        insertStack(inventory, recipe.output.copy());
        return true;
    }

    @Override
    protected ItemStack getRecipeOutput(Recipe recipe) {
        return recipe.output;
    }

    private boolean hasIngredients(Inventory inventory, IngredientRequirement[] requirements) {
        for (IngredientRequirement requirement : requirements) {
            if (countMatching(inventory, requirement.matcher) < requirement.count) {
                return false;
            }
        }
        return true;
    }

    private boolean consumeIngredients(Inventory inventory, IngredientRequirement[] requirements) {
        if (!hasIngredients(inventory, requirements)) {
            return false;
        }

        for (IngredientRequirement requirement : requirements) {
            int remaining = requirement.count;
            for (int slot = 0; slot < inventory.size(); slot++) {
                if (remaining <= 0) {
                    break;
                }
                ItemStack stack = inventory.getStack(slot);
                if (stack.isEmpty() || !requirement.matcher.test(stack)) {
                    continue;
                }
                int removed = Math.min(remaining, stack.getCount());
                stack.decrement(removed);
                remaining -= removed;
                if (stack.isEmpty()) {
                    inventory.setStack(slot, ItemStack.EMPTY);
                }
            }
        }

        return true;
    }

    private int countMatching(Inventory inventory, Predicate<ItemStack> matcher) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && matcher.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private record IngredientRequirement(Predicate<ItemStack> matcher, int count) {
    }

    enum Recipe {
        SMOKER(new ItemStack(Items.SMOKER),
                new IngredientRequirement(stack -> stack.isOf(Items.FURNACE), 1),
                new IngredientRequirement(stack -> stack.isIn(ItemTags.LOGS_THAT_BURN), 4));

        private final ItemStack output;
        private final IngredientRequirement[] requirements;

        Recipe(ItemStack output, IngredientRequirement... requirements) {
            this.output = output;
            this.requirements = requirements;
        }
    }

    @Override
    protected String formatCheckResult(int craftableCount) {
        return craftableCount == 1 ? "1 item available to craft" : craftableCount + " items available to craft";
    }

    @Override
    protected String formatCraftedResult(int craftableCount, ItemStack crafted) {
        String craftedName = crafted.getName().getString();
        return craftableCount == 1
                ? "1 item available to craft - 1 " + craftedName + " crafted"
                : craftableCount + " items available to craft - 1 " + craftedName + " crafted";
    }
}
