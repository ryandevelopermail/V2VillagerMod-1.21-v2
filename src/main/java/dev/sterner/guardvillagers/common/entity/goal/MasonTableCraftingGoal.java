package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.villager.CraftingCheckLogger;
import dev.sterner.guardvillagers.common.villager.behavior.MasonBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public class MasonTableCraftingGoal extends Goal {
    private static final int CHECK_INTERVAL_TICKS = CraftingCheckLogger.MATERIAL_CHECK_INTERVAL_TICKS;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final double MOVE_SPEED = 0.6D;
    private static final int PATH_RETRY_INTERVAL_TICKS = 20;

    private final VillagerEntity villager;
    private BlockPos currentNavigationTarget;
    private long lastPathRequestTick = Long.MIN_VALUE;
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
    private Item lastCraftedOutputItem;
    private final List<ItemStack> craftedOutputsToday = new ArrayList<>();

    public MasonTableCraftingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
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

    public void requestImmediateCraft(ServerWorld world) {
        refreshDailyLimit(world);
        immediateCheckPending = true;
        nextCheckTime = 0L;
    }

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!world.isDay()) {
            return false;
        }
        if (villager.getVillagerData().getProfession() != VillagerProfession.MASON) {
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
        CraftingCheckLogger.report(world, "Mason", immediateCheckPending ? "immediate request" : "natural interval", () -> formatCheckResult(lastCheckCount));
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
            logDailySummary(world);
            lastCraftDay = day;
            dailyCraftLimit = Math.max(1, GuardVillagersConfig.masonTableDailyCraftLimit);
            craftedToday = 0;
            craftedOutputsToday.clear();
            immediateCheckPending = false;
        }
    }

    private int countCraftableRecipes(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return 0;
        }
        int craftableCount = getCraftableRecipes(inventory).size();
        if (craftableCount > 0) {
            return craftableCount;
        }

        return canCraftPickaxeAfterPrerequisites(inventory) ? 1 : 0;
    }

    private void craftOnce(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return;
        }

        runPickaxePrerequisites(world, inventory);

        List<Recipe> craftable = getCraftableRecipes(inventory);
        if (craftable.isEmpty()) {
            return;
        }

        Recipe recipe = pickRecipe(craftable);
        if (consumeIngredients(inventory, recipe.requirements)) {
            insertStack(inventory, recipe.output.copy());
            inventory.markDirty();
            MasonBehavior.onChestInventoryMutated(world, chestPos);
            craftedToday++;
            craftedOutputsToday.add(recipe.output.copyWithCount(1));
            this.lastCraftedOutputItem = recipe.output.getItem();
            CraftingCheckLogger.report(world, "Mason", () -> "crafted final tool: " + formatCraftedResult(lastCheckCount, recipe.output));
        }
    }

    private void runPickaxePrerequisites(ServerWorld world, Inventory inventory) {
        if (!canCraftAnyPickaxeHead(inventory)) {
            return;
        }

        boolean mutated = false;
        if (countMatching(inventory, stack -> stack.isOf(Items.STICK)) < 2) {
            if (countMatching(inventory, stack -> stack.isIn(ItemTags.PLANKS)) == 0) {
                mutated = preprocessLogsToPlanks(inventory) || mutated;
            }

            if (countMatching(inventory, stack -> stack.isOf(Items.STICK)) < 2 && countMatching(inventory, stack -> stack.isIn(ItemTags.PLANKS)) >= 2) {
                ItemStack sticks = new ItemStack(Items.STICK, 4);
                if (canInsertFully(inventory, sticks) && consumeIngredients(inventory,
                        new IngredientRequirement[] {
                                new IngredientRequirement(stack -> stack.isIn(ItemTags.PLANKS), 2)
                        })) {
                    insertStack(inventory, sticks);
                    mutated = true;
                    CraftingCheckLogger.report(world, "Mason", "crafted intermediate sticks");
                }
            }
        }

        if (mutated) {
            inventory.markDirty();
            MasonBehavior.onChestInventoryMutated(world, chestPos);
        }
    }

    private boolean preprocessLogsToPlanks(Inventory inventory) {
        int logs = countMatching(inventory, stack -> stack.isIn(ItemTags.LOGS_THAT_BURN));
        if (logs <= 0) {
            return false;
        }

        ItemStack planks = new ItemStack(Items.OAK_PLANKS, 4);
        if (!canInsertFully(inventory, planks)) {
            return false;
        }

        if (!consumeIngredients(inventory, new IngredientRequirement[] {
                new IngredientRequirement(stack -> stack.isIn(ItemTags.LOGS_THAT_BURN), 1)
        })) {
            return false;
        }

        insertStack(inventory, planks);
        return true;
    }

    private boolean canCraftPickaxeAfterPrerequisites(Inventory inventory) {
        if (!canCraftAnyPickaxeHead(inventory)) {
            return false;
        }

        int stickCount = countMatching(inventory, stack -> stack.isOf(Items.STICK));
        if (stickCount >= 2) {
            return true;
        }

        int plankCount = countMatching(inventory, stack -> stack.isIn(ItemTags.PLANKS));
        if (plankCount >= 2) {
            return canInsertFully(inventory, new ItemStack(Items.STICK, 4));
        }

        int logs = countMatching(inventory, stack -> stack.isIn(ItemTags.LOGS_THAT_BURN));
        return logs > 0
                && canInsertFully(inventory, new ItemStack(Items.OAK_PLANKS, 4))
                && canInsertFully(inventory, new ItemStack(Items.STICK, 4));
    }

    private boolean canCraftAnyPickaxeHead(Inventory inventory) {
        return countMatching(inventory, stack -> stack.isIn(ItemTags.PLANKS)) >= 3
                || countMatching(inventory, stack -> stack.isOf(Items.COBBLESTONE)) >= 3
                || countMatching(inventory, stack -> stack.isOf(Items.IRON_INGOT)) >= 3
                || countMatching(inventory, stack -> stack.isOf(Items.GOLD_INGOT)) >= 3
                || countMatching(inventory, stack -> stack.isOf(Items.DIAMOND)) >= 3;
    }

    private boolean canInsertFully(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.size() && !remaining.isEmpty(); slot++) {
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


    private Recipe pickRecipe(List<Recipe> craftableRecipes) {
        Recipe pickaxePriorityRecipe = pickPickaxePriorityRecipe(craftableRecipes);
        if (pickaxePriorityRecipe != null) {
            return pickaxePriorityRecipe;
        }

        if (craftableRecipes.size() <= 1 || this.lastCraftedOutputItem == null) {
            return craftableRecipes.get(villager.getRandom().nextInt(craftableRecipes.size()));
        }

        List<Recipe> alternatives = craftableRecipes.stream()
                .filter(recipe -> recipe.output.getItem() != lastCraftedOutputItem)
                .toList();
        if (alternatives.isEmpty()) {
            return craftableRecipes.get(0);
        }

        return alternatives.get(0);
    }

    private Recipe pickPickaxePriorityRecipe(List<Recipe> craftableRecipes) {
        for (Recipe recipe : craftableRecipes) {
            if (recipe.isPickaxe()) {
                return recipe;
            }
        }
        return null;
    }

    private List<Recipe> getCraftableRecipes(Inventory inventory) {
        List<Recipe> recipes = new ArrayList<>();
        for (Recipe recipe : Recipe.values()) {
            if (hasIngredients(inventory, recipe.requirements)) {
                recipes.add(recipe);
            }
        }
        return recipes;
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

    private Optional<Inventory> getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        Inventory inventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
        return Optional.ofNullable(inventory);
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

    private enum Stage {
        IDLE,
        GO_TO_TABLE,
        CRAFT,
        DONE
    }

    private record IngredientRequirement(Predicate<ItemStack> matcher, int count) {
    }

    record RecipeSelection(Recipe recipe, String priorityReason, String fallbackReason) {
    }

    enum Recipe {
        STONECUTTER(new ItemStack(Items.STONECUTTER),
                new IngredientRequirement(stack -> stack.isOf(Items.IRON_INGOT), 1),
                new IngredientRequirement(stack -> stack.isOf(Items.STONE), 3)),
        WOODEN_PICKAXE(new ItemStack(Items.WOODEN_PICKAXE),
                new IngredientRequirement(stack -> stack.isIn(ItemTags.PLANKS), 3),
                new IngredientRequirement(stack -> stack.isOf(Items.STICK), 2)),
        STONE_PICKAXE(new ItemStack(Items.STONE_PICKAXE),
                new IngredientRequirement(stack -> stack.isOf(Items.COBBLESTONE), 3),
                new IngredientRequirement(stack -> stack.isOf(Items.STICK), 2)),
        IRON_PICKAXE(new ItemStack(Items.IRON_PICKAXE),
                new IngredientRequirement(stack -> stack.isOf(Items.IRON_INGOT), 3),
                new IngredientRequirement(stack -> stack.isOf(Items.STICK), 2)),
        GOLDEN_PICKAXE(new ItemStack(Items.GOLDEN_PICKAXE),
                new IngredientRequirement(stack -> stack.isOf(Items.GOLD_INGOT), 3),
                new IngredientRequirement(stack -> stack.isOf(Items.STICK), 2)),
        DIAMOND_PICKAXE(new ItemStack(Items.DIAMOND_PICKAXE),
                new IngredientRequirement(stack -> stack.isOf(Items.DIAMOND), 3),
                new IngredientRequirement(stack -> stack.isOf(Items.STICK), 2));

        private final ItemStack output;
        private final IngredientRequirement[] requirements;

        Recipe(ItemStack output, IngredientRequirement... requirements) {
            this.output = output;
            this.requirements = requirements;
        }

        private boolean isPickaxe() {
            return output.getItem() instanceof PickaxeItem;
        }
    }

    private void logDailySummary(ServerWorld world) {
        if (craftedOutputsToday.isEmpty()) {
            return;
        }

        StringBuilder craftedSummary = new StringBuilder();
        for (int i = 0; i < craftedOutputsToday.size(); i++) {
            if (i > 0) {
                craftedSummary.append(", ");
            }
            craftedSummary.append(craftedOutputsToday.get(i).getName().getString());
        }

        CraftingCheckLogger.report(world, "Mason",
                () -> "day " + lastCraftDay + " summary: crafted " + craftedToday + "/" + dailyCraftLimit + " -> [" + craftedSummary + "]");
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
