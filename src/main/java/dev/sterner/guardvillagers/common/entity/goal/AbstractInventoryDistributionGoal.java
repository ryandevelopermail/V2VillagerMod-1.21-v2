package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.util.DistributionRecipientHelper;
import dev.sterner.guardvillagers.common.util.InventoryTransferSafety;
import dev.sterner.guardvillagers.common.util.UniversalDistributionRouter;
import dev.sterner.guardvillagers.common.villager.CraftingCheckLogger;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public abstract class AbstractInventoryDistributionGoal extends Goal {
    protected static final int CHECK_INTERVAL_TICKS = CraftingCheckLogger.MATERIAL_CHECK_INTERVAL_TICKS;
    protected static final int PATH_RETRY_INTERVAL_TICKS = 20;
    protected static final double TARGET_REACH_SQUARED = 4.0D;
    protected static final double MOVE_SPEED = 0.6D;
    protected static final double DEFAULT_UNIVERSAL_RECIPIENT_SCAN_RANGE = 24.0D;
    protected static final int IMMEDIATE_REQUEST_DEBOUNCE_TICKS = 30;

    protected final VillagerEntity villager;
    protected BlockPos jobPos;
    protected BlockPos chestPos;
    protected BlockPos craftingTablePos;
    protected Stage stage = Stage.IDLE;
    protected long nextCheckTime;
    protected boolean immediateCheckPending;
    protected ItemStack pendingItem = ItemStack.EMPTY;
    protected UUID pendingTargetId;
    protected BlockPos pendingTargetPos;
    protected @Nullable BlockPos currentNavigationTarget;
    protected long lastPathRequestTick = Long.MIN_VALUE;
    protected long lastImmediateRequestTick = Long.MIN_VALUE;
    protected boolean pendingUniversalRoute;

    protected AbstractInventoryDistributionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        this.villager = villager;
        setTargets(jobPos, chestPos, craftingTablePos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        BlockPos updatedJobPos = jobPos.toImmutable();
        BlockPos updatedChestPos = chestPos.toImmutable();
        BlockPos updatedCraftingTablePos = craftingTablePos != null ? craftingTablePos.toImmutable() : null;
        if (updatedJobPos.equals(this.jobPos)
                && updatedChestPos.equals(this.chestPos)
                && java.util.Objects.equals(updatedCraftingTablePos, this.craftingTablePos)) {
            return;
        }
        recoverPendingItem();
        this.jobPos = updatedJobPos;
        this.chestPos = updatedChestPos;
        this.craftingTablePos = updatedCraftingTablePos;
        this.stage = Stage.IDLE;
        this.currentNavigationTarget = null;
        this.lastPathRequestTick = Long.MIN_VALUE;
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
        if (!matchesProfession(villager)) {
            return false;
        }
        if (chestPos == null) {
            return false;
        }
        if (!immediateCheckPending && world.getTime() < nextCheckTime) {
            return false;
        }

        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return false;
        }

        boolean universalCandidate = supportsUniversalRouting() && hasUniversalTransferCandidate(world, inventory);
        if (!universalCandidate && !canStartWithInventory(world, inventory)) {
            return false;
        }

        scheduleNextCooldown(world);
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
        recoverPendingItem();
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
            case GO_TO_CHEST -> {
                if (isNear(chestPos)) {
                    Inventory sourceInventory = getChestInventory(world).orElse(null);
                    if (!selectUniversalPendingTransfer(world, sourceInventory) && !selectPendingTransfer(world, sourceInventory)) {
                        stage = Stage.DONE;
                        return;
                    }
                    stage = Stage.GO_TO_TARGET;
                    moveTo(pendingTargetPos);
                } else {
                    moveTo(chestPos);
                }
            }
            case GO_TO_TARGET -> {
                if (pendingItem.isEmpty()) {
                    stage = Stage.DONE;
                    return;
                }
                if (!refreshPendingTarget(world)) {
                    returnPendingItem(world);
                    stage = Stage.DONE;
                    return;
                }
                if (isNear(pendingTargetPos)) {
                    stage = Stage.EXECUTE_TRANSFER;
                } else {
                    moveTo(pendingTargetPos);
                }
            }
            case EXECUTE_TRANSFER -> {
                if (pendingItem.isEmpty()) {
                    stage = Stage.DONE;
                    return;
                }
                if (!refreshPendingTarget(world)) {
                    returnPendingItem(world);
                    stage = Stage.DONE;
                    return;
                }
                if (executePendingTransfer(world)) {
                    clearPendingState();
                    stage = Stage.DONE;
                    return;
                }
                if (refreshPendingTarget(world)) {
                    stage = Stage.GO_TO_TARGET;
                    return;
                }
                returnPendingItem(world);
                stage = Stage.DONE;
            }
            case IDLE, DONE -> {
            }
        }
    }

    public void requestImmediateDistribution() {
        long currentTick = villager.getWorld().getTime();
        if (immediateCheckPending || currentTick - lastImmediateRequestTick < IMMEDIATE_REQUEST_DEBOUNCE_TICKS) {
            return;
        }
        lastImmediateRequestTick = currentTick;
        immediateCheckPending = true;
        nextCheckTime = 0L;
    }

    protected void scheduleNextCooldown(ServerWorld world) {
        nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
        immediateCheckPending = false;
    }

    protected Optional<Inventory> getChestInventory(ServerWorld world) {
        if (chestPos == null) {
            return Optional.empty();
        }
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

    protected void moveTo(BlockPos target) {
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

        villager.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
        currentNavigationTarget = target.toImmutable();
        lastPathRequestTick = currentTick;
    }

    protected boolean isNear(BlockPos target) {
        if (target == null) {
            return false;
        }
        return villager.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    protected BlockPos getDistributionCenter() {
        return chestPos != null ? chestPos : (craftingTablePos != null ? craftingTablePos : villager.getBlockPos());
    }

    protected ItemStack insertStack(Inventory inventory, ItemStack stack) {
        return InventoryTransferSafety.insertStack(inventory, stack);
    }

    protected void returnPendingItem(ServerWorld world) {
        if (pendingItem.isEmpty()) {
            return;
        }
        recoverPendingItem(getChestInventory(world).orElse(null));
    }

    private void recoverPendingItem() {
        if (pendingItem.isEmpty()) {
            return;
        }
        Inventory sourceInventory = villager.getWorld() instanceof ServerWorld world
                ? getChestInventory(world).orElse(null)
                : null;
        recoverPendingItem(sourceInventory);
    }

    private void recoverPendingItem(@Nullable Inventory sourceInventory) {
        if (pendingItem.isEmpty()) {
            return;
        }
        List<ItemStack> recoveryPayload = new java.util.ArrayList<>();
        recoveryPayload.add(pendingItem.copy());
        InventoryTransferSafety.recoverPayload(villager, sourceInventory, recoveryPayload);
        clearPendingState();
    }

    protected void clearPendingState() {
        pendingItem = ItemStack.EMPTY;
        pendingTargetId = null;
        pendingTargetPos = null;
        pendingUniversalRoute = false;
        clearPendingTargetState();
    }

    protected boolean hasDistributableItem(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (isDistributableItem(inventory.getStack(slot))) {
                return true;
            }
        }
        return false;
    }

    protected ItemStack extractSingleDistributableItem(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isDistributableItem(stack)) {
                continue;
            }
            ItemStack extracted = stack.split(1);
            inventory.setStack(slot, stack);
            inventory.markDirty();
            return extracted;
        }
        return ItemStack.EMPTY;
    }

    protected boolean isInventoryAtLeastFull(Inventory inventory, double fullnessThreshold) {
        return getInventoryFullness(inventory) >= fullnessThreshold;
    }

    protected double getInventoryFullness(Inventory inventory) {
        long maxCapacity = 0L;
        long usedCapacity = 0L;

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            int slotLimit = Math.min(inventory.getMaxCountPerStack(), stack.isEmpty() ? 64 : stack.getMaxCount());
            maxCapacity += slotLimit;
            if (!stack.isEmpty()) {
                usedCapacity += Math.min(stack.getCount(), slotLimit);
            }
        }

        return maxCapacity > 0L ? (double) usedCapacity / (double) maxCapacity : 0.0D;
    }

    protected Optional<Inventory> getChestInventoryAt(ServerWorld world, BlockPos position) {
        BlockState state = world.getBlockState(position);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, position, false));
    }

    protected enum Stage {
        IDLE,
        GO_TO_CHEST,
        GO_TO_TARGET,
        EXECUTE_TRANSFER,
        DONE
    }

    protected abstract boolean isDistributableItem(ItemStack stack);

    protected boolean canStartWithInventory(ServerWorld world, Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isDistributableItem(stack)) {
                continue;
            }
            if (findPlacementStand(world, stack).isPresent()) {
                return true;
            }
        }
        return false;
    }

    protected boolean selectPendingTransfer(ServerWorld world, Inventory inventory) {
        if (inventory == null) {
            return false;
        }

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isDistributableItem(stack)) {
                continue;
            }

            Optional<ArmorStandEntity> stand = findPlacementStand(world, stack);
            if (stand.isEmpty()) {
                continue;
            }

            ItemStack extracted = stack.split(1);
            inventory.setStack(slot, stack);
            inventory.markDirty();

            pendingItem = extracted;
            onPendingItemSelected(extracted);
            pendingTargetId = stand.get().getUuid();
            pendingTargetPos = stand.get().getBlockPos();
            pendingUniversalRoute = false;
            return true;
        }
        return false;
    }

    protected boolean supportsUniversalRouting() {
        return true;
    }

    protected double getUniversalRecipientRange() {
        int configured = GuardVillagersConfig.overflowRecipientScanRange;
        return configured > 0 ? configured : DEFAULT_UNIVERSAL_RECIPIENT_SCAN_RANGE;
    }

    protected Optional<UniversalDistributionRouter.ResolvedRoute> resolveUniversalRoute(ServerWorld world, Inventory inventory) {
        return UniversalDistributionRouter.resolve(world, villager, inventory, getInventoryFullness(inventory), getUniversalRecipientRange());
    }

    protected boolean hasUniversalTransferCandidate(ServerWorld world, Inventory inventory) {
        if (!supportsUniversalRouting() || inventory == null) {
            return false;
        }
        return resolveUniversalRoute(world, inventory).isPresent();
    }

    protected boolean selectUniversalPendingTransfer(ServerWorld world, Inventory inventory) {
        if (!supportsUniversalRouting() || inventory == null) {
            return false;
        }

        Optional<UniversalDistributionRouter.ResolvedRoute> route = resolveUniversalRoute(world, inventory);
        if (route.isEmpty()) {
            return false;
        }

        UniversalDistributionRouter.ResolvedRoute resolvedRoute = route.get();
        int sourceSlot = resolvedRoute.sourceSlot();
        if (sourceSlot < 0 || sourceSlot >= inventory.size()) {
            return false;
        }

        ItemStack stack = inventory.getStack(sourceSlot);
        if (stack.isEmpty()) {
            return false;
        }

        UniversalDistributionRouter.SplitPlan splitPlan = resolvedRoute.splitPlan();
        DistributionRecipientHelper.RecipientRecord recipient = splitPlan != null
                ? splitPlan.selectedRecipient()
                : null;
        if (recipient == null) {
            List<DistributionRecipientHelper.RecipientRecord> recipients = resolvedRoute.recipients();
            if (recipients.isEmpty()) {
                return false;
            }
            recipient = recipients.getFirst();
        }
        ItemStack extracted = stack.split(1);
        inventory.setStack(sourceSlot, stack);
        inventory.markDirty();

        pendingItem = extracted;
        pendingTargetId = recipient.recipient().getUuid();
        pendingTargetPos = recipient.chestPos();
        pendingUniversalRoute = true;
        return true;
    }

    protected boolean refreshPendingTarget(ServerWorld world) {
        if (!pendingUniversalRoute) {
            return refreshTargetForPendingItem(world);
        }
        if (pendingItem.isEmpty()) {
            return false;
        }

        Optional<UniversalDistributionRouter.ResolvedRecipients> route = UniversalDistributionRouter.resolveRecipientsForItem(
                world,
                villager,
                pendingItem,
                1.0D,
                getUniversalRecipientRange());
        if (route.isEmpty()) {
            return false;
        }

        UniversalDistributionRouter.ResolvedRecipients resolvedRecipients = route.get();
        List<DistributionRecipientHelper.RecipientRecord> recipients = resolvedRecipients.recipients();
        if (recipients.isEmpty()) {
            return false;
        }

        if (pendingTargetId != null) {
            for (DistributionRecipientHelper.RecipientRecord recipient : recipients) {
                if (recipient.recipient().getUuid().equals(pendingTargetId)) {
                    pendingTargetPos = recipient.chestPos();
                    return true;
                }
            }
        }

        DistributionRecipientHelper.RecipientRecord recipient = resolvedRecipients.splitPlan() != null
                ? resolvedRecipients.splitPlan().selectedRecipient()
                : null;
        if (recipient == null) {
            recipient = recipients.getFirst();
        }
        pendingTargetId = recipient.recipient().getUuid();
        pendingTargetPos = recipient.chestPos();
        return true;
    }

    protected boolean refreshTargetForPendingItem(ServerWorld world) {
        ArmorStandEntity stand = resolveTargetStand(world);
        if (stand != null && isStandAvailableForPendingItem(world, stand)) {
            pendingTargetPos = stand.getBlockPos();
            return true;
        }

        Optional<ArmorStandEntity> selectedStand = findPlacementStand(world, pendingItem);
        if (selectedStand.isEmpty()) {
            return false;
        }
        pendingTargetId = selectedStand.get().getUuid();
        pendingTargetPos = selectedStand.get().getBlockPos();
        return true;
    }

    protected boolean executePendingTransfer(ServerWorld world) {
        if (!pendingUniversalRoute) {
            return executeTransfer(world);
        }

        if (pendingItem.isEmpty() || pendingTargetPos == null) {
            return false;
        }

        BlockState state = world.getBlockState(pendingTargetPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return false;
        }

        Inventory targetInventory = ChestBlock.getInventory(chestBlock, state, world, pendingTargetPos, false);
        if (targetInventory == null) {
            return false;
        }

        ItemStack remaining = insertStack(targetInventory, pendingItem);
        targetInventory.markDirty();
        if (remaining.isEmpty()) {
            return true;
        }

        pendingItem = remaining;
        return false;
    }

    protected boolean executeTransfer(ServerWorld world) {
        ArmorStandEntity stand = resolveTargetStand(world);
        return stand != null
                && isStandAvailableForPendingItem(world, stand)
                && placePendingItemOnStand(world, stand);
    }

    protected ArmorStandEntity resolveTargetStand(ServerWorld world) {
        if (pendingTargetId == null) {
            return null;
        }
        return world.getEntity(pendingTargetId) instanceof ArmorStandEntity stand ? stand : null;
    }

    protected void onPendingItemSelected(ItemStack pendingItem) {
    }

    protected abstract Optional<ArmorStandEntity> findPlacementStand(ServerWorld world, ItemStack stack);

    protected abstract boolean isStandAvailableForPendingItem(ServerWorld world, ArmorStandEntity stand);

    protected abstract boolean placePendingItemOnStand(ServerWorld world, ArmorStandEntity stand);

    protected abstract void clearPendingTargetState();

    protected abstract boolean matchesProfession(VillagerEntity villager);
}
