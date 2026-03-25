package dev.sterner.guardvillagers.common.villager.behavior;

import net.minecraft.block.BlockState;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Emergency-only villager escape behavior for fenced pens.
 */
public final class VillagerFenceGateEscapeHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(VillagerFenceGateEscapeHelper.class);
    private static final String LOG_PREFIX = "[VillagerEmergencyGateEscape]";

    private static final int SCAN_RADIUS = 6;
    private static final int PEN_FENCE_RANGE = 8;
    private static final int GATE_INTERIOR_MAX_DISTANCE = 8;
    private static final double NAVIGATION_SPEED = 0.85D;
    private static final double CROSS_DISTANCE_SQ = 2.25D;

    private VillagerFenceGateEscapeHelper() {
    }

    public static EscapeState tick(ServerWorld world,
                                   VillagerEntity villager,
                                   @Nullable BlockPos activeEscapeGate,
                                   boolean openedByEmergency,
                                   long lastAttemptTick) {
        long now = world.getTime();

        if (activeEscapeGate != null) {
            EscapeState activeResult = handleActiveEscape(world, villager, activeEscapeGate, openedByEmergency, lastAttemptTick, now);
            activeEscapeGate = activeResult.activeGate();
            openedByEmergency = activeResult.openedByEmergency();
            lastAttemptTick = activeResult.lastAttemptTick();
            if (activeEscapeGate != null) {
                return activeResult;
            }
        }

        int cadence = getCadenceTicks(villager.getUuid());
        if (now - lastAttemptTick < cadence) {
            return new EscapeState(null, false, lastAttemptTick);
        }

        lastAttemptTick = now;
        BlockPos villagerPos = villager.getBlockPos();
        if (!isInsideFencePen(world, villagerPos)) {
            return new EscapeState(null, false, lastAttemptTick);
        }

        EscapeCandidate candidate = findEscapeCandidate(world, villagerPos);
        if (candidate == null) {
            LOGGER.debug("{} abort-no-gate villager={} pos={}", LOG_PREFIX, villager.getUuidAsString(), villagerPos.toShortString());
            return new EscapeState(null, false, lastAttemptTick);
        }

        BlockPos gatePos = candidate.gatePos();
        BlockState gateState = world.getBlockState(gatePos);
        boolean openedNow = false;
        if (gateState.getBlock() instanceof FenceGateBlock && !gateState.get(FenceGateBlock.OPEN)) {
            world.setBlockState(gatePos, gateState.with(FenceGateBlock.OPEN, true), 2);
            openedNow = true;
        }

        villager.getNavigation().startMovingTo(
                candidate.outsideTarget().getX() + 0.5D,
                candidate.outsideTarget().getY(),
                candidate.outsideTarget().getZ() + 0.5D,
                NAVIGATION_SPEED);

        LOGGER.debug("{} open villager={} gate={} outside={} openedNow={} cadence={}",
                LOG_PREFIX,
                villager.getUuidAsString(),
                gatePos.toShortString(),
                candidate.outsideTarget().toShortString(),
                openedNow,
                cadence);

        return new EscapeState(gatePos.toImmutable(), openedNow, lastAttemptTick);
    }

    private static EscapeState handleActiveEscape(ServerWorld world,
                                                  VillagerEntity villager,
                                                  BlockPos activeGate,
                                                  boolean openedByEmergency,
                                                  long lastAttemptTick,
                                                  long now) {
        BlockState gateState = world.getBlockState(activeGate);
        if (!(gateState.getBlock() instanceof FenceGateBlock) || !gateState.contains(FenceGateBlock.FACING)) {
            if (openedByEmergency) {
                tryCloseGate(world, activeGate, gateState, villager, "abort-missing-gate");
            }
            LOGGER.debug("{} abort-missing-gate villager={} gate={}", LOG_PREFIX, villager.getUuidAsString(), activeGate.toShortString());
            return new EscapeState(null, false, lastAttemptTick);
        }

        EscapeGateSides sides = resolveGateSides(world, activeGate, gateState);
        if (sides == null || sides.outsideTarget() == null) {
            if (openedByEmergency) {
                tryCloseGate(world, activeGate, gateState, villager, "abort-no-outside");
            }
            LOGGER.debug("{} abort-no-outside villager={} gate={}", LOG_PREFIX, villager.getUuidAsString(), activeGate.toShortString());
            return new EscapeState(null, false, lastAttemptTick);
        }

        BlockPos villagerPos = villager.getBlockPos();
        boolean stillInside = isInsideFencePen(world, villagerPos);

        villager.getNavigation().startMovingTo(
                sides.outsideTarget().getX() + 0.5D,
                sides.outsideTarget().getY(),
                sides.outsideTarget().getZ() + 0.5D,
                NAVIGATION_SPEED);

        if (!stillInside && villagerPos.getSquaredDistance(sides.outsideTarget()) <= CROSS_DISTANCE_SQ) {
            LOGGER.debug("{} cross villager={} gate={} pos={} outside={}",
                    LOG_PREFIX,
                    villager.getUuidAsString(),
                    activeGate.toShortString(),
                    villagerPos.toShortString(),
                    sides.outsideTarget().toShortString());

            if (openedByEmergency) {
                BlockState currentState = world.getBlockState(activeGate);
                tryCloseGate(world, activeGate, currentState, villager, "close");
            }
            return new EscapeState(null, false, lastAttemptTick);
        }

        if (!stillInside && now - lastAttemptTick > 100L) {
            LOGGER.debug("{} abort-timeout villager={} gate={} pos={}", LOG_PREFIX, villager.getUuidAsString(), activeGate.toShortString(), villagerPos.toShortString());
            if (openedByEmergency) {
                BlockState currentState = world.getBlockState(activeGate);
                tryCloseGate(world, activeGate, currentState, villager, "abort-timeout-close");
            }
            return new EscapeState(null, false, now);
        }

        return new EscapeState(activeGate, openedByEmergency, lastAttemptTick);
    }

    @Nullable
    private static EscapeCandidate findEscapeCandidate(ServerWorld world, BlockPos villagerPos) {
        List<BlockPos> gateCandidates = new ArrayList<>();
        BlockPos min = villagerPos.add(-SCAN_RADIUS, 0, -SCAN_RADIUS);
        BlockPos max = villagerPos.add(SCAN_RADIUS, 0, SCAN_RADIUS);
        for (BlockPos cursor : BlockPos.iterate(min, max)) {
            if (!villagerPos.isWithinDistance(cursor, SCAN_RADIUS)) {
                continue;
            }
            BlockState state = world.getBlockState(cursor);
            if (state.getBlock() instanceof FenceGateBlock) {
                gateCandidates.add(cursor.toImmutable());
            }
        }

        EscapeCandidate best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos gatePos : gateCandidates) {
            BlockState gateState = world.getBlockState(gatePos);
            if (!(gateState.getBlock() instanceof FenceGateBlock)) {
                continue;
            }
            EscapeGateSides sides = resolveGateSides(world, gatePos, gateState);
            if (sides == null || sides.outsideTarget() == null) {
                continue;
            }
            double dist = gatePos.getSquaredDistance(villagerPos);
            if (dist < bestDist) {
                bestDist = dist;
                best = new EscapeCandidate(gatePos.toImmutable(), sides.outsideTarget().toImmutable());
            }
        }
        return best;
    }

    @Nullable
    private static EscapeGateSides resolveGateSides(ServerWorld world, BlockPos gatePos, BlockState gateState) {
        if (!gateState.contains(FenceGateBlock.FACING)) {
            return null;
        }

        Direction facing = gateState.get(FenceGateBlock.FACING);
        BlockPos front = gatePos.offset(facing);
        BlockPos back = gatePos.offset(facing.getOpposite());

        BlockPos interior = resolveInterior(world, gatePos, gateState);
        if (interior == null || !isInsideFencePen(world, interior)) {
            return null;
        }

        BlockPos outside;
        if (interior.getSquaredDistance(front) <= interior.getSquaredDistance(back)) {
            outside = back;
        } else {
            outside = front;
        }

        if (isInsideFencePen(world, outside) || !canStandAt(world, outside)) {
            BlockPos alternate = outside.equals(front) ? back : front;
            if (isInsideFencePen(world, alternate) || !canStandAt(world, alternate)) {
                return null;
            }
            outside = alternate;
        }

        return new EscapeGateSides(interior.toImmutable(), outside.toImmutable());
    }

    private static boolean canStandAt(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || !state.blocksMovement();
    }

    @Nullable
    private static BlockPos resolveInterior(ServerWorld world, BlockPos gatePos, BlockState gateState) {
        if (!gateState.contains(FenceGateBlock.FACING)) {
            return null;
        }
        Direction facing = gateState.get(FenceGateBlock.FACING);
        BlockPos front = gatePos.offset(facing);
        BlockPos back = gatePos.offset(facing.getOpposite());

        BlockPos fromFront = findInteriorFromGateSide(world, front, gatePos);
        if (fromFront != null) {
            return fromFront;
        }
        return findInteriorFromGateSide(world, back, gatePos);
    }

    @Nullable
    private static BlockPos findInteriorFromGateSide(ServerWorld world, BlockPos startPos, BlockPos gatePos) {
        if (isInsideFencePen(world, startPos)) {
            return startPos.toImmutable();
        }

        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        java.util.LinkedHashSet<BlockPos> visited = new java.util.LinkedHashSet<>();
        BlockPos immutableStart = startPos.toImmutable();
        queue.add(immutableStart);
        visited.add(immutableStart);

        while (!queue.isEmpty() && visited.size() <= 256) {
            BlockPos current = queue.poll();
            if (isInsideFencePen(world, current)) {
                return current.toImmutable();
            }
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos next = current.offset(dir);
                if (visited.contains(next) || !gatePos.isWithinDistance(next, GATE_INTERIOR_MAX_DISTANCE)) {
                    continue;
                }
                BlockState nextState = world.getBlockState(next);
                if (nextState.getBlock() instanceof FenceBlock || nextState.getBlock() instanceof FenceGateBlock) {
                    continue;
                }
                if (!nextState.isAir() && nextState.blocksMovement()) {
                    continue;
                }
                BlockPos immutableNext = next.toImmutable();
                visited.add(immutableNext);
                queue.add(immutableNext);
            }
        }
        return null;
    }

    private static boolean isInsideFencePen(ServerWorld world, BlockPos pos) {
        return hasFenceInDirection(world, pos, Direction.NORTH)
                && hasFenceInDirection(world, pos, Direction.SOUTH)
                && hasFenceInDirection(world, pos, Direction.WEST)
                && hasFenceInDirection(world, pos, Direction.EAST);
    }

    private static boolean hasFenceInDirection(ServerWorld world, BlockPos start, Direction direction) {
        for (int distance = 1; distance <= PEN_FENCE_RANGE; distance++) {
            BlockPos scanPos = start.offset(direction, distance);
            BlockState state = world.getBlockState(scanPos);
            if (state.getBlock() instanceof FenceBlock || state.getBlock() instanceof FenceGateBlock) {
                return true;
            }
        }
        return false;
    }

    private static int getCadenceTicks(UUID uuid) {
        return 10 + (int) (Math.floorMod(uuid.getLeastSignificantBits(), 11));
    }

    private static void tryCloseGate(ServerWorld world, BlockPos gatePos, BlockState gateState, VillagerEntity villager, String reason) {
        if (!(gateState.getBlock() instanceof FenceGateBlock)) {
            return;
        }
        if (!gateState.get(FenceGateBlock.OPEN)) {
            return;
        }
        world.setBlockState(gatePos, gateState.with(FenceGateBlock.OPEN, false), 2);
        LOGGER.debug("{} {} villager={} gate={}", LOG_PREFIX, reason, villager.getUuidAsString(), gatePos.toShortString());
    }

    public record EscapeState(@Nullable BlockPos activeGate, boolean openedByEmergency, long lastAttemptTick) {
    }

    private record EscapeCandidate(BlockPos gatePos, BlockPos outsideTarget) {
    }

    private record EscapeGateSides(BlockPos interior, @Nullable BlockPos outsideTarget) {
    }
}
