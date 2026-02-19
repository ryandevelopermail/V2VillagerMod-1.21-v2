package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.FishermanGuardEntity;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public class FishermanGuardFishingGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(FishermanGuardFishingGoal.class);
    private static final int WATER_SEARCH_RADIUS = 100;
    private static final double MOVE_SPEED = 0.75D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int MIN_SESSION_TICKS = 20 * 60;
    private static final int MAX_SESSION_TICKS = 20 * 60 * 3;

    private final FishermanGuardEntity guard;
    private Stage stage = Stage.IDLE;
    private @Nullable BlockPos targetWaterPos;
    private long sessionStartTick;
    private long sessionDurationTicks;
    private boolean halfwayLogged;
    private final List<ItemStack> sessionLoot = new ArrayList<>();
    private long nextSessionAttemptTick;

    public FishermanGuardFishingGoal(FishermanGuardEntity guard) {
        this.guard = guard;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (!(this.guard.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!this.guard.isAlive() || world.getTime() < this.nextSessionAttemptTick) {
            return false;
        }
        if (!this.guard.getMainHandStack().isOf(Items.FISHING_ROD)) {
            return false;
        }

        this.targetWaterPos = findNearestFishableWater();
        return this.targetWaterPos != null;
    }

    @Override
    public boolean shouldContinue() {
        return this.guard.isAlive() && this.stage != Stage.DONE;
    }

    @Override
    public void start() {
        this.stage = Stage.MOVING_TO_WATER;
        this.sessionLoot.clear();
        this.halfwayLogged = false;
        if (this.targetWaterPos != null) {
            moveTo(this.targetWaterPos);
        }
    }

    @Override
    public void stop() {
        this.guard.getNavigation().stop();
        this.stage = Stage.DONE;
        this.targetWaterPos = null;
    }

    @Override
    public void tick() {
        if (!(this.guard.getWorld() instanceof ServerWorld world)) {
            this.stage = Stage.DONE;
            return;
        }

        switch (this.stage) {
            case MOVING_TO_WATER -> tickMovingToWater(world);
            case FISHING -> tickFishing(world);
            case RETURNING_TO_STORAGE -> tickReturningToStorage(world);
            case DONE, IDLE -> {
            }
        }
    }

    private void tickMovingToWater(ServerWorld world) {
        if (this.targetWaterPos == null) {
            this.stage = Stage.DONE;
            this.nextSessionAttemptTick = world.getTime() + 200L;
            return;
        }

        this.guard.getLookControl().lookAt(this.targetWaterPos.getX() + 0.5D, this.targetWaterPos.getY() + 0.5D, this.targetWaterPos.getZ() + 0.5D);
        if (isNear(this.targetWaterPos)) {
            this.stage = Stage.FISHING;
            this.sessionStartTick = world.getTime();
            this.sessionDurationTicks = MathHelper.nextInt(this.guard.getRandom(), MIN_SESSION_TICKS, MAX_SESSION_TICKS);
            LOGGER.info("fisherman started fishing session - will complete in {} ticks", this.sessionDurationTicks);
            return;
        }

        if (this.guard.getNavigation().isIdle()) {
            moveTo(this.targetWaterPos);
        }
    }

    private void tickFishing(ServerWorld world) {
        if (this.targetWaterPos != null) {
            this.guard.getNavigation().stop();
            this.guard.getLookControl().lookAt(this.targetWaterPos.getX() + 0.5D, this.targetWaterPos.getY() + 0.5D, this.targetWaterPos.getZ() + 0.5D);
        }

        long elapsed = world.getTime() - this.sessionStartTick;
        if (!this.halfwayLogged && elapsed >= this.sessionDurationTicks / 2L) {
            this.halfwayLogged = true;
            LOGGER.info("fishing session 50% complete");
        }

        if (elapsed < this.sessionDurationTicks) {
            return;
        }

        this.sessionLoot.clear();
        this.sessionLoot.addAll(generateSessionLoot(world));
        logSessionCompletion();
        this.stage = Stage.RETURNING_TO_STORAGE;
        BlockPos storage = getPreferredStoragePos();
        if (storage != null) {
            moveTo(storage);
        }
    }

    private void tickReturningToStorage(ServerWorld world) {
        BlockPos storage = getPreferredStoragePos();
        if (storage == null) {
            this.sessionLoot.clear();
            this.stage = Stage.DONE;
            this.nextSessionAttemptTick = world.getTime() + MathHelper.nextInt(this.guard.getRandom(), 200, 600);
            return;
        }

        if (isNear(storage)) {
            depositLoot(world, storage);
            this.stage = Stage.DONE;
            this.nextSessionAttemptTick = world.getTime() + MathHelper.nextInt(this.guard.getRandom(), 200, 600);
            return;
        }

        if (this.guard.getNavigation().isIdle()) {
            moveTo(storage);
        }
    }

    private void logSessionCompletion() {
        StringBuilder caught = new StringBuilder();
        for (ItemStack stack : this.sessionLoot) {
            if (caught.length() > 0) {
                caught.append('\n');
            }
            caught.append(stack.getCount())
                    .append(' ')
                    .append(stack.getName().getString().toLowerCase());
        }

        LOGGER.info("fisherman [{}] fishing session complete, they caught:\n{}",
                this.guard.getUuidAsString(),
                caught);
    }

    private List<ItemStack> generateSessionLoot(ServerWorld world) {
        List<ItemStack> loot = new ArrayList<>();

        int luckOfSea = getRodEnchantmentLevel(Enchantments.LUCK_OF_THE_SEA);
        int lure = getRodEnchantmentLevel(Enchantments.LURE);
        int rodBonus = Math.max(0, luckOfSea + lure);

        int codCount = MathHelper.nextInt(this.guard.getRandom(), 2, 5) + rodBonus;
        loot.add(new ItemStack(Items.COD, codCount));

        if (this.guard.getRandom().nextFloat() < (0.12F + luckOfSea * 0.08F)) {
            int saddleCount = 1 + (luckOfSea >= 2 && this.guard.getRandom().nextFloat() < 0.35F ? 1 : 0);
            loot.add(new ItemStack(Items.SADDLE, saddleCount));
        }

        int pufferCount = MathHelper.nextInt(this.guard.getRandom(), 0, 1 + Math.min(lure, 2));
        if (pufferCount > 0) {
            loot.add(new ItemStack(Items.PUFFERFISH, pufferCount));
        }

        if (this.guard.getRandom().nextFloat() < (0.25F + lure * 0.05F)) {
            loot.add(new ItemStack(Items.SALMON, MathHelper.nextInt(this.guard.getRandom(), 1, 3 + luckOfSea)));
        }

        return loot;
    }

    private int getRodEnchantmentLevel(net.minecraft.registry.RegistryKey<Enchantment> enchantmentKey) {
        ItemStack rod = this.guard.getMainHandStack();
        if (rod.isEmpty()) {
            return 0;
        }

        RegistryWrapper.Impl<Enchantment> wrapper = this.guard.getRegistryManager().getWrapperOrThrow(RegistryKeys.ENCHANTMENT);
        ItemEnchantmentsComponent enchantments = net.minecraft.enchantment.EnchantmentHelper.getEnchantments(rod);
        return enchantments.getLevel(wrapper.getOrThrow(enchantmentKey));
    }

    private void depositLoot(ServerWorld world, BlockPos storagePos) {
        if (this.sessionLoot.isEmpty()) {
            return;
        }

        BlockEntity blockEntity = world.getBlockEntity(storagePos);
        if (!(blockEntity instanceof Inventory inventory)) {
            this.sessionLoot.clear();
            return;
        }

        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack stack : this.sessionLoot) {
            ItemStack left = insertStack(inventory, stack.copy());
            if (!left.isEmpty()) {
                remaining.add(left);
            }
        }

        this.sessionLoot.clear();
        this.sessionLoot.addAll(remaining);
        inventory.markDirty();
    }

    private ItemStack insertStack(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack;
        for (int i = 0; i < inventory.size() && !remaining.isEmpty(); i++) {
            ItemStack inSlot = inventory.getStack(i);
            if (inSlot.isEmpty()) {
                inventory.setStack(i, remaining);
                return ItemStack.EMPTY;
            }

            if (!ItemStack.areItemsAndComponentsEqual(inSlot, remaining)) {
                continue;
            }

            int room = inSlot.getMaxCount() - inSlot.getCount();
            if (room <= 0) {
                continue;
            }

            int move = Math.min(room, remaining.getCount());
            inSlot.increment(move);
            remaining.decrement(move);
        }

        return remaining;
    }

    private @Nullable BlockPos getPreferredStoragePos() {
        if (this.guard.getPairedJobPos() != null && this.guard.getWorld().getBlockState(this.guard.getPairedJobPos()).isOf(Blocks.BARREL)) {
            return this.guard.getPairedJobPos();
        }
        return this.guard.getPairedChestPos();
    }

    private void moveTo(BlockPos pos) {
        this.guard.getNavigation().startMovingTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, MOVE_SPEED);
    }

    private boolean isNear(BlockPos pos) {
        return this.guard.squaredDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private @Nullable BlockPos findNearestFishableWater() {
        BlockPos center = this.guard.getBlockPos();
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (int dx = -WATER_SEARCH_RADIUS; dx <= WATER_SEARCH_RADIUS; dx++) {
            for (int dy = -WATER_SEARCH_RADIUS; dy <= WATER_SEARCH_RADIUS; dy++) {
                for (int dz = -WATER_SEARCH_RADIUS; dz <= WATER_SEARCH_RADIUS; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!this.guard.getWorld().getBlockState(cursor).isOf(Blocks.WATER)) {
                        continue;
                    }
                    if (!this.guard.getWorld().getBlockState(cursor.up()).isAir()) {
                        continue;
                    }

                    double distance = center.getSquaredDistance(cursor);
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearest = cursor.toImmutable();
                    }
                }
            }
        }

        return nearest;
    }

    private enum Stage {
        IDLE,
        MOVING_TO_WATER,
        FISHING,
        RETURNING_TO_STORAGE,
        DONE
    }
}
