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

public class FarmerCraftingGoal extends AbstractCraftingGoal<FarmerCraftingGoal.Recipe> {
    private boolean guaranteedCraftPending;
    private long guaranteedCraftDay = -1L;

    public FarmerCraftingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    @Override
    protected boolean hasRequiredProfession() {
        return villager.getVillagerData().getProfession() == VillagerProfession.FARMER;
    }

    @Override
    protected String getGoalName() {
        return "Farmer";
    }

    @Override
    protected int getDailyCraftLimit(ServerWorld world) {
        return 2 + villager.getRandom().nextInt(3);
    }

    @Override
    protected void onDailyReset(ServerWorld world) {
        guaranteedCraftPending = false;
        guaranteedCraftDay = world.getTimeOfDay() / 24000L;
    }

    @Override
    protected boolean shouldBypassCooldown(ServerWorld world, boolean immediateCheckPending) {
        long day = world.getTimeOfDay() / 24000L;
        return immediateCheckPending || (guaranteedCraftPending && guaranteedCraftDay == day);
    }

    @Override
    protected void onCheckPerformed(ServerWorld world) {
        guaranteedCraftPending = false;
    }

    public void notifyDailyHarvestComplete(long day) {
        guaranteedCraftPending = true;
        guaranteedCraftDay = day;
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
        WOODEN_HOE(new ItemStack(Items.WOODEN_HOE), new IngredientRequirement(stack -> stack.isIn(ItemTags.PLANKS), 2), new IngredientRequirement(stack -> stack.isOf(Items.STICK), 2)),
        STONE_HOE(new ItemStack(Items.STONE_HOE), new IngredientRequirement(stack -> stack.isOf(Items.COBBLESTONE), 2), new IngredientRequirement(stack -> stack.isOf(Items.STICK), 2)),
        IRON_HOE(new ItemStack(Items.IRON_HOE), new IngredientRequirement(stack -> stack.isOf(Items.IRON_INGOT), 2), new IngredientRequirement(stack -> stack.isOf(Items.STICK), 2)),
        GOLDEN_HOE(new ItemStack(Items.GOLDEN_HOE), new IngredientRequirement(stack -> stack.isOf(Items.GOLD_INGOT), 2), new IngredientRequirement(stack -> stack.isOf(Items.STICK), 2)),
        DIAMOND_HOE(new ItemStack(Items.DIAMOND_HOE), new IngredientRequirement(stack -> stack.isOf(Items.DIAMOND), 2), new IngredientRequirement(stack -> stack.isOf(Items.STICK), 2)),
        BREAD(new ItemStack(Items.BREAD), new IngredientRequirement(stack -> stack.isOf(Items.WHEAT), 3)),
        HAY_BALE(new ItemStack(Items.HAY_BLOCK), new IngredientRequirement(stack -> stack.isOf(Items.WHEAT), 9));

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
