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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
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
    private static final int HOE_COMPONENT_BATCH_SIZE = 2;
    private static final int WOOD_PICKAXE_PLANKS = 3;
    private static final int WOOD_PICKAXE_STICKS = 2;

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
            for (int attempt = 0; attempt < MAX_DISTRIBUTION_ATTEMPTS_PER_VISIT; attempt++) {
                long attemptsBefore = this.distributionAttempts;
                tryDemandDrivenDistribution(world, chestInventory, chestPos);
                if (this.distributionAttempts == attemptsBefore) {
                    break;
                }
                if (world.getTime() < this.nextDistributionAttemptTick) {
                    break;
                }
            }
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
        long now = world.getTime();
        if (now < nextDistributionAttemptTick) {
            return;
        }
        if (distributionAttempts >= MAX_DISTRIBUTION_ATTEMPTS_PER_VISIT) {
            return;
        }

        distributionAttempts++;
        nextDistributionAttemptTick = now + DISTRIBUTION_ATTEMPT_INTERVAL_TICKS;

        LumberjackDemandPlanner.DemandSnapshot demandSnapshot = LumberjackDemandPlanner.buildSnapshot(world, this.guard, sourceInventory);
        List<DistributionCandidate> candidates = collectCandidates(world, sourceInventory, demandSnapshot);
        if (candidates.isEmpty()) {
            return;
        }

        int selectedIndex = firstActionableCandidateIndexForCounts(
                candidates.stream().map(DistributionCandidate::transferAmount).collect(Collectors.toList()),
                candidates.stream().map(candidate -> sourceInventory.getStack(candidate.slot()).getCount()).collect(Collectors.toList())
        );
        if (selectedIndex < 0) {
            return;
        }

        DistributionCandidate selected = candidates.get(selectedIndex);
        LOGGER.debug("lumberjack distribution summary: material={} recipientProfession={} deficitScore={} targetJobPos={} targetChestPos={}",
                selected.materialType().label(),
                selected.recipient().record().recipient().getVillagerData().getProfession(),
                Math.max(selected.demandScore(), selected.recipient().deficit()),
                selected.recipient().record().jobPos(),
                selected.recipient().record().chestPos());
        ItemStack sourceStack = sourceInventory.getStack(selected.slot());
        if (sourceStack.isEmpty()) {
            return;
        }

        int transferAmount = resolveTransferAmount(selected.transferAmount(), sourceStack.getCount());
        if (transferAmount <= 0) {
            return;
        }
        ItemStack moved = sourceStack.split(transferAmount);
        sourceInventory.setStack(selected.slot(), sourceStack);
        sourceInventory.markDirty();

        Optional<Inventory> recipientInventory = Optional.ofNullable(getChestInventory(world, selected.recipient().record().chestPos()));
        if (recipientInventory.isEmpty()) {
            ItemStack putBack = insertIntoInventory(sourceInventory, moved);
            if (!putBack.isEmpty()) {
                dropStackAt(world, sourceChestPos, putBack);
            }
            return;
        }

        ItemStack remaining = insertIntoInventory(recipientInventory.get(), moved);
        recipientInventory.get().markDirty();
        if (remaining.isEmpty()) {
            return;
        }

        ItemStack putBack = insertIntoInventory(sourceInventory, remaining);
        if (!putBack.isEmpty()) {
            dropStackAt(world, sourceChestPos, putBack);
        }
    }

    private List<DistributionCandidate> collectCandidates(ServerWorld world,
                                                          Inventory sourceInventory,
                                                          LumberjackDemandPlanner.DemandSnapshot demandSnapshot) {
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
                candidates.add(new DistributionCandidate(slot, materialType, selectedRecipient, demandScore, transferAmount));
            }
        }

        candidates.sort(Comparator
                .comparingDouble(DistributionCandidate::demandScore).reversed()
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

        if (materialType == LumberjackDemandPlanner.MaterialType.PLANKS
                || materialType == LumberjackDemandPlanner.MaterialType.STICK) {
            Optional<LumberjackDemandPlanner.RecipientDemand> hoeReadyRecipient =
                    findHoeRecipeReadyRecipient(world, sourceInventory, sourceStack, materialType, rankedRecipients);
            if (hoeReadyRecipient.isPresent()) {
                return hoeReadyRecipient.get();
            }

            Optional<LumberjackDemandPlanner.RecipientDemand> toolsmithReadyRecipient =
                    findToolsmithPickaxeRecipeReadyRecipient(world, sourceInventory, sourceStack, materialType, rankedRecipients);
            if (toolsmithReadyRecipient.isPresent()) {
                return toolsmithReadyRecipient.get();
            }
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

        if (materialType == LumberjackDemandPlanner.MaterialType.PLANKS
                || materialType == LumberjackDemandPlanner.MaterialType.STICK) {
            int hoeBatch = determineHoeBatchTransferAmount(world, sourceInventory, materialType, recipient);
            if (hoeBatch > 0) {
                return hoeBatch;
            }

            int toolsmithBatch = determineToolsmithPickaxeBatchTransferAmount(world, sourceInventory, materialType, recipient);
            if (toolsmithBatch > 0) {
                return toolsmithBatch;
            }
        }

        return 1;
    }

    private Optional<LumberjackDemandPlanner.RecipientDemand> findHoeRecipeReadyRecipient(ServerWorld world,
                                                                                            Inventory sourceInventory,
                                                                                            ItemStack sourceStack,
                                                                                            LumberjackDemandPlanner.MaterialType materialType,
                                                                                            List<LumberjackDemandPlanner.RecipientDemand> rankedRecipients) {
        int sourceMaterialCount = sourceStack.getCount();
        for (LumberjackDemandPlanner.RecipientDemand recipient : rankedRecipients) {
            if (!isFarmerRecipient(recipient)) {
                continue;
            }
            int transfer = determineHoeBatchTransferAmount(world, sourceInventory, materialType, recipient);
            if (transfer <= 0 || sourceMaterialCount < transfer) {
                continue;
            }
            return Optional.of(recipient);
        }
        return Optional.empty();
    }

    private int determineHoeBatchTransferAmount(ServerWorld world,
                                                Inventory sourceInventory,
                                                LumberjackDemandPlanner.MaterialType materialType,
                                                LumberjackDemandPlanner.RecipientDemand recipient) {
        if (!isFarmerRecipient(recipient)) {
            return 0;
        }

        Inventory recipientInventory = getChestInventory(world, recipient.record().chestPos());
        if (recipientInventory == null) {
            return 0;
        }

        int recipientPlanks = countMaterialInInventory(recipientInventory, LumberjackDemandPlanner.MaterialType.PLANKS);
        int recipientSticks = countMaterialInInventory(recipientInventory, LumberjackDemandPlanner.MaterialType.STICK);
        int sourcePlanks = countMaterialInInventory(sourceInventory, LumberjackDemandPlanner.MaterialType.PLANKS);
        int sourceSticks = countMaterialInInventory(sourceInventory, LumberjackDemandPlanner.MaterialType.STICK);

        int missingPlanks = Math.max(0, HOE_COMPONENT_BATCH_SIZE - recipientPlanks);
        int missingSticks = Math.max(0, HOE_COMPONENT_BATCH_SIZE - recipientSticks);
        if (missingPlanks == 0 && missingSticks == 0) {
            return 0;
        }

        if (materialType == LumberjackDemandPlanner.MaterialType.PLANKS) {
            if (missingPlanks <= 0 || sourcePlanks <= 0) {
                return 0;
            }
            if (recipientSticks >= HOE_COMPONENT_BATCH_SIZE || sourceSticks >= missingSticks) {
                return Math.min(HOE_COMPONENT_BATCH_SIZE, missingPlanks);
            }
            return 0;
        }

        if (materialType == LumberjackDemandPlanner.MaterialType.STICK) {
            if (missingSticks <= 0 || sourceSticks <= 0) {
                return 0;
            }
            if (recipientPlanks >= HOE_COMPONENT_BATCH_SIZE || sourcePlanks >= missingPlanks) {
                return Math.min(HOE_COMPONENT_BATCH_SIZE, missingSticks);
            }
            return 0;
        }

        return 0;
    }

    private Optional<LumberjackDemandPlanner.RecipientDemand> findToolsmithPickaxeRecipeReadyRecipient(ServerWorld world,
                                                                                                         Inventory sourceInventory,
                                                                                                         ItemStack sourceStack,
                                                                                                         LumberjackDemandPlanner.MaterialType materialType,
                                                                                                         List<LumberjackDemandPlanner.RecipientDemand> rankedRecipients) {
        int sourceMaterialCount = sourceStack.getCount();
        for (LumberjackDemandPlanner.RecipientDemand recipient : rankedRecipients) {
            if (!isToolsmithRecipient(recipient)) {
                continue;
            }
            int transfer = determineToolsmithPickaxeBatchTransferAmount(world, sourceInventory, materialType, recipient);
            if (transfer <= 0 || sourceMaterialCount < transfer) {
                continue;
            }
            return Optional.of(recipient);
        }
        return Optional.empty();
    }

    private int determineToolsmithPickaxeBatchTransferAmount(ServerWorld world,
                                                             Inventory sourceInventory,
                                                             LumberjackDemandPlanner.MaterialType materialType,
                                                             LumberjackDemandPlanner.RecipientDemand recipient) {
        if (!isToolsmithRecipient(recipient)) {
            return 0;
        }

        Inventory recipientInventory = getChestInventory(world, recipient.record().chestPos());
        if (recipientInventory == null) {
            return 0;
        }

        int recipientPlanks = countMaterialInInventory(recipientInventory, LumberjackDemandPlanner.MaterialType.PLANKS);
        int recipientSticks = countMaterialInInventory(recipientInventory, LumberjackDemandPlanner.MaterialType.STICK);
        int sourcePlanks = countMaterialInInventory(sourceInventory, LumberjackDemandPlanner.MaterialType.PLANKS);
        int sourceSticks = countMaterialInInventory(sourceInventory, LumberjackDemandPlanner.MaterialType.STICK);

        return determineToolsmithPickaxeBatchTransferAmountForCounts(
                materialType,
                recipientPlanks,
                recipientSticks,
                sourcePlanks,
                sourceSticks
        );
    }

    static int determineToolsmithPickaxeBatchTransferAmountForCounts(LumberjackDemandPlanner.MaterialType materialType,
                                                                      int recipientPlanks,
                                                                      int recipientSticks,
                                                                      int sourcePlanks,
                                                                      int sourceSticks) {
        int missingPlanks = Math.max(0, WOOD_PICKAXE_PLANKS - recipientPlanks);
        int missingSticks = Math.max(0, WOOD_PICKAXE_STICKS - recipientSticks);
        if (missingPlanks == 0 && missingSticks == 0) {
            return 0;
        }

        if (materialType == LumberjackDemandPlanner.MaterialType.PLANKS) {
            if (missingPlanks <= 0 || sourcePlanks <= 0) {
                return 0;
            }
            if (recipientSticks >= WOOD_PICKAXE_STICKS || sourceSticks >= missingSticks) {
                return Math.min(sourcePlanks, missingPlanks);
            }
            return 0;
        }

        if (materialType == LumberjackDemandPlanner.MaterialType.STICK) {
            if (missingSticks <= 0 || sourceSticks <= 0) {
                return 0;
            }
            if (recipientPlanks >= WOOD_PICKAXE_PLANKS || sourcePlanks >= missingPlanks) {
                return Math.min(sourceSticks, missingSticks);
            }
            return 0;
        }

        return 0;
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

    private boolean isFarmerRecipient(LumberjackDemandPlanner.RecipientDemand recipient) {
        return recipient.record().recipient().getVillagerData().getProfession() == net.minecraft.village.VillagerProfession.FARMER;
    }

    private boolean isToolsmithRecipient(LumberjackDemandPlanner.RecipientDemand recipient) {
        return recipient.record().recipient().getVillagerData().getProfession() == net.minecraft.village.VillagerProfession.TOOLSMITH;
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
                                         int transferAmount) {
    }
}
