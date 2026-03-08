package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.WeaponsmithCraftingMemoryHolder;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.HoeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MaceItem;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.ShovelItem;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolItem;
import net.minecraft.item.TridentItem;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.List;

public class WeaponsmithCraftingGoal extends AbstractCraftingGoal<WeaponsmithCraftingGoal.WeaponRecipe> {

    public WeaponsmithCraftingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super.setTargets(jobPos, chestPos, craftingTablePos);
    }

    @Override
    protected boolean hasRequiredProfession() {
        return villager.getVillagerData().getProfession() == VillagerProfession.WEAPONSMITH;
    }

    @Override
    protected String getGoalName() {
        return "Weaponsmith";
    }

    @Override
    protected int getDailyCraftLimit(ServerWorld world) {
        return 4;
    }

    @Override
    protected List<WeaponRecipe> discoverRecipes(ServerWorld world, Inventory inventory) {
        List<WeaponRecipe> recipes = new ArrayList<>();
        for (RecipeEntry<CraftingRecipe> entry : world.getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = entry.value();
            ItemStack result = recipe.getResult(world.getRegistryManager());
            if (result.isEmpty() || !isWeaponItem(result)) {
                continue;
            }
            if (canCraft(inventory, recipe)) {
                recipes.add(new WeaponRecipe(recipe, result));
            }
        }
        return filterLastCrafted(recipes);
    }

    @Override
    protected boolean canStillCraftRecipe(ServerWorld world, Inventory inventory, WeaponRecipe recipe) {
        return canCraft(inventory, recipe.recipe);
    }

    @Override
    protected boolean craftRecipe(ServerWorld world, Inventory inventory, WeaponRecipe recipe) {
        if (!canInsertOutput(inventory, recipe.output)) {
            return false;
        }
        if (!consumeIngredients(inventory, recipe.recipe)) {
            return false;
        }
        insertStack(inventory, recipe.output.copy());
        return true;
    }

    @Override
    protected void onCraftSucceeded(ServerWorld world, WeaponRecipe recipe) {
        recordLastCrafted(recipe.output);
    }

    @Override
    protected ItemStack getRecipeOutput(WeaponRecipe recipe) {
        return recipe.output;
    }

    private boolean isWeaponItem(ItemStack stack) {
        if (stack.getItem() instanceof SwordItem
                || stack.getItem() instanceof AxeItem
                || stack.getItem() instanceof BowItem
                || stack.getItem() instanceof CrossbowItem
                || stack.getItem() instanceof TridentItem
                || stack.getItem() instanceof MaceItem) {
            return true;
        }

        if (stack.getItem() instanceof ToolItem) {
            return !(stack.getItem() instanceof PickaxeItem
                    || stack.getItem() instanceof ShovelItem
                    || stack.getItem() instanceof HoeItem);
        }

        return false;
    }

    private List<WeaponRecipe> filterLastCrafted(List<WeaponRecipe> recipes) {
        Identifier lastCrafted = getLastCraftedId();
        if (lastCrafted == null || recipes.size() <= 1) {
            return recipes;
        }
        List<WeaponRecipe> filtered = new ArrayList<>();
        for (WeaponRecipe recipe : recipes) {
            Identifier resultId = Registries.ITEM.getId(recipe.output.getItem());
            if (!lastCrafted.equals(resultId)) {
                filtered.add(recipe);
            }
        }
        return filtered.isEmpty() ? recipes : filtered;
    }

    private Identifier getLastCraftedId() {
        if (villager instanceof WeaponsmithCraftingMemoryHolder holder) {
            return holder.guardvillagers$getLastWeaponsmithCrafted();
        }
        return null;
    }

    private void recordLastCrafted(ItemStack stack) {
        if (villager instanceof WeaponsmithCraftingMemoryHolder holder) {
            holder.guardvillagers$setLastWeaponsmithCrafted(Registries.ITEM.getId(stack.getItem()));
        }
    }

    private boolean canCraft(Inventory inventory, CraftingRecipe recipe) {
        List<ItemStack> available = new ArrayList<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty()) {
                available.add(stack.copy());
            }
        }

        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            int matchIndex = findMatchingStack(available, ingredient);
            if (matchIndex < 0) {
                return false;
            }
            ItemStack matched = available.get(matchIndex);
            matched.decrement(1);
            if (matched.isEmpty()) {
                available.remove(matchIndex);
            }
        }

        return true;
    }

    private int findMatchingStack(List<ItemStack> available, Ingredient ingredient) {
        for (int i = 0; i < available.size(); i++) {
            if (ingredient.test(available.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean consumeIngredients(Inventory inventory, CraftingRecipe recipe) {
        if (!canCraft(inventory, recipe)) {
            return false;
        }

        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            for (int slot = 0; slot < inventory.size(); slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (stack.isEmpty() || !ingredient.test(stack)) {
                    continue;
                }
                stack.decrement(1);
                if (stack.isEmpty()) {
                    inventory.setStack(slot, ItemStack.EMPTY);
                }
                break;
            }
        }

        return true;
    }

    record WeaponRecipe(CraftingRecipe recipe, ItemStack output) {
    }

    @Override
    protected String formatCheckResult(int craftableCount) {
        return craftableCount == 1 ? "1 weapon available to craft" : craftableCount + " weapons available to craft";
    }

    @Override
    protected String formatCraftedResult(int craftableCount, ItemStack crafted) {
        String craftedName = crafted.getName().getString();
        return craftableCount == 1
                ? "1 weapon available to craft - 1 " + craftedName + " crafted"
                : craftableCount + " weapons available to craft - 1 " + craftedName + " crafted";
    }
}
