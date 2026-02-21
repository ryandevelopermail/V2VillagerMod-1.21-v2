package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.FishermanGuardEntity;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import net.minecraft.block.ChestBlock;
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
    private final List<ItemStack> butcherDeliveryLoot = new ArrayList<>();
    private final List<ItemStack> localDeliveryLoot = new ArrayList<>();
    private long nextSessionAttemptTick;
    private @Nullable ButcherTransferTarget butcherTransferTarget;

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
        this.butcherDeliveryLoot.clear();
        this.localDeliveryLoot.clear();
        this.halfwayLogged = false;
        this.butcherTransferTarget = null;
        if (this.targetWaterPos != null) {
            moveTo(this.targetWaterPos);
        }
    }

    @Override
    public void stop() {
        this.guard.getNavigation().stop();
        this.stage = Stage.DONE;
        this.targetWaterPos = null;
        this.butcherTransferTarget = null;
        this.butcherDeliveryLoot.clear();
        this.localDeliveryLoot.clear();
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
            case DELIVERING_TO_BUTCHER -> tickDeliveringToButcher(world);
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
        splitSessionLoot(world);

        if (!this.butcherDeliveryLoot.isEmpty()) {
            this.butcherTransferTarget = findNearestButcherTransferTarget(world);
            if (this.butcherTransferTarget != null) {
                LOGGER.info("fisherman [{}] delivering cookable fish to butcher smoker {} chest {}",
                        this.guard.getUuidAsString(),
                        this.butcherTransferTarget.smokerPos().toShortString(),
                        this.butcherTransferTarget.chestPos().toShortString());
                this.stage = Stage.DELIVERING_TO_BUTCHER;
                moveTo(this.butcherTransferTarget.chestPos());
                return;
            }

            LOGGER.info("fisherman [{}] found no valid butcher smoker/chest target; falling back to local storage for cookable fish",
                    this.guard.getUuidAsString());
            this.localDeliveryLoot.addAll(copyLoot(this.butcherDeliveryLoot));
            this.butcherDeliveryLoot.clear();
        }

        this.stage = Stage.RETURNING_TO_STORAGE;
        BlockPos storage = getPreferredStoragePos();
        if (storage != null) {
            moveTo(storage);
        }
    }

    private void tickDeliveringToButcher(ServerWorld world) {
        if (this.butcherTransferTarget == null) {
            this.stage = Stage.RETURNING_TO_STORAGE;
            return;
        }

        BlockPos butcherChestPos = this.butcherTransferTarget.chestPos();
        if (isNear(butcherChestPos)) {
            List<ItemStack> remaining = depositLoot(world, butcherChestPos, this.butcherDeliveryLoot);
            this.butcherDeliveryLoot.clear();
            if (!remaining.isEmpty()) {
                LOGGER.info("fisherman [{}] butcher chest full/invalid, returning {} cookable fish stacks to local storage",
                        this.guard.getUuidAsString(),
                        remaining.size());
                this.localDeliveryLoot.addAll(remaining);
            }

            this.stage = Stage.RETURNING_TO_STORAGE;
            BlockPos localStorage = getPreferredStoragePos();
            if (localStorage != null) {
                moveTo(localStorage);
            }
            return;
        }

        if (this.guard.getNavigation().isIdle()) {
            moveTo(butcherChestPos);
        }
    }

    private void tickReturningToStorage(ServerWorld world) {
        BlockPos storage = getPreferredStoragePos();
        if (storage == null) {
            this.localDeliveryLoot.clear();
            this.stage = Stage.DONE;
            this.nextSessionAttemptTick = world.getTime() + MathHelper.nextInt(this.guard.getRandom(), 200, 600);
            return;
        }

        if (isNear(storage)) {
            List<ItemStack> remaining = depositLoot(world, storage, this.localDeliveryLoot);
            this.localDeliveryLoot.clear();
            this.localDeliveryLoot.addAll(remaining);
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

    private List<ItemStack> depositLoot(ServerWorld world, BlockPos storagePos, List<ItemStack> lootToStore) {
        if (lootToStore.isEmpty()) {
            return List.of();
        }

        BlockEntity blockEntity = world.getBlockEntity(storagePos);
        if (!(blockEntity instanceof Inventory inventory)) {
            return copyLoot(lootToStore);
        }

        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack stack : lootToStore) {
            ItemStack left = insertStack(inventory, stack.copy());
            if (!left.isEmpty()) {
                remaining.add(left);
            }
        }
        inventory.markDirty();
        return remaining;
    }

    private void splitSessionLoot(ServerWorld world) {
        this.butcherDeliveryLoot.clear();
        this.localDeliveryLoot.clear();

        for (ItemStack stack : this.sessionLoot) {
            if (isCookableRawFish(world, stack)) {
                this.butcherDeliveryLoot.add(stack.copy());
            } else {
                this.localDeliveryLoot.add(stack.copy());
            }
        }
    }

    private boolean isCookableRawFish(ServerWorld world, ItemStack stack) {
        if (stack.isEmpty() || !isRawFish(stack)) {
            return false;
        }

        return world.getRecipeManager().getFirstMatch(
                net.minecraft.recipe.RecipeType.SMOKING,
                new net.minecraft.recipe.input.SingleStackRecipeInput(stack.copy()),
                world
        ).isPresent();
    }

    private boolean isRawFish(ItemStack stack) {
        return stack.isOf(Items.COD)
                || stack.isOf(Items.SALMON)
                || stack.isOf(Items.TROPICAL_FISH)
                || stack.isOf(Items.PUFFERFISH);
    }

    private @Nullable ButcherTransferTarget findNearestButcherTransferTarget(ServerWorld world) {
        BlockPos center = this.guard.getBlockPos();
        ButcherTransferTarget bestTarget = null;
        double nearestDistance = Double.MAX_VALUE;
        int pairingRange = MathHelper.ceil(JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE);

        for (BlockPos smokerPos : BlockPos.iterate(center.add(-WATER_SEARCH_RADIUS, -WATER_SEARCH_RADIUS, -WATER_SEARCH_RADIUS),
                center.add(WATER_SEARCH_RADIUS, WATER_SEARCH_RADIUS, WATER_SEARCH_RADIUS))) {
            if (!world.getBlockState(smokerPos).isOf(Blocks.SMOKER)) {
                continue;
            }

            BlockPos pairedChest = findPairedChestForSmoker(world, smokerPos.toImmutable(), pairingRange);
            if (pairedChest == null) {
                continue;
            }

            double distance = center.getSquaredDistance(smokerPos);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                bestTarget = new ButcherTransferTarget(smokerPos.toImmutable(), pairedChest);
            }
        }

        return bestTarget;
    }

    private @Nullable BlockPos findPairedChestForSmoker(ServerWorld world, BlockPos smokerPos, int pairingRange) {
        BlockPos nearestChest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (BlockPos candidatePos : BlockPos.iterate(smokerPos.add(-pairingRange, -pairingRange, -pairingRange),
                smokerPos.add(pairingRange, pairingRange, pairingRange))) {
            if (!(world.getBlockState(candidatePos).getBlock() instanceof ChestBlock)) {
                continue;
            }
            if (!smokerPos.isWithinDistance(candidatePos, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE)) {
                continue;
            }

            BlockEntity blockEntity = world.getBlockEntity(candidatePos);
            if (!(blockEntity instanceof Inventory)) {
                continue;
            }

            double distance = smokerPos.getSquaredDistance(candidatePos);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestChest = candidatePos.toImmutable();
            }
        }

        return nearestChest;
    }

    private List<ItemStack> copyLoot(List<ItemStack> loot) {
        List<ItemStack> copies = new ArrayList<>(loot.size());
        for (ItemStack stack : loot) {
            copies.add(stack.copy());
        }
        return copies;
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
        DELIVERING_TO_BUTCHER,
        RETURNING_TO_STORAGE,
        DONE
    }

    private record ButcherTransferTarget(BlockPos smokerPos, BlockPos chestPos) {
    }
}
