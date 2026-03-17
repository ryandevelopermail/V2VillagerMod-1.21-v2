package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.util.DistributionInventoryAccess;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.util.LumberjackUpgradeState;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import dev.sterner.guardvillagers.common.villager.behavior.LumberjackChestTriggerBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.FurnaceBlockEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public final class LumberjackChestTriggerController {
    private static final Logger LOGGER = LoggerFactory.getLogger(LumberjackChestTriggerController.class);
    private static final long EVALUATION_INTERVAL_TICKS = 100L;
    private static final long RULE_COOLDOWN_TICKS = 40L;
    private static final long V2_AFTER_CHEST_DELAY_TICKS = 40L;
    private static final double VILLAGE_EXPANSION_SCAN_RADIUS = 300.0D;
    private static final long VILLAGE_EXPANSION_SCAN_INTERVAL_TICKS = 400L;
    private static final long VILLAGE_EXPANSION_SCAN_JITTER_TICKS = 120L;
    private static final Map<UUID, UpgradeStage> UPGRADE_STAGE_BY_VILLAGER = new HashMap<>();
    private static final Map<BlockPos, UpgradeStage> UPGRADE_STAGE_BY_JOB_SITE = new HashMap<>();
    private static final Map<UUID, Long> CHEST_PAIRED_TICKS_BY_VILLAGER = new HashMap<>();
    private static final Map<BlockPos, Long> CHEST_PAIRED_TICKS_BY_JOB_SITE = new HashMap<>();
    private static final Set<VillagerProfession> CHECKED_JOB_PROFESSIONS = Set.of(
            VillagerProfession.NONE,
            VillagerProfession.ARMORER,
            VillagerProfession.BUTCHER,
            VillagerProfession.CARTOGRAPHER,
            VillagerProfession.CLERIC,
            VillagerProfession.FARMER,
            VillagerProfession.FISHERMAN,
            VillagerProfession.FLETCHER,
            VillagerProfession.LIBRARIAN,
            VillagerProfession.LEATHERWORKER,
            VillagerProfession.MASON,
            VillagerProfession.SHEPHERD,
            VillagerProfession.TOOLSMITH,
            VillagerProfession.WEAPONSMITH
    );

    private static final List<TriggerRule> RULES = List.of(
            new TriggerRule("craft_place_furnace_modifier", 100,
                    LumberjackChestTriggerController::shouldCraftOrPlaceFurnace,
                    LumberjackChestTriggerController::craftOrPlaceFurnace),
            new TriggerRule("feed_furnace_workflow", 200,
                    LumberjackChestTriggerController::shouldFeedFurnaceWorkflow,
                    LumberjackChestTriggerController::feedFurnaceWorkflow),
            new TriggerRule("craft_equip_best_axe", 300,
                    LumberjackChestTriggerController::shouldCraftOrEquipAxe,
                    LumberjackChestTriggerController::craftOrEquipBestAxe)
    ).stream().sorted(Comparator.comparingInt(TriggerRule::priority)).toList();

    private LumberjackChestTriggerController() {
    }

    public enum UpgradeStage {
        UNPAIRED,
        CHEST_PAIRED,
        TABLE_PAIRED
    }

    public static void tick(ServerWorld world, LumberjackGuardEntity guard) {
        if (!guard.isAlive()) {
            LumberjackChestTriggerBehavior.clearChestWatcher(guard);
            return;
        }

        cleanupInvalidV2ChestDelayEntries(world, guard);

        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos != null) {
            LumberjackChestTriggerBehavior.updateChestWatcher(world, guard, chestPos);
        } else {
            LumberjackChestTriggerBehavior.clearChestWatcher(guard);
        }

        long now = world.getTime();
        if (now >= guard.getNextVillageExpansionScanTick()) {
            runVillageExpansionScan(world, guard);
            long jitter = world.random.nextInt((int) VILLAGE_EXPANSION_SCAN_JITTER_TICKS + 1);
            guard.setNextVillageExpansionScanTick(now + VILLAGE_EXPANSION_SCAN_INTERVAL_TICKS + jitter);
        }

        if (!guard.isTriggerEvaluationRequested() && now < guard.getNextTriggerEvaluationTick()) {
            return;
        }

        evaluateRules(world, guard);
        guard.clearTriggerEvaluationRequest();
        guard.setNextTriggerEvaluationTick(now + EVALUATION_INTERVAL_TICKS);
    }

    public static boolean runImmediateVillageUpgradePass(ServerWorld world, LumberjackGuardEntity guard) {
        if (guard.getPairedChestPos() == null) {
            return false;
        }
        TriggerContext context = new TriggerContext(world, guard, resolveChestInventory(world, guard));
        if (tryPlaceChestForEligibleV1Villager(context)) {
            guard.recordTriggerAction(world.getTime(), "immediate_place_chest_for_v1");
            return true;
        }
        if (hasUnresolvedV1ChestDemand(world, guard)) {
            return false;
        }
        if (tryPlaceCraftingTableForEligibleV2Villager(context)) {
            guard.recordTriggerAction(world.getTime(), "immediate_place_crafting_table_for_v2");
            return true;
        }
        return false;
    }

    public static UpgradeDemand resolveNextUpgradeDemand(ServerWorld world, LumberjackGuardEntity guard) {
        if (guard.getPairedChestPos() == null) {
            return null;
        }

        for (VillagerEntity villager : collectNearbyVillagers(world, guard)) {
            if (!isEligibleV1Villager(world, villager)) {
                continue;
            }
            BlockPos jobPos = resolveVillagerJobSite(world, villager);
            if (jobPos == null) {
                continue;
            }
            if (JobBlockPairingHelper.findNearbyChest(world, jobPos).isEmpty()
                    && findPlacementNearJob(world, jobPos, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE) != null) {
                return UpgradeDemand.v1Chest();
            }
        }

        for (VillagerEntity villager : collectNearbyVillagers(world, guard)) {
            if (!isEligibleV2VillagerMissingCraftingTable(world, villager)) {
                continue;
            }
            BlockPos jobPos = resolveVillagerJobSite(world, villager);
            if (jobPos == null) {
                continue;
            }
            if (findPlacementNearJobAndPairedChest(world, jobPos, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE) != null) {
                return UpgradeDemand.v2CraftingTable();
            }
        }

        // Cluster 5A: once all V1/V2 demands are satisfied, produce fences + gates for pen building.
        // Only demand these if the lumberjack has a crafting table (base pairing is complete).
        if (guard.getPairedCraftingTablePos() != null) {
            Inventory chestInv = resolveChestInventory(world, guard);
            if (chestInv != null) {
                int fences = countItems(chestInv, Items.OAK_FENCE);
                int gates = countItems(chestInv, Items.OAK_FENCE_GATE);
                // Target: enough for a 6×6 pen (20 fence segments + 1 gate)
                if (fences < 20) {
                    return UpgradeDemand.v3Fence();
                }
                if (gates < 1) {
                    return UpgradeDemand.v3FenceGate();
                }
            }
        }

        return null;
    }

    public static int countEligibleV1VillagersMissingPairedChest(ServerWorld world, LumberjackGuardEntity guard) {
        int count = 0;
        for (VillagerEntity villager : collectNearbyVillagers(world, guard)) {
            if (!isEligibleV1Villager(world, villager)) {
                continue;
            }

            BlockPos jobPos = resolveVillagerJobSite(world, villager);
            if (jobPos == null) {
                continue;
            }

            if (JobBlockPairingHelper.findNearbyChest(world, jobPos).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    public static int countEligibleV2VillagersMissingCraftingTable(ServerWorld world, LumberjackGuardEntity guard) {
        int count = 0;
        for (VillagerEntity villager : collectNearbyVillagers(world, guard)) {
            if (isEligibleV2VillagerMissingCraftingTableQuery(world, villager)) {
                count++;
            }
        }
        return count;
    }

    public static boolean isEligibleV2Recipient(ServerWorld world, VillagerEntity villager) {
        if (!isEligibleV1Villager(world, villager)) {
            return false;
        }

        BlockPos jobPos = resolveVillagerJobSite(world, villager);
        if (jobPos == null) {
            return false;
        }

        return JobBlockPairingHelper.findNearbyChest(world, jobPos).isPresent()
                && findNearbyCraftingTable(world, jobPos) != null;
    }

    private static void evaluateRules(ServerWorld world, LumberjackGuardEntity guard) {
        TriggerContext context = new TriggerContext(world, guard, resolveChestInventory(world, guard));
        long now = world.getTime();

        for (TriggerRule rule : RULES) {
            if (!rule.predicate().test(context)) {
                continue;
            }
            if (guard.getLastTriggerRuleId().equals(rule.id()) && now - guard.getLastTriggerActionTick() < RULE_COOLDOWN_TICKS) {
                continue;
            }
            if (rule.action().test(context)) {
                guard.recordTriggerAction(now, rule.id());
                return;
            }
        }
    }

    private static boolean shouldCraftOrEquipAxe(TriggerContext context) {
        if (isAxe(context.guard().getMainHandStack())) {
            return false;
        }

        boolean bootstrapSatisfied = isBootstrapSatisfied(context);

        boolean hasPendingUpgradeDemand = resolveNextUpgradeDemand(context.world(), context.guard()) != null;
        if (context.guard().isBootstrapComplete() && hasPendingUpgradeDemand) {
            return false;
        }

        if (context.guard().isBootstrapComplete() && !hasPendingUpgradeDemand && bootstrapSatisfied) {
            return true;
        }

        if (bootstrapSatisfied) {
            return true;
        }
        return canCraftAxe(context, Items.NETHERITE_INGOT)
                || canCraftAxe(context, Items.DIAMOND)
                || canCraftAxe(context, Items.IRON_INGOT)
                || canCraftAxe(context, Items.COBBLESTONE)
                || canCraftAxe(context, Items.OAK_PLANKS);
    }

    private static boolean craftOrEquipBestAxe(TriggerContext context) {
        for (Item axe : List.of(Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE)) {
            if (equipAxeFromStorage(context, axe)) {
                return true;
            }
        }

        if (craftAndEquipAxe(context, Items.NETHERITE_INGOT, Items.NETHERITE_AXE)) {
            return true;
        }
        if (craftAndEquipAxe(context, Items.DIAMOND, Items.DIAMOND_AXE)) {
            return true;
        }
        if (craftAndEquipAxe(context, Items.IRON_INGOT, Items.IRON_AXE)) {
            return true;
        }
        if (craftAndEquipAxe(context, Items.COBBLESTONE, Items.STONE_AXE)) {
            return true;
        }
        return craftAndEquipWoodAxe(context);
    }

    private static boolean shouldCraftOrPlaceFurnace(TriggerContext context) {
        BlockPos modifierPos = context.guard().getPairedFurnaceModifierPos();
        if (modifierPos != null && context.world().getBlockState(modifierPos).isOf(Blocks.FURNACE)) {
            if (belongsToPairedLumberjackZone(context.world(), context.guard(), modifierPos)) {
                return false;
            }
            LOGGER.debug("Clearing lumberjack furnace modifier: position no longer belongs to paired zone pos={}", modifierPos.toShortString());
            context.guard().setPairedFurnaceModifierPos(null);
        }
        if (modifierPos != null && !context.world().getBlockState(modifierPos).isAir()) {
            context.guard().setPairedFurnaceModifierPos(null);
            return false;
        }
        BlockPos existing = findOwnedExistingFurnace(context.world(), context.guard());
        if (existing != null) {
            context.guard().setPairedFurnaceModifierPos(existing);
            LOGGER.debug("Paired existing lumberjack furnace modifier in zone: pos={} source=workflow_existing", existing.toShortString());
            return false;
        }
        return countByItem(context, Items.COBBLESTONE) >= 8;
    }

    private static boolean craftOrPlaceFurnace(TriggerContext context) {
        FurnacePlacementCandidate candidate = findBestFurnacePlacementCandidate(context.world(), context.guard());
        if (candidate == null) {
            LOGGER.debug("Skip lumberjack furnace modifier placement: no workflow candidate");
            return false;
        }

        BlockPos existing = findOwnedExistingFurnace(context.world(), context.guard());
        if (existing != null) {
            context.guard().setPairedFurnaceModifierPos(existing);
            LOGGER.debug("Paired existing lumberjack furnace modifier in zone: pos={} source=workflow_existing", existing.toShortString());
            return true;
        }

        if (!consumeByItem(context, Items.COBBLESTONE, 8)) {
            return false;
        }

        if (context.world().setBlockState(candidate.pos(), Blocks.FURNACE.getDefaultState())) {
            if (belongsToPairedLumberjackZone(context.world(), context.guard(), candidate.pos())) {
                context.guard().setPairedFurnaceModifierPos(candidate.pos());
                LOGGER.debug("Placed lumberjack furnace modifier: pos={} source={} score={}",
                        candidate.pos().toShortString(), candidate.source(), candidate.score());
                return true;
            }
            LOGGER.debug("Placed furnace rejected for pairing: pos={} source={} score={}",
                    candidate.pos().toShortString(), candidate.source(), candidate.score());
        }

        addToBuffer(context.guard(), new ItemStack(Items.FURNACE));
        LOGGER.debug("Failed to place lumberjack furnace modifier in world; buffered furnace item source={} score={}",
                candidate.source(), candidate.score());
        return true;
    }

    private static boolean shouldFeedFurnaceWorkflow(TriggerContext context) {
        BlockPos modifierPos = context.guard().getPairedFurnaceModifierPos();
        if (modifierPos == null || !context.world().getBlockState(modifierPos).isOf(Blocks.FURNACE)) {
            return false;
        }
        return countMatching(context, stack -> stack.isIn(ItemTags.LOGS)) > 0 || hasFurnaceOutput(context.world(), modifierPos);
    }

    private static boolean feedFurnaceWorkflow(TriggerContext context) {
        BlockPos modifierPos = context.guard().getPairedFurnaceModifierPos();
        if (modifierPos == null) {
            return false;
        }
        if (!(context.world().getBlockEntity(modifierPos) instanceof FurnaceBlockEntity furnace)) {
            return false;
        }

        boolean changed = false;
        changed |= routeCharcoalOutput(context, furnace);

        if (furnace.getStack(0).isEmpty()) {
            ItemStack logs = takeOneMatching(context, stack -> stack.isIn(ItemTags.LOGS));
            if (!logs.isEmpty()) {
                furnace.setStack(0, logs);
                changed = true;
            }
        }

        if (furnace.getStack(1).isEmpty()) {
            ItemStack fuel = takeOneMatching(context, stack -> stack.isIn(ItemTags.LOGS));
            if (!fuel.isEmpty()) {
                furnace.setStack(1, fuel);
                changed = true;
            }
        }

        if (changed) {
            furnace.markDirty();
        }
        return changed;
    }

    private static boolean routeCharcoalOutput(TriggerContext context, FurnaceBlockEntity furnace) {
        ItemStack output = furnace.getStack(2);
        if (output.isEmpty() || !output.isOf(Items.CHARCOAL)) {
            return false;
        }

        int remainingLogs = countMatching(context, stack -> stack.isIn(ItemTags.LOGS));
        ItemStack furnaceInput = furnace.getStack(0);
        if (!furnaceInput.isEmpty() && furnaceInput.isIn(ItemTags.LOGS)) {
            remainingLogs += furnaceInput.getCount();
        }

        ItemStack fuel = furnace.getStack(1);
        int fuelSpace;
        if (fuel.isEmpty()) {
            fuelSpace = output.getMaxCount();
        } else if (fuel.isOf(Items.CHARCOAL)) {
            fuelSpace = fuel.getMaxCount() - fuel.getCount();
        } else {
            fuelSpace = 0;
        }

        if (remainingLogs > 0 && fuelSpace > 0) {
            int moved = Math.min(output.getCount(), fuelSpace);
            if (moved <= 0) {
                return false;
            }

            if (fuel.isEmpty()) {
                furnace.setStack(1, new ItemStack(Items.CHARCOAL, moved));
            } else {
                fuel.increment(moved);
                furnace.setStack(1, fuel);
            }

            output.decrement(moved);
            if (output.isEmpty()) {
                furnace.setStack(2, ItemStack.EMPTY);
            } else {
                furnace.setStack(2, output);
            }
            return true;
        }

        boolean pendingBurnCycle = !furnace.getStack(0).isEmpty() || !furnace.getStack(1).isEmpty();
        if (remainingLogs <= 0 && !pendingBurnCycle) {
            addToInventoryOrBuffer(context, output.copy());
            furnace.setStack(2, ItemStack.EMPTY);
            return true;
        }

        return false;
    }

    private static void runVillageExpansionScan(ServerWorld world, LumberjackGuardEntity guard) {
        runImmediateVillageUpgradePass(world, guard);
    }

    private static boolean tryPlaceChestForEligibleV1Villager(TriggerContext context) {
        if (context.guard().getPairedChestPos() == null) {
            return false;
        }

        if (countByItem(context, Items.CHEST) <= 0 && countByItem(context, Items.TRAPPED_CHEST) <= 0) {
            return false;
        }

        for (VillagerEntity villager : collectNearbyVillagers(context.world(), context.guard())) {
            if (!isEligibleV1Villager(context.world(), villager)) {
                continue;
            }
            BlockPos jobPos = resolveVillagerJobSite(context.world(), villager);
            if (jobPos == null || JobBlockPairingHelper.findNearbyChest(context.world(), jobPos).isPresent()) {
                continue;
            }

            BlockPos placePos = findPlacementNearJob(context.world(), jobPos, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE);
            if (placePos == null) {
                continue;
            }

            if (countByItem(context, Items.CHEST) > 0 && consumeByItem(context, Items.CHEST, 1)) {
                if (context.world().setBlockState(placePos, Blocks.CHEST.getDefaultState())) {
                    JobBlockPairingHelper.handlePairingBlockPlacement(context.world(), placePos, context.world().getBlockState(placePos));
                    transitionUpgradeStageToChestPaired(context.world(), villager.getUuid(), jobPos);
                    return true;
                }
                addToInventoryOrBuffer(context, new ItemStack(Items.CHEST));
            }

            if (countByItem(context, Items.TRAPPED_CHEST) > 0 && consumeByItem(context, Items.TRAPPED_CHEST, 1)) {
                if (context.world().setBlockState(placePos, Blocks.TRAPPED_CHEST.getDefaultState())) {
                    JobBlockPairingHelper.handlePairingBlockPlacement(context.world(), placePos, context.world().getBlockState(placePos));
                    transitionUpgradeStageToChestPaired(context.world(), villager.getUuid(), jobPos);
                    return true;
                }
                addToInventoryOrBuffer(context, new ItemStack(Items.TRAPPED_CHEST));
            }
        }

        return false;
    }

    private static boolean tryPlaceCraftingTableForEligibleV2Villager(TriggerContext context) {
        V2BlockReason v2BlockReason = resolveV2BlockReason(context.world(), context.guard());
        if (v2BlockReason != null) {
            LOGGER.debug("Skip V2 crafting table placement: {}", v2BlockReason.debugReason());
            return false;
        }

        Inventory pairedChestInventory = resolveChestInventory(context.world(), context.guard());
        Inventory contextInventory = context.chestInventory() == pairedChestInventory ? null : context.chestInventory();
        List<ItemStack> contextBuffer = context.guard().getGatheredStackBuffer();

        int availableTables = countMatching(pairedChestInventory, stack -> stack.isOf(Items.CRAFTING_TABLE))
                + countMatching(contextInventory, stack -> stack.isOf(Items.CRAFTING_TABLE))
                + countMatching(contextBuffer, stack -> stack.isOf(Items.CRAFTING_TABLE));
        int availablePlanks = countMatching(pairedChestInventory, stack -> stack.isIn(ItemTags.PLANKS))
                + countMatching(contextInventory, stack -> stack.isIn(ItemTags.PLANKS))
                + countMatching(contextBuffer, stack -> stack.isIn(ItemTags.PLANKS));
        if (availableTables <= 0 && availablePlanks < 4) {
            LOGGER.debug("Skip V2 crafting table placement: no materials available in paired chest/context");
            return false;
        }

        for (VillagerEntity villager : collectNearbyVillagers(context.world(), context.guard())) {
            BlockPos stagedJobPos = resolveVillagerJobSite(context.world(), villager);
            UpgradeStage stage = getTrackedUpgradeStage(context.world(), villager.getUuid(), stagedJobPos);
            if (stage != UpgradeStage.CHEST_PAIRED) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Skip V2 crafting table placement: villager={} stage={} expected={} jobPos={}",
                            villager.getUuid(), stage, UpgradeStage.CHEST_PAIRED,
                            stagedJobPos == null ? "null" : stagedJobPos.toShortString());
                }
                continue;
            }

            if (!isEligibleV2VillagerMissingCraftingTable(context.world(), villager)) {
                LOGGER.debug("Skip V2 crafting table placement: villager={} not eligible", villager.getUuid());
                continue;
            }

            BlockPos jobPos = resolveVillagerJobSite(context.world(), villager);
            if (jobPos == null) {
                LOGGER.debug("Skip V2 crafting table placement: villager={} not eligible (no job site)", villager.getUuid());
                continue;
            }

            BlockPos placePos = findPlacementNearJobAndPairedChest(context.world(), jobPos, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE);
            if (placePos == null) {
                LOGGER.debug("Skip V2 crafting table placement: villager={} jobPos={} no place pos",
                        villager.getUuid(), jobPos.toShortString());
                continue;
            }

            if (!hasPreCraftingTableMaterialReadiness(context.world(), villager, jobPos)) {
                LOGGER.debug("Skip V2 crafting table placement: villager={} jobPos={} not eligible (preconditions)",
                        villager.getUuid(), jobPos.toShortString());
                continue;
            }

            PlacementMaterialUse materialUse = tryConsumeCraftingTableMaterials(pairedChestInventory, contextInventory, contextBuffer);
            if (materialUse == null) {
                LOGGER.debug("Skip V2 crafting table placement: villager={} jobPos={} placePos={} no materials",
                        villager.getUuid(), jobPos.toShortString(), placePos.toShortString());
                continue;
            }

            if (context.world().setBlockState(placePos, Blocks.CRAFTING_TABLE.getDefaultState())) {
                JobBlockPairingHelper.handleCraftingTablePlacement(context.world(), placePos);
                transitionUpgradeStageToTablePaired(context.world(), villager.getUuid(), jobPos);
                LOGGER.debug("Placed V2 crafting table: villager={} jobPos={} placePos={} source={}",
                        villager.getUuid(), jobPos.toShortString(), placePos.toShortString(), materialUse.sourceLabel());
                return true;
            }

            restorePlacementMaterials(context, materialUse);
            LOGGER.debug("Skip V2 crafting table placement: villager={} jobPos={} placePos={} placement failed source={}",
                    villager.getUuid(), jobPos.toShortString(), placePos.toShortString(), materialUse.sourceLabel());
        }

        return false;
    }

    static boolean hasUnresolvedV1ChestDemand(ServerWorld world, LumberjackGuardEntity guard) {
        return resolveV2BlockReason(world, guard) != null;
    }

    static boolean shouldBlockV2TablePlacement(UpgradeDemand nextDemand, int eligibleV1MissingChestCount) {
        return UpgradeDemand.v1Chest().equals(nextDemand) || eligibleV1MissingChestCount > 0;
    }

    private static V2BlockReason resolveV2BlockReason(ServerWorld world, LumberjackGuardEntity guard) {
        UpgradeDemand nextDemand = resolveNextUpgradeDemand(world, guard);
        int eligibleV1MissingChestCount = countEligibleV1VillagersMissingPairedChest(world, guard);
        if (!shouldBlockV2TablePlacement(nextDemand, eligibleV1MissingChestCount)) {
            return null;
        }
        return new V2BlockReason(nextDemand, eligibleV1MissingChestCount);
    }

    private static PlacementMaterialUse tryConsumeCraftingTableMaterials(Inventory pairedChestInventory,
                                                                          Inventory contextInventory,
                                                                          List<ItemStack> contextBuffer) {
        ItemStack fromPairedChest = takeOneFromInventory(pairedChestInventory, stack -> stack.isOf(Items.CRAFTING_TABLE));
        if (!fromPairedChest.isEmpty()) {
            return PlacementMaterialUse.fromTable(MaterialSource.PAIRED_CHEST_TABLE, fromPairedChest);
        }

        ItemStack fromContextInventory = takeOneFromInventory(contextInventory, stack -> stack.isOf(Items.CRAFTING_TABLE));
        if (!fromContextInventory.isEmpty()) {
            return PlacementMaterialUse.fromTable(MaterialSource.CONTEXT_INVENTORY_TABLE, fromContextInventory);
        }

        ItemStack fromContextBuffer = takeOneFromBuffer(contextBuffer, stack -> stack.isOf(Items.CRAFTING_TABLE));
        if (!fromContextBuffer.isEmpty()) {
            return PlacementMaterialUse.fromTable(MaterialSource.CONTEXT_BUFFER_TABLE, fromContextBuffer);
        }

        List<ItemStack> pairedChestPlanks = consumeExactPlanksFromInventory(pairedChestInventory, 4);
        if (!pairedChestPlanks.isEmpty()) {
            if (countTotalItems(pairedChestPlanks) >= 4) {
                return PlacementMaterialUse.fromPlanks(MaterialSource.PAIRED_CHEST_PLANKS, pairedChestPlanks);
            }
            addStacksBackToInventory(pairedChestInventory, pairedChestPlanks);
        }

        List<ItemStack> contextInventoryPlanks = consumeExactPlanksFromInventory(contextInventory, 4);
        if (!contextInventoryPlanks.isEmpty()) {
            if (countTotalItems(contextInventoryPlanks) >= 4) {
                return PlacementMaterialUse.fromPlanks(MaterialSource.CONTEXT_INVENTORY_PLANKS, contextInventoryPlanks);
            }
            addStacksBackToInventory(contextInventory, contextInventoryPlanks);
        }

        List<ItemStack> contextBufferPlanks = consumeExactPlanksFromBuffer(contextBuffer, 4);
        if (!contextBufferPlanks.isEmpty()) {
            if (countTotalItems(contextBufferPlanks) >= 4) {
                return PlacementMaterialUse.fromPlanks(MaterialSource.CONTEXT_BUFFER_PLANKS, contextBufferPlanks);
            }
            addStacksBackToBuffer(contextBuffer, contextBufferPlanks);
        }

        return null;
    }

    private static void restorePlacementMaterials(TriggerContext context, PlacementMaterialUse materialUse) {
        if (materialUse == null) {
            return;
        }

        if (!materialUse.consumedTable().isEmpty()) {
            switch (materialUse.source()) {
                case PAIRED_CHEST_TABLE, CONTEXT_INVENTORY_TABLE -> addToSpecificInventory(resolveSourceInventory(context, materialUse.source()), materialUse.consumedTable().copy());
                case CONTEXT_BUFFER_TABLE -> addToBuffer(context.guard(), materialUse.consumedTable().copy());
                default -> { }
            }
            return;
        }

        if (materialUse.consumedPlanks().isEmpty()) {
            return;
        }

        switch (materialUse.source()) {
            case PAIRED_CHEST_PLANKS, CONTEXT_INVENTORY_PLANKS -> addStacksBackToInventory(resolveSourceInventory(context, materialUse.source()), materialUse.consumedPlanks());
            case CONTEXT_BUFFER_PLANKS -> addStacksBackToBuffer(context.guard().getGatheredStackBuffer(), materialUse.consumedPlanks());
            default -> { }
        }
    }

    private static Inventory resolveSourceInventory(TriggerContext context, MaterialSource source) {
        return switch (source) {
            case PAIRED_CHEST_TABLE, PAIRED_CHEST_PLANKS -> resolveChestInventory(context.world(), context.guard());
            case CONTEXT_INVENTORY_TABLE, CONTEXT_INVENTORY_PLANKS -> context.chestInventory();
            default -> null;
        };
    }

    private static List<ItemStack> consumeExactPlanksFromInventory(Inventory inventory, int amount) {
        List<ItemStack> consumed = new ArrayList<>();
        if (inventory == null || amount <= 0) {
            return consumed;
        }
        int remaining = amount;
        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !stack.isIn(ItemTags.PLANKS)) {
                continue;
            }
            int moved = Math.min(stack.getCount(), remaining);
            ItemStack extracted = stack.copyWithCount(moved);
            stack.decrement(moved);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            consumed.add(extracted);
            remaining -= moved;
        }
        if (!consumed.isEmpty()) {
            inventory.markDirty();
        }
        return consumed;
    }

    private static List<ItemStack> consumeExactPlanksFromBuffer(List<ItemStack> buffer, int amount) {
        List<ItemStack> consumed = new ArrayList<>();
        if (amount <= 0) {
            return consumed;
        }
        int remaining = amount;
        for (int i = 0; i < buffer.size() && remaining > 0; i++) {
            ItemStack stack = buffer.get(i);
            if (stack.isEmpty() || !stack.isIn(ItemTags.PLANKS)) {
                continue;
            }
            int moved = Math.min(stack.getCount(), remaining);
            ItemStack extracted = stack.copyWithCount(moved);
            stack.decrement(moved);
            if (stack.isEmpty()) {
                buffer.set(i, ItemStack.EMPTY);
            }
            consumed.add(extracted);
            remaining -= moved;
        }
        buffer.removeIf(ItemStack::isEmpty);
        return consumed;
    }

    private static int countTotalItems(List<ItemStack> stacks) {
        int total = 0;
        for (ItemStack stack : stacks) {
            total += stack.getCount();
        }
        return total;
    }

    private static void addStacksBackToInventory(Inventory inventory, List<ItemStack> stacks) {
        if (inventory == null) {
            return;
        }
        for (ItemStack stack : stacks) {
            addToSpecificInventory(inventory, stack.copy());
        }
    }

    private static void addStacksBackToBuffer(List<ItemStack> buffer, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            addToSpecificBuffer(buffer, stack.copy());
        }
    }

    private static void addToSpecificInventory(Inventory inventory, ItemStack incoming) {
        if (inventory == null || incoming.isEmpty()) {
            return;
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                inventory.setStack(slot, incoming.copy());
                inventory.markDirty();
                return;
            }
            if (ItemStack.areItemsAndComponentsEqual(existing, incoming) && existing.getCount() < existing.getMaxCount()) {
                int move = Math.min(existing.getMaxCount() - existing.getCount(), incoming.getCount());
                existing.increment(move);
                incoming.decrement(move);
                if (incoming.isEmpty()) {
                    inventory.markDirty();
                    return;
                }
            }
        }
    }

    private static void addToSpecificBuffer(List<ItemStack> buffer, ItemStack incoming) {
        if (incoming.isEmpty()) {
            return;
        }
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
        buffer.add(incoming);
    }

    private enum MaterialSource {
        PAIRED_CHEST_TABLE,
        CONTEXT_INVENTORY_TABLE,
        CONTEXT_BUFFER_TABLE,
        PAIRED_CHEST_PLANKS,
        CONTEXT_INVENTORY_PLANKS,
        CONTEXT_BUFFER_PLANKS
    }

    private record PlacementMaterialUse(MaterialSource source, ItemStack consumedTable, List<ItemStack> consumedPlanks) {
        private static PlacementMaterialUse fromTable(MaterialSource source, ItemStack consumedTable) {
            return new PlacementMaterialUse(source, consumedTable, List.of());
        }

        private static PlacementMaterialUse fromPlanks(MaterialSource source, List<ItemStack> consumedPlanks) {
            return new PlacementMaterialUse(source, ItemStack.EMPTY, consumedPlanks);
        }

        private String sourceLabel() {
            return source.name().toLowerCase();
        }
    }

    private static ArrayList<VillagerEntity> collectNearbyVillagers(ServerWorld world, LumberjackGuardEntity guard) {
        return new ArrayList<>(world.getEntitiesByClass(
                VillagerEntity.class,
                villageExpansionScanBox(guard.getPairedJobPos(), guard.getBlockPos()),
                VillagerEntity::isAlive
        ));
    }

    static Box villageExpansionScanBox(BlockPos pairedJobPos, BlockPos fallbackGuardPos) {
        BlockPos scanCenter = pairedJobPos != null ? pairedJobPos : fallbackGuardPos;
        return new Box(scanCenter).expand(VILLAGE_EXPANSION_SCAN_RADIUS);
    }

    private static boolean isEligibleV1Villager(ServerWorld world, VillagerEntity villager) {
        if (!villager.isAlive() || villager.isBaby()) {
            return false;
        }
        if (villager.getVillagerData().getProfession() == net.minecraft.village.VillagerProfession.NONE
                || villager.getVillagerData().getProfession() == net.minecraft.village.VillagerProfession.NITWIT) {
            return false;
        }

        BlockPos jobPos = resolveVillagerJobSite(world, villager);
        if (jobPos == null) {
            return false;
        }
        return ProfessionDefinitions.isExpectedJobBlock(villager.getVillagerData().getProfession(), world.getBlockState(jobPos));
    }

    private static boolean isEligibleV2VillagerMissingCraftingTableQuery(ServerWorld world, VillagerEntity villager) {
        return isEligibleV2VillagerMissingCraftingTable(world, villager, false);
    }

    private static boolean isEligibleV2VillagerMissingCraftingTable(ServerWorld world, VillagerEntity villager) {
        return isEligibleV2VillagerMissingCraftingTable(world, villager, true);
    }

    private static boolean isEligibleV2VillagerMissingCraftingTable(ServerWorld world,
                                                                     VillagerEntity villager,
                                                                     boolean requireDelay) {
        if (!isEligibleV1Villager(world, villager)) {
            clearUpgradeState(world, villager.getUuid(), null);
            return false;
        }

        BlockPos jobPos = resolveVillagerJobSite(world, villager);
        if (jobPos == null) {
            clearUpgradeState(world, villager.getUuid(), null);
            return false;
        }

        UpgradeStage stage = getTrackedUpgradeStage(world, villager.getUuid(), jobPos);

        BlockPos chestPos = JobBlockPairingHelper.findNearbyChest(world, jobPos).orElse(null);
        if (chestPos == null) {
            clearUpgradeState(world, villager.getUuid(), jobPos);
            return false;
        }

        if (stage != UpgradeStage.CHEST_PAIRED) {
            return false;
        }

        if (findNearbyCraftingTable(world, jobPos) != null) {
            transitionUpgradeStageToTablePaired(world, villager.getUuid(), jobPos);
            return false;
        }

        if (requireDelay && !hasMetV2ChestDelay(world, villager.getUuid(), jobPos)) {
            return false;
        }

        return true;
    }

    static boolean isEligibleV2MissingCraftingTableForStage(UpgradeStage stage,
                                                             boolean hasNearbyChest,
                                                             boolean hasNearbyCraftingTable,
                                                             boolean requireDelay,
                                                             long now,
                                                             long chestPairedTick) {
        if (!hasNearbyChest) {
            return false;
        }
        if (hasNearbyCraftingTable || stage != UpgradeStage.CHEST_PAIRED) {
            return false;
        }
        return !requireDelay || now - chestPairedTick >= V2_AFTER_CHEST_DELAY_TICKS;
    }

    private static void cleanupInvalidV2ChestDelayEntries(ServerWorld world, LumberjackGuardEntity guard) {
        if (UPGRADE_STAGE_BY_VILLAGER.isEmpty()) {
            return;
        }

        Iterator<VillagerEntity> iterator = collectNearbyVillagers(world, guard).iterator();
        while (iterator.hasNext()) {
            VillagerEntity villager = iterator.next();
            UUID villagerId = villager.getUuid();
            if (!UPGRADE_STAGE_BY_VILLAGER.containsKey(villagerId)) {
                continue;
            }

            BlockPos jobPos = resolveVillagerJobSite(world, villager);
            if (jobPos == null) {
                clearUpgradeState(world, villagerId, null);
                continue;
            }

            UpgradeStage stage = getTrackedUpgradeStage(world, villagerId, jobPos);
            if (stage == UpgradeStage.CHEST_PAIRED
                    && (!JobBlockPairingHelper.findNearbyChest(world, jobPos).isPresent()
                    || findNearbyCraftingTable(world, jobPos) != null)) {
                clearUpgradeState(world, villagerId, jobPos);
            }
        }
    }

    private static void transitionUpgradeStageToChestPaired(ServerWorld world, UUID villagerId, BlockPos jobPos) {
        hydrateUpgradeStageToChestPaired(world, world.getTime(), villagerId, jobPos);
    }

    private static void hydrateUpgradeStageToChestPaired(ServerWorld world, long now, UUID villagerId, BlockPos jobPos) {
        BlockPos immutableJobPos = jobPos.toImmutable();
        UPGRADE_STAGE_BY_VILLAGER.put(villagerId, UpgradeStage.CHEST_PAIRED);
        UPGRADE_STAGE_BY_JOB_SITE.put(immutableJobPos, UpgradeStage.CHEST_PAIRED);
        CHEST_PAIRED_TICKS_BY_VILLAGER.put(villagerId, now);
        CHEST_PAIRED_TICKS_BY_JOB_SITE.put(immutableJobPos, now);
        LumberjackUpgradeState.get(world.getServer()).putEntry(world, villagerId, immutableJobPos, UpgradeStage.CHEST_PAIRED, now);
    }

    private static void transitionUpgradeStageToTablePaired(ServerWorld world, UUID villagerId, BlockPos jobPos) {
        BlockPos immutableJobPos = jobPos.toImmutable();
        UPGRADE_STAGE_BY_VILLAGER.put(villagerId, UpgradeStage.TABLE_PAIRED);
        UPGRADE_STAGE_BY_JOB_SITE.put(immutableJobPos, UpgradeStage.TABLE_PAIRED);
        CHEST_PAIRED_TICKS_BY_VILLAGER.remove(villagerId);
        CHEST_PAIRED_TICKS_BY_JOB_SITE.remove(immutableJobPos);
        LumberjackUpgradeState.get(world.getServer()).putEntry(world, villagerId, immutableJobPos, UpgradeStage.TABLE_PAIRED, 0L);
    }

    private static UpgradeStage getTrackedUpgradeStage(ServerWorld world, UUID villagerId, BlockPos jobPos) {
        UpgradeStage stage = getUpgradeStage(villagerId, jobPos);
        if (stage != UpgradeStage.UNPAIRED || jobPos == null) {
            return stage;
        }

        LumberjackUpgradeState.EntryValue entry = LumberjackUpgradeState.get(world.getServer())
                .getEntry(world, villagerId, jobPos)
                .orElse(null);
        if (entry == null || !canHydrateFromPersistedStage(entry.stage())) {
            return UpgradeStage.UNPAIRED;
        }

        if (entry.stage() == UpgradeStage.CHEST_PAIRED) {
            hydrateUpgradeStageToChestPaired(world, entry.chestPairedTick(), villagerId, jobPos);
            return UpgradeStage.CHEST_PAIRED;
        }

        BlockPos immutableJobPos = jobPos.toImmutable();
        UPGRADE_STAGE_BY_VILLAGER.put(villagerId, UpgradeStage.TABLE_PAIRED);
        UPGRADE_STAGE_BY_JOB_SITE.put(immutableJobPos, UpgradeStage.TABLE_PAIRED);
        CHEST_PAIRED_TICKS_BY_VILLAGER.remove(villagerId);
        CHEST_PAIRED_TICKS_BY_JOB_SITE.remove(immutableJobPos);
        return UpgradeStage.TABLE_PAIRED;
    }

    private static UpgradeStage getUpgradeStage(UUID villagerId, BlockPos jobPos) {
        UpgradeStage villagerStage = UPGRADE_STAGE_BY_VILLAGER.get(villagerId);
        if (villagerStage != null) {
            return villagerStage;
        }
        if (jobPos != null) {
            UpgradeStage jobSiteStage = UPGRADE_STAGE_BY_JOB_SITE.get(jobPos);
            if (jobSiteStage != null) {
                return jobSiteStage;
            }
        }
        return UpgradeStage.UNPAIRED;
    }

    private static boolean hasMetV2ChestDelay(ServerWorld world, UUID villagerId, BlockPos jobPos) {
        Long placedTick = CHEST_PAIRED_TICKS_BY_VILLAGER.get(villagerId);
        if (placedTick == null) {
            placedTick = CHEST_PAIRED_TICKS_BY_JOB_SITE.get(jobPos);
        }
        return placedTick != null && world.getTime() - placedTick >= V2_AFTER_CHEST_DELAY_TICKS;
    }

    private static void clearUpgradeState(ServerWorld world, UUID villagerId, BlockPos jobPos) {
        UPGRADE_STAGE_BY_VILLAGER.remove(villagerId);
        CHEST_PAIRED_TICKS_BY_VILLAGER.remove(villagerId);
        if (jobPos != null) {
            UPGRADE_STAGE_BY_JOB_SITE.remove(jobPos);
            CHEST_PAIRED_TICKS_BY_JOB_SITE.remove(jobPos);
            LumberjackUpgradeState.get(world.getServer()).removeEntry(world, villagerId, jobPos);
        }
    }

    static boolean canHydrateFromPersistedStage(UpgradeStage persistedStage) {
        return persistedStage == UpgradeStage.CHEST_PAIRED || persistedStage == UpgradeStage.TABLE_PAIRED;
    }

    private static BlockPos resolveVillagerJobSite(ServerWorld world, VillagerEntity villager) {
        return villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE)
                .filter(globalPos -> globalPos.dimension() == world.getRegistryKey())
                .map(GlobalPos::pos)
                .map(BlockPos::toImmutable)
                .orElse(null);
    }

    private static BlockPos findNearbyCraftingTable(ServerWorld world, BlockPos center) {
        int range = (int) Math.ceil(JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE);
        for (BlockPos checkPos : BlockPos.iterate(center.add(-range, -range, -range), center.add(range, range, range))) {
            if (center.isWithinDistance(checkPos, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE)
                    && world.getBlockState(checkPos).isOf(Blocks.CRAFTING_TABLE)) {
                return checkPos.toImmutable();
            }
        }
        return null;
    }

    private static BlockPos findPlacementNearJob(ServerWorld world, BlockPos center, double range) {
        return findPlacementNear(world, center, range, null);
    }

    private static BlockPos findPlacementNearJobAndPairedChest(ServerWorld world, BlockPos jobPos, double range) {
        BlockPos pairedChestPos = JobBlockPairingHelper.findNearbyChest(world, jobPos).orElse(null);
        if (pairedChestPos == null) {
            return null;
        }
        return findPlacementNear(world, jobPos, range, pairedChestPos);
    }

    private static boolean hasPreCraftingTableMaterialReadiness(ServerWorld world, VillagerEntity villager, BlockPos jobPos) {
        VillagerProfession profession = villager.getVillagerData().getProfession();
        if (profession != VillagerProfession.FARMER && profession != VillagerProfession.MASON) {
            return true;
        }

        BlockPos chestPos = JobBlockPairingHelper.findNearbyChest(world, jobPos).orElse(null);
        if (chestPos == null) {
            return false;
        }

        Inventory recipientInventory = DistributionInventoryAccess.getChestInventory(world, chestPos).orElse(null);
        if (recipientInventory == null) {
            return false;
        }

        int sticks = countMatching(recipientInventory, stack -> stack.isOf(Items.STICK));
        if (sticks < 2) {
            return false;
        }

        int planks = countMatching(recipientInventory, stack -> stack.isIn(ItemTags.PLANKS));
        int cobble = countMatching(recipientInventory, stack -> stack.isOf(Items.COBBLESTONE));
        int iron = countMatching(recipientInventory, stack -> stack.isOf(Items.IRON_INGOT));
        int gold = countMatching(recipientInventory, stack -> stack.isOf(Items.GOLD_INGOT));
        int diamonds = countMatching(recipientInventory, stack -> stack.isOf(Items.DIAMOND));

        if (profession == VillagerProfession.MASON) {
            return planks >= 3 || cobble >= 3 || iron >= 3 || gold >= 3 || diamonds >= 3;
        }

        return planks >= 2 || cobble >= 2 || iron >= 2 || gold >= 2 || diamonds >= 2;
    }

    private static BlockPos findPlacementNear(ServerWorld world, BlockPos center, double range, BlockPos secondaryAnchor) {
        int blockRange = (int) Math.ceil(range);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos candidate : BlockPos.iterate(center.add(-blockRange, -1, -blockRange), center.add(blockRange, 1, blockRange))) {
            if (!center.isWithinDistance(candidate, range)) {
                continue;
            }
            if (secondaryAnchor != null && !secondaryAnchor.isWithinDistance(candidate, range)) {
                continue;
            }
            if (!world.getBlockState(candidate).isAir()) {
                continue;
            }

            BlockPos below = candidate.down();
            if (!world.getBlockState(below).isSolidBlock(world, below)) {
                continue;
            }

            double distance = center.getSquaredDistance(candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate.toImmutable();
            }
        }

        return best;
    }

    private static BlockPos findOwnedExistingFurnace(ServerWorld world, LumberjackGuardEntity guard) {
        BlockPos chestPos = guard.getPairedChestPos();
        BlockPos tablePos = guard.getPairedCraftingTablePos();
        if (chestPos == null || tablePos == null) {
            return null;
        }

        int range = (int) Math.ceil(JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE + 2);
        BlockPos min = BlockPos.ofFloored(
                Math.min(chestPos.getX(), tablePos.getX()) - range,
                Math.min(chestPos.getY(), tablePos.getY()) - 1,
                Math.min(chestPos.getZ(), tablePos.getZ()) - range
        );
        BlockPos max = BlockPos.ofFloored(
                Math.max(chestPos.getX(), tablePos.getX()) + range,
                Math.max(chestPos.getY(), tablePos.getY()) + 1,
                Math.max(chestPos.getZ(), tablePos.getZ()) + range
        );

        BlockPos best = null;
        int bestScore = Integer.MIN_VALUE;
        for (BlockPos pos : BlockPos.iterate(min, max)) {
            if (!world.getBlockState(pos).isOf(Blocks.FURNACE)) {
                continue;
            }
            if (!belongsToPairedLumberjackZone(world, guard, pos)) {
                continue;
            }
            int score = scoreWorkflowPosition(world, pos, chestPos, tablePos);
            if (score > bestScore) {
                best = pos.toImmutable();
                bestScore = score;
            }
        }
        return best;
    }

    private static FurnacePlacementCandidate findBestFurnacePlacementCandidate(ServerWorld world, LumberjackGuardEntity guard) {
        BlockPos chestPos = guard.getPairedChestPos();
        BlockPos tablePos = guard.getPairedCraftingTablePos();
        if (chestPos == null || tablePos == null) {
            return null;
        }

        int range = (int) Math.ceil(JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE + 2);
        BlockPos min = BlockPos.ofFloored(
                Math.min(chestPos.getX(), tablePos.getX()) - range,
                Math.min(chestPos.getY(), tablePos.getY()) - 1,
                Math.min(chestPos.getZ(), tablePos.getZ()) - range
        );
        BlockPos max = BlockPos.ofFloored(
                Math.max(chestPos.getX(), tablePos.getX()) + range,
                Math.max(chestPos.getY(), tablePos.getY()) + 1,
                Math.max(chestPos.getZ(), tablePos.getZ()) + range
        );

        FurnacePlacementCandidate best = null;
        for (BlockPos pos : BlockPos.iterate(min, max)) {
            if (!world.getBlockState(pos).isAir()) {
                continue;
            }
            BlockPos below = pos.down();
            if (!world.getBlockState(below).isSolidBlock(world, below)) {
                continue;
            }
            if (!belongsToPairedLumberjackZone(world, guard, pos)) {
                continue;
            }

            int score = scoreWorkflowPosition(world, pos, chestPos, tablePos);
            if (best == null || score > best.score()) {
                best = new FurnacePlacementCandidate(pos.toImmutable(), score, "workflow_scored");
            }
        }
        return best;
    }

    private static int scoreWorkflowPosition(ServerWorld world, BlockPos candidate, BlockPos chestPos, BlockPos tablePos) {
        int score = 0;
        score -= (int) candidate.getSquaredDistance(chestPos);
        score -= (int) candidate.getSquaredDistance(tablePos);
        if (isAdjacent(candidate, chestPos)) {
            score += 6;
        }
        if (isAdjacent(candidate, tablePos)) {
            score += 6;
        }
        if (hasNearbyModifier(world, candidate)) {
            score += 8;
        }
        return score;
    }

    private static boolean belongsToPairedLumberjackZone(ServerWorld world, LumberjackGuardEntity guard, BlockPos pos) {
        BlockPos chestPos = guard.getPairedChestPos();
        BlockPos tablePos = guard.getPairedCraftingTablePos();
        if (chestPos == null || tablePos == null) {
            return false;
        }
        double range = JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE + 2.0D;
        if (!pos.isWithinDistance(chestPos, range) || !pos.isWithinDistance(tablePos, range)) {
            return false;
        }
        return !isAdjacentToUnrelatedJobBlock(world, pos, chestPos, tablePos);
    }

    private static boolean isAdjacentToUnrelatedJobBlock(ServerWorld world, BlockPos candidate, BlockPos chestPos, BlockPos tablePos) {
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = candidate.offset(direction);
            BlockState state = world.getBlockState(adjacent);
            if (!isAnyKnownJobBlock(state)) {
                continue;
            }
            if (adjacent.equals(tablePos) || adjacent.equals(chestPos)) {
                continue;
            }
            if (adjacent.isWithinDistance(tablePos, 1.5D) || adjacent.isWithinDistance(chestPos, 1.5D)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean isAnyKnownJobBlock(BlockState state) {
        for (VillagerProfession profession : CHECKED_JOB_PROFESSIONS) {
            if (ProfessionDefinitions.isExpectedJobBlock(profession, state)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNearbyModifier(ServerWorld world, BlockPos pos) {
        for (BlockPos checkPos : BlockPos.iterateOutwards(pos, 1, 1, 1)) {
            if (world.getBlockState(checkPos).isOf(dev.sterner.guardvillagers.GuardVillagers.GUARD_STAND_MODIFIER)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAdjacent(BlockPos a, BlockPos b) {
        return a.getManhattanDistance(b) == 1;
    }

    private record FurnacePlacementCandidate(BlockPos pos, int score, String source) {
    }

    private static boolean hasAnyAxeAvailable(TriggerContext context) {
        for (Item axe : List.of(Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE)) {
            if (countByItem(context, axe) > 0) {
                return true;
            }
        }
        return false;
    }

    static boolean isBootstrapSatisfied(ServerWorld world, LumberjackGuardEntity guard) {
        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos == null) {
            return false;
        }
        Inventory chestInventory = resolveChestInventory(world, guard);
        if (chestInventory == null) {
            return false;
        }
        return isBootstrapSatisfied(new TriggerContext(world, guard, chestInventory));
    }

    private static boolean isBootstrapSatisfied(TriggerContext context) {
        return isAxe(context.guard().getMainHandStack()) || hasAnyAxeAvailable(context);
    }

    private static boolean canCraftAxe(TriggerContext context, Item material) {
        int sticks = countByItem(context, Items.STICK);
        if (sticks < 2) {
            return false;
        }
        if (material == Items.OAK_PLANKS) {
            return countMatching(context, stack -> stack.isIn(ItemTags.PLANKS)) >= 3;
        }
        return countByItem(context, material) >= 3;
    }

    private static boolean craftAndEquipAxe(TriggerContext context, Item material, Item axeOutput) {
        if (!canCraftAxe(context, material)) {
            return false;
        }
        boolean consumedMaterial = material == Items.OAK_PLANKS
                ? consumeMatching(context, stack -> stack.isIn(ItemTags.PLANKS), 3)
                : consumeByItem(context, material, 3);
        if (!consumedMaterial || !consumeByItem(context, Items.STICK, 2)) {
            return false;
        }
        context.guard().equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, new ItemStack(axeOutput));
        context.guard().setBootstrapComplete(true);
        return true;
    }

    private static boolean craftAndEquipWoodAxe(TriggerContext context) {
        return craftAndEquipAxe(context, Items.OAK_PLANKS, Items.WOODEN_AXE);
    }

    private static boolean equipAxeFromStorage(TriggerContext context, Item axe) {
        ItemStack pulled = takeOneByItem(context, axe);
        if (pulled.isEmpty()) {
            return false;
        }
        ItemStack previous = context.guard().getMainHandStack();
        if (!previous.isEmpty()) {
            addToBuffer(context.guard(), previous.copy());
        }
        context.guard().equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, pulled);
        context.guard().setBootstrapComplete(true);
        return true;
    }

    private static boolean isAxe(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof AxeItem;
    }

    private static boolean hasFurnaceOutput(ServerWorld world, BlockPos pos) {
        if (!(world.getBlockEntity(pos) instanceof FurnaceBlockEntity furnace)) {
            return false;
        }
        return !furnace.getStack(2).isEmpty();
    }

    private static Inventory resolveChestInventory(ServerWorld world, LumberjackGuardEntity guard) {
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

    private static int countByItem(TriggerContext context, Item item) {
        return countMatching(context, stack -> stack.isOf(item));
    }

    private static int countMatching(TriggerContext context, Predicate<ItemStack> predicate) {
        return countMatching(context.chestInventory(), predicate) + countMatching(context.guard().getGatheredStackBuffer(), predicate);
    }

    /** Simple item count helper used by resolveNextUpgradeDemand. */
    private static int countItems(Inventory inventory, Item item) {
        return countMatching(inventory, stack -> stack.isOf(item));
    }

    private static int countMatching(Inventory inventory, Predicate<ItemStack> predicate) {
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

    private static int countMatching(List<ItemStack> stacks, Predicate<ItemStack> predicate) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty() && predicate.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean consumeByItem(TriggerContext context, Item item, int amount) {
        return consumeMatching(context, stack -> stack.isOf(item), amount);
    }

    private static boolean consumeMatching(TriggerContext context, Predicate<ItemStack> predicate, int amount) {
        int remaining = consumeFromInventory(context.chestInventory(), predicate, amount);
        remaining = consumeFromBuffer(context.guard().getGatheredStackBuffer(), predicate, remaining);
        return remaining <= 0;
    }

    private static ItemStack takeOneByItem(TriggerContext context, Item item) {
        return takeOneMatching(context, stack -> stack.isOf(item));
    }

    private static ItemStack takeOneMatching(TriggerContext context, Predicate<ItemStack> predicate) {
        ItemStack pulled = takeOneFromInventory(context.chestInventory(), predicate);
        if (!pulled.isEmpty()) {
            return pulled;
        }
        return takeOneFromBuffer(context.guard().getGatheredStackBuffer(), predicate);
    }

    private static ItemStack takeOneFromInventory(Inventory inventory, Predicate<ItemStack> predicate) {
        if (inventory == null) {
            return ItemStack.EMPTY;
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !predicate.test(stack)) {
                continue;
            }
            ItemStack split = stack.split(1);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            inventory.markDirty();
            return split;
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack takeOneFromBuffer(List<ItemStack> buffer, Predicate<ItemStack> predicate) {
        for (int i = 0; i < buffer.size(); i++) {
            ItemStack stack = buffer.get(i);
            if (stack.isEmpty() || !predicate.test(stack)) {
                continue;
            }
            ItemStack split = stack.split(1);
            if (stack.isEmpty()) {
                buffer.set(i, ItemStack.EMPTY);
            }
            buffer.removeIf(ItemStack::isEmpty);
            return split;
        }
        return ItemStack.EMPTY;
    }

    private static int consumeFromInventory(Inventory inventory, Predicate<ItemStack> predicate, int amount) {
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
        if (remaining != amount) {
            inventory.markDirty();
        }
        return remaining;
    }

    private static int consumeFromBuffer(List<ItemStack> buffer, Predicate<ItemStack> predicate, int amount) {
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

    private static void addToInventoryOrBuffer(TriggerContext context, ItemStack incoming) {
        Inventory inventory = context.chestInventory();
        if (inventory != null) {
            for (int slot = 0; slot < inventory.size(); slot++) {
                ItemStack existing = inventory.getStack(slot);
                if (existing.isEmpty()) {
                    inventory.setStack(slot, incoming.copy());
                    inventory.markDirty();
                    return;
                }
                if (ItemStack.areItemsAndComponentsEqual(existing, incoming) && existing.getCount() < existing.getMaxCount()) {
                    int move = Math.min(existing.getMaxCount() - existing.getCount(), incoming.getCount());
                    existing.increment(move);
                    incoming.decrement(move);
                    if (incoming.isEmpty()) {
                        inventory.markDirty();
                        return;
                    }
                }
            }
        }

        if (!incoming.isEmpty()) {
            addToBuffer(context.guard(), incoming);
        }
    }

    private static void addToBuffer(LumberjackGuardEntity guard, ItemStack incoming) {
        List<ItemStack> buffer = guard.getGatheredStackBuffer();
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

    public record UpgradeDemand(Item outputItem, int planksCost, int stickCost) {
        /** Back-compat constructor for callers that don't use sticks. */
        public UpgradeDemand(Item outputItem, int planksCost) {
            this(outputItem, planksCost, 0);
        }

        public static UpgradeDemand v1Chest() {
            return new UpgradeDemand(Items.CHEST, 8, 0);
        }

        public static UpgradeDemand v2CraftingTable() {
            return new UpgradeDemand(Items.CRAFTING_TABLE, 4, 0);
        }

        // Cluster 5A — fence: 4 planks + 2 sticks → 3 fences (vanilla recipe)
        public static UpgradeDemand v3Fence() {
            return new UpgradeDemand(Items.OAK_FENCE, 4, 2);
        }

        // Cluster 5A — fence gate: 2 planks + 4 sticks → 1 gate (vanilla recipe)
        public static UpgradeDemand v3FenceGate() {
            return new UpgradeDemand(Items.OAK_FENCE_GATE, 2, 4);
        }
    }

    private record TriggerRule(String id, int priority, Predicate<TriggerContext> predicate, Predicate<TriggerContext> action) {
    }

    private record TriggerContext(ServerWorld world, LumberjackGuardEntity guard, Inventory chestInventory) {
    }

    private record V2BlockReason(UpgradeDemand nextDemand, int eligibleV1MissingChestCount) {
        private String debugReason() {
            if (UpgradeDemand.v1Chest().equals(nextDemand)) {
                return "next demand is V1 chest";
            }
            return "eligible V1 villagers still missing chest=" + eligibleV1MissingChestCount;
        }
    }
}
