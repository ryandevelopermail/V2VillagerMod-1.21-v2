package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.MasonGuardEntity;
import dev.sterner.guardvillagers.common.util.VillageAnchorState;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Cluster 4 — Mason Defensive Wall Builder.
 *
 * <p>Behaviour summary:
 * <ol>
 *   <li>Scan all job-site POI blocks + beds within BELL_EFFECT_RANGE of the nearest QM chest
 *       → compute axis-aligned bounding rectangle → expand 10 blocks outward.</li>
 *   <li>Elect the mason with the most cobblestone as the builder; non-builders transfer their
 *       stone into the elected mason's paired chest, then resume normal goals.</li>
 *   <li>The elected builder must have enough cobblestone for ALL 1-high perimeter segments
 *       (excluding gap positions) before placing any block.</li>
 *   <li>Gap rules: skip DIRT_PATH positions; always leave at least 1 forced gap so the wall
 *       is never fully closed; reserve one position per face (4 total) for lumberjack fence
 *       gates (stored in entity NBT, not placed here).</li>
 *   <li>The builder walks to each wall segment position, places cobblestone, marks it done, and
 *       persists progress across restarts via NBT on MasonGuardEntity.</li>
 * </ol>
 */
public class MasonWallBuilderGoal extends Goal {

    private static final Logger LOGGER = LoggerFactory.getLogger(MasonWallBuilderGoal.class);

    // How often to re-evaluate whether we should start (in ticks)
    private static final int SCAN_INTERVAL_TICKS = 400;
    // Blocks to expand the village bounding box outward to form the wall rectangle
    private static final int WALL_EXPAND = 10;
    // Minimum stone needed before the elected builder starts placing
    private static final int STONE_PER_SEGMENT = 1; // 1-high wall = 1 block per pos
    /**
     * Maximum allowed height difference between a perimeter position's surface Y
     * and the bell Y before that position is considered too steep to wall.
     * Positions steeper than this are skipped entirely (left open).
     */
    private static final int STEEP_SKIP_THRESHOLD = 3;
    /**
     * Maximum number of blocks a fill column can extend downward to close a terrain dip.
     * Dips deeper than this are also skipped to avoid filling ravines/caves.
     */
    private static final int MAX_FILL_DEPTH = 4;
    // Movement speed
    private static final double MOVE_SPEED = 0.55D;
    // Reach distance squared (close enough to place)
    private static final double REACH_SQ = 3.5D * 3.5D;
    // Maximum range for scanning peers
    private static final double PEER_SCAN_RANGE = VillageGuardStandManager.BELL_EFFECT_RANGE;

    private final MasonGuardEntity guard;

    private long nextScanTick = 0L;
    private Stage stage = Stage.IDLE;

    // The computed wall segments for this run (only on the elected builder)
    private List<BlockPos> pendingSegments = new ArrayList<>();
    private int currentSegmentIndex = 0;

    // Whether this mason is the elected builder this cycle
    private boolean isElectedBuilder = false;

    // Transfers: list of (peerChestPos, amount) to drain from peer chests into our chest
    private List<TransferTask> pendingTransfers = new ArrayList<>();
    private int currentTransferIndex = 0;

    public MasonWallBuilderGoal(MasonGuardEntity guard) {
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
        if (guard.isMiningSessionActive()) return false;
        if (guard.getPairedChestPos() == null) return false;
        if (world.getTime() < nextScanTick) return false;

        // Resolve village anchor from nearest QM chest — no bell required
        Optional<BlockPos> anchorOpt = VillageAnchorState.get(world.getServer())
                .getNearestQmChest(world, guard.getBlockPos(), (int) PEER_SCAN_RANGE);
        if (anchorOpt.isEmpty()) return false;

        nextScanTick = world.getTime() + SCAN_INTERVAL_TICKS;

        return tryInitiateBuildCycle(world, anchorOpt.get());
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.IDLE && stage != Stage.DONE && guard.isAlive();
    }

    @Override
    public void start() {
        currentSegmentIndex = 0;
        currentTransferIndex = 0;
        if (isElectedBuilder && !pendingTransfers.isEmpty()) {
            stage = Stage.TRANSFER_FROM_PEERS;
        } else if (isElectedBuilder && !pendingSegments.isEmpty()) {
            stage = Stage.MOVE_TO_SEGMENT;
        } else {
            stage = Stage.DONE;
        }
    }

