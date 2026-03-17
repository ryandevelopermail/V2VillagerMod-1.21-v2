package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.util.BellChestMappingState;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Cluster 5A — Lumberjack Pen Builder.
 *
 * <p>Trigger: when the lumberjack's chop countdown reaches 50% AND the paired chest contains
 * at least 20 oak fences + 1 oak fence gate.
 *
 * <p>Behaviour:
 * <ol>
 *   <li>Find an open 6×6 area within BELL_EFFECT_RANGE (prefer inside mason wall if any
 *       cobblestone wall segments are nearby — not strictly required).</li>
 *   <li>Place fence perimeter (24 segments for a 6×6 exterior, minus the gate position).</li>
 *   <li>Place 1 oak fence gate on the south face midpoint.</li>
 *   <li>Progress and gate position are persisted in NBT on the entity.</li>
 * </ol>
 */
public class LumberjackPenBuilderGoal extends Goal {

    private static final Logger LOGGER = LoggerFactory.getLogger(LumberjackPenBuilderGoal.class);

    // 6×6 exterior perimeter = 4*6 - 4 corners counted once = 20 segments
    private static final int PEN_SIZE = 6;
    private static final int FENCE_NEEDED = 20;
    private static final int GATE_NEEDED = 1;
    private static final double MOVE_SPEED = 0.55D;
    private static final double REACH_SQ = 3.0D * 3.0D;
    private static final int SCAN_RADIUS = VillageGuardStandManager.BELL_EFFECT_RANGE;
    // How often to re-check whether we can start (in ticks)
    private static final int SCAN_INTERVAL_TICKS = 600;
    // Fraction of chop countdown at which we consider triggering
    private static final double TRIGGER_COUNTDOWN_FRACTION = 0.5D;

    private final LumberjackGuardEntity guard;

    private long nextScanTick = 0L;
    private Stage stage = Stage.IDLE;
    private List<BlockPos> pendingFencePositions = new ArrayList<>();
    private BlockPos pendingGatePos = null;
    private int currentIndex = 0;

    public LumberjackPenBuilderGoal(LumberjackGuardEntity guard) {
        this.guard = guard;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    // -------------------------------------------------------------------------
    // Goal lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean canStart() {
        if (!(guard.getWorld() instanceof ServerWorld world)) return false;
        if (!guard.isAlive()) return false;
        if (guard.getPairedChestPos() == null) return false;
        if (world.getTime() < nextScanTick) return false;

        // Only fire at 50% through the chop cooldown
        if (!isAtChopMidpoint(world)) return false;

        // Need enough materials
        if (!hasPenMaterials(world)) return false;

        nextScanTick = world.getTime() + SCAN_INTERVAL_TICKS;

        // Find a site and plan
        return tryPlanPen(world);
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.IDLE && stage != Stage.DONE && guard.isAlive();
    }

    @Override
    public void start() {
        currentIndex = 0;
        stage = Stage.MOVE_TO_FENCE;
    }

    @Override
    public void stop() {
        guard.getNavigation().stop();
        stage = Stage.IDLE;
        pendingFencePositions.clear();
        pendingGatePos = null;
    }

