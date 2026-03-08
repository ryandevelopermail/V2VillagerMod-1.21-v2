package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class ShepherdCraftingGoal extends AbstractCraftingGoal<ShepherdCraftingGoal.Recipe> {

    public ShepherdCraftingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super.setTargets(jobPos, chestPos, craftingTablePos);
    }

    @Override
    protected boolean hasRequiredProfession() {
        return villager.getVillagerData().getProfession() == VillagerProfession.SHEPHERD;
    }

    @Override
    protected String getGoalName() {
        return "Shepherd";
    }

    @Override
    protected int getDailyCraftLimit(ServerWorld world) {
        return 2 + villager.getRandom().nextInt(3);
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
        WHITE_BANNER(new ItemStack(Items.WHITE_BANNER), new IngredientRequirement(stack -> stack.isOf(Items.WHITE_WOOL), 6), new IngredientRequirement(stack -> stack.isOf(Items.STICK), 1)),
        ORANGE_BANNER(new ItemStack(Items.ORANGE_BANNER), new IngredientRequirement(stack -> stack.isOf(Items.ORANGE_WOOL), 6), new IngredientRequirement(stack -> stack.isOf(Items.STICK), 1)),
        MAGENTA_BANNER(new ItemStack(Items.MAGENTA_BANNER), new IngredientRequirement(stack -> stack.isOf(Items.MAGENTA_WOOL), 6), new IngredientRequirement(stack -> stack.isOf(Items.STICK), 1)),
        LIGHT_BLUE_BANNER(new ItemStack(Items.LIGHT_BLUE_BANNER), new IngredientRequirement(stack -> stack.isOf(Items.LIGHT_BLUE_WOOL), 6), new IngredientRequirement(stack -> stack.isOf(Items.STICK), 1)),
        YELLOW_BANNER(new ItemStack(Items.YELLOW_BANNER), new IngredientRequirement(stack -> stack.isOf(Items.YELLOW_WOOL), 6), new IngredientRequirement(stack -> stack.isOf(Items.STICK), 1)),
        LIME_BANNER(new ItemStack(Items.LIME_BANNER), new IngredientRequirement(stack -> stack.isOf(Items.LIME_WOOL), 6), new IngredientRequirement(stack -> stack.isOf(Items.STICK), 1)),
        PINK_BANNER(new ItemStack(Items.PINK_BANNER), new IngredientRequirement(stack -> stack.isOf(Items.PINK_WOOL), 6), new IngredientRequirement(stack -> stack.isOf(Items.STICK), 1)),
        GRAY_BANNER(new ItemStack(Items.GRAY_BANNER), new IngredientRequirement(stack -> stack.isOf(Items.GRAY_WOOL), 6), new IngredientRequirement(stack -> stack.isOf(Items.STICK), 1)),
        LIGHT_GRAY_BANNER(new ItemStack(Items.LIGHT_GRAY_BANNER), new IngredientRequirement(stack -> stack.isOf(Items.LIGHT_GRAY_WOOL), 6), new IngredientRequirement(stack -> stack.isOf(Items.STICK), 1)),
        CYAN_BANNER(new ItemStack(Items.CYAN_BANNER), new IngredientRequirement(stack -> stack.isOf(Items.CYAN_WOOL), 6), new IngredientRequirement(stack -> stack.isOf(Items.STICK), 1)),
        PURPLE_BANNER(new ItemStack(Items.PURPLE_BANNER), new IngredientRequirement(stack -> stack.isOf(Items.PURPLE_WOOL), 6), new IngredientRequirement(stack -> stack.isOf(Items.STICK), 1)),
        BLUE_BANNER(new ItemStack(Items.BLUE_BANNER), new IngredientRequirement(stack -> stack.isOf(Items.BLUE_WOOL), 6), new IngredientRequirement(stack -> stack.isOf(Items.STICK), 1)),
        BROWN_BANNER(new ItemStack(Items.BROWN_BANNER), new IngredientRequirement(stack -> stack.isOf(Items.BROWN_WOOL), 6), new IngredientRequirement(stack -> stack.isOf(Items.STICK), 1)),
        GREEN_BANNER(new ItemStack(Items.GREEN_BANNER), new IngredientRequirement(stack -> stack.isOf(Items.GREEN_WOOL), 6), new IngredientRequirement(stack -> stack.isOf(Items.STICK), 1)),
        RED_BANNER(new ItemStack(Items.RED_BANNER), new IngredientRequirement(stack -> stack.isOf(Items.RED_WOOL), 6), new IngredientRequirement(stack -> stack.isOf(Items.STICK), 1)),
        BLACK_BANNER(new ItemStack(Items.BLACK_BANNER), new IngredientRequirement(stack -> stack.isOf(Items.BLACK_WOOL), 6), new IngredientRequirement(stack -> stack.isOf(Items.STICK), 1));

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