    @Override
    public void stop() {
        guard.getNavigation().stop();
        stage = Stage.IDLE;
        isElectedBuilder = false;
        pendingSegments.clear();
        pendingTransfers.clear();
    }

    @Override
    public void tick() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case TRANSFER_FROM_PEERS -> tickTransfer(world);
            case MOVE_TO_SEGMENT -> tickMoveToSegment(world);
            case PLACE_BLOCK -> tickPlaceBlock(world);
            case DONE -> stage = Stage.IDLE;
            default -> {}
        }
    }

    // -------------------------------------------------------------------------
    // Initiation — election + planning
    // -------------------------------------------------------------------------

    /**
     * Attempts to start a wall-build cycle.  Returns true if this mason has a role to play
     * (either elected builder or stone donor).
     *
     * @param anchorPos the QM chest position — used as the geographic village center
     */
    private boolean tryInitiateBuildCycle(ServerWorld world, BlockPos anchorPos) {
        // 1. Compute wall rectangle
        Optional<WallRect> rectOpt = computeWallRect(world, anchorPos);
        if (rectOpt.isEmpty()) {
            LOGGER.debug("MasonWallBuilder {}: no village bounds found near anchor {}",
                    guard.getUuidAsString(), anchorPos.toShortString());
            return false;
        }
        WallRect rect = rectOpt.get();

        // 2. Compute all wall segment positions for the rectangle
        List<BlockPos> allSegments = computeWallSegments(world, rect);
        if (allSegments.isEmpty()) {
            return false;
        }

        // Filter to unbuilt segments only
        List<BlockPos> unbuilt = allSegments.stream()
                .filter(pos -> !world.getBlockState(pos).isOf(Blocks.COBBLESTONE))
                .toList();
        if (unbuilt.isEmpty()) {
            LOGGER.debug("MasonWallBuilder {}: wall already complete", guard.getUuidAsString());
            return false;
        }

        // 3. Find all peer masons near the anchor
        List<MasonGuardEntity> peers = getPeerMasons(world, anchorPos);

        // 4. Count cobblestone across all peers (including self)
        int myStone = countCobblestoneInChest(world, guard.getPairedChestPos());
        int totalStone = myStone;
        for (MasonGuardEntity peer : peers) {
            if (peer == guard) continue;
            if (peer.getPairedChestPos() != null) {
                totalStone += countCobblestoneInChest(world, peer.getPairedChestPos());
            }
        }

        int needed = unbuilt.size() * STONE_PER_SEGMENT;
        if (totalStone < needed) {
            LOGGER.debug("MasonWallBuilder {}: not enough stone ({}/{}) for wall", guard.getUuidAsString(), totalStone, needed);
            return false;
        }

        // 5. Elect builder — the mason (including self) with the most stone
        MasonGuardEntity electedBuilder = guard;
        int electedStone = myStone;
        for (MasonGuardEntity peer : peers) {
            if (peer == guard) continue;
            if (peer.getPairedChestPos() == null) continue;
            int peerStone = countCobblestoneInChest(world, peer.getPairedChestPos());
            if (peerStone > electedStone) {
                electedStone = peerStone;
                electedBuilder = peer;
            }
        }

        if (electedBuilder != guard) {
            // This mason is a donor — it will offer its stone to the elected builder
            // The elected builder's own goal tick will handle collecting
            // We just need to mark ourselves as a donor (no goal action needed this cycle)
            LOGGER.debug("MasonWallBuilder {}: acting as donor to elected builder {}",
                    guard.getUuidAsString(), electedBuilder.getUuidAsString());
            return false; // Non-builder masons don't run this goal this cycle
        }

        // This mason is the elected builder
        isElectedBuilder = true;

        // 6. Build transfer list from peers
        pendingTransfers = new ArrayList<>();
        for (MasonGuardEntity peer : peers) {
            if (peer == guard) continue;
            if (peer.getPairedChestPos() == null) continue;
            int peerStone = countCobblestoneInChest(world, peer.getPairedChestPos());
            if (peerStone > 0) {
                pendingTransfers.add(new TransferTask(peer.getPairedChestPos(), guard.getPairedChestPos(), peerStone));
            }
        }

        // 7. Store segments on guard for NBT persistence
        pendingSegments = new ArrayList<>(unbuilt);
        guard.setWallSegments(pendingSegments);

        // 8. Store gate reservation positions (1 per face) on the guard
        List<BlockPos> gatePositions = pickGatePositions(rect, unbuilt);
        guard.setWallGatePositions(gatePositions);

        LOGGER.info("MasonWallBuilder {}: elected builder; {} segments to place, {} transfers pending",
                guard.getUuidAsString(), pendingSegments.size(), pendingTransfers.size());
        return true;
    }

    // -------------------------------------------------------------------------
    // Stage ticks
    // -------------------------------------------------------------------------

    private void tickTransfer(ServerWorld world) {
        if (currentTransferIndex >= pendingTransfers.size()) {
            stage = pendingSegments.isEmpty() ? Stage.DONE : Stage.MOVE_TO_SEGMENT;
            return;
        }

        TransferTask task = pendingTransfers.get(currentTransferIndex);

        // Walk to the source chest
        double distSq = guard.squaredDistanceTo(
                task.sourceChestPos().getX() + 0.5,
                task.sourceChestPos().getY() + 0.5,
                task.sourceChestPos().getZ() + 0.5);

        if (distSq > REACH_SQ) {
            guard.getNavigation().startMovingTo(
                    task.sourceChestPos().getX() + 0.5,
                    task.sourceChestPos().getY() + 0.5,
                    task.sourceChestPos().getZ() + 0.5,
                    MOVE_SPEED);
            return;
        }

        // Close enough — transfer stone
        transferCobblestone(world, task.sourceChestPos(), task.destChestPos(), task.amount());
        currentTransferIndex++;
    }

    private void tickMoveToSegment(ServerWorld world) {
        // Skip already-built or gate-reserved segments
        while (currentSegmentIndex < pendingSegments.size()) {
            BlockPos pos = pendingSegments.get(currentSegmentIndex);
            if (isGatePosition(pos) || world.getBlockState(pos).isOf(Blocks.COBBLESTONE)) {
                currentSegmentIndex++;
                continue;
            }
            break;
        }

        if (currentSegmentIndex >= pendingSegments.size()) {
            stage = Stage.DONE;
            guard.clearWallSegments();
            return;
        }

        BlockPos target = pendingSegments.get(currentSegmentIndex);
        double distSq = guard.squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);

        if (distSq <= REACH_SQ) {
            stage = Stage.PLACE_BLOCK;
        } else {
            guard.getNavigation().startMovingTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, MOVE_SPEED);
        }
    }

    private void tickPlaceBlock(ServerWorld world) {
        if (currentSegmentIndex >= pendingSegments.size()) {
            stage = Stage.DONE;
            guard.clearWallSegments();
            return;
        }

        BlockPos target = pendingSegments.get(currentSegmentIndex);

        // Verify still unbuilt
        if (world.getBlockState(target).isOf(Blocks.COBBLESTONE) || isGatePosition(target)) {
            currentSegmentIndex++;
            stage = Stage.MOVE_TO_SEGMENT;
            return;
        }

        // Check we still have stone in our chest
        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos == null || countCobblestoneInChest(world, chestPos) < 1) {
            LOGGER.debug("MasonWallBuilder {}: ran out of stone mid-build", guard.getUuidAsString());
            stage = Stage.DONE;
            return;
        }

        // Consume 1 cobblestone from chest
        if (!consumeCobblestoneFromChest(world, chestPos, 1)) {
            stage = Stage.DONE;
            return;
        }

        // Place the block
        world.setBlockState(target, Blocks.COBBLESTONE.getDefaultState());
        LOGGER.debug("MasonWallBuilder {}: placed cobblestone at {}", guard.getUuidAsString(), target.toShortString());

        currentSegmentIndex++;
        stage = Stage.MOVE_TO_SEGMENT;
    }

    // -------------------------------------------------------------------------
    // Wall geometry
    // -------------------------------------------------------------------------

    /**
     * Scans job-site POI blocks and beds within BELL_EFFECT_RANGE of the QM chest anchor,
     * computes their bounding box, expands by WALL_EXPAND, and returns a rectangle.
     */
    private Optional<WallRect> computeWallRect(ServerWorld world, BlockPos anchorPos) {
        int range = VillageGuardStandManager.BELL_EFFECT_RANGE;
        Box searchBox = new Box(anchorPos).expand(range);

        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean found = false;

        // Scan job-site POI via the POI storage
        PointOfInterestStorage poiStorage = world.getPointOfInterestStorage();
        Stream<BlockPos> poiStream = poiStorage.getInSquare(
                type -> true,
                anchorPos,
                range,
                PointOfInterestStorage.OccupationStatus.ANY
        ).map(poi -> poi.getPos());

        for (BlockPos pos : (Iterable<BlockPos>) poiStream::iterator) {
            if (!searchBox.contains(pos.getX(), pos.getY(), pos.getZ())) continue;
            if (pos.getX() < minX) minX = pos.getX();
            if (pos.getX() > maxX) maxX = pos.getX();
            if (pos.getZ() < minZ) minZ = pos.getZ();
            if (pos.getZ() > maxZ) maxZ = pos.getZ();
            found = true;
        }

        // Also scan for beds
        for (BlockPos pos : BlockPos.iterate(
                (int) searchBox.minX, (int) searchBox.minY, (int) searchBox.minZ,
                (int) searchBox.maxX, (int) searchBox.maxY, (int) searchBox.maxZ)) {
            if (world.getBlockState(pos).isIn(BlockTags.BEDS)) {
                if (pos.getX() < minX) minX = pos.getX();
                if (pos.getX() > maxX) maxX = pos.getX();
                if (pos.getZ() < minZ) minZ = pos.getZ();
                if (pos.getZ() > maxZ) maxZ = pos.getZ();
                found = true;
            }
        }

        if (!found) return Optional.empty();

        // Expand by WALL_EXPAND and snap to a rectangle
        int wallY = anchorPos.getY(); // wall is at anchor Y level

        return Optional.of(new WallRect(
                minX - WALL_EXPAND,
                minZ - WALL_EXPAND,
                maxX + WALL_EXPAND,
                maxZ + WALL_EXPAND,
                wallY
        ));
    }

    /**
     * Returns all wall segment positions around the rectangle perimeter with terrain adaptation:
     * <ul>
     *   <li>Skip DIRT_PATH positions (natural gaps / roads)</li>
     *   <li>Skip positions where {@code |surfaceY - bellY| > STEEP_SKIP_THRESHOLD} (too steep)</li>
     *   <li>Skip positions where the dip depth exceeds {@code MAX_FILL_DEPTH} (ravine guard)</li>
     *   <li>For positions above ground level: place wall block on top of the surface
     *       (wall sits on hill rather than being buried inside it)</li>
     *   <li>For positions below ground level: emit a column of blocks from surfaceY+1
     *       down to bellY, filling the dip so the wall has no gap underneath</li>
     *   <li>Force at least 1 gap so the wall is never fully enclosed</li>
     *   <li>Gate positions (1 per face) are included in the list but tagged separately</li>
     * </ul>
     */
    private List<BlockPos> computeWallSegments(ServerWorld world, WallRect rect) {
        List<BlockPos> segments = new ArrayList<>();
        int bellY = rect.y();

        // North face (z = minZ), South face (z = maxZ)
        for (int x = rect.minX(); x <= rect.maxX(); x++) {
            resolveWallColumn(world, x, rect.minZ(), bellY, segments);
            resolveWallColumn(world, x, rect.maxZ(), bellY, segments);
        }
        // West face (x = minX), East face (x = maxX) — corners already covered above
        for (int z = rect.minZ() + 1; z < rect.maxZ(); z++) {
            resolveWallColumn(world, rect.minX(), z, bellY, segments);
            resolveWallColumn(world, rect.maxX(), z, bellY, segments);
        }

        // Ensure at least 1 forced gap (remove last segment if the wall would be fully closed)
        if (!segments.isEmpty()) {
            int perimeterTotal = 2 * (rect.maxX() - rect.minX() + rect.maxZ() - rect.minZ());
            if (segments.size() >= perimeterTotal) {
                segments.remove(segments.size() - 1);
            }
        }

        return segments;
    }

    /**
     * Resolves the wall block(s) for one perimeter (x, z) position, adapting to terrain:
     *
     * <ol>
     *   <li>Sample surface Y via {@code MOTION_BLOCKING_NO_LEAVES} heightmap
     *       (top of solid/liquid column, ignoring leaf canopy).</li>
     *   <li>Compute {@code delta = surfaceY - bellY}.</li>
     *   <li>If {@code delta > STEEP_SKIP_THRESHOLD}: the ground rises more than the threshold
     *       above bell level — the position is on a steep hill; skip it.</li>
     *   <li>If {@code -delta > MAX_FILL_DEPTH}: the ground is too far below bell level
     *       (ravine / cliff) — skip it.</li>
     *   <li>If {@code delta >= 0}: the surface is at or above bell level. Place the wall
     *       block at {@code surfaceY + 1} so it sits visibly on top of the ground.</li>
     *   <li>If {@code delta < 0}: the surface dips below bell level. Emit a column of
     *       blocks from {@code surfaceY + 1} up to {@code bellY} (inclusive) to fill the
     *       gap underneath the wall line.</li>
     *   <li>In all cases, skip DIRT_PATH and already-cobblestone positions.</li>
     * </ol>
     */
    private void resolveWallColumn(ServerWorld world, int x, int z, int bellY, List<BlockPos> out) {
        // Surface Y = top of the solid column (leaves excluded so forest canopy doesn't skew it)
        int surfaceY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        int delta = surfaceY - bellY;

        // Too steep upward — skip
        if (delta > STEEP_SKIP_THRESHOLD) {
            return;
        }

        // Too deep downward — skip (ravine / cliff guard)
        if (-delta > MAX_FILL_DEPTH) {
            return;
        }

        if (delta >= 0) {
            // Ground is at or above bell level: place wall block on top of surface
            BlockPos pos = new BlockPos(x, surfaceY + 1, z);
            addSegmentIfValid(world, pos, out);
        } else {
            // Ground dips below bell level: fill column from ground surface up to bell Y
            // so there is no gap underneath the wall line.
            for (int y = surfaceY + 1; y <= bellY; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                addSegmentIfValid(world, pos, out);
            }
        }
    }

    private void addSegmentIfValid(ServerWorld world, BlockPos pos, List<BlockPos> segments) {
        // Skip road tiles — natural gaps should stay open
        if (world.getBlockState(pos).isOf(Blocks.DIRT_PATH)) return;
        // Skip already-built positions
        if (world.getBlockState(pos).isOf(Blocks.COBBLESTONE)) return;
        segments.add(pos.toImmutable());
    }

    /**
     * Picks one gate position per wall face (N/S/E/W) — the midpoint of each face.
     * These are stored on the guard entity for lumberjack fence gate placement later.
     */
    private List<BlockPos> pickGatePositions(WallRect rect, List<BlockPos> unbuiltSegments) {
        List<BlockPos> gates = new ArrayList<>();
        int y = rect.y();
        Set<BlockPos> unbuiltSet = new HashSet<>(unbuiltSegments);

        // North face midpoint
        int northMidX = (rect.minX() + rect.maxX()) / 2;
        BlockPos northGate = new BlockPos(northMidX, y, rect.minZ());
        if (unbuiltSet.contains(northGate)) gates.add(northGate);

        // South face midpoint
        BlockPos southGate = new BlockPos(northMidX, y, rect.maxZ());
        if (unbuiltSet.contains(southGate)) gates.add(southGate);

        // West face midpoint
        int westMidZ = (rect.minZ() + rect.maxZ()) / 2;
        BlockPos westGate = new BlockPos(rect.minX(), y, westMidZ);
        if (unbuiltSet.contains(westGate)) gates.add(westGate);

        // East face midpoint
        BlockPos eastGate = new BlockPos(rect.maxX(), y, westMidZ);
        if (unbuiltSet.contains(eastGate)) gates.add(eastGate);

        return gates;
    }

    private boolean isGatePosition(BlockPos pos) {
        List<BlockPos> gates = guard.getWallGatePositions();
        return gates != null && gates.contains(pos);
    }

    // -------------------------------------------------------------------------
    // Peer mason utilities
    // -------------------------------------------------------------------------

    private List<MasonGuardEntity> getPeerMasons(ServerWorld world, BlockPos anchorPos) {
        Box searchBox = new Box(anchorPos).expand(PEER_SCAN_RANGE);
        return world.getEntitiesByClass(MasonGuardEntity.class, searchBox,
                mason -> mason.isAlive() && mason.getPairedChestPos() != null);
    }

    // -------------------------------------------------------------------------
    // Inventory utilities
    // -------------------------------------------------------------------------

    private int countCobblestoneInChest(ServerWorld world, BlockPos chestPos) {
        Optional<Inventory> inv = getInventory(world, chestPos);
        if (inv.isEmpty()) return 0;
        int count = 0;
        Inventory inventory = inv.get();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.COBBLESTONE)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean consumeCobblestoneFromChest(ServerWorld world, BlockPos chestPos, int amount) {
        Optional<Inventory> inv = getInventory(world, chestPos);
        if (inv.isEmpty()) return false;
        Inventory inventory = inv.get();
        int remaining = amount;
        for (int i = 0; i < inventory.size() && remaining > 0; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.COBBLESTONE)) {
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

    private void transferCobblestone(ServerWorld world, BlockPos sourcePos, BlockPos destPos, int maxAmount) {
        Optional<Inventory> srcOpt = getInventory(world, sourcePos);
        Optional<Inventory> dstOpt = getInventory(world, destPos);
        if (srcOpt.isEmpty() || dstOpt.isEmpty()) return;

        Inventory src = srcOpt.get();
        Inventory dst = dstOpt.get();
        int transferred = 0;

        for (int i = 0; i < src.size() && transferred < maxAmount; i++) {
            ItemStack stack = src.getStack(i);
            if (stack.isEmpty() || !stack.isOf(Items.COBBLESTONE)) continue;

            int toMove = Math.min(stack.getCount(), maxAmount - transferred);
            ItemStack moving = stack.copyWithCount(toMove);
            ItemStack remainder = insertIntoInventory(dst, moving);
            int moved = toMove - remainder.getCount();

            if (moved > 0) {
                stack.decrement(moved);
                if (stack.isEmpty()) src.setStack(i, ItemStack.EMPTY);
                transferred += moved;
            }
        }

        if (transferred > 0) {
            src.markDirty();
            dst.markDirty();
            LOGGER.debug("MasonWallBuilder {}: transferred {} cobblestone from {} to {}",
                    guard.getUuidAsString(), transferred, sourcePos.toShortString(), destPos.toShortString());
        }
    }

    private ItemStack insertIntoInventory(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < inventory.size() && !remaining.isEmpty(); i++) {
            ItemStack existing = inventory.getStack(i);
            if (existing.isEmpty()) {
                if (!inventory.isValid(i, remaining)) continue;
                int moved = Math.min(remaining.getCount(), remaining.getMaxCount());
                inventory.setStack(i, remaining.copyWithCount(moved));
                remaining.decrement(moved);
            } else if (ItemStack.areItemsAndComponentsEqual(existing, remaining) && inventory.isValid(i, remaining)) {
                int space = existing.getMaxCount() - existing.getCount();
                if (space <= 0) continue;
                int moved = Math.min(space, remaining.getCount());
                existing.increment(moved);
                remaining.decrement(moved);
            }
        }
        return remaining;
    }

    private Optional<Inventory> getInventory(ServerWorld world, BlockPos pos) {
        if (pos == null) return Optional.empty();
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) return Optional.empty();
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, pos, false));
    }

    // -------------------------------------------------------------------------
    // Records / enums
    // -------------------------------------------------------------------------

    private enum Stage {
        IDLE,
        TRANSFER_FROM_PEERS,
        MOVE_TO_SEGMENT,
        PLACE_BLOCK,
        DONE
    }

    private record WallRect(int minX, int minZ, int maxX, int maxZ, int y) {}

    private record TransferTask(BlockPos sourceChestPos, BlockPos destChestPos, int amount) {}
}
