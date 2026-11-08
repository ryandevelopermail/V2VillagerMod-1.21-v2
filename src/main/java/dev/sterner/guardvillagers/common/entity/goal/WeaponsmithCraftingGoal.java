package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.professionalstorage.WeaponsmithWorkMetrics;
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
import java.util.function.BooleanSupplier;

public class WeaponsmithCraftingGoal extends AbstractCraftingGoal<WeaponsmithCraftingGoal.WeaponRecipe> {

    public WeaponsmithCraftingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
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
        return filterLastCrafted(discoverCraftableRecipesReadOnly(world, inventory));
    }

    public int countCraftableRecipesReadOnly(ServerWorld world, Inventory inventory) {
        return discoverCraftableRecipesReadOnly(world, inventory).size();
    }

    private List<WeaponRecipe> discoverCraftableRecipesReadOnly(ServerWorld world, Inventory inventory) {
        List<WeaponRecipe> recipes = new ArrayList<>();
        for (RecipeEntry<CraftingRecipe> entry : world.getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = entry.value();
            ItemStack result = recipe.getResult(world.getRegistryManager());
            boolean supportedOutput = !result.isEmpty() && isCraftedWeapon(result);
            boolean ingredientsAvailable = supportedOutput && canCraft(inventory, recipe);
            if (isRecipeAvailable(supportedOutput, ingredientsAvailable)) {
                recipes.add(new WeaponRecipe(recipe, result));
            }
        }
        return recipes;
    }

    @Override
    protected boolean canStillCraftRecipe(ServerWorld world, Inventory inventory, WeaponRecipe recipe) {
        return canCraft(inventory, recipe.recipe);
    }

    @Override
    protected boolean craftRecipe(ServerWorld world, Inventory inventory, WeaponRecipe recipe) {
        return executeConfirmedCraft(
                () -> canInsertOutput(inventory, recipe.output),
                () -> consumeIngredients(inventory, recipe.recipe),
                () -> insertStack(inventory, recipe.output.copy()).isEmpty());
    }

    @Override
    protected void onCraftSucceeded(ServerWorld world, WeaponRecipe recipe) {
        runConfirmedCraftEffects(
                () -> recordLastCrafted(recipe.output),
                () -> WeaponsmithWorkMetrics.recordWeaponsCrafted(
                        world,
                        villager.getUuid(),
                        recipe.output.getCount()));
    }

    @Override
    protected ItemStack getRecipeOutput(WeaponRecipe recipe) {
        return recipe.output;
    }

    public static boolean isCraftedWeapon(ItemStack stack) {
        return isCraftedWeaponShape(
                stack.getItem() instanceof SwordItem,
                stack.getItem() instanceof AxeItem,
                stack.getItem() instanceof BowItem,
                stack.getItem() instanceof CrossbowItem,
                stack.getItem() instanceof TridentItem,
                stack.getItem() instanceof MaceItem,
                stack.getItem() instanceof ToolItem,
                stack.getItem() instanceof PickaxeItem,
                stack.getItem() instanceof ShovelItem,
                stack.getItem() instanceof HoeItem);
    }

    static boolean isCraftedWeaponShape(
            boolean sword,
            boolean axe,
            boolean bow,
            boolean crossbow,
            boolean trident,
            boolean mace,
            boolean tool,
            boolean pickaxe,
            boolean shovel,
            boolean hoe
    ) {
        if (sword || axe || bow || crossbow || trident || mace) {
            return true;
        }
        return tool && !pickaxe && !shovel && !hoe;
    }

    static boolean isRecipeAvailable(boolean supportedOutput, boolean ingredientsAvailable) {
        return supportedOutput && ingredientsAvailable;
    }

    static boolean executeConfirmedCraft(
            BooleanSupplier hasOutputCapacity,
            BooleanSupplier consumedIngredients,
            BooleanSupplier insertedCompleteOutput
    ) {
        return hasOutputCapacity.getAsBoolean()
                && consumedIngredients.getAsBoolean()
                && insertedCompleteOutput.getAsBoolean();
    }

    static void runConfirmedCraftEffects(Runnable lastCraftedMemory, Runnable metricWrite) {
        lastCraftedMemory.run();
        metricWrite.run();
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
