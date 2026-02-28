package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.MasonGuardEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.ShovelItem;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;

public class MasonMiningStairGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(MasonMiningStairGoal.class);
    private static final double MOVE_SPEED = 0.7D;
    private static final double TARGET_REACH_SQUARED = 1.8D;
    private static final int NO_PROGRESS_LIMIT_TICKS = 600;
    private static final int MINING_DURATION_MIN_TICKS = 1200;
    private static final int MINING_DURATION_MAX_TICKS = 3600;
    private static final int MINING_RUN_COOLDOWN_TICKS = 200;
    private static final int REQUIRED_STAIR_CLEARANCE = 3;
    private final MasonGuardEntity guard;
    private Direction miningDirection;
    private BlockPos origin;
    private BlockPos currentStepTarget;
    private int stepIndex;
    private int noProgressTicks;
    private double lastDistanceToTarget = Double.MAX_VALUE;
    private int minedBlockCount;
    private int depositedItemCount;
    private ReturnReason returnReason = ReturnReason.NONE;
    private long cooldownUntilTick;
    private long miningSessionEndTick;
    private Stage stage = Stage.IDLE;

    public MasonMiningStairGoal(MasonGuardEntity guard) {
        this.guard = guard;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (!(guard.getWorld() instanceof ServerWorld world) || !guard.isAlive()) {
            return false;
        }
        if (guard.getTarget() != null || guard.isAttacking()) {
            return false;
        }

        BlockPos jobPos = guard.getPairedJobPos();
        BlockPos chestPos = guard.getPairedChestPos();
        if (jobPos == null || chestPos == null) {
            return false;
        }
        if (!world.isChunkLoaded(jobPos) || !world.isChunkLoaded(chestPos)) {
            return false;
        }
        if (!hasUsableMiningTool()) {
            return false;
        }

        long worldTime = world.getTime();
        if (worldTime < guard.getNextMiningStartTick()) {
            return false;
        }

        Direction directionFromChest = Direction.getFacing(jobPos.getX() - chestPos.getX(), 0, jobPos.getZ() - chestPos.getZ());
        Direction fallbackDirection = directionFromChest.getAxis().isHorizontal() ? directionFromChest : guard.getHorizontalFacing();

        Direction persistedDirection = Direction.byId(guard.getMiningDirectionId());
        BlockPos persistedOrigin = guard.getMiningOrigin();
        int persistedStepIndex = guard.getMiningStepIndex();
        if (persistedOrigin != null && persistedDirection.getAxis().isHorizontal() && persistedStepIndex >= 0) {
            this.miningDirection = persistedDirection;
            this.origin = persistedOrigin;
            this.stepIndex = persistedStepIndex;
        } else {
            this.miningDirection = fallbackDirection;
            this.origin = guard.getBlockPos();
            this.stepIndex = 0;
            guard.setMiningProgress(this.origin, this.stepIndex, this.miningDirection.getId());
        }

        this.currentStepTarget = computeStepTarget(stepIndex);
        this.noProgressTicks = 0;
        this.lastDistanceToTarget = Double.MAX_VALUE;
        this.minedBlockCount = 0;
        this.depositedItemCount = 0;
        this.returnReason = ReturnReason.NONE;
        this.cooldownUntilTick = guard.getNextMiningStartTick();
        int runDurationTicks = MINING_DURATION_MIN_TICKS + guard.getRandom().nextInt(MINING_DURATION_MAX_TICKS - MINING_DURATION_MIN_TICKS + 1);
        this.miningSessionEndTick = worldTime + runDurationTicks;
        this.stage = Stage.MINING;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        if (!(guard.getWorld() instanceof ServerWorld world) || !guard.isAlive()) {
            return false;
        }
        BlockPos chestPos = guard.getPairedChestPos();
        BlockPos jobPos = guard.getPairedJobPos();
        if (chestPos == null || jobPos == null) {
            return false;
        }
        if (!world.isChunkLoaded(chestPos) || !world.isChunkLoaded(jobPos)) {
            return false;
        }
        if (stage == Stage.MINING && !hasUsableMiningTool()) {
            beginReturn(ReturnReason.TOOL_BROKE);
        }
        return stage != Stage.DONE;
    }

    @Override
    public void start() {
        guard.getNavigation().startMovingTo(currentStepTarget.getX() + 0.5D, currentStepTarget.getY(), currentStepTarget.getZ() + 0.5D, MOVE_SPEED);
    }

    @Override
    public void stop() {
        guard.getNavigation().stop();
        clearTemporaryMiningState();
        stage = Stage.DONE;
    }

    @Override
    public void tick() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        guard.setTarget(null);

        if (stage == Stage.MINING) {
            tickMining(world);
            return;
        }

        if (stage == Stage.RETURN_TO_CHEST) {
            BlockPos chestPos = guard.getPairedChestPos();
            if (chestPos == null) {
                stage = Stage.DONE;
                return;
            }
            guard.getNavigation().startMovingTo(chestPos.getX() + 0.5D, chestPos.getY(), chestPos.getZ() + 0.5D, MOVE_SPEED);
            if (guard.squaredDistanceTo(Vec3d.ofCenter(chestPos)) <= 4.0D) {
                stage = Stage.DEPOSIT;
            }
            return;
        }

        if (stage == Stage.DEPOSIT) {
            depositMinedMaterials(world);
            logTelemetry();
            clearTemporaryMiningState();
            stage = Stage.DONE;
        }
    }

    private void tickMining(ServerWorld world) {
        if (!clearCurrentHeadBlock(world)) {
            beginReturn(ReturnReason.CANNOT_ADVANCE);
            return;
        }

        if (world.getTime() >= miningSessionEndTick) {
            beginReturn(ReturnReason.MINING_TIME_LIMIT_REACHED);
            return;
        }

        if (!ensureStepClear(world, currentStepTarget)) {
            beginReturn(ReturnReason.CANNOT_ADVANCE);
            return;
        }

        guard.getNavigation().startMovingTo(currentStepTarget.getX() + 0.5D, currentStepTarget.getY(), currentStepTarget.getZ() + 0.5D, MOVE_SPEED);
        double distanceToTarget = guard.squaredDistanceTo(Vec3d.ofBottomCenter(currentStepTarget));
        if (distanceToTarget + 0.01D < lastDistanceToTarget) {
            noProgressTicks = 0;
            lastDistanceToTarget = distanceToTarget;
        } else {
            noProgressTicks++;
        }

        if (noProgressTicks >= NO_PROGRESS_LIMIT_TICKS) {
            beginReturn(ReturnReason.STUCK_30_SECONDS);
            return;
        }

        if (distanceToTarget <= TARGET_REACH_SQUARED) {
            stepIndex++;
            guard.setMiningProgress(origin, stepIndex, miningDirection.getId());
            currentStepTarget = computeStepTarget(stepIndex);
            noProgressTicks = 0;
            lastDistanceToTarget = Double.MAX_VALUE;
        }
    }

    private boolean ensureStepClear(ServerWorld world, BlockPos footTarget) {
        if (!hasSafeSupport(world, footTarget)) {
            return false;
        }

        for (int i = 0; i < REQUIRED_STAIR_CLEARANCE; i++) {
            if (!clearBlockIfNeeded(world, footTarget.up(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean clearCurrentHeadBlock(ServerWorld world) {
        BlockPos guardFeet = guard.getBlockPos();
        for (int i = 1; i < REQUIRED_STAIR_CLEARANCE; i++) {
            if (!clearBlockIfNeeded(world, guardFeet.up(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean hasSafeSupport(ServerWorld world, BlockPos footTarget) {
        BlockPos supportPos = footTarget.down();
        BlockState supportState = world.getBlockState(supportPos);
        if (supportState.isAir() || supportState.getCollisionShape(world, supportPos).isEmpty()) {
            return false;
        }
        return !supportState.getFluidState().isIn(FluidTags.WATER) && !supportState.getFluidState().isIn(FluidTags.LAVA);
    }

    private boolean clearBlockIfNeeded(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.getCollisionShape(world, pos).isEmpty()) {
            return true;
        }
        if (state.getFluidState().isIn(FluidTags.WATER) || state.getFluidState().isIn(FluidTags.LAVA)) {
            return false;
        }
        if (!canMine(world, pos, state)) {
            return false;
        }

        if (world.breakBlock(pos, true, guard)) {
            minedBlockCount++;
        }
        collectNearbyDrops(world, pos);
        return true;
    }

    private void collectNearbyDrops(ServerWorld world, BlockPos pos) {
        Box box = new Box(pos).expand(1.5D);
        for (ItemEntity itemEntity : world.getEntitiesByClass(ItemEntity.class, box, entity -> entity.isAlive() && !entity.getStack().isEmpty())) {
            ItemStack remaining = guard.guardInventory.addStack(itemEntity.getStack());
            if (remaining.isEmpty()) {
                itemEntity.discard();
            } else {
                itemEntity.setStack(remaining);
            }
        }
    }

    private boolean canMine(ServerWorld world, BlockPos pos, BlockState state) {
        if (state.isAir() || state.getHardness(world, pos) < 0.0F) {
            return false;
        }

        ItemStack stack = guard.getMainHandStack();
        if (stack.isEmpty()) {
            return false;
        }

        if (stack.getItem() instanceof PickaxeItem) {
            return state.isIn(BlockTags.PICKAXE_MINEABLE) || stack.isSuitableFor(state);
        }
        if (stack.getItem() instanceof ShovelItem) {
            return state.isIn(BlockTags.SHOVEL_MINEABLE) || stack.isSuitableFor(state);
        }
        return false;
    }

    private boolean hasUsableMiningTool() {
        ItemStack stack = guard.getMainHandStack();
        return !stack.isEmpty() && (stack.getItem() instanceof PickaxeItem || stack.getItem() instanceof ShovelItem);
    }

    private BlockPos computeStepTarget(int index) {
        return origin.offset(miningDirection, index + 1).down(index + 1);
    }

    private boolean isMiningTool(ItemStack stack) {
        return stack.getItem() instanceof PickaxeItem || stack.getItem() instanceof ShovelItem;
    }


    private void beginReturn(ReturnReason reason) {
        if (stage != Stage.MINING) {
            return;
        }
        this.returnReason = reason;
        this.cooldownUntilTick = guard.getWorld().getTime() + MINING_RUN_COOLDOWN_TICKS;
        guard.setNextMiningStartTick(cooldownUntilTick);

        if (reason.resetsMiningProgress) {
            guard.clearMiningProgress();
        } else {
            guard.setMiningProgress(origin, stepIndex, miningDirection.getId());
        }

        this.stage = Stage.RETURN_TO_CHEST;
    }

    private void depositMinedMaterials(ServerWorld world) {
        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos == null) {
            return;
        }

        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return;
        }

        Inventory chestInventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
        if (chestInventory == null) {
            return;
        }

        for (int i = 0; i < guard.guardInventory.size(); i++) {
            ItemStack stack = guard.guardInventory.getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (isMiningTool(stack)) {
                continue;
            }

            int beforeCount = stack.getCount();
            ItemStack remaining = insertStack(chestInventory, stack);
            depositedItemCount += beforeCount - remaining.getCount();
            guard.guardInventory.setStack(i, remaining);
        }

        guard.guardInventory.markDirty();
        chestInventory.markDirty();
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

    private void clearTemporaryMiningState() {
        this.noProgressTicks = 0;
        this.lastDistanceToTarget = Double.MAX_VALUE;
        this.currentStepTarget = null;
        this.stepIndex = 0;
        this.miningSessionEndTick = 0L;
    }

    private void logTelemetry() {
        String returnReasonText = switch (returnReason) {
            case BATCH_COMPLETE -> "BATCH_COMPLETE";
            case MINING_TIME_LIMIT_REACHED -> "MINING_TIME_LIMIT_REACHED";
            case TOOL_BROKE -> "TOOL_BROKE";
            case CANNOT_ADVANCE -> "CANNOT_ADVANCE";
            case STUCK_30_SECONDS -> "STUCK_30_SECONDS";
            default -> "NONE";
        };

        LOGGER.info("Mason guard {} mining deposit telemetry: minedBlocks={}, depositedItems={}, returnReason={}, cooldownUntilTick={}, mainHand={} ",
                guard.getUuidAsString(),
                minedBlockCount,
                depositedItemCount,
                returnReasonText,
                cooldownUntilTick,
                guard.getMainHandStack().isEmpty() ? "empty" : Registries.ITEM.getId(guard.getMainHandStack().getItem()));
    }

    private enum Stage {
        IDLE,
        MINING,
        RETURN_TO_CHEST,
        DEPOSIT,
        DONE
    }

    private enum ReturnReason {
        NONE(false),
        BATCH_COMPLETE(false),
        MINING_TIME_LIMIT_REACHED(false),
        TOOL_BROKE(false),
        CANNOT_ADVANCE(true),
        STUCK_30_SECONDS(true);

        private final boolean resetsMiningProgress;

        ReturnReason(boolean resetsMiningProgress) {
            this.resetsMiningProgress = resetsMiningProgress;
        }
    }
}
