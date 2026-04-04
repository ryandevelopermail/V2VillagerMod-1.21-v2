package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.MasonGuardEntity;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
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
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

public class MasonMiningStairGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(MasonMiningStairGoal.class);
    private static final double MOVE_SPEED = 0.7D;
    private static final double TARGET_REACH_SQUARED = 1.8D;
    private static final int NO_PROGRESS_LIMIT_TICKS = 600;
    private static final int MINING_DURATION_MIN_TICKS = 1200;
    private static final int MINING_DURATION_MAX_TICKS = 3600;
    private static final int BATCH_MIN_STEPS = 20;
    private static final int BATCH_MAX_STEPS = 56;
    private static final int SESSION_BACKOFF_MIN_TICKS = 20 * 60 * 2;
    private static final int SESSION_BACKOFF_MAX_TICKS = 20 * 60 * 5;
    private static final int FAILURE_BACKOFF_MIN_TICKS = 20 * 60 * 1;
    private static final int FAILURE_BACKOFF_MAX_TICKS = 20 * 60 * 3;
    private static final int REQUIRED_STAIR_CLEARANCE = 3;
    private static final int GRAVITY_SCAN_HEIGHT = 6;
    private static final int RECOVERY_TRIGGER_TICKS = 50;
    private static final int RECOVERY_MAX_TICKS = 80;
    private static final int FAILURE_REASON_RESET_THRESHOLD = 3;
    private static final int MIN_RESERVED_COBBLESTONE = 8;
    private static final int MIN_RESERVED_DIRT = 8;
    private final MasonGuardEntity guard;
    private Direction miningDirection;
    private BlockPos origin;
    private BlockPos rejoinStepTarget;
    private BlockPos rejoinDeepTarget;
    private BlockPos currentStepTarget;
    private int stepIndex;
    private int noProgressTicks;
    private double lastDistanceToTarget = Double.MAX_VALUE;
    private int minedBlockCount;
    private int depositedItemCount;
    private ReturnReason returnReason = ReturnReason.NONE;
    private long nextSessionStartTick;
    private long miningSessionStartTick;
    private long miningSessionEndTick;
    private int plannedMiningDurationTicks;
    private int travelAllowanceTicks;
    private int sessionStepTarget;
    private long cooldownQuarterLogTick;
    private long cooldownThreeQuarterLogTick;
    private long cooldownProgressLoggedForStartTick = -1L;
    private BlockPos recoveryMoveTarget;
    private BlockPos returnStepTarget;
    private int recoveryTicks;
    private boolean recoveryAttemptedForStep;
    private int repairedObstructionCount;
    private int placedSupportCount;
    private ReturnReason lastFailureReason = ReturnReason.NONE;
    private int consecutiveFailureCount;
    private boolean blockedByProtectedJobBlock;
    private final Set<BlockPos> protectedBlockSkipPositions = new HashSet<>();
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
        if (guard.isMiningSessionActive()) {
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
            maybeLogCooldownProgress(worldTime);
            return false;
        }

        this.cooldownQuarterLogTick = 0L;
        this.cooldownThreeQuarterLogTick = 0L;
        this.cooldownProgressLoggedForStartTick = -1L;

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
            guard.setMiningPathAnchors(this.origin, null);
        }

        BlockPos storedStart = guard.getMiningStartPos();
        BlockPos storedDeepest = guard.getMiningLastMinedPos();
        if (storedStart == null && this.origin != null) {
            guard.setMiningPathAnchors(this.origin, storedDeepest);
        }

        if (stepIndex > 0) {
            BlockPos deepestTarget = storedDeepest != null ? storedDeepest : computeStepTarget(stepIndex - 1);
            if (storedStart != null && !storedStart.equals(deepestTarget)) {
                this.rejoinStepTarget = storedStart;
                this.rejoinDeepTarget = deepestTarget;
            } else {
                this.rejoinStepTarget = deepestTarget;
                this.rejoinDeepTarget = null;
            }
        } else {
            this.rejoinStepTarget = null;
            this.rejoinDeepTarget = null;
        }
        this.currentStepTarget = computeStepTarget(stepIndex);
        this.noProgressTicks = 0;
        this.lastDistanceToTarget = Double.MAX_VALUE;
        this.minedBlockCount = 0;
        this.depositedItemCount = 0;
        this.returnReason = ReturnReason.NONE;
        this.nextSessionStartTick = guard.getNextMiningStartTick();
        BlockPos startTarget = this.rejoinStepTarget != null ? this.rejoinStepTarget : this.currentStepTarget;
        this.plannedMiningDurationTicks = MINING_DURATION_MIN_TICKS + guard.getRandom().nextInt(MINING_DURATION_MAX_TICKS - MINING_DURATION_MIN_TICKS + 1);
        this.travelAllowanceTicks = estimateTravelAllowanceTicks(startTarget);
        this.miningSessionStartTick = -1L;
        this.miningSessionEndTick = -1L;
        this.sessionStepTarget = MathHelper.nextInt(guard.getRandom(), BATCH_MIN_STEPS, BATCH_MAX_STEPS);
        this.stage = this.rejoinStepTarget != null ? Stage.REJOIN_LAST_STEP : Stage.MINING;
        this.recoveryMoveTarget = null;
        this.returnStepTarget = null;
        this.recoveryTicks = 0;
        this.recoveryAttemptedForStep = false;
        this.repairedObstructionCount = 0;
        this.placedSupportCount = 0;
        this.blockedByProtectedJobBlock = false;
        this.protectedBlockSkipPositions.clear();
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
        guard.setMiningSessionActive(true);
        BlockPos startTarget = this.rejoinStepTarget != null ? this.rejoinStepTarget : this.currentStepTarget;
        LOGGER.info("Mason guard {} starting mining session: origin={}, startPos={}, deepestPos={}, stepIndex={}, direction={}, plannedDurationTicks={}, travelAllowanceTicks={}, miningStartTick={}, sessionEndTick={}, sessionStepTarget={}, startTarget={}, nextEligibleStartTick={}, worldTime={}",
                guard.getUuidAsString(),
                origin == null ? "none" : origin.toShortString(),
                guard.getMiningStartPos() == null ? "none" : guard.getMiningStartPos().toShortString(),
                guard.getMiningLastMinedPos() == null ? "none" : guard.getMiningLastMinedPos().toShortString(),
                stepIndex,
                miningDirection == null ? "none" : miningDirection,
                plannedMiningDurationTicks,
                travelAllowanceTicks,
                miningSessionStartTick,
                miningSessionEndTick,
                sessionStepTarget,
                startTarget == null ? "none" : startTarget.toShortString(),
                guard.getNextMiningStartTick(),
                guard.getWorld().getTime());
        if (guard.getWorld() instanceof ServerWorld world && stage == Stage.MINING) {
            activateMiningStage(world);
        }
        guard.getNavigation().startMovingTo(startTarget.getX() + 0.5D, startTarget.getY(), startTarget.getZ() + 0.5D, MOVE_SPEED);
    }

    @Override
    public void stop() {
        guard.getNavigation().stop();
        guard.setMiningSessionActive(false);
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

        if (stage == Stage.REJOIN_LAST_STEP) {
            tickRejoinLastStep(world);
            return;
        }

        if (stage == Stage.MINING) {
            tickMining(world);
            return;
        }

        if (stage == Stage.RETURN_TO_CHEST) {
            BlockPos chestPos = guard.getPairedChestPos();
            if (chestPos == null) {
                guard.setMiningSessionActive(false);
                stage = Stage.DONE;
                return;
            }
            if (returnStepTarget == null && stepIndex > 0) {
                returnStepTarget = computeStepTarget(stepIndex - 1);
            }

            if (returnStepTarget != null) {
                BlockPos guardFeet = guard.getBlockPos();
                if (!repairStairTransitionIfNeeded(world, guardFeet, returnStepTarget) || !ensureStepClear(world, returnStepTarget)) {
                    beginReturn(ReturnReason.CANNOT_ADVANCE);
                    return;
                }

                guard.getNavigation().startMovingTo(returnStepTarget.getX() + 0.5D, returnStepTarget.getY(), returnStepTarget.getZ() + 0.5D, MOVE_SPEED);
                if (guard.squaredDistanceTo(Vec3d.ofBottomCenter(returnStepTarget)) <= TARGET_REACH_SQUARED) {
                    if (stepIndex > 1) {
                        stepIndex--;
                        returnStepTarget = computeStepTarget(stepIndex - 1);
                    } else {
                        stepIndex = 0;
                        returnStepTarget = null;
                    }
                }
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
            guard.setMiningSessionActive(false);
            clearTemporaryMiningState();
            stage = Stage.DONE;
        }
    }

    private void tickRejoinLastStep(ServerWorld world) {
        if (rejoinStepTarget == null) {
            activateMiningStage(world);
            this.noProgressTicks = 0;
            this.lastDistanceToTarget = Double.MAX_VALUE;
            return;
        }

        if (!clearCurrentHeadBlock(world)) {
            beginReturn(ReturnReason.CANNOT_ADVANCE);
            return;
        }

        if (!ensureStepClear(world, rejoinStepTarget)) {
            if (!repairStairTransitionIfNeeded(world, guard.getBlockPos(), rejoinStepTarget) || !ensureStepClear(world, rejoinStepTarget)) {
                beginReturn(ReturnReason.CANNOT_ADVANCE);
                return;
            }
        }

        if (tickRecoveryMove(world)) {
            return;
        }

        guard.getNavigation().startMovingTo(rejoinStepTarget.getX() + 0.5D, rejoinStepTarget.getY(), rejoinStepTarget.getZ() + 0.5D, MOVE_SPEED);
        double distanceToTarget = guard.squaredDistanceTo(Vec3d.ofBottomCenter(rejoinStepTarget));
        if (distanceToTarget + 0.01D < lastDistanceToTarget) {
            noProgressTicks = 0;
            lastDistanceToTarget = distanceToTarget;
        } else {
            noProgressTicks++;
            if (noProgressTicks >= RECOVERY_TRIGGER_TICKS && !recoveryAttemptedForStep) {
                recoveryAttemptedForStep = true;
                if (tryStartRecoveryMove(world, rejoinStepTarget, "rejoin")) {
                    return;
                }
            }
        }

        if (noProgressTicks >= NO_PROGRESS_LIMIT_TICKS) {
            beginReturn(ReturnReason.STUCK_30_SECONDS);
            return;
        }

        if (distanceToTarget <= TARGET_REACH_SQUARED) {
            if (rejoinDeepTarget != null && !rejoinDeepTarget.equals(rejoinStepTarget)) {
                this.rejoinStepTarget = rejoinDeepTarget;
                this.rejoinDeepTarget = null;
                this.noProgressTicks = 0;
                this.lastDistanceToTarget = Double.MAX_VALUE;
                return;
            }
            this.rejoinStepTarget = null;
            this.noProgressTicks = 0;
            this.lastDistanceToTarget = Double.MAX_VALUE;
            this.recoveryAttemptedForStep = false;
            activateMiningStage(world);
        }
    }

    private void tickMining(ServerWorld world) {
        if (miningSessionStartTick < 0L || miningSessionEndTick < 0L) {
            activateMiningStage(world);
        }

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

        if (tickRecoveryMove(world)) {
            return;
        }

        guard.getNavigation().startMovingTo(currentStepTarget.getX() + 0.5D, currentStepTarget.getY(), currentStepTarget.getZ() + 0.5D, MOVE_SPEED);
        double distanceToTarget = guard.squaredDistanceTo(Vec3d.ofBottomCenter(currentStepTarget));
        if (distanceToTarget + 0.01D < lastDistanceToTarget) {
            noProgressTicks = 0;
            lastDistanceToTarget = distanceToTarget;
        } else {
            noProgressTicks++;
            if (noProgressTicks >= RECOVERY_TRIGGER_TICKS && !recoveryAttemptedForStep) {
                recoveryAttemptedForStep = true;
                if (tryStartRecoveryMove(world, currentStepTarget, "mining")) {
                    return;
                }
            }
        }

        if (noProgressTicks >= NO_PROGRESS_LIMIT_TICKS) {
            beginReturn(ReturnReason.STUCK_30_SECONDS);
            return;
        }

        if (distanceToTarget <= TARGET_REACH_SQUARED) {
            stepIndex++;
            guard.setMiningProgress(origin, stepIndex, miningDirection.getId());
            BlockPos reachedStepPos = currentStepTarget;
            guard.setMiningPathAnchors(guard.getMiningStartPos() == null ? origin : guard.getMiningStartPos(), reachedStepPos);
            if (stepIndex >= sessionStepTarget) {
                beginReturn(ReturnReason.BATCH_COMPLETE);
                return;
            }
            BlockPos nextStepTarget = computeStepTarget(stepIndex);
            if (!repairStairTransitionIfNeeded(world, reachedStepPos, nextStepTarget) || !ensureStepClear(world, nextStepTarget)) {
                beginReturn(ReturnReason.CANNOT_ADVANCE);
                return;
            }
            currentStepTarget = nextStepTarget;
            noProgressTicks = 0;
            lastDistanceToTarget = Double.MAX_VALUE;
            recoveryAttemptedForStep = false;
        }
    }

    private boolean repairStairTransitionIfNeeded(ServerWorld world, BlockPos fromStep, BlockPos toStep) {
        boolean attemptedRepair = false;

        for (int i = 0; i < REQUIRED_STAIR_CLEARANCE; i++) {
            BlockPos clearancePos = toStep.up(i);
            BlockState clearanceState = world.getBlockState(clearancePos);
            if (!clearanceState.isAir() && !clearanceState.getCollisionShape(world, clearancePos).isEmpty()) {
                if (!clearBlockIfNeeded(world, clearancePos)) {
                    LOGGER.warn("Mason guard {} failed stair-transition repair while clearing obstruction at {} (from={} -> to={})",
                            guard.getUuidAsString(),
                            clearancePos.toShortString(),
                            fromStep.toShortString(),
                            toStep.toShortString());
                    return false;
                }
                attemptedRepair = true;
                repairedObstructionCount++;
                LOGGER.debug("Mason guard {} stair transition repaired by cleared obstruction at {} (from={} -> to={})",
                        guard.getUuidAsString(),
                        clearancePos.toShortString(),
                        fromStep.toShortString(),
                        toStep.toShortString());
            }
        }

        BlockPos supportPos = toStep.down();
        if (!hasSafeSupport(world, toStep)) {
            BlockState supportState = world.getBlockState(supportPos);
            if (!supportState.isAir() && !supportState.getCollisionShape(world, supportPos).isEmpty()) {
                if (!clearBlockIfNeeded(world, supportPos)) {
                    LOGGER.warn("Mason guard {} failed stair-transition repair while clearing support position {} (from={} -> to={})",
                            guard.getUuidAsString(),
                            supportPos.toShortString(),
                            fromStep.toShortString(),
                            toStep.toShortString());
                    return false;
                }
            }

            BlockState fillState = guard.getRandom().nextBoolean() ? Blocks.COBBLESTONE.getDefaultState() : Blocks.DIRT.getDefaultState();
            if (!world.setBlockState(supportPos, fillState)) {
                LOGGER.warn("Mason guard {} failed stair-transition repair while placing support block at {} (from={} -> to={})",
                        guard.getUuidAsString(),
                        supportPos.toShortString(),
                        fromStep.toShortString(),
                        toStep.toShortString());
                return false;
            }
            attemptedRepair = true;
            placedSupportCount++;
            LOGGER.debug("Mason guard {} stair transition repaired by placed support block {} at {} (from={} -> to={})",
                    guard.getUuidAsString(),
                    fillState.getBlock().getName().getString(),
                    supportPos.toShortString(),
                    fromStep.toShortString(),
                    toStep.toShortString());
        }

        if (toStep.getY() > fromStep.getY()) {
            Direction transitionDirection = Direction.getFacing(toStep.getX() - fromStep.getX(), 0, toStep.getZ() - fromStep.getZ());
            if (!transitionDirection.getAxis().isHorizontal()) {
                transitionDirection = miningDirection != null && miningDirection.getAxis().isHorizontal() ? miningDirection : guard.getHorizontalFacing();
            }

            for (int i = 1; i <= 2; i++) {
                BlockPos ascentEdgePos = fromStep.offset(transitionDirection).up(i);
                BlockState ascentEdgeState = world.getBlockState(ascentEdgePos);
                if (ascentEdgeState.isAir() || ascentEdgeState.getCollisionShape(world, ascentEdgePos).isEmpty()) {
                    continue;
                }
                if (!clearBlockIfNeeded(world, ascentEdgePos)) {
                    LOGGER.warn("Mason guard {} failed stair-transition repair while clearing ascent edge at {} (from={} -> to={})",
                            guard.getUuidAsString(),
                            ascentEdgePos.toShortString(),
                            fromStep.toShortString(),
                            toStep.toShortString());
                    return false;
                }
                attemptedRepair = true;
                repairedObstructionCount++;
                LOGGER.debug("Mason guard {} stair transition repaired by cleared obstruction at {} (from={} -> to={})",
                        guard.getUuidAsString(),
                        ascentEdgePos.toShortString(),
                        fromStep.toShortString(),
                        toStep.toShortString());
            }
        }

        return !attemptedRepair || ensureStepClear(world, toStep);
    }

    private boolean ensureStepClear(ServerWorld world, BlockPos footTarget) {
        if (!clearGravitySensitiveColumns(world, footTarget)) {
            return false;
        }

        if (!ensureOrPlaceSupport(world, footTarget)) {
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

    private boolean ensureOrPlaceSupport(ServerWorld world, BlockPos footTarget) {
        if (hasSafeSupport(world, footTarget)) {
            return true;
        }

        BlockPos supportPos = footTarget.down();
        BlockState supportState = world.getBlockState(supportPos);
        if ((!supportState.isAir() && !supportState.isReplaceable())
                || !supportState.getFluidState().isEmpty()
                || !world.getOtherEntities(null, new Box(supportPos), entity -> entity.isAlive()).isEmpty()) {
            return false;
        }

        SupportPlacementChoice placementChoice = findSupportPlacementChoice();
        if (placementChoice == null) {
            return false;
        }

        if (!world.setBlockState(supportPos, placementChoice.block.getDefaultState())) {
            return false;
        }

        placementChoice.stack.decrement(1);
        guard.guardInventory.markDirty();
        placedSupportCount++;
        LOGGER.debug("Mason guard {} support-placement pos={} block={}",
                guard.getUuidAsString(),
                supportPos.toShortString(),
                Registries.BLOCK.getId(placementChoice.block));
        return true;
    }

    private SupportPlacementChoice findSupportPlacementChoice() {
        SupportPlacementChoice cobblestone = findSupportBlockInInventory(Blocks.COBBLESTONE);
        if (cobblestone != null) {
            return cobblestone;
        }
        return findSupportBlockInInventory(Blocks.DIRT);
    }

    private SupportPlacementChoice findSupportBlockInInventory(net.minecraft.block.Block block) {
        Inventory inventory = guard.guardInventory;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            if (blockItem.getBlock() == block) {
                return new SupportPlacementChoice(block, stack);
            }
        }
        return null;
    }

    private record SupportPlacementChoice(net.minecraft.block.Block block, ItemStack stack) {
    }

    private boolean clearBlockIfNeeded(ServerWorld world, BlockPos pos) {
        if (protectedBlockSkipPositions.contains(pos)) {
            return false;
        }

        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.getCollisionShape(world, pos).isEmpty()) {
            return true;
        }
        if (state.getFluidState().isIn(FluidTags.WATER) || state.getFluidState().isIn(FluidTags.LAVA)) {
            return false;
        }
        if (isProtectedJobBlock(pos, state)) {
            blockedByProtectedJobBlock = true;
            protectedBlockSkipPositions.add(pos.toImmutable());
            LOGGER.info("Mason guard {} protected-block-skip blockId={} pos={}",
                    guard.getUuidAsString(),
                    Registries.BLOCK.getId(state.getBlock()),
                    pos.toShortString());
            return false;
        }
        if (!canMine(world, pos, state)) {
            return false;
        }

        if (state.getBlock() instanceof FallingBlock) {
            LOGGER.debug("Mason guard {} gravity-clear breaking falling block at {} ({})",
                    guard.getUuidAsString(),
                    pos.toShortString(),
                    state.getBlock().getName().getString());
        }

        if (world.breakBlock(pos, true, guard)) {
            minedBlockCount++;
        }
        collectNearbyDrops(world, pos);
        return true;
    }


    private boolean isProtectedJobBlock(BlockPos pos, BlockState state) {
        BlockPos pairedJobPos = guard.getPairedJobPos();
        if (pairedJobPos != null && pairedJobPos.equals(pos)) {
            return true;
        }

        return Registries.VILLAGER_PROFESSION.stream()
                .filter(ProfessionDefinitions::hasDefinition)
                .anyMatch(profession -> ProfessionDefinitions.isExpectedJobBlock(profession, state));
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
            return state.isIn(BlockTags.PICKAXE_MINEABLE)
                    || state.isIn(BlockTags.SHOVEL_MINEABLE)
                    || stack.isSuitableFor(state);
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

    private void activateMiningStage(ServerWorld world) {
        this.stage = Stage.MINING;
        if (this.miningSessionStartTick >= 0L && this.miningSessionEndTick >= 0L) {
            return;
        }

        long worldTime = world.getTime();
        this.miningSessionStartTick = worldTime;
        // Session end is measured from NOW (when the mason has arrived at the mining front),
        // NOT from canStart() time. Travel time was already spent getting here.
        // Add a small forward-travel buffer scaled to how deep we already are: each step
        // is roughly 1.4 blocks of diagonal travel at MOVE_SPEED 0.7 blocks/tick ≈ 2 ticks/step.
        int depthTravelBuffer = stepIndex * 2;
        this.miningSessionEndTick = worldTime + plannedMiningDurationTicks + depthTravelBuffer;
        LOGGER.info("Mason guard {} mining stage activated: miningStartTick={}, plannedDurationTicks={}, travelAllowanceTicks={}, sessionEndTick={}, stage={}, stepIndex={}",
                guard.getUuidAsString(),
                this.miningSessionStartTick,
                this.plannedMiningDurationTicks,
                this.travelAllowanceTicks,
                this.miningSessionEndTick,
                this.stage,
                this.stepIndex);
    }

    private int estimateTravelAllowanceTicks(BlockPos startTarget) {
        if (startTarget == null) {
            return 0;
        }

        double travelDistance = guard.getPos().distanceTo(Vec3d.ofBottomCenter(startTarget));
        return Math.max(0, MathHelper.ceil(travelDistance / MOVE_SPEED));
    }

    private boolean isMiningTool(ItemStack stack) {
        return stack.getItem() instanceof PickaxeItem || stack.getItem() instanceof ShovelItem;
    }


    private void beginReturn(ReturnReason reason) {
        if (stage != Stage.MINING && stage != Stage.REJOIN_LAST_STEP) {
            return;
        }
        this.returnReason = reason;
        long worldTime = guard.getWorld().getTime();
        boolean protectedEarlyAbort = reason == ReturnReason.CANNOT_ADVANCE
                && blockedByProtectedJobBlock
                && minedBlockCount <= 0;
        if (protectedEarlyAbort) {
            rerouteMiningDirectionAwayFromProtectedBlocks();
        }
        long backoffTicks = protectedEarlyAbort ? 20L : computeSessionBackoffTicks(reason);
        this.nextSessionStartTick = worldTime + backoffTicks;
        guard.setNextMiningStartTick(this.nextSessionStartTick);
        this.cooldownQuarterLogTick = worldTime + Math.max(1L, backoffTicks / 4L);
        this.cooldownThreeQuarterLogTick = worldTime + Math.max(1L, (backoffTicks * 3L) / 4L);
        this.cooldownProgressLoggedForStartTick = -1L;

        boolean forceSafeAnchorReset = protectedEarlyAbort ? false : trackFailureReason(reason);

        LOGGER.info("Mason guard {} ending mining session: reason={}, minedBlocks={}, currentStepIndex={}, startPos={}, deepestPos={}, backoffTicks={}, nextEligibleStartTick={}, plannedDurationTicks={}, travelAllowanceTicks={}, miningStartTick={}, miningSessionEndTick={}",
                guard.getUuidAsString(),
                reason,
                minedBlockCount,
                stepIndex,
                guard.getMiningStartPos() == null ? "none" : guard.getMiningStartPos().toShortString(),
                guard.getMiningLastMinedPos() == null ? "none" : guard.getMiningLastMinedPos().toShortString(),
                backoffTicks,
                this.nextSessionStartTick,
                this.plannedMiningDurationTicks,
                this.travelAllowanceTicks,
                this.miningSessionStartTick,
                this.miningSessionEndTick);

        LOGGER.info("Mason guard {} cooldown started: startTick={}, quarterTick={}, threeQuarterTick={}, nextEligibleStartTick={}",
                guard.getUuidAsString(),
                worldTime,
                this.cooldownQuarterLogTick,
                this.cooldownThreeQuarterLogTick,
                this.nextSessionStartTick);

        maybeLogRepairSummary();

        if (forceSafeAnchorReset) {
            resetMiningProgressToSafeAnchor();
        } else if (reason.resetsMiningProgress) {
            guard.clearMiningProgress();
        } else {
            guard.setMiningProgress(origin, stepIndex, miningDirection.getId());
        }

        this.stage = Stage.RETURN_TO_CHEST;
    }

    private void rerouteMiningDirectionAwayFromProtectedBlocks() {
        if (origin == null) {
            return;
        }
        Direction current = miningDirection != null && miningDirection.getAxis().isHorizontal()
                ? miningDirection
                : guard.getHorizontalFacing();
        Direction[] candidates = new Direction[]{
                current.rotateYClockwise(),
                current.rotateYCounterclockwise(),
                current.getOpposite(),
                current
        };
        for (Direction candidate : candidates) {
            if (!candidate.getAxis().isHorizontal()) {
                continue;
            }
            if (isDirectionBlockedByProtectedJobBlock(candidate)) {
                continue;
            }
            miningDirection = candidate;
            stepIndex = 0;
            currentStepTarget = computeStepTarget(0);
            guard.setMiningProgress(origin, 0, miningDirection.getId());
            guard.setMiningPathAnchors(origin, null);
            LOGGER.info("Mason guard {} rerouted initial mining direction away from protected job block: newDirection={} origin={}",
                    guard.getUuidAsString(),
                    miningDirection,
                    origin.toShortString());
            return;
        }
    }

    private boolean isDirectionBlockedByProtectedJobBlock(Direction direction) {
        if (origin == null || direction == null || !direction.getAxis().isHorizontal()) {
            return false;
        }
        BlockPos footTarget = origin.offset(direction).down();
        for (int i = 0; i < REQUIRED_STAIR_CLEARANCE; i++) {
            BlockPos pos = footTarget.up(i);
            BlockState state = guard.getWorld().getBlockState(pos);
            if (state.isAir() || state.getCollisionShape(guard.getWorld(), pos).isEmpty()) {
                continue;
            }
            if (isProtectedJobBlock(pos, state)) {
                return true;
            }
        }
        return false;
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

        Inventory chestInventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
        if (chestInventory == null) {
            return;
        }

        int cobblestoneReserveRemaining = Math.max(0, MIN_RESERVED_COBBLESTONE - countInventoryItem(Blocks.COBBLESTONE.asItem()));
        int dirtReserveRemaining = Math.max(0, MIN_RESERVED_DIRT - countInventoryItem(Blocks.DIRT.asItem()));

        for (int i = 0; i < guard.guardInventory.size(); i++) {
            ItemStack stack = guard.guardInventory.getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (isMiningTool(stack)) {
                continue;
            }

            int reserveToKeep = 0;
            if (stack.isOf(Blocks.COBBLESTONE.asItem()) && cobblestoneReserveRemaining > 0) {
                reserveToKeep = Math.min(stack.getCount(), cobblestoneReserveRemaining);
                cobblestoneReserveRemaining -= reserveToKeep;
            } else if (stack.isOf(Blocks.DIRT.asItem()) && dirtReserveRemaining > 0) {
                reserveToKeep = Math.min(stack.getCount(), dirtReserveRemaining);
                dirtReserveRemaining -= reserveToKeep;
            }

            int depositCount = stack.getCount() - reserveToKeep;
            if (depositCount <= 0) {
                continue;
            }

            ItemStack toDeposit = stack.copy();
            toDeposit.setCount(depositCount);
            ItemStack remaining = insertStack(chestInventory, toDeposit);
            depositedItemCount += depositCount - remaining.getCount();

            int finalCount = reserveToKeep + remaining.getCount();
            if (finalCount <= 0) {
                guard.guardInventory.setStack(i, ItemStack.EMPTY);
            } else {
                ItemStack finalStack = stack.copy();
                finalStack.setCount(finalCount);
                guard.guardInventory.setStack(i, finalStack);
            }
        }

        guard.guardInventory.markDirty();
        chestInventory.markDirty();
    }

    private int countInventoryItem(net.minecraft.item.Item item) {
        int total = 0;
        for (int i = 0; i < guard.guardInventory.size(); i++) {
            ItemStack stack = guard.guardInventory.getStack(i);
            if (stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
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

    private void maybeLogCooldownProgress(long worldTime) {
        long nextStartTick = guard.getNextMiningStartTick();
        if (nextStartTick <= 0L) {
            return;
        }

        if (this.cooldownProgressLoggedForStartTick != nextStartTick) {
            LOGGER.info("Mason guard {} waiting for next mining session: nextEligibleStartTick={}, currentTick={}, ticksRemaining={} (~{}s)",
                    guard.getUuidAsString(),
                    nextStartTick,
                    worldTime,
                    Math.max(0L, nextStartTick - worldTime),
                    Math.max(0L, nextStartTick - worldTime) / 20L);
            this.cooldownProgressLoggedForStartTick = nextStartTick;
        }

        if (this.cooldownQuarterLogTick > 0L && worldTime >= this.cooldownQuarterLogTick) {
            long ticksRemaining = Math.max(0L, nextStartTick - worldTime);
            LOGGER.info("Mason guard {} mining cooldown reached 25%: nextEligibleStartTick={}, ticksRemaining={} (~{}s)",
                    guard.getUuidAsString(),
                    nextStartTick,
                    ticksRemaining,
                    ticksRemaining / 20L);
            this.cooldownQuarterLogTick = 0L;
        }

        if (this.cooldownThreeQuarterLogTick > 0L && worldTime >= this.cooldownThreeQuarterLogTick) {
            long ticksRemaining = Math.max(0L, nextStartTick - worldTime);
            LOGGER.info("Mason guard {} mining cooldown reached 75%: nextEligibleStartTick={}, ticksRemaining={} (~{}s)",
                    guard.getUuidAsString(),
                    nextStartTick,
                    ticksRemaining,
                    ticksRemaining / 20L);
            this.cooldownThreeQuarterLogTick = 0L;
        }

    }

    private int computeSessionBackoffTicks(ReturnReason reason) {
        boolean isFailureReason = reason == ReturnReason.CANNOT_ADVANCE || reason == ReturnReason.STUCK_30_SECONDS || reason == ReturnReason.TOOL_BROKE;
        if (isFailureReason) {
            return MathHelper.nextInt(guard.getRandom(), FAILURE_BACKOFF_MIN_TICKS, FAILURE_BACKOFF_MAX_TICKS);
        }
        return MathHelper.nextInt(guard.getRandom(), SESSION_BACKOFF_MIN_TICKS, SESSION_BACKOFF_MAX_TICKS);
    }

    private void clearTemporaryMiningState() {
        this.noProgressTicks = 0;
        this.lastDistanceToTarget = Double.MAX_VALUE;
        this.currentStepTarget = null;
        this.rejoinStepTarget = null;
        this.rejoinDeepTarget = null;
        this.stepIndex = 0;
        this.returnStepTarget = null;
        this.miningSessionStartTick = -1L;
        this.miningSessionEndTick = 0L;
        this.plannedMiningDurationTicks = 0;
        this.travelAllowanceTicks = 0;
        this.sessionStepTarget = 0;
        this.recoveryMoveTarget = null;
        this.recoveryTicks = 0;
        this.recoveryAttemptedForStep = false;
        this.blockedByProtectedJobBlock = false;
        this.protectedBlockSkipPositions.clear();
    }

    private boolean clearGravitySensitiveColumns(ServerWorld world, BlockPos footTarget) {
        int baseY = footTarget.getY();
        int topY = baseY + REQUIRED_STAIR_CLEARANCE + GRAVITY_SCAN_HEIGHT;

        BlockPos[] columns = new BlockPos[]{
                footTarget,
                footTarget.north(),
                footTarget.south(),
                footTarget.east(),
                footTarget.west()
        };

        for (BlockPos columnBase : columns) {
            int highestFallingY = Integer.MIN_VALUE;
            for (int y = topY; y >= baseY; y--) {
                BlockPos scanPos = new BlockPos(columnBase.getX(), y, columnBase.getZ());
                if (world.getBlockState(scanPos).getBlock() instanceof FallingBlock) {
                    highestFallingY = y;
                    break;
                }
            }

            if (highestFallingY == Integer.MIN_VALUE) {
                continue;
            }

            LOGGER.info("Mason guard {} gravity-clear detected unstable column at {}, clearing top-down from y={} to y={}",
                    guard.getUuidAsString(),
                    columnBase.toShortString(),
                    highestFallingY,
                    baseY);

            for (int y = highestFallingY; y >= baseY; y--) {
                BlockPos clearPos = new BlockPos(columnBase.getX(), y, columnBase.getZ());
                if (!clearBlockIfNeeded(world, clearPos)) {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean tickRecoveryMove(ServerWorld world) {
        if (recoveryMoveTarget == null) {
            return false;
        }

        if (!ensureStepClear(world, recoveryMoveTarget)) {
            LOGGER.info("Mason guard {} recovery move aborted (blocked): target={}",
                    guard.getUuidAsString(),
                    recoveryMoveTarget.toShortString());
            recoveryMoveTarget = null;
            recoveryTicks = 0;
            return false;
        }

        guard.getNavigation().startMovingTo(recoveryMoveTarget.getX() + 0.5D, recoveryMoveTarget.getY(), recoveryMoveTarget.getZ() + 0.5D, MOVE_SPEED);
        recoveryTicks++;
        double recoveryDistance = guard.squaredDistanceTo(Vec3d.ofBottomCenter(recoveryMoveTarget));
        if (recoveryDistance <= TARGET_REACH_SQUARED || recoveryTicks >= RECOVERY_MAX_TICKS) {
            LOGGER.info("Mason guard {} recovery move completed: target={}, reached={}, ticks={}.",
                    guard.getUuidAsString(),
                    recoveryMoveTarget.toShortString(),
                    recoveryDistance <= TARGET_REACH_SQUARED,
                    recoveryTicks);
            recoveryMoveTarget = null;
            recoveryTicks = 0;
            noProgressTicks = 0;
            lastDistanceToTarget = Double.MAX_VALUE;
        }
        return true;
    }

    private boolean tryStartRecoveryMove(ServerWorld world, BlockPos baseTarget, String context) {
        if (recoveryMoveTarget != null || !miningDirection.getAxis().isHorizontal()) {
            return false;
        }

        Direction left = miningDirection.rotateYCounterclockwise();
        Direction right = miningDirection.rotateYClockwise();
        BlockPos[] candidates = new BlockPos[]{
                baseTarget.offset(left),
                baseTarget.offset(right),
                baseTarget.up(),
                baseTarget.down()
        };

        for (BlockPos candidate : candidates) {
            if (Math.abs(candidate.getY() - baseTarget.getY()) > 1) {
                continue;
            }
            if (!hasSafeSupport(world, candidate)) {
                continue;
            }
            if (!ensureStepClear(world, candidate)) {
                continue;
            }

            recoveryMoveTarget = candidate;
            recoveryTicks = 0;
            LOGGER.info("Mason guard {} recovery move started: context={}, from={}, candidate={}, noProgressTicks={}",
                    guard.getUuidAsString(),
                    context,
                    baseTarget.toShortString(),
                    candidate.toShortString(),
                    noProgressTicks);
            return true;
        }

        LOGGER.info("Mason guard {} recovery move unavailable: context={}, baseTarget={}, noProgressTicks={}",
                guard.getUuidAsString(),
                context,
                baseTarget.toShortString(),
                noProgressTicks);
        return false;
    }

    private boolean trackFailureReason(ReturnReason reason) {
        if (!reason.resetsMiningProgress) {
            lastFailureReason = ReturnReason.NONE;
            consecutiveFailureCount = 0;
            return false;
        }

        if (reason == lastFailureReason) {
            consecutiveFailureCount++;
        } else {
            lastFailureReason = reason;
            consecutiveFailureCount = 1;
        }

        LOGGER.info("Mason guard {} failure-reason tracker: reason={}, consecutiveCount={}, threshold={}",
                guard.getUuidAsString(),
                reason,
                consecutiveFailureCount,
                FAILURE_REASON_RESET_THRESHOLD);

        return consecutiveFailureCount >= FAILURE_REASON_RESET_THRESHOLD;
    }

    private void resetMiningProgressToSafeAnchor() {
        BlockPos safeAnchor = guard.getMiningStartPos();
        BlockPos chestPos = guard.getPairedChestPos();
        if (safeAnchor == null && chestPos != null) {
            BlockPos chestAdjacent = miningDirection != null && miningDirection.getAxis().isHorizontal()
                    ? chestPos.offset(miningDirection)
                    : chestPos.up();
            safeAnchor = chestAdjacent;
        }

        if (safeAnchor == null) {
            guard.clearMiningProgress();
            LOGGER.info("Mason guard {} safe-anchor reset fallback: no anchor found, mining progress cleared", guard.getUuidAsString());
            return;
        }

        guard.setMiningProgress(safeAnchor, 0, miningDirection == null ? guard.getHorizontalFacing().getId() : miningDirection.getId());
        guard.setMiningPathAnchors(safeAnchor, null);
        this.origin = safeAnchor;
        this.stepIndex = 0;
        this.currentStepTarget = computeStepTarget(0);

        LOGGER.info("Mason guard {} safe-anchor reset applied after repeated failures: anchor={}, reason={}, count={}",
                guard.getUuidAsString(),
                safeAnchor.toShortString(),
                lastFailureReason,
                consecutiveFailureCount);

        lastFailureReason = ReturnReason.NONE;
        consecutiveFailureCount = 0;
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

        int reservedCobblestone = countInventoryItem(Blocks.COBBLESTONE.asItem());
        int reservedDirt = countInventoryItem(Blocks.DIRT.asItem());

        LOGGER.info("Mason guard {} mining deposit telemetry: minedBlocks={}, depositedItems={}, returnReason={}, nextEligibleStartTick={}, reservedCobblestone={}, reservedDirt={}, mainHand={} ",
                guard.getUuidAsString(),
                minedBlockCount,
                depositedItemCount,
                returnReasonText,
                nextSessionStartTick,
                reservedCobblestone,
                reservedDirt,
                guard.getMainHandStack().isEmpty() ? "empty" : Registries.ITEM.getId(guard.getMainHandStack().getItem()));
    }

    static boolean hasRepairSummaryData(int repairedObstructions, int placedSupports) {
        return repairedObstructions > 0 || placedSupports > 0;
    }

    private void maybeLogRepairSummary() {
        if (!hasRepairSummaryData(repairedObstructionCount, placedSupportCount)) {
            return;
        }

        LOGGER.info("Mason guard {} stair-repair summary: repairedObstructions={}, supportsPlaced={}, reason={}, stepIndex={}",
                guard.getUuidAsString(),
                repairedObstructionCount,
                placedSupportCount,
                returnReason,
                stepIndex);
    }

    private enum Stage {
        IDLE,
        REJOIN_LAST_STEP,
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
