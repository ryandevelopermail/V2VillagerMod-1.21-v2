package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.function.BooleanSupplier;

public class LumberjackGuardCraftingGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(LumberjackGuardCraftingGoal.class);
    // Per-day cap for axe/tool-upgrade outputs (does not apply to V1 chest / V2 table promotion).
    private static final int DAILY_CRAFT_LIMIT = 4;
    // Higher per-day cap specifically for V1 chest and V2 crafting table promotion outputs.
    // A village can need many chests and tables in quick succession; throttling them at 4/day
    // would stretch promotion over many real-world MC days.
    private static final int DAILY_PROMOTION_CRAFT_LIMIT = 16;
    private static final int BOOTSTRAP_CHEST_PLANK_REQUIREMENT = 8;
    private static final int BOOTSTRAP_AXE_PLANK_REQUIREMENT = 3;
    private static final int BOOTSTRAP_AXE_STICK_REQUIREMENT = 2;
    private static final long UNPAIRED_RECOVERY_THRESHOLD_TICKS = 20L * 60L * 3L;
    private static final long UNREADY_PROMOTION_WARNING_THRESHOLD_TICKS = 20L * 60L * 2L;
    private static final int FORCED_RECOVERY_CHEST_SCAN_RADIUS = 2;

    private final LumberjackGuardEntity guard;
    private long lastCraftDay = -1L;
    private int craftedToday;
    private int promotionCraftedToday;
    private boolean basePairingEstablished;
    private long unpairedSinceTick = Long.MIN_VALUE;
    private long promotionDemandUnreadySinceTick = Long.MIN_VALUE;
    private boolean promotionDemandUnreadyWarningEmitted;
    private int recentPlacementFailureCount;

    public LumberjackGuardCraftingGoal(LumberjackGuardEntity guard) {
        this.guard = guard;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    static boolean craftBootstrapChestAndAttemptPlacementIfNeeded(
            boolean shouldCraftBootstrapChest,
            BooleanSupplier craftChest,
            BooleanSupplier attemptPlacement
    ) {
        if (!shouldCraftBootstrapChest || !craftChest.getAsBoolean()) {
            return false;
        }
        attemptPlacement.getAsBoolean();
        return true;
    }

    @Override
    public boolean canStart() {
        if (!(this.guard.getWorld() instanceof ServerWorld world)) {
            return false;
        }

        refreshDailyLimit(world);
        if (this.guard.getWorkflowStage() != LumberjackGuardEntity.WorkflowStage.CRAFTING || !this.guard.isAlive()) {
            return false;
        }
        boolean bootstrapSession = isBootstrapSession();
        boolean hasInputs = hasCraftingInputs(world);
        if (!hasInputs) {
            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.DEPOSITING);
            return false;
        }
        if (!bootstrapSession && !world.isDay()) {
            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.DEPOSITING);
            return false;
        }
        if (!bootstrapSession && craftedToday >= DAILY_CRAFT_LIMIT && promotionCraftedToday >= DAILY_PROMOTION_CRAFT_LIMIT) {
            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.DEPOSITING);
            return false;
        }
        return true;
    }

    private boolean hasCraftingInputs(ServerWorld world) {
        Inventory chestInventory = resolveChestInventory(world);
        return hasAnyCraftingInput(this.guard.getGatheredStackBuffer()) || hasAnyCraftingInput(chestInventory);
    }

    private boolean hasAnyCraftingInput(List<ItemStack> stacks) {
        return countMatching(stacks, this::isCraftingInput) > 0;
    }

    private boolean hasAnyCraftingInput(Inventory inventory) {
        return countMatching(inventory, this::isCraftingInput) > 0;
    }

    private boolean isCraftingInput(ItemStack stack) {
        return stack.isIn(ItemTags.LOGS)
                || stack.isIn(ItemTags.PLANKS)
                || stack.isOf(Items.STICK)
                || stack.isOf(Items.CHEST)
                || stack.isOf(Items.WOODEN_AXE);
    }

    @Override
    public boolean shouldContinue() {
        return this.guard.getWorkflowStage() == LumberjackGuardEntity.WorkflowStage.CRAFTING;
    }

    @Override
    public void tick() {
        if (!(this.guard.getWorld() instanceof ServerWorld world)) {
            return;
        }

        BlockPos tablePos = this.guard.getPairedCraftingTablePos();
        if (tablePos == null) {
            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.DEPOSITING);
            return;
        }

        if (this.guard.squaredDistanceTo(Vec3d.ofCenter(tablePos)) > 9.0D) {
            this.guard.getNavigation().startMovingTo(tablePos.getX() + 0.5D, tablePos.getY(), tablePos.getZ() + 0.5D, 0.8D);
            return;
        }

        if (!basePairingEstablished) {
            basePairingEstablished = tryPlaceAndBindChest(world);
        }

        updatePairingRecoveryWindow(world, tablePos);

        Inventory chestInventory = resolveChestInventory(world);
        performWoodConversion(chestInventory);
        // craftPriorityOutputs now returns the number of promotion items crafted this visit.
        // Non-promotion crafts (axe upgrades etc.) still count against the regular daily limit.
        int promotionCraftsThisVisit = craftPriorityOutputs(world, chestInventory, isBasePairingReadyForDemand());

        if (promotionCraftsThisVisit < 0) {
            // Negative sentinel: a non-promotion craft happened (e.g. axe upgrade).
            craftedToday++;
        } else {
            promotionCraftedToday += promotionCraftsThisVisit;
        }

        this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.DEPOSITING);
    }

    private void updatePairingRecoveryWindow(ServerWorld world, BlockPos tablePos) {
        if (this.guard.getPairedChestPos() != null) {
            unpairedSinceTick = Long.MIN_VALUE;
            recentPlacementFailureCount = 0;
            return;
        }

        long now = world.getTime();
        if (unpairedSinceTick == Long.MIN_VALUE) {
            unpairedSinceTick = now;
            return;
        }

        if (now - unpairedSinceTick < UNPAIRED_RECOVERY_THRESHOLD_TICKS) {
            return;
        }

        Inventory chestInventory = resolveChestInventory(world);
        int chestOnHand = countByItem(chestInventory, Items.CHEST) + countByItem(this.guard.getGatheredStackBuffer(), Items.CHEST);
        boolean recovered = tryPlaceAndBindChestForRecovery(world, this.guard, chestInventory, FORCED_RECOVERY_CHEST_SCAN_RADIUS);
        LOGGER.warn("lumberjack_pairing_recovery guard_uuid={} table_pos={} chest_on_hand={} recent_placement_failures={} unpaired_ticks={} forced_scan_radius={} recovered={}",
                this.guard.getUuidAsString(),
                tablePos.toShortString(),
                chestOnHand,
                recentPlacementFailureCount,
                now - unpairedSinceTick,
                FORCED_RECOVERY_CHEST_SCAN_RADIUS,
                recovered);
        unpairedSinceTick = now;
    }

    @Override
    public void stop() {
        this.guard.getNavigation().stop();
    }

    private boolean performWoodConversion(Inventory chestInventory) {
        boolean converted = false;

        int availableLogs = countMatching(chestInventory, stack -> stack.isIn(ItemTags.LOGS))
                + countMatching(this.guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.LOGS));
        int logsToConvert;
        if (isBootstrapSession()) {
            int availablePlanks = countMatching(chestInventory, stack -> stack.isIn(ItemTags.PLANKS))
                    + countMatching(this.guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.PLANKS));
            int availableSticks = countByItem(chestInventory, Items.STICK)
                    + countByItem(this.guard.getGatheredStackBuffer(), Items.STICK);

            int requiredPlanksForChest = shouldCraftBootstrapChest(chestInventory) ? BOOTSTRAP_CHEST_PLANK_REQUIREMENT : 0;
            int requiredPlanksForAxe = shouldCraftBootstrapAxe(chestInventory) ? BOOTSTRAP_AXE_PLANK_REQUIREMENT : 0;
            int requiredSticksForAxe = shouldCraftBootstrapAxe(chestInventory) ? BOOTSTRAP_AXE_STICK_REQUIREMENT : 0;

            int stickDeficit = Math.max(0, requiredSticksForAxe - availableSticks);
            int additionalPlanksNeededForStickCrafting = stickDeficit > 0 ? 2 : 0; // 2 planks -> 4 sticks
            int totalRequiredPlanks = requiredPlanksForChest + requiredPlanksForAxe + additionalPlanksNeededForStickCrafting;
            int plankDeficit = Math.max(0, totalRequiredPlanks - availablePlanks);
            logsToConvert = Math.min(availableLogs, (plankDeficit + 3) / 4);
        } else {
            logsToConvert = availableLogs / 2;
        }

        if (logsToConvert > 0 && consumeMatching(chestInventory, this.guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.LOGS), logsToConvert)) {
            addToBuffer(new ItemStack(Items.OAK_PLANKS, logsToConvert * 4));
            converted = true;
        }

        int availablePlanks = countMatching(chestInventory, stack -> stack.isIn(ItemTags.PLANKS))
                + countMatching(this.guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.PLANKS));
        int planksToConvert;
        if (isBootstrapSession()) {
            int chestPlankReserve = shouldCraftBootstrapChest(chestInventory) ? BOOTSTRAP_CHEST_PLANK_REQUIREMENT : 0;
            int requiredSticks = shouldCraftBootstrapAxe(chestInventory) ? BOOTSTRAP_AXE_STICK_REQUIREMENT : 0;
            int availableSticks = countByItem(chestInventory, Items.STICK) + countByItem(this.guard.getGatheredStackBuffer(), Items.STICK);
            int stickDeficit = Math.max(0, requiredSticks - availableSticks);

            int planksAvailableAfterReserve = Math.max(0, availablePlanks - chestPlankReserve);
            int planksNeededForSticks = stickDeficit > 0 ? 2 : 0; // 2 planks -> 4 sticks
            planksToConvert = Math.min(planksAvailableAfterReserve, planksNeededForSticks);
        } else {
            // Cap stick production: only convert planks to sticks up to the stick target cap.
            // Sticks are a byproduct, not the primary output — planks should be the dominant product.
            int stickTarget = 64;
            int currentSticks = countByItem(chestInventory, Items.STICK)
                    + countByItem(this.guard.getGatheredStackBuffer(), Items.STICK);
            int sticksNeeded = Math.max(0, stickTarget - currentSticks);
            // 2 planks → 4 sticks, so planks needed = ceil(sticksNeeded / 4) * 2 → simpler: sticksNeeded / 2
            int planksNeededForStickTarget = (sticksNeeded + 3) / 4 * 2; // round up to plank pairs
            planksToConvert = Math.min(availablePlanks / 2, planksNeededForStickTarget);
        }

        if (planksToConvert > 0 && consumeMatching(chestInventory, this.guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.PLANKS), planksToConvert)) {
            addToBuffer(new ItemStack(Items.STICK, planksToConvert * 2));
            converted = true;
        }

        if (chestInventory != null) {
            chestInventory.markDirty();
        }

        return converted;
    }

    /**
     * Craft priority outputs for this visit.
     *
     * Returns:
     *   > 0  — number of promotion items (V1 chests / V2 crafting tables) crafted this visit
     *   0    — nothing crafted this visit (no demand or no materials)
     *  -1    — a non-promotion craft happened (axe upgrade etc.); counts against regular daily limit
     */
    private int craftPriorityOutputs(ServerWorld world, Inventory chestInventory, boolean demandEnabled) {
        if (isBootstrapSession()) {
            boolean meaningfulAction = false;

            boolean shouldAttemptChestPlacement = this.guard.getPairedChestPos() == null
                    && hasChestOnHand(chestInventory, this.guard.getGatheredStackBuffer());
            if (shouldAttemptChestPlacement) {
                boolean placed = tryPlaceAndBindChest(world);
                if (placed) {
                    basePairingEstablished = true;
                }
                meaningfulAction = true;
            } else if (this.guard.getPairedChestPos() == null && shouldCraftBootstrapChest(chestInventory)) {
                boolean craftedChest = craftIfPossible(chestInventory, BOOTSTRAP_CHEST_PLANK_REQUIREMENT, 0, Items.CHEST);
                if (craftedChest) {
                    LOGGER.debug("LumberjackCrafting {}: crafted bootstrap chest; attempting immediate placement",
                            this.guard.getUuidAsString());
                    meaningfulAction = true;
                    boolean placed = tryPlaceAndBindChest(world);
                    if (placed) {
                        basePairingEstablished = true;
                    }
                } else {
                    LOGGER.debug("LumberjackCrafting {}: bootstrap chest craft fallback failed (need {} planks)",
                            this.guard.getUuidAsString(), BOOTSTRAP_CHEST_PLANK_REQUIREMENT);
                }
            }

            if (shouldCraftBootstrapAxe(chestInventory) && craftIfPossible(chestInventory, BOOTSTRAP_AXE_PLANK_REQUIREMENT, BOOTSTRAP_AXE_STICK_REQUIREMENT, Items.WOODEN_AXE)) {
                meaningfulAction = true;
            }

            equipBootstrapAxeFromSupplies(chestInventory);
            // Bootstrap crafts don't count against either daily limit.
            return 0;
        }

        if (!demandEnabled || !isBasePairingReadyForDemand()) {
            maybeWarnOnExtendedPromotionDemandWithoutPairing(world, chestInventory);
            return 0;
        }
        promotionDemandUnreadySinceTick = Long.MIN_VALUE;
        promotionDemandUnreadyWarningEmitted = false;

        // Batch-craft all available promotion demands in one visit (up to the promotion daily limit).
        // Previously only one item was crafted per visit, causing promotion to stretch over many chop
        // cycles (3-8 min each) — now we drain as many demands as materials + limits allow.
        int promotionCraftsThisVisit = 0;
        int remainingPromotionBudget = DAILY_PROMOTION_CRAFT_LIMIT - promotionCraftedToday;
        while (remainingPromotionBudget > promotionCraftsThisVisit) {
            LumberjackChestTriggerController.UpgradeDemand demand = LumberjackChestTriggerController.resolveNextUpgradeDemand(world, this.guard);
            if (demand == null) {
                break;
            }
            boolean isPromotionDemand = demand.equals(LumberjackChestTriggerController.UpgradeDemand.v1Chest())
                    || demand.equals(LumberjackChestTriggerController.UpgradeDemand.v2CraftingTable());
            if (!isPromotionDemand && craftedToday >= DAILY_CRAFT_LIMIT) {
                // Non-promotion demand and regular daily limit already reached — stop.
                break;
            }
            int outputOnHand = countByItem(chestInventory, demand.outputItem())
                    + countByItem(this.guard.getGatheredStackBuffer(), demand.outputItem());
            if (outputOnHand >= demand.outputCount()) {
                // Already have this output stocked; run upgrade pass and try next demand.
                LumberjackChestTriggerController.runImmediateVillageUpgradePass(world, this.guard);
                break;
            }
            if (!craftIfPossible(chestInventory, demand.planksCost(), demand.stickCost(), demand.outputItem(), demand.outputCount())) {
                // Not enough materials for this demand right now — stop.
                break;
            }
            stashCraftedOutput(chestInventory, demand.outputItem(), demand.outputCount());
            LumberjackChestTriggerController.runImmediateVillageUpgradePass(world, this.guard);
            if (isPromotionDemand) {
                promotionCraftsThisVisit++;
            } else {
                // Non-promotion craft: signal to tick() that it should count against regular limit.
                return -1;
            }
        }

        return promotionCraftsThisVisit;
    }

    private void maybeWarnOnExtendedPromotionDemandWithoutPairing(ServerWorld world, Inventory chestInventory) {
        LumberjackChestTriggerController.UpgradeDemand demand = LumberjackChestTriggerController.resolveNextUpgradeDemand(world, this.guard);
        if (demand == null) {
            promotionDemandUnreadySinceTick = Long.MIN_VALUE;
            promotionDemandUnreadyWarningEmitted = false;
            return;
        }

        boolean promotionDemand = demand.equals(LumberjackChestTriggerController.UpgradeDemand.v1Chest())
                || demand.equals(LumberjackChestTriggerController.UpgradeDemand.v2CraftingTable());
        if (!promotionDemand) {
            promotionDemandUnreadySinceTick = Long.MIN_VALUE;
            promotionDemandUnreadyWarningEmitted = false;
            return;
        }

        long now = world.getTime();
        if (promotionDemandUnreadySinceTick == Long.MIN_VALUE) {
            promotionDemandUnreadySinceTick = now;
            return;
        }
        if (promotionDemandUnreadyWarningEmitted || now - promotionDemandUnreadySinceTick < UNREADY_PROMOTION_WARNING_THRESHOLD_TICKS) {
            return;
        }

        BlockPos tablePos = this.guard.getPairedCraftingTablePos();
        int chestOnHand = countByItem(chestInventory, Items.CHEST) + countByItem(this.guard.getGatheredStackBuffer(), Items.CHEST);
        LOGGER.warn("lumberjack_pairing_not_ready_for_promotion guard_uuid={} demand_output={} table_pos={} chest_on_hand={} recent_placement_failures={} demand_unready_ticks={}",
                this.guard.getUuidAsString(),
                demand.outputItem(),
                tablePos == null ? "none" : tablePos.toShortString(),
                chestOnHand,
                recentPlacementFailureCount,
                now - promotionDemandUnreadySinceTick);
        promotionDemandUnreadyWarningEmitted = true;
    }

    private boolean isBootstrapSession() {
        return this.guard.getPairedChestPos() == null;
    }

    private boolean isBasePairingReadyForDemand() {
        return basePairingEstablished && !isBootstrapSession();
    }

    private boolean tryPlaceAndBindChest(ServerWorld world) {
        boolean placed = tryPlaceAndBindChestForRecovery(world, this.guard, resolveChestInventory(world));
        if (placed) {
            recentPlacementFailureCount = 0;
        } else if (this.guard.getPairedChestPos() == null) {
            recentPlacementFailureCount++;
        }
        return placed;
    }

    public static boolean tryPlaceAndBindChestForRecovery(ServerWorld world, LumberjackGuardEntity guard, Inventory chestInventory) {
        return tryPlaceAndBindChestForRecovery(world, guard, chestInventory, 1);
    }

    public static boolean tryPlaceAndBindChestForRecovery(ServerWorld world,
                                                          LumberjackGuardEntity guard,
                                                          Inventory chestInventory,
                                                          int searchRadius) {
        BlockPos pairedChestPos = guard.getPairedChestPos();
        if (pairedChestPos != null) {
            return resolveChestInventoryForGuard(world, guard) != null;
        }

        BlockPos tablePos = guard.getPairedCraftingTablePos();
        if (tablePos == null) {
            LOGGER.debug("LumberjackCrafting {}: chest placement skipped — no paired crafting table",
                    guard.getUuidAsString());
            return false;
        }

        ItemStack chestStack = takeOneChestForPlacement(guard, chestInventory);
        if (chestStack.isEmpty()) {
            LOGGER.debug("LumberjackCrafting {}: chest placement skipped — no chest item in chest inventory/buffer",
                    guard.getUuidAsString());
            return false;
        }

        boolean hadBlockedCandidates = false;
        boolean hadUnsupportedCandidates = false;
        int boundedRadius = Math.max(1, searchRadius);
        for (BlockPos candidate : iteratePlacementCandidates(tablePos, boundedRadius)) {
            BlockPos below = candidate.down();
            if (!world.getBlockState(candidate).isAir()) {
                hadBlockedCandidates = true;
                continue;
            }
            if (!world.getBlockState(below).isSolidBlock(world, below)) {
                hadUnsupportedCandidates = true;
                continue;
            }
            if (!world.setBlockState(candidate, Blocks.CHEST.getDefaultState())) {
                hadBlockedCandidates = true;
                continue;
            }

            guard.setPairedChestPos(candidate);
            guard.setBootstrapComplete(false);
            LOGGER.info("lumberjack_chest_pairing_success guard_uuid={} table_pos={} chest_pos={} scan_radius={}",
                    guard.getUuidAsString(), tablePos.toShortString(), candidate.toShortString(), boundedRadius);
            return true;
        }

        if (hadBlockedCandidates && hadUnsupportedCandidates) {
            LOGGER.debug("LumberjackCrafting {}: chest placement failed — candidates blocked or unsupported around table {} (scanRadius={})",
                    guard.getUuidAsString(), tablePos.toShortString(), boundedRadius);
        } else if (hadBlockedCandidates) {
            LOGGER.debug("LumberjackCrafting {}: chest placement failed — candidates blocked around table {} (scanRadius={})",
                    guard.getUuidAsString(), tablePos.toShortString(), boundedRadius);
        } else if (hadUnsupportedCandidates) {
            LOGGER.debug("LumberjackCrafting {}: chest placement failed — candidates lacked solid support around table {} (scanRadius={})",
                    guard.getUuidAsString(), tablePos.toShortString(), boundedRadius);
        } else {
            LOGGER.debug("LumberjackCrafting {}: chest placement failed — no valid candidate around table {} (scanRadius={})",
                    guard.getUuidAsString(), tablePos.toShortString(), boundedRadius);
        }

        if (chestInventory != null) {
            ItemStack remainder = insertIntoInventoryStatic(chestInventory, chestStack);
            if (remainder.isEmpty()) {
                chestInventory.markDirty();
                LOGGER.debug("LumberjackCrafting {}: returned unplaced chest item to paired inventory",
                        guard.getUuidAsString());
                return false;
            }
            chestStack = remainder;
        }

        addToBufferStatic(guard, chestStack);
        LOGGER.debug("LumberjackCrafting {}: buffered unplaced chest item after placement failure",
                guard.getUuidAsString());
        return false;
    }

    private static List<BlockPos> iteratePlacementCandidates(BlockPos tablePos, int searchRadius) {
        if (searchRadius <= 1) {
            return List.of(
                    tablePos.offset(Direction.NORTH),
                    tablePos.offset(Direction.SOUTH),
                    tablePos.offset(Direction.WEST),
                    tablePos.offset(Direction.EAST)
            );
        }

        List<BlockPos> candidates = new java.util.ArrayList<>();
        for (Direction direction : Direction.Type.HORIZONTAL) {
            candidates.add(tablePos.offset(direction));
        }
        for (int radius = 2; radius <= searchRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    candidates.add(tablePos.add(dx, 0, dz));
                }
            }
        }
        return candidates;
    }

    public static boolean ensureChestCraftingSuppliesForRecovery(LumberjackGuardEntity guard, Inventory chestInventory) {
        int availableLogs = countMatchingStatic(chestInventory, stack -> stack.isIn(ItemTags.LOGS))
                + countMatchingStatic(guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.LOGS));
        int logsToConvert = availableLogs / 2;
        if (logsToConvert <= 0) {
            return false;
        }

        if (!consumeMatchingStatic(chestInventory, guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.LOGS), logsToConvert)) {
            return false;
        }

        addToBufferStatic(guard, new ItemStack(Items.OAK_PLANKS, logsToConvert * 4));
        return true;
    }

    public static boolean craftChestForRecovery(LumberjackGuardEntity guard, Inventory chestInventory) {
        int chestCount = countByItemStatic(chestInventory, Items.CHEST) + countByItemStatic(guard.getGatheredStackBuffer(), Items.CHEST);
        if (chestCount > 0) {
            return true;
        }

        int planks = countMatchingStatic(chestInventory, stack -> stack.isIn(ItemTags.PLANKS))
                + countMatchingStatic(guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.PLANKS));
        if (planks < BOOTSTRAP_CHEST_PLANK_REQUIREMENT) {
            return false;
        }

        if (!consumeMatchingStatic(chestInventory, guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.PLANKS), BOOTSTRAP_CHEST_PLANK_REQUIREMENT)) {
            return false;
        }

        addToBufferStatic(guard, new ItemStack(Items.CHEST, 1));
        return true;
    }

    public static boolean craftSingleUpgradeDemandOutputIfPossible(LumberjackGuardEntity guard,
                                                                    Inventory chestInventory,
                                                                    LumberjackChestTriggerController.UpgradeDemand demand) {
        return craftSingleUpgradeDemandOutputIfPossible(guard.getGatheredStackBuffer(), chestInventory, demand);
    }

    static boolean craftSingleUpgradeDemandOutputIfPossible(List<ItemStack> gatheredStackBuffer,
                                                            Inventory chestInventory,
                                                            LumberjackChestTriggerController.UpgradeDemand demand) {
        if (demand == null) {
            return false;
        }

        int planks = countMatchingStatic(chestInventory, stack -> stack.isIn(ItemTags.PLANKS))
                + countMatchingStatic(gatheredStackBuffer, stack -> stack.isIn(ItemTags.PLANKS));
        int sticks = countByItemStatic(chestInventory, Items.STICK) + countByItemStatic(gatheredStackBuffer, Items.STICK);
        if (sticks < demand.stickCost()) {
            return false;
        }
        if (planks < demand.planksCost()) {
            return false;
        }

        boolean consumedPlanks = consumeMatchingStatic(chestInventory, gatheredStackBuffer, stack -> stack.isIn(ItemTags.PLANKS), demand.planksCost());
        boolean consumedSticks = demand.stickCost() == 0 || consumeByItemStatic(chestInventory, gatheredStackBuffer, Items.STICK, demand.stickCost());
        if (!consumedPlanks || !consumedSticks) {
            return false;
        }

        addToBufferStatic(gatheredStackBuffer, new ItemStack(demand.outputItem(), demand.outputCount()));
        return true;
    }

    public static Inventory resolveChestInventoryForGuard(ServerWorld world, LumberjackGuardEntity guard) {
        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos == null || !world.getBlockState(chestPos).isOf(Blocks.CHEST)) {
            return null;
        }
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }
        return ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
    }

    private static ItemStack takeOneChestForPlacement(LumberjackGuardEntity guard, Inventory chestInventory) {
        if (chestInventory != null) {
            for (int slot = 0; slot < chestInventory.size(); slot++) {
                ItemStack stack = chestInventory.getStack(slot);
                if (stack.isEmpty() || !stack.isOf(Items.CHEST)) {
                    continue;
                }

                ItemStack split = stack.split(1);
                if (stack.isEmpty()) {
                    chestInventory.setStack(slot, ItemStack.EMPTY);
                }
                chestInventory.markDirty();
                return split;
            }
        }

        return takeOneByItemStatic(guard.getGatheredStackBuffer(), Items.CHEST);
    }

    private boolean shouldCraftBootstrapAxe(Inventory chestInventory) {
        int equippedAxes = this.guard.getMainHandStack().isOf(Items.WOODEN_AXE) ? 1 : 0;
        int axesOnHand = equippedAxes + countByItem(chestInventory, Items.WOODEN_AXE) + countByItem(this.guard.getGatheredStackBuffer(), Items.WOODEN_AXE);
        return axesOnHand < 1;
    }

    private boolean shouldCraftBootstrapChest(Inventory chestInventory) {
        int chestsOnHand = countByItem(chestInventory, Items.CHEST) + countByItem(this.guard.getGatheredStackBuffer(), Items.CHEST);
        return chestsOnHand < 1;
    }

    private boolean hasChestOnHand(Inventory chestInventory, List<ItemStack> guardBuffer) {
        return countByItem(chestInventory, Items.CHEST) + countByItem(guardBuffer, Items.CHEST) > 0;
    }

    private void equipBootstrapAxeFromSupplies(Inventory chestInventory) {
        if (this.guard.getMainHandStack().isOf(Items.WOODEN_AXE)) {
            return;
        }

        ItemStack bufferAxe = takeOneByItem(this.guard.getGatheredStackBuffer(), Items.WOODEN_AXE);
        if (!bufferAxe.isEmpty()) {
            this.guard.equipStack(EquipmentSlot.MAINHAND, bufferAxe);
            return;
        }

        ItemStack chestAxe = takeOneByItem(chestInventory, Items.WOODEN_AXE);
        if (!chestAxe.isEmpty()) {
            this.guard.equipStack(EquipmentSlot.MAINHAND, chestAxe);
            chestInventory.markDirty();
        }
    }

    private void stashCraftedOutput(Inventory chestInventory, Item item, int expectedCount) {
        if (!isBasePairingReadyForDemand()) {
            return;
        }

        ItemStack craftedStack = takeUpToByItem(this.guard.getGatheredStackBuffer(), item, expectedCount);
        if (craftedStack.isEmpty()) {
            return;
        }

        if (chestInventory != null) {
            ItemStack remaining = insertIntoInventory(chestInventory, craftedStack);
            if (remaining.isEmpty()) {
                chestInventory.markDirty();
                return;
            }
            craftedStack = remaining;
        }

        addToBuffer(craftedStack);
    }

    private boolean craftIfPossible(Inventory chestInventory, int planksCost, int stickCost, Item output) {
        return craftIfPossible(chestInventory, planksCost, stickCost, output, 1);
    }

    private boolean craftIfPossible(Inventory chestInventory, int planksCost, int stickCost, Item output, int outputCount) {
        int planks = countMatching(chestInventory, stack -> stack.isIn(ItemTags.PLANKS))
                + countMatching(this.guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.PLANKS));
        int sticks = countByItem(chestInventory, Items.STICK) + countByItem(this.guard.getGatheredStackBuffer(), Items.STICK);
        if (planks < planksCost || sticks < stickCost) {
            return false;
        }

        boolean consumedPlanks = consumeMatching(chestInventory, this.guard.getGatheredStackBuffer(), stack -> stack.isIn(ItemTags.PLANKS), planksCost);
        boolean consumedSticks = stickCost == 0 || consumeByItem(chestInventory, this.guard.getGatheredStackBuffer(), Items.STICK, stickCost);
        if (consumedPlanks && consumedSticks) {
            addToBuffer(new ItemStack(output, outputCount));
            return true;
        }

        return false;
    }

    private ItemStack insertIntoInventory(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.size() && !remaining.isEmpty(); slot++) {
            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                inventory.setStack(slot, remaining);
                remaining = ItemStack.EMPTY;
            } else if (ItemStack.areItemsAndComponentsEqual(existing, remaining)) {
                int transfer = Math.min(existing.getMaxCount() - existing.getCount(), remaining.getCount());
                if (transfer > 0) {
                    existing.increment(transfer);
                    remaining.decrement(transfer);
                    inventory.setStack(slot, existing);
                }
            }
        }
        return remaining;
    }

    private ItemStack takeOneByItem(List<ItemStack> stacks, Item item) {
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (stack.isEmpty() || !stack.isOf(item)) {
                continue;
            }

            ItemStack split = stack.split(1);
            if (stack.isEmpty()) {
                stacks.set(i, ItemStack.EMPTY);
            }
            stacks.removeIf(ItemStack::isEmpty);
            return split;
        }

        return ItemStack.EMPTY;
    }

    private ItemStack takeUpToByItem(List<ItemStack> stacks, Item item, int amount) {
        if (amount <= 0) {
            return ItemStack.EMPTY;
        }
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (stack.isEmpty() || !stack.isOf(item)) {
                continue;
            }

            int splitAmount = Math.min(amount, stack.getCount());
            ItemStack split = stack.split(splitAmount);
            if (stack.isEmpty()) {
                stacks.set(i, ItemStack.EMPTY);
            }
            stacks.removeIf(ItemStack::isEmpty);
            return split;
        }
        return ItemStack.EMPTY;
    }

    private ItemStack takeOneByItem(Inventory inventory, Item item) {
        if (inventory == null) {
            return ItemStack.EMPTY;
        }

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !stack.isOf(item)) {
                continue;
            }

            ItemStack split = stack.split(1);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            return split;
        }

        return ItemStack.EMPTY;
    }

    private Inventory resolveChestInventory(ServerWorld world) {
        return resolveChestInventoryForGuard(world, this.guard);
    }

    private static ItemStack insertIntoInventoryStatic(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.size() && !remaining.isEmpty(); slot++) {
            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                inventory.setStack(slot, remaining);
                remaining = ItemStack.EMPTY;
            } else if (ItemStack.areItemsAndComponentsEqual(existing, remaining)) {
                int transfer = Math.min(existing.getMaxCount() - existing.getCount(), remaining.getCount());
                if (transfer > 0) {
                    existing.increment(transfer);
                    remaining.decrement(transfer);
                    inventory.setStack(slot, existing);
                }
            }
        }
        return remaining;
    }

    private static void addToBufferStatic(LumberjackGuardEntity guard, ItemStack incoming) {
        addToBufferStatic(guard.getGatheredStackBuffer(), incoming);
    }

    private static void addToBufferStatic(List<ItemStack> buffer, ItemStack incoming) {
        for (ItemStack existing : buffer) {
            if (ItemStack.areItemsAndComponentsEqual(existing, incoming) && existing.getCount() < existing.getMaxCount()) {
                int move = Math.min(existing.getMaxCount() - existing.getCount(), incoming.getCount());
                existing.increment(move);
                incoming.decrement(move);
                if (incoming.isEmpty()) {
                    return;
                }
            }
        }

        if (!incoming.isEmpty()) {
            buffer.add(incoming);
        }
    }

    private static int countMatchingStatic(Inventory inventory, java.util.function.Predicate<ItemStack> predicate) {
        if (inventory == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && predicate.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countMatchingStatic(List<ItemStack> stacks, java.util.function.Predicate<ItemStack> predicate) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty() && predicate.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countByItemStatic(Inventory inventory, Item item) {
        return countMatchingStatic(inventory, stack -> stack.isOf(item));
    }

    private static int countByItemStatic(List<ItemStack> stacks, Item item) {
        return countMatchingStatic(stacks, stack -> stack.isOf(item));
    }

    private static boolean consumeMatchingStatic(Inventory inventory, List<ItemStack> buffer, java.util.function.Predicate<ItemStack> predicate, int amount) {
        int remaining = consumeFromInventoryStatic(inventory, predicate, amount);
        remaining = consumeFromBufferStatic(buffer, predicate, remaining);
        return remaining <= 0;
    }

    private static boolean consumeByItemStatic(Inventory inventory, List<ItemStack> buffer, Item item, int amount) {
        return consumeMatchingStatic(inventory, buffer, stack -> stack.isOf(item), amount);
    }

    private static int consumeFromInventoryStatic(Inventory inventory, java.util.function.Predicate<ItemStack> predicate, int amount) {
        if (inventory == null) {
            return amount;
        }

        int remaining = amount;
        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !predicate.test(stack)) {
                continue;
            }

            int moved = Math.min(stack.getCount(), remaining);
            stack.decrement(moved);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            remaining -= moved;
        }
        return remaining;
    }

    private static int consumeFromBufferStatic(List<ItemStack> buffer, java.util.function.Predicate<ItemStack> predicate, int amount) {
        int remaining = amount;
        for (int i = 0; i < buffer.size() && remaining > 0; i++) {
            ItemStack stack = buffer.get(i);
            if (stack.isEmpty() || !predicate.test(stack)) {
                continue;
            }

            int moved = Math.min(stack.getCount(), remaining);
            stack.decrement(moved);
            if (stack.isEmpty()) {
                buffer.set(i, ItemStack.EMPTY);
            }
            remaining -= moved;
        }

        buffer.removeIf(ItemStack::isEmpty);
        return remaining;
    }

    private static ItemStack takeOneByItemStatic(List<ItemStack> stacks, Item item) {
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (stack.isEmpty() || !stack.isOf(item)) {
                continue;
            }

            ItemStack split = stack.split(1);
            if (stack.isEmpty()) {
                stacks.set(i, ItemStack.EMPTY);
            }
            stacks.removeIf(ItemStack::isEmpty);
            return split;
        }

        return ItemStack.EMPTY;
    }

    private void addToBuffer(ItemStack incoming) {
        List<ItemStack> buffer = this.guard.getGatheredStackBuffer();
        for (ItemStack existing : buffer) {
            if (ItemStack.areItemsAndComponentsEqual(existing, incoming) && existing.getCount() < existing.getMaxCount()) {
                int move = Math.min(existing.getMaxCount() - existing.getCount(), incoming.getCount());
                existing.increment(move);
                incoming.decrement(move);
                if (incoming.isEmpty()) {
                    return;
                }
            }
        }

        if (!incoming.isEmpty()) {
            buffer.add(incoming);
        }
    }

    private int countMatching(Inventory inventory, java.util.function.Predicate<ItemStack> predicate) {
        if (inventory == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && predicate.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countMatching(List<ItemStack> stacks, java.util.function.Predicate<ItemStack> predicate) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty() && predicate.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countByItem(Inventory inventory, Item item) {
        return countMatching(inventory, stack -> stack.isOf(item));
    }

    private int countByItem(List<ItemStack> stacks, Item item) {
        return countMatching(stacks, stack -> stack.isOf(item));
    }

    private boolean consumeMatching(Inventory inventory, List<ItemStack> buffer, java.util.function.Predicate<ItemStack> predicate, int amount) {
        int remaining = consumeFromInventory(inventory, predicate, amount);
        remaining = consumeFromBuffer(buffer, predicate, remaining);
        return remaining <= 0;
    }

    private boolean consumeByItem(Inventory inventory, List<ItemStack> buffer, Item item, int amount) {
        return consumeMatching(inventory, buffer, stack -> stack.isOf(item), amount);
    }

    private int consumeFromInventory(Inventory inventory, java.util.function.Predicate<ItemStack> predicate, int amount) {
        if (inventory == null) {
            return amount;
        }

        int remaining = amount;
        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !predicate.test(stack)) {
                continue;
            }

            int moved = Math.min(stack.getCount(), remaining);
            stack.decrement(moved);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            remaining -= moved;
        }
        return remaining;
    }

    private int consumeFromBuffer(List<ItemStack> buffer, java.util.function.Predicate<ItemStack> predicate, int amount) {
        int remaining = amount;
        for (int i = 0; i < buffer.size() && remaining > 0; i++) {
            ItemStack stack = buffer.get(i);
            if (stack.isEmpty() || !predicate.test(stack)) {
                continue;
            }

            int moved = Math.min(stack.getCount(), remaining);
            stack.decrement(moved);
            if (stack.isEmpty()) {
                buffer.set(i, ItemStack.EMPTY);
            }
            remaining -= moved;
        }

        buffer.removeIf(ItemStack::isEmpty);
        return remaining;
    }

    private void refreshDailyLimit(ServerWorld world) {
        long day = world.getTime() / 24000L;
        if (day != lastCraftDay) {
            lastCraftDay = day;
            craftedToday = 0;
            promotionCraftedToday = 0;
        }
    }
}
