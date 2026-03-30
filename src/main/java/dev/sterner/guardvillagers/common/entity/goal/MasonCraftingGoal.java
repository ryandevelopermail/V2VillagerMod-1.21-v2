package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.villager.CraftingCheckLogger;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.StonecuttingRecipe;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MasonCraftingGoal extends AbstractCraftingGoal<MasonCraftingGoal.MasonRecipe> {
    private static final Set<Item> MASONRY_OUTPUTS = Set.of(
            Items.STONE_BRICKS,
            Items.CHISELED_STONE_BRICKS,
            Items.POLISHED_ANDESITE,
            Items.POLISHED_DIORITE,
            Items.POLISHED_GRANITE,
            Items.STONE_SLAB,
            Items.STONE_BRICK_SLAB,
            Items.STONE_STAIRS,
            Items.STONE_BRICK_STAIRS,
            Items.COBBLESTONE_WALL,
            Items.STONE_BRICK_WALL,
            Items.BRICK,
            Items.BRICKS,
            Items.TERRACOTTA,
            Items.QUARTZ_PILLAR,
            Items.SMOOTH_QUARTZ,
            Items.QUARTZ_SLAB,
            Items.QUARTZ_STAIRS,
            Items.SMOOTH_QUARTZ_SLAB,
            Items.SMOOTH_QUARTZ_STAIRS
    );

    private CraftingCheckTrigger pendingTrigger = CraftingCheckTrigger.SCHEDULED;
    private Item lastCraftedOutputItem;

    public MasonCraftingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        super(villager, jobPos, chestPos, null);
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        super.setTargets(jobPos, chestPos, null);
    }

    @Override
    protected boolean hasRequiredProfession() {
        return villager.getVillagerData().getProfession() == VillagerProfession.MASON;
    }

    @Override
    protected boolean requiresCraftingTable() {
        return false;
    }

    @Override
    protected BlockPos getWorkPos(MasonRecipe recipe) {
        return jobPos;
    }

    @Override
    protected String getGoalName() {
        return "Mason";
    }

    @Override
    protected int getDailyCraftLimit(ServerWorld world) {
        return 4;
    }

    @Override
    protected void onDailyReset(ServerWorld world) {
        pendingTrigger = CraftingCheckTrigger.SCHEDULED;
    }

    public void requestImmediateCraft(ServerWorld world, CraftingCheckTrigger trigger) {
        pendingTrigger = trigger;
        super.requestImmediateCraft(world);
    }

    @Override
    protected void onCheckPerformed(ServerWorld world) {
        pendingTrigger = CraftingCheckTrigger.SCHEDULED;
    }

    @Override
    protected void logCheck(ServerWorld world, boolean immediate, int craftableCount) {
        CraftingCheckTrigger trigger = immediate ? pendingTrigger : CraftingCheckTrigger.SCHEDULED;
        int intervalTicks = immediate ? 0 : CHECK_INTERVAL_TICKS;
        CraftingCheckLogger.report(world, getGoalName(), trigger.name(), intervalTicks, () -> formatCheckResult(craftableCount));
    }

    @Override
    protected List<MasonRecipe> discoverRecipes(ServerWorld world, Inventory inventory) {
        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.MASON, world.getBlockState(jobPos))) {
            return List.of();
        }

        List<MasonRecipe> recipes = new ArrayList<>();
        for (RecipeEntry<StonecuttingRecipe> entry : world.getRecipeManager().listAllOfType(RecipeType.STONECUTTING)) {
            StonecuttingRecipe recipe = entry.value();
            ItemStack result = recipe.getResult(world.getRegistryManager());
            if (result.isEmpty() || !isMasonryItem(result)) {
                continue;
            }
            if (canCraft(inventory, recipe)) {
                recipes.add(new MasonRecipe(recipe, result));
            }
        }
        return recipes;
    }

    @Override
    protected MasonRecipe chooseRecipe(List<MasonRecipe> craftableRecipes) {
        return pickRecipeAvoidingLastOutput(craftableRecipes);
    }

    @Override
    protected boolean canStillCraftRecipe(ServerWorld world, Inventory inventory, MasonRecipe recipe) {
        return canCraft(inventory, recipe.recipe);
    }

    @Override
    protected boolean craftRecipe(ServerWorld world, Inventory inventory, MasonRecipe recipe) {
        if (!canInsertOutput(inventory, recipe.output)) {
            return false;
        }
        if (!consumeIngredient(inventory, recipe.recipe)) {
            return false;
        }
        insertStack(inventory, recipe.output.copy());
        return true;
    }

    @Override
    protected void onCraftSucceeded(ServerWorld world, MasonRecipe recipe) {
        this.lastCraftedOutputItem = recipe.output.getItem();
    }

    @Override
    protected ItemStack getRecipeOutput(MasonRecipe recipe) {
        return recipe.output;
    }

    private MasonRecipe pickRecipeAvoidingLastOutput(List<MasonRecipe> craftableRecipes) {
        if (craftableRecipes.size() <= 1 || this.lastCraftedOutputItem == null) {
            return craftableRecipes.get(villager.getRandom().nextInt(craftableRecipes.size()));
        }

        List<MasonRecipe> alternatives = craftableRecipes.stream()
                .filter(recipe -> recipe.output.getItem() != this.lastCraftedOutputItem)
                .toList();
        if (alternatives.isEmpty()) {
            return craftableRecipes.get(villager.getRandom().nextInt(craftableRecipes.size()));
        }

        return alternatives.get(villager.getRandom().nextInt(alternatives.size()));
    }

    private boolean isMasonryItem(ItemStack stack) {
        return MASONRY_OUTPUTS.contains(stack.getItem());
    }

    private boolean canCraft(Inventory inventory, StonecuttingRecipe recipe) {
        Ingredient ingredient = getPrimaryIngredient(recipe);
        if (ingredient == null || ingredient.isEmpty()) {
            return false;
        }
        return findMatchingSlot(inventory, ingredient) >= 0;
    }

    private int findMatchingSlot(Inventory inventory, Ingredient ingredient) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && ingredient.test(stack)) {
                return slot;
            }
        }
        return -1;
    }

    private boolean consumeIngredient(Inventory inventory, StonecuttingRecipe recipe) {
        Ingredient ingredient = getPrimaryIngredient(recipe);
        if (ingredient == null || ingredient.isEmpty()) {
            return false;
        }

        int slot = findMatchingSlot(inventory, ingredient);
        if (slot < 0) {
            return false;
        }

        ItemStack stack = inventory.getStack(slot);
        stack.decrement(1);
        if (stack.isEmpty()) {
            inventory.setStack(slot, ItemStack.EMPTY);
        }
        return true;
    }

    private Ingredient getPrimaryIngredient(StonecuttingRecipe recipe) {
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) {
            return null;
        }
        return ingredients.get(0);
    }

    record MasonRecipe(StonecuttingRecipe recipe, ItemStack output) {
    }

    public enum CraftingCheckTrigger {
        SCHEDULED,
        CHEST_PAIRED,
        CHEST_CONTENT_CHANGED
    }

    @Override
    protected String formatCheckResult(int craftableCount) {
        return craftableCount == 1 ? "1 masonry item available to craft" : craftableCount + " masonry items available to craft";
    }

    @Override
    protected String formatCraftedResult(int craftableCount, ItemStack crafted) {
        String craftedName = crafted.getName().getString();
        return craftableCount == 1
                ? "1 masonry item available to craft - 1 " + craftedName + " crafted"
                : craftableCount + " masonry items available to craft - 1 " + craftedName + " crafted";
    }
}
