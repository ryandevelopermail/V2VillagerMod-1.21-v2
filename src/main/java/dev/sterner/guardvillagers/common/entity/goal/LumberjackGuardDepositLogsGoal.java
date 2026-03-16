package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.util.LumberjackDemandPlanner;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LumberjackGuardDepositLogsGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(LumberjackGuardDepositLogsGoal.class);
    private static final double ITEM_PICKUP_RADIUS = 2.5D;
    private static final int RECOVERY_CHEST_PLANK_REQUIREMENT = 8;
    private static final int DISTRIBUTION_ATTEMPT_INTERVAL_TICKS = 20;
    private static final int MAX_DISTRIBUTION_ATTEMPTS_PER_VISIT = 6;
    static final int BUTCHER_LOG_TRANSFER_CLAMP = 1;

    private final LumberjackGuardEntity guard;
    private long nextDistributionAttemptTick;
    private int distributionAttempts;
    private final EnumMap<LumberjackDemandPlanner.MaterialType, Integer> materialRecipientCursor =
            new EnumMap<>(LumberjackDemandPlanner.MaterialType.class);

    public LumberjackGuardDepositLogsGoal(LumberjackGuardEntity guard) {
        this.guard = guard;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    public static void runOpportunisticDemandDistribution(ServerWorld world, LumberjackGuardEntity guard) {
        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos == null) {
            return;
        }

        LumberjackGuardDepositLogsGoal helper = new LumberjackGuardDepositLogsGoal(guard);
        Inventory chestInventory = helper.getChestInventory(world, chestPos);
        if (chestInventory == null) {
            return;
        }

        helper.tryDemandDrivenDistribution(world, chestInventory, chestPos);
    }

    public static List<String> runMidpointAuditedDemandDistribution(ServerWorld world,
                                                                     LumberjackGuardEntity guard,
                                                                     LumberjackVillageDemandAudit.AuditSnapshot auditSnapshot) {
        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos == null) {
            return List.of();
        }

        LumberjackGuardDepositLogsGoal helper = new LumberjackGuardDepositLogsGoal(guard);
        Inventory chestInventory = helper.getChestInventory(world, chestPos);
        if (chestInventory == null) {
            return List.of();
        }

        return helper.tryDemandDrivenDistributionWithAudit(world, chestInventory, chestPos, auditSnapshot);
    }

    @Override
    public boolean canStart() {
        return this.guard.getWorld() instanceof ServerWorld
                && this.guard.isAlive()
                && this.guard.getWorkflowStage() == LumberjackGuardEntity.WorkflowStage.DEPOSITING
                && !this.guard.getGatheredStackBuffer().isEmpty();
    }

    @Override
    public void start() {
        this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.MOVING_TO_CHEST);
        this.nextDistributionAttemptTick = Long.MIN_VALUE;
        this.distributionAttempts = 0;
    }

    @Override
    public boolean shouldContinue() {
        return this.guard.getWorkflowStage() == LumberjackGuardEntity.WorkflowStage.MOVING_TO_CHEST
                && !this.guard.getGatheredStackBuffer().isEmpty();
    }

    @Override
    public void tick() {
        if (!(this.guard.getWorld() instanceof ServerWorld world)) {
            return;
        }
        BlockPos chestPos = this.guard.getPairedChestPos();
        if (chestPos == null) {
            if (attemptChestRecovery(world, "paired chest missing", false, null)) {
                return;
            }
            lastResortDropAll(world, this.guard.getBlockPos(), "no paired chest and recovery could not proceed");
            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.IDLE);
            return;
        }

        if (this.guard.squaredDistanceTo(Vec3d.ofCenter(chestPos)) > 9.0D) {
            collectNearbyWoodDrops(world);
            this.guard.getNavigation().startMovingTo(chestPos.getX() + 0.5D, chestPos.getY(), chestPos.getZ() + 0.5D, 0.8D);
            return;
        }

        Inventory chestInventory = getChestInventory(world, chestPos);
        if (chestInventory == null) {
            if (attemptChestRecovery(world, "paired chest inventory unavailable", true, chestPos)) {
                return;
            }
            lastResortDropAll(world, chestPos, "paired chest inventory unavailable and recovery could not proceed");
            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.IDLE);
            return;
        }

        List<ItemStack> buffer = this.guard.getGatheredStackBuffer();
        for (int i = 0; i < buffer.size(); i++) {
            ItemStack stack = buffer.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack remaining = insertIntoInventory(chestInventory, stack);
            buffer.set(i, remaining);
        }
        buffer.removeIf(ItemStack::isEmpty);

        if (buffer.isEmpty()) {
            if (LumberjackChestTriggerController.isBootstrapSatisfied(world, this.guard)) {
                this.guard.setBootstrapComplete(true);
            }
            runDemandDrivenDistributionBatch(world, chestInventory, chestPos, Map.of());
            this.guard.requestTriggerEvaluation();
            LumberjackChestTriggerController.runImmediateVillageUpgradePass(world, this.guard);
            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.IDLE);
            return;
        }

        tryDemandDrivenDistribution(world, chestInventory, chestPos);

        if (buffer.isEmpty()) {
            this.guard.requestTriggerEvaluation();
            LumberjackChestTriggerController.runImmediateVillageUpgradePass(world, this.guard);
            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.IDLE);
        }
    }

    private void tryDemandDrivenDistribution(ServerWorld world, Inventory sourceInventory, BlockPos sourceChestPos) {
        executeDemandDrivenDistributionAttempt(world, sourceInventory, sourceChestPos, Map.of(), true, -1);
    }

    private void runDemandDrivenDistributionBatch(ServerWorld world,
                                                  Inventory sourceInventory,
                                                  BlockPos sourceChestPos,
                                                  Map<UUID, Integer> prioritizedRecipientRanks) {
        for (int passIndex = 0; passIndex < MAX_DISTRIBUTION_ATTEMPTS_PER_VISIT; passIndex++) {
            Optional<DistributionCandidate> selected = executeDemandDrivenDistributionAttempt(
                    world,
                    sourceInventory,
                    sourceChestPos,
                    prioritizedRecipientRanks,
                    false,
                    passIndex);
            if (selected.isEmpty()) {
                break;
            }
        }

        this.nextDistributionAttemptTick = world.getTime() + DISTRIBUTION_ATTEMPT_INTERVAL_TICKS;
    }

    private List<String> tryDemandDrivenDistributionWithAudit(ServerWorld world,
                                                              Inventory sourceInventory,
                                                              BlockPos sourceChestPos,
                                                              LumberjackVillageDemandAudit.AuditSnapshot auditSnapshot) {
        Map<UUID, Integer> prioritizedRecipientRanks = auditSnapshot.prioritizedRecipientRanks();
        Set<String> recipientsChosen = new LinkedHashSet<>();

        for (int passIndex = 0; passIndex < MAX_DISTRIBUTION_ATTEMPTS_PER_VISIT; passIndex++) {
            Optional<DistributionCandidate> selected = executeDemandDrivenDistributionAttempt(
                    world,
                    sourceInventory,
                    sourceChestPos,
                    prioritizedRecipientRanks,
                    false,
                    passIndex);
            if (selected.isEmpty()) {
                break;
            }

            DistributionCandidate candidate = selected.get();
            recipientsChosen.add(candidate.materialType().label() + "->"
                    + candidate.recipient().record().recipient().getVillagerData().getProfession() + "@"
                    + candidate.recipient().record().recipient().getUuidAsString());
        }

        return List.copyOf(recipientsChosen);
    }

    private Optional<DistributionCandidate> executeDemandDrivenDistributionAttempt(ServerWorld world,
                                                                                    Inventory sourceInventory,
                                                                                    BlockPos sourceChestPos,
                                                                                    Map<UUID, Integer> prioritizedRecipientRanks,
                                                                                    boolean enforceThrottle,
                                                                                    int passIndex) {
        long now = world.getTime();
        if (enforceThrottle && now < nextDistributionAttemptTick) {
            return Optional.empty();
        }
        if (distributionAttempts >= MAX_DISTRIBUTION_ATTEMPTS_PER_VISIT) {
            return Optional.empty();
        }

        LumberjackDemandPlanner.DemandSnapshot demandSnapshot = LumberjackDemandPlanner.buildSnapshot(world, this.guard, sourceInventory);
        List<DistributionCandidate> candidates = collectCandidates(world, sourceInventory, demandSnapshot, prioritizedRecipientRanks);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        int selectedIndex = firstActionableCandidateIndexForCounts(
                candidates.stream().map(DistributionCandidate::transferAmount).collect(Collectors.toList()),
                candidates.stream().map(candidate -> sourceInventory.getStack(candidate.slot()).getCount()).collect(Collectors.toList())
        );
        if (selectedIndex < 0) {
            return Optional.empty();
        }

        DistributionCandidate selected = candidates.get(selectedIndex);
        LOGGER.debug("lumberjack distribution summary: material={} recipientProfession={} deficitScore={} toolRecipeDemandPath={} targetJobPos={} targetChestPos={} auditedPriority={}",
                selected.materialType().label(),
                selected.recipient().record().recipient().getVillagerData().getProfession(),
                Math.max(selected.demandScore(), selected.recipient().deficit()),
                selected.recipient().toolRecipeDemandRoute(),
                selected.recipient().record().jobPos(),
                selected.recipient().record().chestPos(),
                selected.auditPriorityRank());
        ItemStack sourceStack = sourceInventory.getStack(selected.slot());
        if (sourceStack.isEmpty()) {
            return Optional.empty();
        }

        int transferAmount = resolveTransferAmount(selected.transferAmount(), sourceStack.getCount());
        if (transferAmount <= 0) {
            return Optional.empty();
        }

        distributionAttempts++;
        if (enforceThrottle) {
            nextDistributionAttemptTick = now + DISTRIBUTION_ATTEMPT_INTERVAL_TICKS;
        }
        LOGGER.debug("lumberjack distribution transfer: material={} recipientUuid={} recipientProfession={} transferCount={} visitPassIndex={} distributionAttempts={}/{}",
                selected.materialType().label(),
                selected.recipient().record().recipient().getUuidAsString(),
                selected.recipient().record().recipient().getVillagerData().getProfession(),
                transferAmount,
                passIndex,
                this.distributionAttempts,
                MAX_DISTRIBUTION_ATTEMPTS_PER_VISIT);

        ItemStack moved = sourceStack.split(transferAmount);
        sourceInventory.setStack(selected.slot(), sourceStack);
        sourceInventory.markDirty();

        Optional<Inventory> recipientInventory = Optional.ofNullable(getChestInventory(world, selected.recipient().record().chestPos()));
        if (recipientInventory.isEmpty()) {
            ItemStack putBack = insertIntoInventory(sourceInventory, moved);
            if (!putBack.isEmpty()) {
                dropStackAt(world, sourceChestPos, putBack);
            }
            return Optional.empty();
        }

        ItemStack remaining = insertIntoInventory(recipientInventory.get(), moved);
        recipientInventory.get().markDirty();
        if (remaining.isEmpty()) {
            return Optional.of(selected);
        }

        ItemStack putBack = insertIntoInventory(sourceInventory, remaining);
        if (!putBack.isEmpty()) {
            dropStackAt(world, sourceChestPos, putBack);
        }
        return Optional.of(selected);
    }

    private List<DistributionCandidate> collectCandidates(ServerWorld world,
                                                          Inventory sourceInventory,
                                                          LumberjackDemandPlanner.DemandSnapshot demandSnapshot,
                                                          Map<UUID, Integer> prioritizedRecipientRanks) {
        List<DistributionCandidate> candidates = new ArrayList<>();
        for (int slot = 0; slot < sourceInventory.size(); slot++) {
            ItemStack stack = sourceInventory.getStack(slot);
            LumberjackDemandPlanner.MaterialType materialType = LumberjackDemandPlanner.MaterialType.fromStack(stack);
            if (materialType == null) {
                continue;
            }

            List<LumberjackDemandPlanner.RecipientDemand> rankedRecipients = demandSnapshot.rankedRecipientsFor(materialType);
            if (rankedRecipients.isEmpty()) {
                continue;
            }

            double demandScore = demandSnapshot.weightedDeficitFor(materialType);
            LumberjackDemandPlanner.RecipientDemand selectedRecipient =
                    selectRecipientForMaterial(world, sourceInventory, stack, materialType, rankedRecipients);
            int transferAmount =
                    determineTransferAmount(world, sourceInventory, stack, materialType, selectedRecipient, rankedRecipients);
            if (transferAmount > 0) {
                int auditPriorityRank = Integer.MAX_VALUE;
                if ((materialType == LumberjackDemandPlanner.MaterialType.PLANKS
                        || materialType == LumberjackDemandPlanner.MaterialType.STICK)
                        && prioritizedRecipientRanks.containsKey(selectedRecipient.record().recipient().getUuid())) {
                    auditPriorityRank = prioritizedRecipientRanks.get(selectedRecipient.record().recipient().getUuid());
                }
                candidates.add(new DistributionCandidate(slot, materialType, selectedRecipient, demandScore, transferAmount, auditPriorityRank));
            }
        }

        candidates.sort(Comparator
                .comparingInt(DistributionCandidate::auditPriorityRank)
                .thenComparing(Comparator.comparingDouble(DistributionCandidate::demandScore).reversed())
                .thenComparing(Comparator.comparingInt((DistributionCandidate candidate) -> candidate.recipient().deficit()).reversed())
                .thenComparingDouble(candidate -> candidate.recipient().record().sourceSquaredDistance())
                .thenComparing(candidate -> candidate.recipient().record().recipient().getUuid(), UUID::compareTo));
        return candidates;
    }

    private LumberjackDemandPlanner.RecipientDemand selectRecipientForMaterial(ServerWorld world,
                                                                                Inventory sourceInventory,
                                                                                ItemStack sourceStack,
                                                                                LumberjackDemandPlanner.MaterialType materialType,
                                                                                List<LumberjackDemandPlanner.RecipientDemand> rankedRecipients) {
        if (materialType == LumberjackDemandPlanner.MaterialType.CHARCOAL) {
            return pickRoundRobinRecipient(materialType, rankedRecipients);
        }

        return rankedRecipients.getFirst();
    }

    private LumberjackDemandPlanner.RecipientDemand pickRoundRobinRecipient(LumberjackDemandPlanner.MaterialType materialType,
                                                                             List<LumberjackDemandPlanner.RecipientDemand> recipients) {
        int cursor = materialRecipientCursor.getOrDefault(materialType, 0);
        int index = Math.floorMod(cursor, recipients.size());
        materialRecipientCursor.put(materialType, cursor + 1);
        return recipients.get(index);
    }

    private int determineTransferAmount(ServerWorld world,
                                        Inventory sourceInventory,
                                        ItemStack sourceStack,
                                        LumberjackDemandPlanner.MaterialType materialType,
                                        LumberjackDemandPlanner.RecipientDemand recipient,
                                        List<LumberjackDemandPlanner.RecipientDemand> allRecipients) {
        if (materialType == LumberjackDemandPlanner.MaterialType.CHARCOAL) {
            int divisor = Math.max(1, allRecipients.size());
            return Math.max(1, sourceStack.getCount() / divisor);
        }

        int plannedTransferAmount;
        if (materialType == LumberjackDemandPlanner.MaterialType.PLANKS
                || materialType == LumberjackDemandPlanner.MaterialType.STICK
                || materialType == LumberjackDemandPlanner.MaterialType.LOGS) {
            int maxDeficitBound = Math.max(1, Math.min(recipient.deficit(), 8));
            plannedTransferAmount = Math.min(sourceStack.getCount(), maxDeficitBound);
        } else {
            plannedTransferAmount = 1;
        }

        return clampTransferForRecipient(
                materialType,
                recipient.record().recipient().getVillagerData().getProfession(),
                plannedTransferAmount);
    }


    static int clampTransferForRecipient(LumberjackDemandPlanner.MaterialType materialType,
                                         VillagerProfession profession,
                                         int transferAmount) {
        if (materialType == LumberjackDemandPlanner.MaterialType.LOGS
                && profession == VillagerProfession.BUTCHER) {
            return Math.min(transferAmount, BUTCHER_LOG_TRANSFER_CLAMP);
        }
        return transferAmount;
    }

    static int resolveTransferAmount(int requestedTransferAmount, int sourceStackCount) {
        if (requestedTransferAmount <= 0 || sourceStackCount <= 0) {
            return 0;
        }
        return Math.min(requestedTransferAmount, sourceStackCount);
    }

    static int firstActionableCandidateIndexForCounts(List<Integer> requestedTransfers, List<Integer> sourceCounts) {
        if (requestedTransfers.size() != sourceCounts.size()) {
            throw new IllegalArgumentException("requestedTransfers and sourceCounts must have matching sizes");
        }

        for (int index = 0; index < requestedTransfers.size(); index++) {
            int transferAmount = resolveTransferAmount(requestedTransfers.get(index), sourceCounts.get(index));
            if (transferAmount > 0) {
                return index;
            }
        }

        return -1;
    }

    private int countMaterialInInventory(Inventory inventory, LumberjackDemandPlanner.MaterialType materialType) {
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (materialType.matches(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    @Override
    public void stop() {
        this.guard.getNavigation().stop();
        if (this.guard.getWorkflowStage() == LumberjackGuardEntity.WorkflowStage.MOVING_TO_CHEST) {
            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.IDLE);
        }
    }

    private Inventory getChestInventory(ServerWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }
        return ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
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
        inventory.markDirty();
        return remaining;
    }


    private void collectNearbyWoodDrops(ServerWorld world) {
        Box pickupBox = this.guard.getBoundingBox().expand(ITEM_PICKUP_RADIUS, 1.0D, ITEM_PICKUP_RADIUS);
        List<ItemEntity> nearbyItems = world.getEntitiesByClass(ItemEntity.class,
                pickupBox,
                entity -> entity.isAlive() && !entity.getStack().isEmpty() && isGatherableWoodDrop(entity.getStack()));

        for (ItemEntity itemEntity : nearbyItems) {
            bufferStack(itemEntity.getStack().copy());
            itemEntity.discard();
        }
    }

    private void bufferStack(ItemStack incoming) {
        List<ItemStack> buffer = this.guard.getGatheredStackBuffer();
        for (ItemStack existing : buffer) {
            if (ItemStack.areItemsAndComponentsEqual(existing, incoming) && existing.getCount() < existing.getMaxCount()) {
                int transfer = Math.min(existing.getMaxCount() - existing.getCount(), incoming.getCount());
                existing.increment(transfer);
                incoming.decrement(transfer);
                if (incoming.isEmpty()) {
                    return;
                }
            }
        }

        if (!incoming.isEmpty()) {
            buffer.add(incoming);
        }
    }

    private boolean isGatherableWoodDrop(ItemStack stack) {
        return stack.isIn(ItemTags.LOGS)
                || stack.isIn(ItemTags.PLANKS)
                || stack.isOf(Items.STICK)
                || stack.isOf(Items.CHARCOAL);
    }


    private boolean attemptChestRecovery(ServerWorld world, String reason, boolean clearInvalidPairing, BlockPos previousChestPos) {
        BlockPos tablePos = this.guard.getPairedCraftingTablePos();
        if (tablePos == null) {
            LOGGER.warn("Lumberjack Guard {} cannot recover chest during deposit: {} (no paired crafting table)",
                    this.guard.getUuidAsString(), reason);
            return false;
        }

        if (clearInvalidPairing) {
            this.guard.setPairedChestPos(null);
        }

        Inventory pairedChestInventory = LumberjackGuardCraftingGoal.resolveChestInventoryForGuard(world, this.guard);
        LumberjackGuardCraftingGoal.ensureChestCraftingSuppliesForRecovery(this.guard, pairedChestInventory);

        int availablePlanks = countInInventoryAndBuffer(pairedChestInventory, stack -> stack.isIn(ItemTags.PLANKS));
        boolean hasChest = countInInventoryAndBuffer(pairedChestInventory, stack -> stack.isOf(Items.CHEST)) > 0;

        if ((!hasChest && availablePlanks >= RECOVERY_CHEST_PLANK_REQUIREMENT)
                || (hasChest && this.guard.getPairedChestPos() == null)) {
            if (LumberjackGuardCraftingGoal.craftChestForRecovery(this.guard, pairedChestInventory)
                    && LumberjackGuardCraftingGoal.tryPlaceAndBindChestForRecovery(world, this.guard, pairedChestInventory)) {
                LOGGER.info("Lumberjack Guard {} recovered chest during deposit after {}",
                        this.guard.getUuidAsString(), reason);
                this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.MOVING_TO_CHEST);
                return true;
            }
        }

        int planksAfterConversion = countInInventoryAndBuffer(pairedChestInventory, stack -> stack.isIn(ItemTags.PLANKS));
        if (planksAfterConversion < RECOVERY_CHEST_PLANK_REQUIREMENT && this.guard.getPairedChestPos() == null) {
            if (!this.guard.isActiveSession()
                    && LumberjackGuardChopTreesGoal.scheduleSingleTreeRecoverySession(world, this.guard)) {
                LOGGER.info("Lumberjack Guard {} lacks recovery planks ({} / {}) after {}; scheduled constrained single-tree cycle",
                        this.guard.getUuidAsString(), planksAfterConversion, RECOVERY_CHEST_PLANK_REQUIREMENT, reason);
                return true;
            }

            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.DEPOSITING);
            LOGGER.info("Lumberjack Guard {} awaiting constrained recovery cycle after {} (planks {} / {})",
                    this.guard.getUuidAsString(), reason, planksAfterConversion, RECOVERY_CHEST_PLANK_REQUIREMENT);
            return true;
        }

        if (this.guard.getPairedChestPos() != null) {
            this.guard.setWorkflowStage(LumberjackGuardEntity.WorkflowStage.MOVING_TO_CHEST);
            return true;
        }

        if (previousChestPos != null) {
            LOGGER.warn("Lumberjack Guard {} recovery stalled after {} at {}",
                    this.guard.getUuidAsString(), reason, previousChestPos);
        }
        return false;
    }

    private int countInInventoryAndBuffer(Inventory inventory, java.util.function.Predicate<ItemStack> predicate) {
        int total = 0;
        if (inventory != null) {
            for (int slot = 0; slot < inventory.size(); slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (!stack.isEmpty() && predicate.test(stack)) {
                    total += stack.getCount();
                }
            }
        }

        for (ItemStack stack : this.guard.getGatheredStackBuffer()) {
            if (!stack.isEmpty() && predicate.test(stack)) {
                total += stack.getCount();
            }
        }

        return total;
    }

    private void lastResortDropAll(ServerWorld world, BlockPos pos, String reason) {
        LOGGER.warn("Lumberjack Guard {} dropping buffered items as last resort: {}", this.guard.getUuidAsString(), reason);
        dropAll(world, pos);
    }

    private void dropAll(ServerWorld world, BlockPos pos) {
        for (ItemStack stack : this.guard.getGatheredStackBuffer()) {
            if (!stack.isEmpty()) {
                world.spawnEntity(new ItemEntity(world, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, stack));
            }
        }
        this.guard.getGatheredStackBuffer().clear();
    }

    private void dropStackAt(ServerWorld world, BlockPos pos, ItemStack stack) {
        if (!stack.isEmpty()) {
            world.spawnEntity(new ItemEntity(world, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, stack));
        }
    }

    private record DistributionCandidate(int slot,
                                         LumberjackDemandPlanner.MaterialType materialType,
                                         LumberjackDemandPlanner.RecipientDemand recipient,
                                         double demandScore,
                                         int transferAmount,
                                         int auditPriorityRank) {
    }
}
