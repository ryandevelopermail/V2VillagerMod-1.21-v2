package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.villager.CraftingCheckLogger;
import dev.sterner.guardvillagers.common.util.ArmorerStandManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class ArmorerCraftingGoal extends Goal {
    private static final int CHECK_INTERVAL_TICKS = CraftingCheckLogger.MATERIAL_CHECK_INTERVAL_TICKS;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final double MOVE_SPEED = 0.6D;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private BlockPos craftingTablePos;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;
    private long lastCraftDay = -1L;
    private int dailyCraftLimit;
    private int craftedToday;
    private int lastCheckCount;
    private boolean immediateCheckPending;

    public ArmorerCraftingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        this.villager = villager;
        setTargets(jobPos, chestPos, craftingTablePos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.craftingTablePos = craftingTablePos.toImmutable();
        this.stage = Stage.IDLE;
    }

    public BlockPos getCraftingTablePos() {
        return craftingTablePos;
    }

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!world.isDay()) {
            return false;
        }
        if (villager.getVillagerData().getProfession() != VillagerProfession.ARMORER) {
            return false;
        }
        if (craftingTablePos == null || chestPos == null) {
            return false;
        }
        if (!world.getBlockState(craftingTablePos).isOf(Blocks.CRAFTING_TABLE)) {
            return false;
        }
        refreshDailyLimit(world);
        if (craftedToday >= dailyCraftLimit) {
            return false;
        }

        if (!immediateCheckPending && world.getTime() < nextCheckTime) {
            return false;
        }

        lastCheckCount = countCraftableRecipes(world);
        CraftingCheckLogger.report(world, "Armorer", formatCheckResult(lastCheckCount));
        nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
        immediateCheckPending = false;
        return lastCheckCount > 0;
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.DONE && villager.isAlive();
    }

    @Override
    public void start() {
        stage = Stage.GO_TO_TABLE;
        moveTo(craftingTablePos);
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        stage = Stage.DONE;
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case GO_TO_TABLE -> {
                if (isNear(craftingTablePos)) {
                    stage = Stage.CRAFT;
                } else {
                    moveTo(craftingTablePos);
                }
            }
            case CRAFT -> {
                craftOnce(world);
                stage = Stage.DONE;
            }
            case IDLE, DONE -> {
            }
        }
    }

    private void refreshDailyLimit(ServerWorld world) {
        long day = world.getTimeOfDay() / 24000L;
        if (day != lastCraftDay) {
            lastCraftDay = day;
            dailyCraftLimit = 2 + villager.getRandom().nextInt(3);
            craftedToday = 0;
            immediateCheckPending = false;
        }
    }

    public void requestImmediateCraft(ServerWorld world) {
        refreshDailyLimit(world);
        immediateCheckPending = true;
        nextCheckTime = 0L;
    }

    private int countCraftableRecipes(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return 0;
        }
        return getCraftableRecipes(world, inventory).size();
    }

    private void craftOnce(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return;
        }

        List<ArmorRecipe> craftable = getCraftableRecipes(world, inventory);
        if (craftable.isEmpty()) {
            return;
        }

        ArmorRecipe recipe = craftable.get(villager.getRandom().nextInt(craftable.size()));
        if (consumeIngredients(inventory, recipe.recipe)) {
            ItemStack crafted = recipe.output.copy();
            if (ArmorerStandManager.tryPlaceArmorOnStand(world, villager, craftingTablePos, crafted)) {
                crafted.decrement(1);
            }
            ItemStack remaining = insertStack(inventory, crafted);
            if (!remaining.isEmpty()) {
                ItemStack villagerRemaining = insertStack(villager.getInventory(), remaining);
                if (!villagerRemaining.isEmpty()) {
                    villager.dropStack(villagerRemaining);
                }
                villager.getInventory().markDirty();
            }
            inventory.markDirty();
            craftedToday++;
            CraftingCheckLogger.report(world, "Armorer", formatCraftedResult(lastCheckCount, recipe.output));
        }
    }

    private List<ArmorRecipe> getCraftableRecipes(ServerWorld world, Inventory inventory) {
        List<ArmorRecipe> recipes = new ArrayList<>();
        for (RecipeEntry<CraftingRecipe> entry : world.getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = entry.value();
            ItemStack result = recipe.getResult(world.getRegistryManager());
            if (result.isEmpty() || !(result.getItem() instanceof ArmorItem)) {
                continue;
            }
            if (canCraft(inventory, recipe)) {
                recipes.add(new ArmorRecipe(recipe, result));
            }
        }
        return recipes;
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

    private Optional<Inventory> getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        Inventory inventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
        return Optional.ofNullable(inventory);
    }

    private void moveTo(BlockPos target) {
        villager.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
    }

    private boolean isNear(BlockPos target) {
        return villager.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
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

    private enum Stage {
        IDLE,
        GO_TO_TABLE,
        CRAFT,
        DONE
    }

    private record ArmorRecipe(CraftingRecipe recipe, ItemStack output) {
    }

    private String formatCheckResult(int craftableCount) {
        if (craftableCount == 1) {
            return "1 armor item available to craft";
        }
        return craftableCount + " armor items available to craft";
    }

    private String formatCraftedResult(int craftableCount, ItemStack crafted) {
        String craftedName = crafted.getName().getString();
        if (craftableCount == 1) {
            return "1 armor item available to craft - 1 " + craftedName + " crafted";
        }
        return craftableCount + " armor items available to craft - 1 " + craftedName + " crafted";
    }
}
