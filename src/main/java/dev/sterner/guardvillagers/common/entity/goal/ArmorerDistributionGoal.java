package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.ArmorerStandManager;
import dev.sterner.guardvillagers.common.villager.CraftingCheckLogger;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

public class ArmorerDistributionGoal extends Goal {
    private static final int CHECK_INTERVAL_TICKS = CraftingCheckLogger.MATERIAL_CHECK_INTERVAL_TICKS;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final double MOVE_SPEED = 0.6D;
    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private BlockPos craftingTablePos;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;
    private boolean immediateCheckPending;
    private ItemStack pendingItem = ItemStack.EMPTY;
    private EquipmentSlot pendingSlot;
    private UUID pendingStandId;
    private BlockPos standTargetPos;

    public ArmorerDistributionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        this.villager = villager;
        setTargets(jobPos, chestPos, craftingTablePos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.craftingTablePos = craftingTablePos != null ? craftingTablePos.toImmutable() : null;
        this.stage = Stage.IDLE;
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
        if (villager.getVillagerData().getProfession() != VillagerProfession.ARMORER) {
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
        if (!hasArmorInChest(inventory)) {
            return false;
        }
        if (!hasAvailableStand(world, inventory)) {
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

        switch (stage) {
            case GO_TO_CHEST -> {
                if (isNear(chestPos)) {
                    if (!selectItemAndStand(world)) {
                        stage = Stage.DONE;
                        return;
                    }
                    stage = Stage.GO_TO_STAND;
                    moveTo(standTargetPos);
                } else {
                    moveTo(chestPos);
                }
            }
            case GO_TO_STAND -> {
                if (pendingItem.isEmpty()) {
                    stage = Stage.DONE;
                    return;
                }
                ArmorStandEntity stand = resolveTargetStand(world);
                if (stand == null || !ArmorerStandManager.isStandAvailableForSlot(villager, stand, pendingSlot)) {
                    if (!selectStand(world)) {
                        returnPendingItem(world);
                        stage = Stage.DONE;
                        return;
                    }
                    moveTo(standTargetPos);
                    return;
                }
                if (isNear(standTargetPos)) {
                    stage = Stage.PLACE_ON_STAND;
                } else {
                    moveTo(standTargetPos);
                }
            }
            case PLACE_ON_STAND -> {
                if (pendingItem.isEmpty()) {
                    stage = Stage.DONE;
                    return;
                }
                ArmorStandEntity stand = resolveTargetStand(world);
                if (stand == null || !ArmorerStandManager.isStandAvailableForSlot(villager, stand, pendingSlot)) {
                    if (!selectStand(world)) {
                        returnPendingItem(world);
                        stage = Stage.DONE;
                        return;
                    }
                    stage = Stage.GO_TO_STAND;
                    return;
                }
                if (ArmorerStandManager.placeArmorOnStand(world, villager, stand, pendingItem)) {
                    clearPendingStand();
                    stage = Stage.DONE;
                    return;
                }
                if (selectStand(world)) {
                    stage = Stage.GO_TO_STAND;
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
        immediateCheckPending = true;
        nextCheckTime = 0L;
    }

    private boolean selectItemAndStand(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return false;
        }

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isArmorItem(stack)) {
                continue;
            }
            EquipmentSlot equipmentSlot = ((ArmorItem) stack.getItem()).getSlotType();
            Optional<ArmorStandEntity> stand = findPlacementStand(world, equipmentSlot);
            if (stand.isEmpty()) {
                continue;
            }
            ItemStack extracted = stack.split(1);
            inventory.setStack(slot, stack);
            inventory.markDirty();
            pendingItem = extracted;
            pendingSlot = equipmentSlot;
            pendingStandId = stand.get().getUuid();
            standTargetPos = stand.get().getBlockPos();
            return true;
        }
        return false;
    }

    private boolean hasArmorInChest(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (isArmorItem(inventory.getStack(slot))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAvailableStand(ServerWorld world, Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isArmorItem(stack)) {
                continue;
            }
            EquipmentSlot equipmentSlot = ((ArmorItem) stack.getItem()).getSlotType();
            if (findPlacementStand(world, equipmentSlot).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private boolean selectStand(ServerWorld world) {
        Optional<ArmorStandEntity> stand = findPlacementStand(world, pendingSlot);
        if (stand.isEmpty()) {
            return false;
        }
        pendingStandId = stand.get().getUuid();
        standTargetPos = stand.get().getBlockPos();
        return true;
    }

    private Optional<ArmorStandEntity> findPlacementStand(ServerWorld world, EquipmentSlot slot) {
        BlockPos center = chestPos != null ? chestPos : (craftingTablePos != null ? craftingTablePos : villager.getBlockPos());
        return ArmorerStandManager.findPlacementStand(world, villager, center, slot);
    }

    private void returnPendingItem(ServerWorld world) {
        if (pendingItem.isEmpty()) {
            return;
        }
        ItemStack remaining = insertStack(getChestInventory(world).orElse(villager.getInventory()), pendingItem);
        if (!remaining.isEmpty()) {
            ItemStack villagerRemaining = insertStack(villager.getInventory(), remaining);
            if (!villagerRemaining.isEmpty()) {
                villager.dropStack(villagerRemaining);
            }
            villager.getInventory().markDirty();
        }
        clearPendingStand();
    }

    private void clearPendingStand() {
        pendingItem = ItemStack.EMPTY;
        pendingSlot = null;
        pendingStandId = null;
        standTargetPos = null;
    }

    private ArmorStandEntity resolveTargetStand(ServerWorld world) {
        if (pendingStandId == null) {
            return null;
        }
        return world.getEntity(pendingStandId) instanceof ArmorStandEntity stand ? stand : null;
    }

    private boolean isArmorItem(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ArmorItem;
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

    private enum Stage {
        IDLE,
        GO_TO_CHEST,
        GO_TO_STAND,
        PLACE_ON_STAND,
        DONE
    }
}
