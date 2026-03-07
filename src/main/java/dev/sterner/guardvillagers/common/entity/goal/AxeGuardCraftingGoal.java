package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.AxeGuardEntity;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public class AxeGuardCraftingGoal extends Goal {
    private static final double MOVE_SPEED = 0.65D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int CHECK_INTERVAL_TICKS = 80;

    private final AxeGuardEntity guard;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;

    private long lastCraftDay = -1L;
    private int dailyCraftLimit;
    private int craftedToday;

    private boolean logsTriggerPending;
    private boolean planksTriggerPending;
    private boolean cobbleTriggerPending;
    private boolean chestTriggerPending;
    private boolean tableTriggerPending;

    private int observedLogs = -1;
    private int observedPlanks = -1;
    private int observedCobblestone = -1;
    private int observedChests = -1;
    private int observedCraftingTables = -1;

    public AxeGuardCraftingGoal(AxeGuardEntity guard) {
        this.guard = guard;
        setControls(EnumSet.of(Control.MOVE));
    }

    public void requestImmediateCheck() {
        if (guard.getWorld() instanceof ServerWorld world) {
            refreshDailyLimit(world);
        }
        nextCheckTime = 0L;
    }

    public void onChestInventoryChanged(ServerWorld world) {
        Inventory inventory = guard.getPairedChestInventory(world).orElse(null);
        if (inventory == null) {
            return;
        }

        int currentLogs = countMatching(inventory, this::isBurnableLog);
        int currentPlanks = countMatching(inventory, this::isPlanks);
        int currentCobblestone = countMatching(inventory, stack -> stack.isOf(Items.COBBLESTONE));
        int currentChests = countMatching(inventory, stack -> stack.isOf(Items.CHEST));
        int currentTables = countMatching(inventory, stack -> stack.isOf(Items.CRAFTING_TABLE));

        if (observedLogs >= 0 && currentLogs > observedLogs) logsTriggerPending = true;
        if (observedPlanks >= 0 && currentPlanks > observedPlanks) planksTriggerPending = true;
        if (observedCobblestone >= 0 && currentCobblestone > observedCobblestone) cobbleTriggerPending = true;
        if (observedChests >= 0 && currentChests > observedChests) chestTriggerPending = true;
        if (observedCraftingTables >= 0 && currentTables > observedCraftingTables) tableTriggerPending = true;

        captureObservedCounts(inventory);
    }

    @Override
    public boolean canStart() {
        if (!(guard.getWorld() instanceof ServerWorld world) || !guard.isAlive()) {
            return false;
        }
        if (guard.getChestPos() == null || world.getTime() < nextCheckTime) {
            return false;
        }

        Inventory inventory = guard.getPairedChestInventory(world).orElse(null);
        if (inventory == null) {
            return false;
        }

        refreshDailyLimit(world);
        boolean hasPendingTriggers = logsTriggerPending || planksTriggerPending || cobbleTriggerPending || chestTriggerPending || tableTriggerPending;
        if (!hasPendingTriggers && craftedToday >= dailyCraftLimit) {
            return false;
        }

        if (!hasPendingTriggers && !hasAnyCraftableWork(world, inventory)) {
            nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
            return false;
        }

        nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.DONE && guard.isAlive();
    }

    @Override
    public void start() {
        stage = Stage.GO_TO_CHEST;
        moveTo(guard.getChestPos());
    }

    @Override
    public void stop() {
        guard.getNavigation().stop();
        stage = Stage.DONE;
    }

    @Override
    public void tick() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        if (stage == Stage.GO_TO_CHEST) {
            if (isNear(guard.getChestPos())) {
                stage = Stage.CRAFT;
            } else if (guard.getNavigation().isIdle()) {
                moveTo(guard.getChestPos());
            }
            return;
        }

        if (stage == Stage.CRAFT) {
            runCraftingWorkflow(world);
            stage = Stage.DONE;
        }
    }

    private void refreshDailyLimit(ServerWorld world) {
        long day = world.getTimeOfDay() / 24000L;
        if (day != lastCraftDay) {
            lastCraftDay = day;
            dailyCraftLimit = 4 + guard.getRandom().nextInt(3);
            craftedToday = 0;
        }
    }

    private void runCraftingWorkflow(ServerWorld world) {
        Inventory inventory = guard.getPairedChestInventory(world).orElse(null);
        if (inventory == null) {
            return;
        }

        if (logsTriggerPending) {
            handleLogsTrigger(inventory);
            logsTriggerPending = false;
        }
        if (planksTriggerPending) {
            handlePlanksTrigger(world, inventory);
            planksTriggerPending = false;
        }
        if (cobbleTriggerPending) {
            attemptCraftAndPlaceFurnace(world, inventory);
            cobbleTriggerPending = false;
        }

        if (craftedToday < dailyCraftLimit) {
            craftRandomFromAvailable(world, inventory, 1, 2);
        }

        chestTriggerPending = false;
        tableTriggerPending = false;
        captureObservedCounts(inventory);
        inventory.markDirty();
    }

    private void handleLogsTrigger(Inventory inventory) {
        int totalLogs = countMatching(inventory, this::isBurnableLog);
        int logsForPlanks = totalLogs / 2;
        if (logsForPlanks <= 0) {
            return;
        }
        int consumedLogs = consumeMatching(inventory, this::isBurnableLog, logsForPlanks);
        if (consumedLogs <= 0) {
            return;
        }
        ItemStack rem = guard.insertIntoInventory(inventory, new ItemStack(Items.OAK_PLANKS, consumedLogs * 4));
        if (!rem.isEmpty()) {
            guard.dropStack(rem);
        }
    }

    private void handlePlanksTrigger(ServerWorld world, Inventory inventory) {
        int totalPlanks = countMatching(inventory, this::isPlanks);
        int planksForSticks = (int) Math.floor(totalPlanks * 0.25D);
        if (planksForSticks > 0) {
            int consumedPlanks = consumeMatching(inventory, this::isPlanks, planksForSticks);
            if (consumedPlanks > 0) {
                ItemStack rem = guard.insertIntoInventory(inventory, new ItemStack(Items.STICK, consumedPlanks * 2));
                if (!rem.isEmpty()) {
                    guard.dropStack(rem);
                }
            }
        }

        craftRandomFromAvailable(world, inventory, 1, 3);
    }

    private void attemptCraftAndPlaceFurnace(ServerWorld world, Inventory inventory) {
        if (guard.getPairedFurnacePos() != null && world.getBlockState(guard.getPairedFurnacePos()).isOf(Blocks.FURNACE)) {
            return;
        }

        if (countMatching(inventory, stack -> stack.isOf(Items.COBBLESTONE)) < 8) {
            return;
        }

        if (consumeMatching(inventory, stack -> stack.isOf(Items.COBBLESTONE), 8) < 8) {
            return;
        }

        BlockPos anchor = guard.getCraftingTablePos() != null ? guard.getCraftingTablePos() : guard.getJobPos();
        Optional<BlockPos> placement = findAdjacentPlacement(world, anchor, Blocks.FURNACE.getDefaultState());
        if (placement.isEmpty()) {
            guard.insertIntoInventory(inventory, new ItemStack(Items.COBBLESTONE, 8));
            return;
        }

        BlockPos furnacePos = placement.get();
        world.setBlockState(furnacePos, Blocks.FURNACE.getDefaultState());
        guard.setPairedFurnacePos(furnacePos);
        JobBlockPairingHelper.handleSpecialModifierPlacement(world, furnacePos, world.getBlockState(furnacePos));
    }

    private void craftRandomFromAvailable(ServerWorld world, Inventory inventory, int minCount, int maxCount) {
        if (craftedToday >= dailyCraftLimit) {
            return;
        }

        boolean hasCraftingTable = hasCraftingTable(world);
        int craftAttempts = minCount + guard.getRandom().nextInt(Math.max(1, maxCount - minCount + 1));
        for (int i = 0; i < craftAttempts && craftedToday < dailyCraftLimit; i++) {
            List<Recipe> craftable = getCraftableRecipes(inventory, hasCraftingTable);
            if (craftable.isEmpty()) {
                return;
            }

            Recipe recipe = craftable.get(guard.getRandom().nextInt(craftable.size()));
            if (!consumeRequirements(inventory, recipe.requirements)) {
                continue;
            }

            ItemStack remainder = guard.insertIntoInventory(inventory, recipe.output.copy());
            if (!remainder.isEmpty()) {
                guard.dropStack(remainder);
            }
            craftedToday++;
        }
    }

    private boolean hasAnyCraftableWork(ServerWorld world, Inventory inventory) {
        if (countMatching(inventory, this::isBurnableLog) > 0) return true;
        if (countMatching(inventory, this::isPlanks) > 0) return true;
        return !getCraftableRecipes(inventory, hasCraftingTable(world)).isEmpty();
    }

    private List<Recipe> getCraftableRecipes(Inventory inventory, boolean hasCraftingTable) {
        List<Recipe> recipes = new ArrayList<>();
        for (Recipe recipe : Recipe.values()) {
            if (recipe.requiresCraftingTable && !hasCraftingTable) continue;
            if (hasRequirements(inventory, recipe.requirements)) recipes.add(recipe);
        }
        return recipes;
    }

    private boolean hasRequirements(Inventory inventory, IngredientRequirement[] requirements) {
        for (IngredientRequirement requirement : requirements) {
            if (countMatching(inventory, requirement.matcher) < requirement.count) return false;
        }
        return true;
    }

    private boolean consumeRequirements(Inventory inventory, IngredientRequirement[] requirements) {
        if (!hasRequirements(inventory, requirements)) {
            return false;
        }

        for (IngredientRequirement requirement : requirements) {
            int consumed = consumeMatching(inventory, requirement.matcher, requirement.count);
            if (consumed < requirement.count) {
                return false;
            }
        }
        return true;
    }

    private int countMatching(Inventory inventory, Predicate<ItemStack> matcher) {
        return guard.countMatching(inventory, matcher);
    }

    private int consumeMatching(Inventory inventory, Predicate<ItemStack> matcher, int requestedCount) {
        return guard.consumeMatching(inventory, matcher, requestedCount);
    }

    private void captureObservedCounts(Inventory inventory) {
        observedLogs = countMatching(inventory, this::isBurnableLog);
        observedPlanks = countMatching(inventory, this::isPlanks);
        observedCobblestone = countMatching(inventory, stack -> stack.isOf(Items.COBBLESTONE));
        observedChests = countMatching(inventory, stack -> stack.isOf(Items.CHEST));
        observedCraftingTables = countMatching(inventory, stack -> stack.isOf(Items.CRAFTING_TABLE));
    }

    private boolean hasCraftingTable(ServerWorld world) {
        if (guard.getJobPos() != null && world.getBlockState(guard.getJobPos()).isOf(Blocks.CRAFTING_TABLE)) return true;
        return guard.getCraftingTablePos() != null && world.getBlockState(guard.getCraftingTablePos()).isOf(Blocks.CRAFTING_TABLE);
    }

    private boolean isBurnableLog(ItemStack stack) {
        return stack.isIn(ItemTags.LOGS_THAT_BURN) || stack.isIn(ItemTags.LOGS);
    }

    private boolean isPlanks(ItemStack stack) {
        return stack.isIn(ItemTags.PLANKS);
    }

    private Optional<BlockPos> findAdjacentPlacement(ServerWorld world, BlockPos anchor, BlockState toPlace) {
        if (anchor == null) {
            return Optional.empty();
        }
        for (BlockPos candidate : List.of(anchor.north(), anchor.south(), anchor.east(), anchor.west())) {
            if (!world.getBlockState(candidate).isReplaceable()) continue;
            if (!toPlace.canPlaceAt(world, candidate)) continue;
            if (!world.getBlockState(candidate.up()).isAir()) continue;
            return Optional.of(candidate.toImmutable());
        }
        return Optional.empty();
    }

    private void moveTo(BlockPos target) {
        if (target == null) return;
        guard.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
    }

    private boolean isNear(BlockPos target) {
        return target != null && guard.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private enum Stage { IDLE, GO_TO_CHEST, CRAFT, DONE }

    private record IngredientRequirement(Predicate<ItemStack> matcher, int count) {}

    private enum Recipe {
        CRAFTING_TABLE(new ItemStack(Items.CRAFTING_TABLE), false,
                new IngredientRequirement(stack -> stack.isIn(ItemTags.PLANKS), 4)),
        CHEST(new ItemStack(Items.CHEST), true,
                new IngredientRequirement(stack -> stack.isIn(ItemTags.PLANKS), 8)),
        WOODEN_AXE(new ItemStack(Items.WOODEN_AXE), true,
                new IngredientRequirement(stack -> stack.isIn(ItemTags.PLANKS), 3),
                new IngredientRequirement(stack -> stack.isOf(Items.STICK), 2)),
        STONE_AXE(new ItemStack(Items.STONE_AXE), true,
                new IngredientRequirement(stack -> stack.isOf(Items.COBBLESTONE), 3),
                new IngredientRequirement(stack -> stack.isOf(Items.STICK), 2)),
        IRON_AXE(new ItemStack(Items.IRON_AXE), true,
                new IngredientRequirement(stack -> stack.isOf(Items.IRON_INGOT), 3),
                new IngredientRequirement(stack -> stack.isOf(Items.STICK), 2)),
        GOLDEN_AXE(new ItemStack(Items.GOLDEN_AXE), true,
                new IngredientRequirement(stack -> stack.isOf(Items.GOLD_INGOT), 3),
                new IngredientRequirement(stack -> stack.isOf(Items.STICK), 2)),
        DIAMOND_AXE(new ItemStack(Items.DIAMOND_AXE), true,
                new IngredientRequirement(stack -> stack.isOf(Items.DIAMOND), 3),
                new IngredientRequirement(stack -> stack.isOf(Items.STICK), 2));

        private final ItemStack output;
        private final boolean requiresCraftingTable;
        private final IngredientRequirement[] requirements;

        Recipe(ItemStack output, boolean requiresCraftingTable, IngredientRequirement... requirements) {
            this.output = output;
            this.requiresCraftingTable = requiresCraftingTable;
            this.requirements = requirements;
        }
    }
}
