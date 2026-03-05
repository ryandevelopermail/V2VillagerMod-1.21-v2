package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.villager.LumberjackProfession;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public class LumberjackCraftingGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(LumberjackCraftingGoal.class);
    private static final double MOVE_SPEED = 0.65D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int CHECK_INTERVAL_TICKS = 80;

    private final VillagerEntity villager;

    private BlockPos jobPos;
    private BlockPos chestPos;
    private @Nullable BlockPos craftingTablePos;
    private @Nullable BlockPos pairedFurnacePos;

    private Stage stage = Stage.IDLE;
    private long nextCheckTime;
    private boolean immediateCheckPending;

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

    public LumberjackCraftingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, @Nullable BlockPos craftingTablePos) {
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

    public void setPairedFurnacePos(@Nullable BlockPos furnacePos) {
        this.pairedFurnacePos = furnacePos == null ? null : furnacePos.toImmutable();
    }

    public void requestImmediateCheck(ServerWorld world) {
        refreshDailyLimit(world);
        immediateCheckPending = true;
        nextCheckTime = 0L;
    }

    public void onChestInventoryChanged(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return;
        }

        int currentLogs = countMatching(inventory, this::isBurnableLog);
        int currentPlanks = countMatching(inventory, this::isPlanks);
        int currentCobblestone = countMatching(inventory, stack -> stack.isOf(Items.COBBLESTONE));
        int currentChests = countMatching(inventory, stack -> stack.isOf(Items.CHEST));
        int currentTables = countMatching(inventory, stack -> stack.isOf(Items.CRAFTING_TABLE));

        if (observedLogs >= 0 && currentLogs > observedLogs) {
            logsTriggerPending = true;
        }
        if (observedPlanks >= 0 && currentPlanks > observedPlanks) {
            planksTriggerPending = true;
        }
        if (observedCobblestone >= 0 && currentCobblestone > observedCobblestone) {
            cobbleTriggerPending = true;
        }
        if (observedChests >= 0 && currentChests > observedChests) {
            chestTriggerPending = true;
        }
        if (observedCraftingTables >= 0 && currentTables > observedCraftingTables) {
            tableTriggerPending = true;
        }

        captureObservedCounts(inventory);
    }

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!villager.isAlive() || villager.getVillagerData().getProfession() != LumberjackProfession.LUMBERJACK) {
            return false;
        }
        if (!immediateCheckPending && world.getTime() < nextCheckTime) {
            return false;
        }

        Inventory inventory = getChestInventory(world).orElse(null);
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
            immediateCheckPending = false;
            return false;
        }

        nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
        immediateCheckPending = false;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.DONE && villager.isAlive();
    }

    @Override
    public void start() {
        stage = Stage.GO_TO_CHEST;
        moveTo(chestPos);
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

        if (stage == Stage.GO_TO_CHEST) {
            if (isNear(chestPos)) {
                stage = Stage.CRAFT;
            } else if (villager.getNavigation().isIdle()) {
                moveTo(chestPos);
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
            dailyCraftLimit = 4 + villager.getRandom().nextInt(3);
            craftedToday = 0;
        }
    }

    private void runCraftingWorkflow(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
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

        ItemStack planks = new ItemStack(Items.OAK_PLANKS, consumedLogs * 4);
        ItemStack planksRemainder = insertStack(inventory, planks);
        if (!planksRemainder.isEmpty()) {
            villager.dropStack(planksRemainder);
        }

        LOGGER.info("Lumberjack {} processed log trigger: {} logs converted to planks (furnacePaired={})",
                villager.getUuidAsString(),
                consumedLogs,
                pairedFurnacePos != null);
    }

    private void handlePlanksTrigger(ServerWorld world, Inventory inventory) {
        int totalPlanks = countMatching(inventory, this::isPlanks);
        int planksForSticks = (int) Math.floor(totalPlanks * 0.25D);
        if (planksForSticks > 0) {
            int consumedPlanks = consumeMatching(inventory, this::isPlanks, planksForSticks);
            if (consumedPlanks > 0) {
                ItemStack sticks = new ItemStack(Items.STICK, consumedPlanks * 2);
                ItemStack stickRemainder = insertStack(inventory, sticks);
                if (!stickRemainder.isEmpty()) {
                    villager.dropStack(stickRemainder);
                }
            }
        }

        craftRandomFromAvailable(world, inventory, 1, 3);
        LOGGER.info("Lumberjack {} processed plank trigger: converted {} planks to sticks and ran craft burst",
                villager.getUuidAsString(),
                planksForSticks);
    }

    private void attemptCraftAndPlaceFurnace(ServerWorld world, Inventory inventory) {
        if (pairedFurnacePos != null && world.getBlockState(pairedFurnacePos).isOf(Blocks.FURNACE)) {
            return;
        }

        int cobbleCount = countMatching(inventory, stack -> stack.isOf(Items.COBBLESTONE));
        if (cobbleCount < 8) {
            return;
        }

        int consumedCobble = consumeMatching(inventory, stack -> stack.isOf(Items.COBBLESTONE), 8);
        if (consumedCobble < 8) {
            return;
        }

        BlockPos anchor = craftingTablePos != null ? craftingTablePos : jobPos;
        Optional<BlockPos> placement = findAdjacentPlacement(world, anchor, Blocks.FURNACE.getDefaultState());
        if (placement.isEmpty()) {
            insertStack(inventory, new ItemStack(Items.COBBLESTONE, 8));
            LOGGER.info("Lumberjack {} could not place crafted furnace near {}", villager.getUuidAsString(), anchor.toShortString());
            return;
        }

        BlockPos furnacePos = placement.get();
        world.setBlockState(furnacePos, Blocks.FURNACE.getDefaultState());
        pairedFurnacePos = furnacePos.toImmutable();
        JobBlockPairingHelper.handleSpecialModifierPlacement(world, furnacePos, world.getBlockState(furnacePos));

        LOGGER.info("Lumberjack {} crafted and placed furnace at {} from chest cobblestone trigger",
                villager.getUuidAsString(),
                furnacePos.toShortString());
    }

    private void craftRandomFromAvailable(ServerWorld world, Inventory inventory, int minCount, int maxCount) {
        if (craftedToday >= dailyCraftLimit) {
            return;
        }

        boolean hasCraftingTable = hasCraftingTable(world);
        int craftAttempts = minCount + villager.getRandom().nextInt(Math.max(1, maxCount - minCount + 1));
        for (int i = 0; i < craftAttempts && craftedToday < dailyCraftLimit; i++) {
            List<Recipe> craftable = getCraftableRecipes(inventory, hasCraftingTable);
            if (craftable.isEmpty()) {
                return;
            }

            Recipe recipe = craftable.get(villager.getRandom().nextInt(craftable.size()));
            if (!consumeRequirements(inventory, recipe.requirements)) {
                continue;
            }

            ItemStack remainder = insertStack(inventory, recipe.output.copy());
            if (!remainder.isEmpty()) {
                villager.dropStack(remainder);
            }
            craftedToday++;
            LOGGER.info("Lumberjack {} crafted {}", villager.getUuidAsString(), recipe.output.getItem());
        }
    }

    private boolean hasAnyCraftableWork(ServerWorld world, Inventory inventory) {
        if (countMatching(inventory, this::isBurnableLog) > 0) {
            return true;
        }
        if (countMatching(inventory, this::isPlanks) > 0) {
            return true;
        }
        return !getCraftableRecipes(inventory, hasCraftingTable(world)).isEmpty();
    }

    private List<Recipe> getCraftableRecipes(Inventory inventory, boolean hasCraftingTable) {
        List<Recipe> recipes = new ArrayList<>();
        for (Recipe recipe : Recipe.values()) {
            if (recipe.requiresCraftingTable && !hasCraftingTable) {
                continue;
            }
            if (hasRequirements(inventory, recipe.requirements)) {
                recipes.add(recipe);
            }
        }
        return recipes;
    }

    private boolean hasRequirements(Inventory inventory, IngredientRequirement[] requirements) {
        for (IngredientRequirement requirement : requirements) {
            if (countMatching(inventory, requirement.matcher) < requirement.count) {
                return false;
            }
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
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && matcher.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int consumeMatching(Inventory inventory, Predicate<ItemStack> matcher, int requestedCount) {
        int remaining = requestedCount;
        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !matcher.test(stack)) {
                continue;
            }

            int removed = Math.min(remaining, stack.getCount());
            stack.decrement(removed);
            remaining -= removed;
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
        }

        if (remaining != requestedCount) {
            inventory.markDirty();
        }
        return requestedCount - remaining;
    }

    private void captureObservedCounts(Inventory inventory) {
        observedLogs = countMatching(inventory, this::isBurnableLog);
        observedPlanks = countMatching(inventory, this::isPlanks);
        observedCobblestone = countMatching(inventory, stack -> stack.isOf(Items.COBBLESTONE));
        observedChests = countMatching(inventory, stack -> stack.isOf(Items.CHEST));
        observedCraftingTables = countMatching(inventory, stack -> stack.isOf(Items.CRAFTING_TABLE));
    }

    private boolean hasCraftingTable(ServerWorld world) {
        if (world.getBlockState(jobPos).isOf(Blocks.CRAFTING_TABLE)) {
            return true;
        }
        return craftingTablePos != null && world.getBlockState(craftingTablePos).isOf(Blocks.CRAFTING_TABLE);
    }

    private boolean isBurnableLog(ItemStack stack) {
        return stack.isIn(ItemTags.LOGS_THAT_BURN) || stack.isIn(ItemTags.LOGS);
    }

    private boolean isPlanks(ItemStack stack) {
        return stack.isIn(ItemTags.PLANKS);
    }

    private Optional<Inventory> getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, chestPos, true));
    }

    private Optional<BlockPos> findAdjacentPlacement(ServerWorld world, BlockPos anchor, BlockState toPlace) {
        for (BlockPos candidate : List.of(anchor.north(), anchor.south(), anchor.east(), anchor.west())) {
            if (!world.getBlockState(candidate).isReplaceable()) {
                continue;
            }
            if (!toPlace.canPlaceAt(world, candidate)) {
                continue;
            }
            if (!world.getBlockState(candidate.up()).isAir()) {
                continue;
            }
            return Optional.of(candidate.toImmutable());
        }
        return Optional.empty();
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

    private void moveTo(BlockPos target) {
        villager.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
    }

    private boolean isNear(BlockPos target) {
        return villager.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private enum Stage {
        IDLE,
        GO_TO_CHEST,
        CRAFT,
        DONE
    }

    private record IngredientRequirement(Predicate<ItemStack> matcher, int count) {
    }

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
