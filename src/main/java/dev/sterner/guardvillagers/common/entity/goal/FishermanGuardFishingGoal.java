package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagersConfig;
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
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.entity.ai.pathing.Path;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;

public class FishermanGuardFishingGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(FishermanGuardFishingGoal.class);
    private static final int WATER_SEARCH_RADIUS = 100;
    private static final double MOVE_SPEED = 0.75D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int MIN_SESSION_TICKS = 20 * 60;
    private static final int MAX_SESSION_TICKS = 20 * 60 * 3;
    private static final int TARGET_TIMEOUT_TICKS = 20 * 30;
    private static final int NAV_IDLE_RESELECT_THRESHOLD = 5;
    private static final int STAGE_IDLE_WATCHDOG_TICKS = 20 * 20;
    private static final int BUTCHER_DELIVERY_FISH_THRESHOLD = 1;

    private final FishermanGuardEntity guard;
    private Stage stage = Stage.IDLE;
    private @Nullable BlockPos targetWaterPos;
    private @Nullable BlockPos targetStandPos;
    private long sessionStartTick;
    private long sessionDurationTicks;
    private boolean halfwayLogged;
    private final List<ItemStack> sessionLoot = new ArrayList<>();
    private final List<ItemStack> butcherDeliveryLoot = new ArrayList<>();
    private final List<ItemStack> localDeliveryLoot = new ArrayList<>();
    private long nextSessionAttemptTick;
    private @Nullable ButcherTransferTarget butcherTransferTarget;
    private final Map<BlockPos, Long> invalidWaterTargetCooldowns = new HashMap<>();
    private long targetAcquiredTick;
    private int movingNavIdleStreak;
    private long lastProgressTick;
    private int sessionCastAttempts;
    private int sessionFishCaught;
    private int sessionDeliveryAttempts;
    private int sessionDeliverySuccesses;

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

        FishingTarget target = findNearestFishableWater(world);
        if (target == null) {
            this.nextSessionAttemptTick = world.getTime() + GuardVillagersConfig.fishermanInvalidWaterRescanCooldownTicks;
            return false;
        }

        this.targetWaterPos = target.waterPos();
        this.targetStandPos = target.standPos();
        this.targetAcquiredTick = world.getTime();
        return isCurrentTargetStillViable(world);
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
        this.movingNavIdleStreak = 0;
        this.lastProgressTick = this.guard.getWorld().getTime();
        this.sessionCastAttempts = 0;
        this.sessionFishCaught = 0;
        this.sessionDeliveryAttempts = 0;
        this.sessionDeliverySuccesses = 0;
        if (this.targetStandPos != null) {
            moveTo(this.targetStandPos);
        }
    }

    @Override
    public void stop() {
        this.guard.getNavigation().stop();
        this.stage = Stage.DONE;
        this.targetWaterPos = null;
        this.targetStandPos = null;
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

        runStageIdleWatchdog(world);

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
            this.nextSessionAttemptTick = world.getTime() + GuardVillagersConfig.fishermanInvalidWaterRescanCooldownTicks;
            return;
        }

        this.guard.getLookControl().lookAt(this.targetWaterPos.getX() + 0.5D, this.targetWaterPos.getY() + 0.5D, this.targetWaterPos.getZ() + 0.5D);
        if (this.targetStandPos != null && isNear(this.targetStandPos)) {
            transitionToStage(Stage.FISHING, world);
            this.sessionStartTick = world.getTime();
            this.sessionDurationTicks = MathHelper.nextInt(this.guard.getRandom(), MIN_SESSION_TICKS, MAX_SESSION_TICKS);
            LOGGER.info("fisherman [{}] started fishing session target={} stand={} duration_ticks={}",
                    this.guard.getUuidAsString(),
                    this.targetWaterPos.toShortString(),
                    this.targetStandPos.toShortString(),
                    this.sessionDurationTicks);
            return;
        }

        if ((world.getTime() - this.targetAcquiredTick) >= TARGET_TIMEOUT_TICKS) {
            blacklistInvalidCurrentTarget(world, "target timeout while moving");
            if (!retargetToNextViableWater(world)) {
                this.stage = Stage.DONE;
                this.nextSessionAttemptTick = world.getTime() + GuardVillagersConfig.fishermanInvalidWaterRescanCooldownTicks;
            }
            return;
        }

        if (!isCurrentTargetStillViable(world)) {
            blacklistInvalidCurrentTarget(world, "became invalid while moving");
            if (!retargetToNextViableWater(world)) {
                this.stage = Stage.DONE;
                this.nextSessionAttemptTick = world.getTime() + GuardVillagersConfig.fishermanInvalidWaterRescanCooldownTicks;
            }
            return;
        }

        if (this.guard.getNavigation().isIdle()) {
            this.movingNavIdleStreak++;
            if (this.movingNavIdleStreak >= NAV_IDLE_RESELECT_THRESHOLD) {
                blacklistInvalidCurrentTarget(world, "repeated nav idle while moving");
                if (!retargetToNextViableWater(world)) {
                    this.stage = Stage.DONE;
                    this.nextSessionAttemptTick = world.getTime() + GuardVillagersConfig.fishermanInvalidWaterRescanCooldownTicks;
                }
                return;
            }
            if (this.targetStandPos != null && !moveTo(this.targetStandPos)) {
                blacklistInvalidCurrentTarget(world, "pathfinder could not move to stand position");
                if (!retargetToNextViableWater(world)) {
                    this.stage = Stage.DONE;
                    this.nextSessionAttemptTick = world.getTime() + GuardVillagersConfig.fishermanInvalidWaterRescanCooldownTicks;
                }
            }
        } else {
            this.movingNavIdleStreak = 0;
            markProgress(world);
        }
    }

    private void tickFishing(ServerWorld world) {
        if (this.targetWaterPos != null) {
            this.guard.getNavigation().stop();
            this.guard.getLookControl().lookAt(this.targetWaterPos.getX() + 0.5D, this.targetWaterPos.getY() + 0.5D, this.targetWaterPos.getZ() + 0.5D);
        }

        long elapsed = world.getTime() - this.sessionStartTick;
        this.sessionCastAttempts++;
        if (!this.halfwayLogged && elapsed >= this.sessionDurationTicks / 2L) {
            this.halfwayLogged = true;
            LOGGER.info("fishing session 50% complete");
        }

        if (elapsed < this.sessionDurationTicks) {
            return;
        }

        this.sessionLoot.clear();
        this.sessionLoot.addAll(generateSessionLoot(world));
        this.sessionFishCaught = this.sessionLoot.stream().mapToInt(ItemStack::getCount).sum();
        logSessionCompletion();
        splitSessionLoot(world);

        if (isButcherDeliveryThresholdMet()) {
            this.butcherTransferTarget = findNearestButcherTransferTarget(world);
            if (this.butcherTransferTarget != null) {
                LOGGER.info("fisherman [{}] delivering cookable fish to butcher smoker {} chest {}",
                        this.guard.getUuidAsString(),
                        this.butcherTransferTarget.smokerPos().toShortString(),
                        this.butcherTransferTarget.chestPos().toShortString());
                transitionToStage(Stage.DELIVERING_TO_BUTCHER, world);
                moveTo(this.butcherTransferTarget.chestPos());
                return;
            }

            LOGGER.info("fisherman [{}] found no valid butcher smoker/chest target; falling back to local storage for cookable fish",
                    this.guard.getUuidAsString());
            this.localDeliveryLoot.addAll(copyLoot(this.butcherDeliveryLoot));
            this.butcherDeliveryLoot.clear();
        }

        transitionToStage(Stage.RETURNING_TO_STORAGE, world);
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
            this.sessionDeliveryAttempts++;
            List<ItemStack> remaining = depositLoot(world, butcherChestPos, this.butcherDeliveryLoot);
            this.butcherDeliveryLoot.clear();
            if (!remaining.isEmpty()) {
                LOGGER.info("fisherman [{}] butcher chest full/invalid, returning {} cookable fish stacks to local storage",
                        this.guard.getUuidAsString(),
                        remaining.size());
                this.localDeliveryLoot.addAll(remaining);
            }

            if (remaining.isEmpty()) {
                this.sessionDeliverySuccesses++;
            }
            transitionToStage(Stage.RETURNING_TO_STORAGE, world);
            BlockPos localStorage = getPreferredStoragePos();
            if (localStorage != null) {
                moveTo(localStorage);
            }
            return;
        }

        if (this.guard.getNavigation().isIdle()) {
            this.sessionDeliveryAttempts++;
            moveTo(butcherChestPos);
        } else {
            markProgress(world);
        }
    }

    private void tickReturningToStorage(ServerWorld world) {
        BlockPos storage = getPreferredStoragePos();
        if (storage == null) {
            this.localDeliveryLoot.clear();
            transitionToStage(Stage.DONE, world);
            this.nextSessionAttemptTick = world.getTime() + MathHelper.nextInt(this.guard.getRandom(), 200, 600);
            return;
        }

        if (isNear(storage)) {
            List<ItemStack> remaining = depositLoot(world, storage, this.localDeliveryLoot);
            this.localDeliveryLoot.clear();
            this.localDeliveryLoot.addAll(remaining);
            transitionToStage(Stage.DONE, world);
            this.nextSessionAttemptTick = world.getTime() + MathHelper.nextInt(this.guard.getRandom(), 200, 600);
            return;
        }

        if (this.guard.getNavigation().isIdle()) {
            moveTo(storage);
        } else {
            markProgress(world);
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
        LOGGER.info("fisherman [{}] session telemetry target={} stand={} casts={} fish_caught={} butcher_stacks={} local_stacks={} delivery_attempts={} delivery_successes={}",
                this.guard.getUuidAsString(),
                this.targetWaterPos != null ? this.targetWaterPos.toShortString() : "none",
                this.targetStandPos != null ? this.targetStandPos.toShortString() : "none",
                this.sessionCastAttempts,
                this.sessionFishCaught,
                this.butcherDeliveryLoot.size(),
                this.localDeliveryLoot.size(),
                this.sessionDeliveryAttempts,
                this.sessionDeliverySuccesses);
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

    private boolean moveTo(BlockPos pos) {
        return this.guard.getNavigation().startMovingTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, MOVE_SPEED);
    }

    private boolean isNear(BlockPos pos) {
        return this.guard.squaredDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private @Nullable FishingTarget findNearestFishableWater(ServerWorld world) {
        cleanupExpiredInvalidWaterTargetCooldowns(world.getTime());
        BlockPos center = this.guard.getBlockPos();
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        List<FishingTargetCandidate> candidates = new ArrayList<>();

        for (int dx = -WATER_SEARCH_RADIUS; dx <= WATER_SEARCH_RADIUS; dx++) {
            for (int dy = -WATER_SEARCH_RADIUS; dy <= WATER_SEARCH_RADIUS; dy++) {
                for (int dz = -WATER_SEARCH_RADIUS; dz <= WATER_SEARCH_RADIUS; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockPos candidateWater = cursor.toImmutable();
                    if (!isFishableWaterCandidate(world, candidateWater)) {
                        continue;
                    }

                    if (isBlacklistedWaterTarget(candidateWater, world.getTime())) {
                        continue;
                    }

                    double distance = center.getSquaredDistance(candidateWater);
                    candidates.add(new FishingTargetCandidate(candidateWater, distance));
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(FishingTargetCandidate::distanceSquared));
        for (FishingTargetCandidate candidate : candidates) {
            FishingTarget viable = toViableFishingTarget(world, candidate.waterPos());
            if (viable != null) {
                return viable;
            }
            markWaterTargetInvalid(candidate.waterPos(), world.getTime(), "failed stand/path viability");
        }

        return null;
    }

    private boolean isFishableWaterCandidate(ServerWorld world, BlockPos waterPos) {
        if (!world.getBlockState(waterPos).isOf(Blocks.WATER)) {
            return false;
        }
        if (!world.getBlockState(waterPos.up()).isAir()) {
            return false;
        }
        if (!hasOpenWaterVolume(world, waterPos)) {
            return false;
        }

        boolean skyVisible = world.isSkyVisible(waterPos.up());
        int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, waterPos.getX(), waterPos.getZ()) - 1;
        boolean surfaceQualified = waterPos.getY() >= topY;
        return (GuardVillagersConfig.fishermanRequireSkyVisibleWater && skyVisible)
                || (GuardVillagersConfig.fishermanAllowSurfaceQualifiedWater && surfaceQualified);
    }

    private @Nullable FishingTarget toViableFishingTarget(ServerWorld world, BlockPos waterPos) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos standPos = waterPos.offset(direction);
            if (!isStandableAdjacentBlock(world, standPos)) {
                continue;
            }
            Path path = this.guard.getNavigation().findPathTo(standPos, 0);
            if (path == null || !path.reachesTarget()) {
                continue;
            }
            return new FishingTarget(waterPos, standPos.toImmutable());
        }
        return null;
    }

    private boolean isStandableAdjacentBlock(ServerWorld world, BlockPos standPos) {
        if (!world.getBlockState(standPos).isAir()) {
            return false;
        }
        if (!world.getBlockState(standPos.up()).isAir()) {
            return false;
        }
        return world.getBlockState(standPos.down()).isSideSolidFullSquare(world, standPos.down(), Direction.UP);
    }

    private boolean isCurrentTargetStillViable(ServerWorld world) {
        if (this.targetWaterPos == null || this.targetStandPos == null) {
            return false;
        }
        if (!isFishableWaterCandidate(world, this.targetWaterPos)) {
            return false;
        }
        if (!isStandableAdjacentBlock(world, this.targetStandPos)) {
            return false;
        }
        Path path = this.guard.getNavigation().findPathTo(this.targetStandPos, 0);
        return path != null && path.reachesTarget();
    }

    private boolean retargetToNextViableWater(ServerWorld world) {
        FishingTarget replacement = findNearestFishableWater(world);
        if (replacement == null) {
            this.targetWaterPos = null;
            this.targetStandPos = null;
            return false;
        }
        this.targetWaterPos = replacement.waterPos();
        this.targetStandPos = replacement.standPos();
        this.targetAcquiredTick = world.getTime();
        this.movingNavIdleStreak = 0;
        markProgress(world);
        moveTo(this.targetStandPos);
        return true;
    }

    private void blacklistInvalidCurrentTarget(ServerWorld world, String reason) {
        if (this.targetWaterPos != null) {
            markWaterTargetInvalid(this.targetWaterPos, world.getTime(), reason);
        }
    }

    private void markWaterTargetInvalid(BlockPos waterPos, long worldTime, String reason) {
        long until = worldTime + GuardVillagersConfig.fishermanInvalidWaterRescanCooldownTicks;
        this.invalidWaterTargetCooldowns.put(waterPos.toImmutable(), until);
        LOGGER.debug("fisherman [{}] blacklisted water target {} until {} ({})",
                this.guard.getUuidAsString(),
                waterPos.toShortString(),
                until,
                reason);
    }

    private boolean isBlacklistedWaterTarget(BlockPos waterPos, long worldTime) {
        Long until = this.invalidWaterTargetCooldowns.get(waterPos);
        return until != null && until > worldTime;
    }

    private void cleanupExpiredInvalidWaterTargetCooldowns(long worldTime) {
        this.invalidWaterTargetCooldowns.entrySet().removeIf(entry -> entry.getValue() <= worldTime);
    }

    private boolean hasOpenWaterVolume(ServerWorld world, BlockPos waterPos) {
        int sameLevelWater = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos check = waterPos.add(dx, 0, dz);
                if (world.getBlockState(check).isOf(Blocks.WATER) && world.getBlockState(check.up()).isAir()) {
                    sameLevelWater++;
                }
            }
        }
        if (sameLevelWater < 9) {
            return false;
        }

        return world.getBlockState(waterPos.up()).isAir() && world.getBlockState(waterPos.up(2)).isAir();
    }

    private boolean isButcherDeliveryThresholdMet() {
        int butcherFishCount = this.butcherDeliveryLoot.stream().mapToInt(ItemStack::getCount).sum();
        return butcherFishCount >= BUTCHER_DELIVERY_FISH_THRESHOLD;
    }

    private void runStageIdleWatchdog(ServerWorld world) {
        if (this.stage == Stage.DONE || this.stage == Stage.IDLE || this.stage == Stage.FISHING) {
            return;
        }
        if ((world.getTime() - this.lastProgressTick) < STAGE_IDLE_WATCHDOG_TICKS) {
            return;
        }

        LOGGER.warn("fisherman [{}] stage idle watchdog triggered stage={} target={} stand={}, forcing target reacquisition",
                this.guard.getUuidAsString(),
                this.stage,
                this.targetWaterPos != null ? this.targetWaterPos.toShortString() : "none",
                this.targetStandPos != null ? this.targetStandPos.toShortString() : "none");

        if (!retargetToNextViableWater(world)) {
            this.stage = Stage.DONE;
            this.nextSessionAttemptTick = world.getTime() + GuardVillagersConfig.fishermanInvalidWaterRescanCooldownTicks;
            return;
        }
        transitionToStage(Stage.MOVING_TO_WATER, world);
    }

    private void transitionToStage(Stage nextStage, ServerWorld world) {
        this.stage = nextStage;
        this.lastProgressTick = world.getTime();
    }

    private void markProgress(ServerWorld world) {
        this.lastProgressTick = world.getTime();
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

    private record FishingTarget(BlockPos waterPos, BlockPos standPos) {
    }

    private record FishingTargetCandidate(BlockPos waterPos, double distanceSquared) {
    }
}
