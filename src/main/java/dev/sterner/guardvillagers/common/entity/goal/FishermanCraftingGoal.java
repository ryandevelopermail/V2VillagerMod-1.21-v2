package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.villager.CraftingCheckLogger;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.village.VillagerProfession;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class FishermanCraftingGoal extends Goal {
    private static final int CHECK_INTERVAL_TICKS = CraftingCheckLogger.MATERIAL_CHECK_INTERVAL_TICKS;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final double MOVE_SPEED = 0.6D;
    private static final int PATH_RETRY_INTERVAL_TICKS = 20;
    private static final int MIN_OPERATIONAL_ROD_STOCK = 1;

    private final VillagerEntity villager;
    private BlockPos currentNavigationTarget;
    private long lastPathRequestTick = Long.MIN_VALUE;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private @Nullable BlockPos craftingTablePos;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;
    private long lastCraftDay = -1L;
    private int dailyCraftLimit;
    private int craftedToday;
    private int lastCheckCount;
    private boolean immediateCheckPending;

    public FishermanCraftingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, @Nullable BlockPos craftingTablePos) {
        this.villager = villager;
        setTargets(jobPos, chestPos, craftingTablePos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos, @Nullable BlockPos craftingTablePos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.craftingTablePos = craftingTablePos == null ? null : craftingTablePos.toImmutable();
        this.stage = Stage.IDLE;
    }

    public @Nullable BlockPos getCraftingTablePos() {
        return craftingTablePos;
    }

    public void requestImmediateCraft(ServerWorld world) {
        refreshDailyLimit(world);
        immediateCheckPending = true;
        nextCheckTime = 0L;
    }

    private static final int NON_TABLE_GRID_SIZE = 2;

    private boolean hasValidCraftingTable(ServerWorld world) {
        return craftingTablePos != null && world.getBlockState(craftingTablePos).isOf(Blocks.CRAFTING_TABLE);
    }

    private boolean canUseRecipeWithoutCraftingTable(net.minecraft.recipe.CraftingRecipe recipe) {
        return recipe.fits(NON_TABLE_GRID_SIZE, NON_TABLE_GRID_SIZE);
    }

    private boolean hasNonTableCraftableRecipe(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) return false;
        for (RecipeEntry<CraftingRecipe> entry : world.getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = entry.value();
            ItemStack result = recipe.getResult(world.getRegistryManager());
            if (!isFishermanOutput(result)) continue;
            if (!canUseRecipeWithoutCraftingTable(recipe)) continue;
            if (canCraft(inventory, recipe)) return true;
        }
        return false;
    }

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!world.isDay()) {
            return false;
        }
        if (villager.getVillagerData().getProfession() != VillagerProfession.FISHERMAN) {
            return false;
        }
        if (chestPos == null) {
            return false;
        }
        // Require crafting table only if no non-table recipe is craftable.
        // Fishing rod (sticks + string) fits a 2×2 grid and never needs a table.
        boolean hasCraftingTable = hasValidCraftingTable(world);
        if (!hasCraftingTable && !hasNonTableCraftableRecipe(world)) {
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
        CraftingCheckLogger.report(world, "Fisherman", immediateCheckPending ? "immediate request" : "natural interval", formatCheckResult(lastCheckCount));
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
        if (villager.getWorld() instanceof ServerWorld world && hasValidCraftingTable(world)) {
            stage = Stage.GO_TO_TABLE;
            moveTo(craftingTablePos);
        } else {
            // No crafting table — craft in place (only 2×2 recipes are eligible)
            stage = Stage.CRAFT;
        }
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        currentNavigationTarget = null;
        lastPathRequestTick = Long.MIN_VALUE;
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
            dailyCraftLimit = 4;
            craftedToday = 0;
            immediateCheckPending = false;
        }
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

        List<FishermanRecipe> craftable = getCraftableRecipes(world, inventory);
        if (craftable.isEmpty()) {
            return;
        }

        List<ItemStack> outputs = craftable.stream().map(FishermanRecipe::output).toList();
        int selectedIndex = selectRecipeIndex(outputs, isFishingRodNeeded(inventory), villager.getRandom());
        if (selectedIndex < 0) {
            return;
        }

        FishermanRecipe recipe = craftable.get(selectedIndex);
        if (!canInsertOutput(inventory, recipe.output)) {
            return;
        }
        if (consumeIngredients(inventory, recipe.recipe)) {
            insertStack(inventory, recipe.output.copy());
            inventory.markDirty();
            craftedToday++;
            CraftingCheckLogger.report(world, "Fisherman", formatCraftedResult(lastCheckCount, recipe.output));
        }
    }

    private boolean isFishingRodNeeded(Inventory inventory) {
        return countItem(inventory, Items.FISHING_ROD) < MIN_OPERATIONAL_ROD_STOCK;
    }

    static int selectRecipeIndex(List<ItemStack> outputs, boolean fishingRodNeeded, Random random) {
        if (outputs.isEmpty()) {
            return -1;
        }

        if (fishingRodNeeded) {
            for (int i = 0; i < outputs.size(); i++) {
                if (outputs.get(i).isOf(Items.FISHING_ROD)) {
                    return i;
                }
            }
        }

        return random.nextInt(outputs.size());
    }

    private int countItem(Inventory inventory, Item item) {
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private List<FishermanRecipe> getCraftableRecipes(ServerWorld world, Inventory inventory) {
        boolean hasCraftingTable = hasValidCraftingTable(world);
        List<FishermanRecipe> recipes = new ArrayList<>();
        for (RecipeEntry<CraftingRecipe> entry : world.getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = entry.value();
            ItemStack result = recipe.getResult(world.getRegistryManager());
            if (!isFishermanOutput(result)) {
                continue;
            }
            // Without a crafting table, only 2×2 recipes are available
            if (!hasCraftingTable && !canUseRecipeWithoutCraftingTable(recipe)) {
                continue;
            }
            if (canCraft(inventory, recipe)) {
                recipes.add(new FishermanRecipe(recipe, result));
            }
        }
        return recipes;
    }

    private boolean isFishermanOutput(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.isOf(Items.FISHING_ROD)
                || stack.isOf(Items.BUCKET)) {
            return true;
        }
        return stack.isIn(ItemTags.BOATS) || stack.isIn(ItemTags.CHEST_BOATS);
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
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, chestPos, false));
        }
        if (state.getBlock() instanceof BarrelBlock) {
            if (world.getBlockEntity(chestPos) instanceof BarrelBlockEntity barrel) {
                return Optional.of(barrel);
            }
        }
        return Optional.empty();
    }

    private void moveTo(BlockPos target) {
        moveTo(target, MOVE_SPEED);
    }

    private void moveTo(BlockPos target, double speed) {
        if (target == null) {
            return;
        }

        long currentTick = villager.getWorld().getTime();
        boolean shouldRequestPath = !target.equals(currentNavigationTarget)
                || villager.getNavigation().isIdle()
                || currentTick - lastPathRequestTick >= PATH_RETRY_INTERVAL_TICKS;
        if (!shouldRequestPath) {
            return;
        }

        villager.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, speed);
        currentNavigationTarget = target.toImmutable();
        lastPathRequestTick = currentTick;
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
        GO_TO_TABLE,
        CRAFT,
        DONE
    }

    private record FishermanRecipe(CraftingRecipe recipe, ItemStack output) {
    }

    private String formatCheckResult(int craftableCount) {
        if (craftableCount == 1) {
            return "1 item available to craft";
        }
        return craftableCount + " items available to craft";
    }

    private String formatCraftedResult(int craftableCount, ItemStack crafted) {
        String craftedName = crafted.getName().getString();
        if (craftableCount == 1) {
            return "1 item available to craft - 1 " + craftedName + " crafted";
        }
        return craftableCount + " items available to craft - 1 " + craftedName + " crafted";
    }
}
