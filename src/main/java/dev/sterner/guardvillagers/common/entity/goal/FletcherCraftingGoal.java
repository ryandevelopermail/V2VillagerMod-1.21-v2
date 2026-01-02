package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.villager.CraftingCheckLogger;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ArrowItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.village.VillagerProfession;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class FletcherCraftingGoal extends Goal {
    private static final int CHECK_INTERVAL_TICKS = CraftingCheckLogger.MATERIAL_CHECK_INTERVAL_TICKS;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final double MOVE_SPEED = 0.6D;
    private static final int CRAFT_INTERVAL_MIN_TICKS = 20 * 60 * 3;
    private static final int CRAFT_INTERVAL_MAX_TICKS = 20 * 60 * 10;
    private static final Logger LOGGER = LoggerFactory.getLogger(FletcherCraftingGoal.class);

    private final VillagerEntity villager;
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
    private boolean craftCountdownActive;
    private long craftCountdownStartTime;
    private long craftCountdownTotalTicks;
    private long nextCraftTriggerTime;
    private int lastCountdownLogStep;

    public FletcherCraftingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, @Nullable BlockPos craftingTablePos) {
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

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!world.isDay()) {
            return false;
        }
        if (villager.getVillagerData().getProfession() != VillagerProfession.FLETCHER) {
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

        if (craftCountdownActive) {
            if (nextCraftTriggerTime > 0L) {
                logCraftCountdownProgress();
            }
            if (world.getTime() < nextCraftTriggerTime) {
                return false;
            }
        }

        if (!immediateCheckPending && world.getTime() < nextCheckTime) {
            return false;
        }

        lastCheckCount = countCraftableRecipes(world);
        CraftingCheckLogger.report(world, "Fletcher", formatCheckResult(lastCheckCount));
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
        clearCraftCountdown();
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
                startCraftCountdown("session ended");
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
            dailyCraftLimit = MathHelper.nextInt(villager.getRandom(), 1, 3);
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

        List<FletcherRecipe> craftable = getCraftableRecipes(world, inventory);
        if (craftable.isEmpty()) {
            return;
        }

        FletcherRecipe recipe = craftable.get(villager.getRandom().nextInt(craftable.size()));
        if (recipe.output.getItem() instanceof ArrowItem) {
            int craftedCount = craftArrowStack(inventory, recipe.recipe, recipe.output);
            if (craftedCount > 0) {
                craftedToday++;
                CraftingCheckLogger.report(world, "Fletcher", formatCraftedResult(lastCheckCount, recipe.output, craftedCount));
            }
            return;
        }

        if (!canInsertOutput(inventory, recipe.output)) {
            return;
        }
        if (consumeIngredients(inventory, recipe.recipe)) {
            insertStack(inventory, recipe.output.copy());
            inventory.markDirty();
            craftedToday++;
            CraftingCheckLogger.report(world, "Fletcher", formatCraftedResult(lastCheckCount, recipe.output, recipe.output.getCount()));
        }
    }

    private int craftArrowStack(Inventory inventory, CraftingRecipe recipe, ItemStack output) {
        int totalCrafted = 0;
        int outputCount = output.getCount();
        int maxStackSize = output.getMaxCount();
        while (totalCrafted + outputCount <= maxStackSize) {
            if (!canInsertOutput(inventory, output)) {
                break;
            }
            if (!consumeIngredients(inventory, recipe)) {
                break;
            }
            insertStack(inventory, output.copy());
            totalCrafted += outputCount;
        }
        if (totalCrafted > 0) {
            inventory.markDirty();
        }
        return totalCrafted;
    }

    private void startCraftCountdown(String reason) {
        craftCountdownTotalTicks = nextRandomCraftInterval();
        craftCountdownStartTime = villager.getWorld().getTime();
        nextCraftTriggerTime = craftCountdownStartTime + craftCountdownTotalTicks;
        lastCountdownLogStep = 0;
        craftCountdownActive = true;
        LOGGER.info("Fletcher {} craft countdown started ({} ticks) {}",
                villager.getUuidAsString(),
                craftCountdownTotalTicks,
                reason);
    }

    private void logCraftCountdownProgress() {
        if (craftCountdownTotalTicks <= 0L) {
            return;
        }
        long remainingTicks = nextCraftTriggerTime - villager.getWorld().getTime();
        long elapsedTicks = villager.getWorld().getTime() - craftCountdownStartTime;
        int step = Math.min(4, (int) ((elapsedTicks * 4L) / craftCountdownTotalTicks));
        if (step <= lastCountdownLogStep || step == 0) {
            return;
        }
        lastCountdownLogStep = step;
        int percent = step * 25;
        LOGGER.info("Fletcher {} craft countdown {}% ({} ticks remaining)",
                villager.getUuidAsString(),
                percent,
                Math.max(remainingTicks, 0L));
    }

    private long nextRandomCraftInterval() {
        return MathHelper.nextInt(villager.getRandom(), CRAFT_INTERVAL_MIN_TICKS, CRAFT_INTERVAL_MAX_TICKS);
    }

    private void clearCraftCountdown() {
        nextCraftTriggerTime = 0L;
        craftCountdownTotalTicks = 0L;
        craftCountdownStartTime = 0L;
        lastCountdownLogStep = 0;
        craftCountdownActive = false;
    }

    private List<FletcherRecipe> getCraftableRecipes(ServerWorld world, Inventory inventory) {
        List<FletcherRecipe> recipes = new ArrayList<>();
        for (RecipeEntry<CraftingRecipe> entry : world.getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = entry.value();
            ItemStack result = recipe.getResult(world.getRegistryManager());
            if (result.isEmpty() || !isFletcherRecipe(result)) {
                continue;
            }
            if (canCraft(inventory, recipe)) {
                recipes.add(new FletcherRecipe(recipe, result));
            }
        }
        return recipes;
    }

    private boolean isFletcherRecipe(ItemStack result) {
        if (result.isOf(Items.TARGET)) {
            return true;
        }
        if (result.getItem() instanceof BowItem) {
            return true;
        }
        if (result.getItem() instanceof CrossbowItem) {
            return true;
        }
        return result.getItem() instanceof ArrowItem;
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

    private record FletcherRecipe(CraftingRecipe recipe, ItemStack output) {
    }

    private String formatCheckResult(int craftableCount) {
        if (craftableCount == 1) {
            return "1 fletcher item available to craft";
        }
        return craftableCount + " fletcher items available to craft";
    }

    private String formatCraftedResult(int craftableCount, ItemStack crafted, int craftedCount) {
        String craftedName = crafted.getName().getString();
        if (craftableCount == 1) {
            return "1 fletcher item available to craft - " + craftedCount + " " + craftedName + " crafted";
        }
        return craftableCount + " fletcher items available to craft - " + craftedCount + " " + craftedName + " crafted";
    }
}
