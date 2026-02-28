package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.MasonGuardEntity;
import dev.sterner.guardvillagers.common.villager.CraftingCheckLogger;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class MasonGuardStonecuttingGoal extends Goal {
    private static final int CHECK_INTERVAL_TICKS = CraftingCheckLogger.MATERIAL_CHECK_INTERVAL_TICKS;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final double MOVE_SPEED = 0.6D;
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

    private final MasonGuardEntity guard;
    private long nextCheckTime;
    private Stage stage = Stage.IDLE;

    public MasonGuardStonecuttingGoal(MasonGuardEntity guard) {
        this.guard = guard;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    @Override
    public boolean canStart() {
        if (!(guard.getWorld() instanceof ServerWorld world) || !guard.isAlive()) {
            return false;
        }

        BlockPos jobPos = guard.getPairedJobPos();
        BlockPos chestPos = guard.getPairedChestPos();
        if (jobPos == null || chestPos == null) {
            return false;
        }

        if (world.getTime() < nextCheckTime) {
            return false;
        }
        nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;

        Optional<Inventory> inventory = getChestInventory(world, chestPos);
        return inventory.filter(value -> !getCraftableRecipes(world, value).isEmpty()).isPresent();
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.DONE && guard.isAlive();
    }

    @Override
    public void start() {
        this.stage = Stage.GO_TO_STONECUTTER;
        moveTo(guard.getPairedJobPos());
    }

    @Override
    public void stop() {
        guard.getNavigation().stop();
        this.stage = Stage.DONE;
    }

    @Override
    public void tick() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            this.stage = Stage.DONE;
            return;
        }

        BlockPos jobPos = guard.getPairedJobPos();
        BlockPos chestPos = guard.getPairedChestPos();
        if (jobPos == null || chestPos == null) {
            this.stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case GO_TO_STONECUTTER -> {
                if (isNear(jobPos)) {
                    stage = Stage.CRAFT;
                } else {
                    moveTo(jobPos);
                }
            }
            case CRAFT -> {
                Optional<Inventory> optionalInventory = getChestInventory(world, chestPos);
                if (optionalInventory.isEmpty()) {
                    stage = Stage.DONE;
                    return;
                }
                Inventory inventory = optionalInventory.get();
                List<MasonRecipe> craftableRecipes = getCraftableRecipes(world, inventory);
                if (craftableRecipes.isEmpty()) {
                    stage = Stage.DONE;
                    return;
                }

                MasonRecipe recipe = craftableRecipes.get(guard.getRandom().nextInt(craftableRecipes.size()));
                if (canInsertOutput(inventory, recipe.output()) && consumeIngredient(inventory, recipe.recipe())) {
                    ItemStack remaining = insertStack(inventory, recipe.output().copy());
                    if (remaining.isEmpty()) {
                        inventory.markDirty();
                    }
                }
                stage = Stage.DONE;
            }
            case DONE, IDLE -> {
            }
        }
    }

    private List<MasonRecipe> getCraftableRecipes(ServerWorld world, Inventory inventory) {
        List<MasonRecipe> recipes = new ArrayList<>();
        for (RecipeEntry<StonecuttingRecipe> entry : world.getRecipeManager().listAllOfType(RecipeType.STONECUTTING)) {
            StonecuttingRecipe recipe = entry.value();
            ItemStack result = recipe.craft(new net.minecraft.recipe.input.SingleStackRecipeInput(ItemStack.EMPTY), world.getRegistryManager());
            if (result.isEmpty() || !isMasonryItem(result)) {
                continue;
            }
            if (canConsumeIngredient(inventory, recipe)) {
                recipes.add(new MasonRecipe(recipe, result));
            }
        }
        return recipes;
    }

    private boolean isMasonryItem(ItemStack stack) {
        return MASONRY_OUTPUTS.contains(stack.getItem());
    }

    private boolean canConsumeIngredient(Inventory inventory, StonecuttingRecipe recipe) {
        Ingredient ingredient = getPrimaryIngredient(recipe);
        return ingredient != null && findMatchingSlot(inventory, ingredient) >= 0;
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

    private Optional<Inventory> getChestInventory(ServerWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        Inventory inventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
        return Optional.ofNullable(inventory);
    }

    private void moveTo(BlockPos target) {
        guard.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
    }

    private boolean isNear(BlockPos target) {
        return guard.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private ItemStack insertStack(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (remaining.isEmpty()) {
                return ItemStack.EMPTY;
            }

            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                if (!inventory.isValid(slot, remaining)) {
                    continue;
                }
                int moved = Math.min(remaining.getCount(), remaining.getMaxCount());
                ItemStack toInsert = remaining.copy();
                toInsert.setCount(moved);
                inventory.setStack(slot, toInsert);
                remaining.decrement(moved);
                continue;
            }

            if (!ItemStack.areItemsAndComponentsEqual(existing, remaining)) {
                continue;
            }

            if (!inventory.isValid(slot, remaining)) {
                continue;
            }

            int space = existing.getMaxCount() - existing.getCount();
            if (space <= 0) {
                continue;
            }

            int moved = Math.min(space, remaining.getCount());
            existing.increment(moved);
            remaining.decrement(moved);
        }

        return remaining;
    }

    private boolean canInsertOutput(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (remaining.isEmpty()) {
                return true;
            }

            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                if (!inventory.isValid(slot, remaining)) {
                    continue;
                }
                int moved = Math.min(remaining.getCount(), remaining.getMaxCount());
                remaining.decrement(moved);
                continue;
            }

            if (!ItemStack.areItemsAndComponentsEqual(existing, remaining)) {
                continue;
            }

            if (!inventory.isValid(slot, remaining)) {
                continue;
            }

            int space = existing.getMaxCount() - existing.getCount();
            if (space <= 0) {
                continue;
            }

            int moved = Math.min(space, remaining.getCount());
            remaining.decrement(moved);
        }

        return remaining.isEmpty();
    }

    private enum Stage {
        IDLE,
        GO_TO_STONECUTTER,
        CRAFT,
        DONE
    }

    private record MasonRecipe(StonecuttingRecipe recipe, ItemStack output) {
    }
}
