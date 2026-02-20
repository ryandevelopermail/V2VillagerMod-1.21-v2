package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.village.VillagerProfession;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class ButcherMeatDistributionGoal extends Goal {
    private static final double MOVE_SPEED = 0.6D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final double RECIPIENT_SCAN_RANGE = 20.0D;
    private static final int MIN_INTERVAL_TICKS = 160;
    private static final int MAX_INTERVAL_TICKS = 320;
    private static final Set<Item> COOKED_MEATS = Set.of(
            Items.COOKED_BEEF,
            Items.COOKED_PORKCHOP,
            Items.COOKED_CHICKEN,
            Items.COOKED_MUTTON,
            Items.COOKED_RABBIT,
            Items.COOKED_COD,
            Items.COOKED_SALMON
    );

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private long nextCheckTime;
    private Stage stage = Stage.IDLE;
    private ItemStack pendingItem = ItemStack.EMPTY;
    private UUID pendingGuardId;
    private BlockPos pendingGuardPos;

    public ButcherMeatDistributionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        setTargets(jobPos, chestPos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.stage = Stage.IDLE;
    }

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!world.isDay()) {
            return false;
        }
        if (!villager.isAlive() || villager.isSleeping()) {
            return false;
        }
        if (villager.getVillagerData().getProfession() != VillagerProfession.BUTCHER) {
            return false;
        }
        if (world.getTime() < nextCheckTime) {
            return false;
        }

        Inventory chestInventory = getChestInventory(world).orElse(null);
        if (chestInventory == null || findCookedMeatSlot(chestInventory) < 0) {
            scheduleNextCheck(world);
            return false;
        }

        List<GuardEntity> recipients = findEligibleRecipients(world, ItemStack.EMPTY);
        if (recipients.isEmpty()) {
            scheduleNextCheck(world);
            return false;
        }

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
        clearPendingState();
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
                if (!isNear(chestPos)) {
                    moveTo(chestPos);
                    return;
                }

                Inventory chestInventory = getChestInventory(world).orElse(null);
                if (chestInventory == null) {
                    stage = Stage.DONE;
                    return;
                }

                int meatSlot = findCookedMeatSlot(chestInventory);
                if (meatSlot < 0) {
                    stage = Stage.DONE;
                    return;
                }

                ItemStack stack = chestInventory.getStack(meatSlot);
                List<GuardEntity> recipients = findEligibleRecipients(world, stack);
                if (recipients.isEmpty()) {
                    stage = Stage.DONE;
                    return;
                }

                pendingItem = stack.split(1);
                chestInventory.setStack(meatSlot, stack);
                chestInventory.markDirty();

                GuardEntity recipient = recipients.get(0);
                pendingGuardId = recipient.getUuid();
                pendingGuardPos = recipient.getBlockPos();
                stage = Stage.GO_TO_GUARD;
                moveTo(pendingGuardPos);
            }
            case GO_TO_GUARD -> {
                GuardEntity guard = resolvePendingGuard(world);
                if (guard == null || pendingItem.isEmpty()) {
                    returnPending(world);
                    stage = Stage.DONE;
                    return;
                }

                pendingGuardPos = guard.getBlockPos();
                if (!isNear(pendingGuardPos)) {
                    moveTo(pendingGuardPos);
                    return;
                }

                if (transferToGuard(guard)) {
                    scheduleNextCheck(world);
                    clearPendingState();
                    stage = Stage.DONE;
                } else {
                    returnPending(world);
                    stage = Stage.DONE;
                }
            }
            case IDLE, DONE -> {
            }
        }
    }

    private boolean transferToGuard(GuardEntity guard) {
        if (pendingItem.isEmpty()) {
            return false;
        }

        if (guard.getOffHandStack().isEmpty() && GuardEatFoodGoal.isConsumable(pendingItem)) {
            guard.equipStack(net.minecraft.entity.EquipmentSlot.OFFHAND, pendingItem.copy());
            return true;
        }

        ItemStack remaining = insertStack(guard.guardInventory, pendingItem.copy());
        guard.guardInventory.markDirty();
        return remaining.isEmpty();
    }

    private List<GuardEntity> findEligibleRecipients(ServerWorld world, ItemStack candidateStack) {
        Box box = new Box(villager.getBlockPos()).expand(RECIPIENT_SCAN_RANGE);
        return world.getEntitiesByClass(GuardEntity.class, box, guard -> guard.isAlive() && canReceive(guard, candidateStack))
                .stream()
                .sorted(Comparator.comparingDouble(villager::squaredDistanceTo)
                        .thenComparing(GuardEntity::getUuid, UUID::compareTo))
                .toList();
    }

    private boolean canReceive(GuardEntity guard, ItemStack candidateStack) {
        if (candidateStack.isEmpty()) {
            return true;
        }
        if (guard.getOffHandStack().isEmpty() && GuardEatFoodGoal.isConsumable(candidateStack)) {
            return true;
        }
        return canInsert(guard.guardInventory, candidateStack);
    }

    private int findCookedMeatSlot(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && COOKED_MEATS.contains(stack.getItem())) {
                return slot;
            }
        }
        return -1;
    }

    private void scheduleNextCheck(ServerWorld world) {
        nextCheckTime = world.getTime() + MIN_INTERVAL_TICKS + villager.getRandom().nextInt(MAX_INTERVAL_TICKS - MIN_INTERVAL_TICKS + 1);
    }

    private GuardEntity resolvePendingGuard(ServerWorld world) {
        if (pendingGuardId == null) {
            return null;
        }
        return world.getEntity(pendingGuardId) instanceof GuardEntity guard && guard.isAlive() ? guard : null;
    }

    private Optional<Inventory> getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, chestPos, true));
    }

    private void moveTo(BlockPos target) {
        villager.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
    }

    private boolean isNear(BlockPos target) {
        return villager.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private void returnPending(ServerWorld world) {
        if (pendingItem.isEmpty()) {
            return;
        }

        Inventory chestInventory = getChestInventory(world).orElse(villager.getInventory());
        ItemStack remaining = insertStack(chestInventory, pendingItem.copy());
        if (!remaining.isEmpty()) {
            ItemStack villagerRemaining = insertStack(villager.getInventory(), remaining);
            if (!villagerRemaining.isEmpty()) {
                villager.dropStack(villagerRemaining);
            }
        }
    }

    private void clearPendingState() {
        pendingItem = ItemStack.EMPTY;
        pendingGuardId = null;
        pendingGuardPos = null;
    }

    private boolean canInsert(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (remaining.isEmpty()) {
                return true;
            }

            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                if (inventory.isValid(slot, remaining)) {
                    int moved = Math.min(remaining.getCount(), remaining.getMaxCount());
                    remaining.decrement(moved);
                }
                continue;
            }

            if (!ItemStack.areItemsAndComponentsEqual(existing, remaining)) {
                continue;
            }
            if (!inventory.isValid(slot, remaining)) {
                continue;
            }

            int space = existing.getMaxCount() - existing.getCount();
            if (space > 0) {
                remaining.decrement(Math.min(space, remaining.getCount()));
            }
        }

        return remaining.isEmpty();
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
        GO_TO_CHEST,
        GO_TO_GUARD,
        DONE
    }
}
