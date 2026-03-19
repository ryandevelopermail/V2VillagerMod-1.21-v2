package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.util.VillageAnchorState;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
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

        // Need enough materials — chop-midpoint gate removed: pen building fires whenever
        // materials are ready, not only mid-cooldown. Cooldown is SCAN_INTERVAL_TICKS (600t).
        if (!hasPenMaterials(world)) {
            nextScanTick = world.getTime() + SCAN_INTERVAL_TICKS;
            return false;
        }

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
                && world.getBlockState(pendingFencePositions.get(currentIndex)).isIn(BlockTags.FENCES)) {
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
        if (world.getBlockState(target).isIn(BlockTags.FENCES)) {
            currentIndex++;
            stage = Stage.MOVE_TO_FENCE;
            return;
        }

        // Pick fence block from chest (any wood type) before consuming
        Optional<Inventory> invOpt = getInventory(world, guard.getPairedChestPos());
        Block fenceBlock = invOpt.map(this::chooseFenceBlock).orElse(Blocks.OAK_FENCE);

        if (!consumeTagFromChest(world, ItemTags.FENCES)) {
            LOGGER.debug("LumberjackPen {}: ran out of fences mid-build", guard.getUuidAsString());
            stage = Stage.DONE;
            return;
        }

        world.setBlockState(target, fenceBlock.getDefaultState());
        LOGGER.debug("LumberjackPen {}: placed {} at {}", guard.getUuidAsString(), fenceBlock, target.toShortString());
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

        // Pick gate block from chest (any wood type) before consuming
        Optional<Inventory> invOpt2 = getInventory(world, guard.getPairedChestPos());
        Block gateBlock = invOpt2.map(this::chooseGateBlock).orElse(Blocks.OAK_FENCE_GATE);

        if (!consumeTagFromChest(world, ItemTags.FENCE_GATES)) {
            LOGGER.debug("LumberjackPen {}: no gate available", guard.getUuidAsString());
            stage = Stage.DONE;
            return;
        }

        // Face the gate south (default open direction)
        world.setBlockState(pendingGatePos,
                gateBlock.getDefaultState().with(FenceGateBlock.FACING, Direction.SOUTH));
        LOGGER.info("LumberjackPen {}: placed fence gate at {}", guard.getUuidAsString(), pendingGatePos.toShortString());
        pendingGatePos = null;
        stage = Stage.DONE;
    }

    // -------------------------------------------------------------------------
    // Planning
    // -------------------------------------------------------------------------

    private boolean tryPlanPen(ServerWorld world) {
        VillageAnchorState anchorState = VillageAnchorState.get(world.getServer());
        BlockPos anchorPos = anchorState.getNearestQmChest(world, guard.getBlockPos(), SCAN_RADIUS)
                .orElse(guard.getBlockPos());
        BlockPos origin = findPenOrigin(world, anchorPos);
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

        // Filter out already-built positions (any fence type)
        List<BlockPos> unbuilt = fences.stream()
                .filter(pos -> !world.getBlockState(pos).isIn(BlockTags.FENCES))
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
     * Finds a flat, open 6×6 area within scan range of the anchor (QM chest).
     * Prefers positions inside or near mason wall cobblestone (soft preference).
     */
    private BlockPos findPenOrigin(ServerWorld world, BlockPos anchorPos) {
        if (anchorPos == null) return null;

        int searchStep = 4;
        for (int dx = -SCAN_RADIUS + PEN_SIZE; dx <= SCAN_RADIUS - PEN_SIZE; dx += searchStep) {
            for (int dz = -SCAN_RADIUS + PEN_SIZE; dz <= SCAN_RADIUS - PEN_SIZE; dz += searchStep) {
                BlockPos candidate = anchorPos.add(dx, 0, dz);
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
                // Need replaceable space at pen level and one above (covers carpet, flowers, cave_air, etc.)
                if (!world.getBlockState(pos).isReplaceable()) return false;
                if (!world.getBlockState(pos.up()).isReplaceable()) return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean hasPenMaterials(ServerWorld world) {
        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos == null) return false;
        Optional<Inventory> inv = getInventory(world, chestPos);
        if (inv.isEmpty()) return false;
        Inventory inventory = inv.get();
        // Accept any fence type (oak, spruce, birch, etc.) via item tags
        return countItemTag(inventory, ItemTags.FENCES) >= FENCE_NEEDED
                && countItemTag(inventory, ItemTags.FENCE_GATES) >= GATE_NEEDED;
    }

    /** Counts items in the inventory that match a given item tag. */
    private int countItemTag(Inventory inventory, net.minecraft.registry.tag.TagKey<Item> tag) {
        int total = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (!s.isEmpty() && s.isIn(tag)) total += s.getCount();
        }
        return total;
    }

    /**
     * Picks the first fence item in the chest (any wood type) and returns the corresponding
     * Block, or OAK_FENCE as fallback.
     */
    private Block chooseFenceBlock(Inventory inventory) {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (!s.isEmpty() && s.isIn(ItemTags.FENCES) && s.getItem() instanceof BlockItem bi) {
                return bi.getBlock();
            }
        }
        return Blocks.OAK_FENCE;
    }

    /** Picks the first fence-gate item in the chest and returns its Block, or OAK_FENCE_GATE. */
    private Block chooseGateBlock(Inventory inventory) {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (!s.isEmpty() && s.isIn(ItemTags.FENCE_GATES) && s.getItem() instanceof BlockItem bi) {
                return bi.getBlock();
            }
        }
        return Blocks.OAK_FENCE_GATE;
    }

    /** Consumes one item matching the given tag from the inventory. Returns true if successful. */
    private boolean consumeTagFromChest(ServerWorld world, net.minecraft.registry.tag.TagKey<Item> tag) {
        Optional<Inventory> inv = getInventory(world, guard.getPairedChestPos());
        if (inv.isEmpty()) return false;
        Inventory inventory = inv.get();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (!s.isEmpty() && s.isIn(tag)) {
                s.decrement(1);
                if (s.isEmpty()) inventory.setStack(i, ItemStack.EMPTY);
                inventory.markDirty();
                return true;
            }
        }
        return false;
    }

    private Optional<Inventory> getInventory(ServerWorld world, BlockPos pos) {
        if (pos == null) return Optional.empty();
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) return Optional.empty();
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, pos, false));
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