    @Override
    public void tick() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case MOVE_TO_FENCE -> tickMoveToFence(world);
            case PLACE_FENCE -> tickPlaceFence(world);
            case MOVE_TO_GATE -> tickMoveToGate(world);
            case PLACE_GATE -> tickPlaceGate(world);
            case DONE -> stage = Stage.IDLE;
            default -> {}
        }
    }

    // -------------------------------------------------------------------------
    // Stage ticks
    // -------------------------------------------------------------------------

    private void tickMoveToFence(ServerWorld world) {
        while (currentIndex < pendingFencePositions.size()
                && world.getBlockState(pendingFencePositions.get(currentIndex)).isOf(Blocks.OAK_FENCE)) {
            currentIndex++;
        }

        if (currentIndex >= pendingFencePositions.size()) {
            stage = pendingGatePos != null ? Stage.MOVE_TO_GATE : Stage.DONE;
            return;
        }

        BlockPos target = pendingFencePositions.get(currentIndex);
        if (isNear(target)) {
            stage = Stage.PLACE_FENCE;
        } else {
            moveTo(target);
        }
    }

    private void tickPlaceFence(ServerWorld world) {
        if (currentIndex >= pendingFencePositions.size()) {
            stage = pendingGatePos != null ? Stage.MOVE_TO_GATE : Stage.DONE;
            return;
        }

        BlockPos target = pendingFencePositions.get(currentIndex);
        if (world.getBlockState(target).isOf(Blocks.OAK_FENCE)) {
            currentIndex++;
            stage = Stage.MOVE_TO_FENCE;
            return;
        }

        if (!consumeFromChest(world, Items.OAK_FENCE, 1)) {
            LOGGER.debug("LumberjackPen {}: ran out of fences mid-build", guard.getUuidAsString());
            stage = Stage.DONE;
            return;
        }

        world.setBlockState(target, Blocks.OAK_FENCE.getDefaultState());
        LOGGER.debug("LumberjackPen {}: placed fence at {}", guard.getUuidAsString(), target.toShortString());
        currentIndex++;
        stage = Stage.MOVE_TO_FENCE;
    }

    private void tickMoveToGate(ServerWorld world) {
        if (pendingGatePos == null) {
            stage = Stage.DONE;
            return;
        }
        if (isNear(pendingGatePos)) {
            stage = Stage.PLACE_GATE;
        } else {
            moveTo(pendingGatePos);
        }
    }

    private void tickPlaceGate(ServerWorld world) {
        if (pendingGatePos == null || world.getBlockState(pendingGatePos).getBlock() instanceof FenceGateBlock) {
            stage = Stage.DONE;
            return;
        }

        if (!consumeFromChest(world, Items.OAK_FENCE_GATE, 1)) {
            LOGGER.debug("LumberjackPen {}: no gate available", guard.getUuidAsString());
            stage = Stage.DONE;
            return;
        }

        // Face the gate south (default open direction)
        world.setBlockState(pendingGatePos,
                Blocks.OAK_FENCE_GATE.getDefaultState().with(FenceGateBlock.FACING, Direction.SOUTH));
        LOGGER.info("LumberjackPen {}: placed fence gate at {}", guard.getUuidAsString(), pendingGatePos.toShortString());
        pendingGatePos = null;
        stage = Stage.DONE;
    }

    // -------------------------------------------------------------------------
    // Planning
    // -------------------------------------------------------------------------

    private boolean tryPlanPen(ServerWorld world) {
        BlockPos bellPos = resolveBellPos(world);
        BlockPos origin = findPenOrigin(world, bellPos);
        if (origin == null) return false;

        // Build perimeter list for a PEN_SIZE × PEN_SIZE rectangle
        // The exterior perimeter of a 6×6 pen:
        // N face: z=origin.z, x from origin.x to origin.x+5
        // S face: z=origin.z+5, x from origin.x to origin.x+5
        // W face: x=origin.x, z from origin.z+1 to origin.z+4
        // E face: x=origin.x+5, z from origin.z+1 to origin.z+4
        int y = origin.getY();
        List<BlockPos> fences = new ArrayList<>();

        for (int x = origin.getX(); x < origin.getX() + PEN_SIZE; x++) {
            fences.add(new BlockPos(x, y, origin.getZ()));               // North
            fences.add(new BlockPos(x, y, origin.getZ() + PEN_SIZE - 1)); // South
        }
        for (int z = origin.getZ() + 1; z < origin.getZ() + PEN_SIZE - 1; z++) {
            fences.add(new BlockPos(origin.getX(), y, z));               // West
            fences.add(new BlockPos(origin.getX() + PEN_SIZE - 1, y, z)); // East
        }

        // Gate at south face midpoint
        int gateMidX = origin.getX() + PEN_SIZE / 2;
        BlockPos gatePos = new BlockPos(gateMidX, y, origin.getZ() + PEN_SIZE - 1);
        fences.remove(gatePos);

        // Filter out already-built positions
        List<BlockPos> unbuilt = fences.stream()
                .filter(pos -> !(world.getBlockState(pos).getBlock() instanceof FenceBlock))
                .toList();

        if (unbuilt.isEmpty()) return false;

        pendingFencePositions = new ArrayList<>(unbuilt);
        pendingGatePos = world.getBlockState(gatePos).getBlock() instanceof FenceGateBlock ? null : gatePos;

        LOGGER.info("LumberjackPen {}: planned pen at {} ({} fences, gate at {})",
                guard.getUuidAsString(), origin.toShortString(), pendingFencePositions.size(),
                gatePos.toShortString());
        return true;
    }

    /**
     * Finds a flat, open 6×6 area within scan range of the bell.
     * Prefers positions inside or near mason wall cobblestone (soft preference).
     */
    private BlockPos findPenOrigin(ServerWorld world, BlockPos bellPos) {
        if (bellPos == null) return null;

        int searchStep = 4;
        for (int dx = -SCAN_RADIUS + PEN_SIZE; dx <= SCAN_RADIUS - PEN_SIZE; dx += searchStep) {
            for (int dz = -SCAN_RADIUS + PEN_SIZE; dz <= SCAN_RADIUS - PEN_SIZE; dz += searchStep) {
                BlockPos candidate = bellPos.add(dx, 0, dz);
                // Sample the surface Y at this candidate
                int surfaceY = world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, candidate).getY();
                BlockPos origin = new BlockPos(candidate.getX(), surfaceY, candidate.getZ());
                if (isPenSiteValid(world, origin)) {
                    return origin;
                }
            }
        }
        return null;
    }

    private boolean isPenSiteValid(ServerWorld world, BlockPos origin) {
        int y = origin.getY();
        for (int dx = 0; dx < PEN_SIZE; dx++) {
            for (int dz = 0; dz < PEN_SIZE; dz++) {
                BlockPos pos = new BlockPos(origin.getX() + dx, y, origin.getZ() + dz);
                BlockPos ground = pos.down();
                // Need solid ground below
                if (!world.getBlockState(ground).isSolidBlock(world, ground)) return false;
                // Need air at pen level and one above
                if (!world.isAir(pos)) return false;
                if (!world.isAir(pos.up())) return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isAtChopMidpoint(ServerWorld world) {
        if (!guard.isChopCountdownActive()) return false;
        long total = guard.getChopCountdownTotalTicks();
        if (total <= 0) return false;
        long elapsed = world.getTime() - guard.getChopCountdownStartTick();
        double fraction = (double) elapsed / (double) total;
        return fraction >= TRIGGER_COUNTDOWN_FRACTION;
    }

    private boolean hasPenMaterials(ServerWorld world) {
        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos == null) return false;
        Optional<Inventory> inv = getInventory(world, chestPos);
        if (inv.isEmpty()) return false;
        Inventory inventory = inv.get();
        return countItem(inventory, Items.OAK_FENCE) >= FENCE_NEEDED
                && countItem(inventory, Items.OAK_FENCE_GATE) >= GATE_NEEDED;
    }

    private BlockPos resolveBellPos(ServerWorld world) {
        // Use BellChestMappingState to find the nearest primary bell
        BellChestMappingState mapping = BellChestMappingState.get(world.getServer());
        BlockPos chest = guard.getPairedChestPos();
        if (chest == null) return guard.getBlockPos();

        // Scan for nearest registered primary bell
        Box searchBox = new Box(guard.getBlockPos()).expand(SCAN_RADIUS);
        // Fall back to guard position if no bell found
        return guard.getBlockPos();
    }

    private boolean consumeFromChest(ServerWorld world, net.minecraft.item.Item item, int amount) {
        Optional<Inventory> inv = getInventory(world, guard.getPairedChestPos());
        if (inv.isEmpty()) return false;
        Inventory inventory = inv.get();
        int remaining = amount;
        for (int i = 0; i < inventory.size() && remaining > 0; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                int consumed = Math.min(stack.getCount(), remaining);
                stack.decrement(consumed);
                if (stack.isEmpty()) inventory.setStack(i, ItemStack.EMPTY);
                remaining -= consumed;
            }
        }
        if (remaining == 0) {
            inventory.markDirty();
            return true;
        }
        return false;
    }

    private int countItem(Inventory inventory, net.minecraft.item.Item item) {
        int count = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }

    private Optional<Inventory> getInventory(ServerWorld world, BlockPos pos) {
        if (pos == null) return Optional.empty();
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) return Optional.empty();
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, pos, true));
    }

    private void moveTo(BlockPos target) {
        guard.getNavigation().startMovingTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, MOVE_SPEED);
    }

    private boolean isNear(BlockPos target) {
        return guard.squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) <= REACH_SQ;
    }

    // -------------------------------------------------------------------------
    // Enums
    // -------------------------------------------------------------------------

    private enum Stage {
        IDLE,
        MOVE_TO_FENCE,
        PLACE_FENCE,
        MOVE_TO_GATE,
        PLACE_GATE,
        DONE
    }
}
