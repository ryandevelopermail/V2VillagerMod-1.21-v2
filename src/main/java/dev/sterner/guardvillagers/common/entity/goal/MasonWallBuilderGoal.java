package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagersConfig;
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
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.registry.tag.PointOfInterestTypeTags;
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
 *   <li>Scan configured POI set (job sites only, jobs+beds, or all POIs) within
 *       BELL_EFFECT_RANGE of the nearest QM chest
 *       → compute axis-aligned bounding rectangle → expand 10 blocks outward.</li>
 *   <li>Elect the mason with the most cobblestone as the builder; non-builders transfer their
 *       stone into the elected mason's paired chest, then resume normal goals.</li>
 *   <li>The elected builder must have enough cobblestone for ALL planned perimeter placements
 *       (excluding gap positions) before placing any block. This is a one-thick perimeter in
 *       X/Z that may include vertical fill columns where terrain dips below anchor level.</li>
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
    // (required total is per planned block placement, including anti-gap vertical fill columns).
    private static final int STONE_PER_SEGMENT = 1;
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
    private int lastProgressLoggedSegmentCount = 0;
    private BlockPos activeMoveTarget = null;
    private int activeMoveTargetTicks = 0;
    private double lastMoveDistSq = Double.MAX_VALUE;

    // Whether this mason is the elected builder this cycle
    private boolean isElectedBuilder = false;

    // Transfers: list of (peerChestPos, amount) to drain from peer chests into our chest
    private List<TransferTask> pendingTransfers = new ArrayList<>();
    private int currentTransferIndex = 0;

    /**
     * Cached wall rectangle from the last successful computeWallRect() call.
     * Re-used across canStart() cycles as long as the anchor position hasn't changed.
     * This avoids the expensive POI stream scan + heightmap lookups every 400 ticks.
     * Invalidated when the anchor changes or the wall is marked complete.
     */
    private WallRect cachedWallRect = null;
    /** Anchor position that produced {@link #cachedWallRect}. Null = no cache. */
    private BlockPos cachedWallRectAnchor = null;
    /** POI footprint signature that produced {@link #cachedWallRect}. Null = no cache. */
    private PoiFootprintSignature cachedPoiFootprintSignature = null;
    /** Last mode logged for wall footprint scans; avoids repeating the same info line every cycle. */
    private GuardVillagersConfig.MasonWallPoiMode lastLoggedPoiMode = null;

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
    public boolean canStop() {
        // Keep the elected builder focused until the current build cycle finishes.
        return !isElectedBuilder || stage == Stage.DONE || stage == Stage.IDLE;
    }

    @Override
    public void start() {
        currentSegmentIndex = 0;
        currentTransferIndex = 0;
        lastProgressLoggedSegmentCount = 0;
        activeMoveTarget = null;
        activeMoveTargetTicks = 0;
        lastMoveDistSq = Double.MAX_VALUE;
        if (isElectedBuilder && !pendingTransfers.isEmpty()) {
            stage = Stage.TRANSFER_FROM_PEERS;
        } else if (isElectedBuilder && !pendingSegments.isEmpty()) {
            stage = Stage.MOVE_TO_SEGMENT;
        } else {
            stage = Stage.DONE;
        }
        if (isElectedBuilder) {
            String firstTarget = pendingSegments.isEmpty() ? "none" : pendingSegments.get(0).toShortString();
            LOGGER.info("MasonWallBuilder {}: build cycle started (segments={}, transfers={}, firstTarget={})",
                    guard.getUuidAsString(), pendingSegments.size(), pendingTransfers.size(), firstTarget);
        }
    }

    @Override
    public void stop() {
        guard.getNavigation().stop();
        if (isElectedBuilder && currentSegmentIndex > 0 && currentSegmentIndex < pendingSegments.size()) {
            LOGGER.info("MasonWallBuilder {}: build cycle paused/interrupted at {}/{}",
                    guard.getUuidAsString(), currentSegmentIndex, pendingSegments.size());
        }
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
        GuardVillagersConfig.MasonWallPoiMode poiMode = resolveWallPoiMode();
        if (poiMode != lastLoggedPoiMode) {
            LOGGER.info("MasonWallBuilder {}: wall POI scan mode={}", guard.getUuidAsString(), poiMode);
            LOGGER.info("MasonWallBuilder {}: wall profile=one-thick perimeter with anti-gap vertical fill (maxDepth={})",
                    guard.getUuidAsString(), MAX_FILL_DEPTH);
            lastLoggedPoiMode = poiMode;
        }

        // 1. Compute current POI footprint signature near the anchor using configured POI mode.
        // Recompute the wall rectangle whenever this signature changes, even if the anchor is unchanged.
        Optional<PoiFootprintSignature> signatureOpt = computePoiFootprintSignature(world, anchorPos);
        if (signatureOpt.isEmpty()) {
            LOGGER.debug("MasonWallBuilder {}: no village bounds found near anchor {}",
                    guard.getUuidAsString(), anchorPos.toShortString());
            cachedWallRect = null;
            cachedWallRectAnchor = null;
            cachedPoiFootprintSignature = null;
            return false;
        }

        PoiFootprintSignature currentSignature = signatureOpt.get();

        // 2. Compute (or reuse cached) wall rectangle.
        WallRect rect;
        boolean cacheValid = cachedWallRect != null
                && anchorPos.equals(cachedWallRectAnchor)
                && currentSignature.equals(cachedPoiFootprintSignature);
        if (cacheValid) {
            rect = cachedWallRect;
        } else {
            rect = computeWallRect(anchorPos, currentSignature);
            cachedWallRect = rect;
            cachedWallRectAnchor = anchorPos.toImmutable();
            cachedPoiFootprintSignature = currentSignature;
        }

        // 3. Compute all wall segment positions for the rectangle
        List<BlockPos> allSegments = computeWallSegments(world, rect);
        if (allSegments.isEmpty()) {
            return false;
        }

        // Filter to unbuilt segments only
        List<BlockPos> unbuilt = allSegments.stream()
                .filter(pos -> !isPlacedWallBlock(world.getBlockState(pos)))
                .toList();
        if (unbuilt.isEmpty()) {
            LOGGER.debug("MasonWallBuilder {}: wall already complete", guard.getUuidAsString());
            return false;
        }

        // Determine gate reservations before computing required stone so gate exclusions
        // are reflected in the build threshold.
        List<BlockPos> gatePositions = pickGatePositions(rect, unbuilt);
        Set<BlockPos> gateSet = new HashSet<>(gatePositions);

        int requiredStone = unbuilt.stream()
                .filter(pos -> !gateSet.contains(pos))
                .mapToInt(pos -> STONE_PER_SEGMENT)
                .sum();
        if (requiredStone < 1) {
            LOGGER.debug("MasonWallBuilder {}: no non-gate wall segments remain (available={}, required={})",
                    guard.getUuidAsString(), 0, requiredStone);
            return false;
        }

        // 4. Find all peer masons near the anchor
        List<MasonGuardEntity> peers = getPeerMasons(world, anchorPos);

        // 5. Count cobblestone across all peers (including self)
        int myStone = countWallMaterialUnitsInChest(world, guard.getPairedChestPos());
        int totalStone = myStone;
        for (MasonGuardEntity peer : peers) {
            if (peer == guard) continue;
            if (peer.getPairedChestPos() != null) {
                totalStone += countWallMaterialUnitsInChest(world, peer.getPairedChestPos());
            }
        }

        if (totalStone < requiredStone) {
            LOGGER.info("MasonWallBuilder {}: insufficient stone (available={}, required={})",
                    guard.getUuidAsString(), totalStone, requiredStone);
            return false;
        }
        LOGGER.info("MasonWallBuilder {}: stone threshold met (available={}, required={})",
                guard.getUuidAsString(), totalStone, requiredStone);

        // 6. Elect builder — the mason (including self) with the most stone
        MasonGuardEntity electedBuilder = guard;
        int electedStone = myStone;
        for (MasonGuardEntity peer : peers) {
            if (peer == guard) continue;
            if (peer.getPairedChestPos() == null) continue;
            int peerStone = countWallMaterialUnitsInChest(world, peer.getPairedChestPos());
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

        // 7. Build transfer list from peers
        pendingTransfers = new ArrayList<>();
        for (MasonGuardEntity peer : peers) {
            if (peer == guard) continue;
            if (peer.getPairedChestPos() == null) continue;
            int peerStone = countWallMaterialUnitsInChest(world, peer.getPairedChestPos());
            if (peerStone > 0) {
                pendingTransfers.add(new TransferTask(peer.getPairedChestPos(), guard.getPairedChestPos(), peerStone));
            }
        }

        // 8. Store segments on guard for NBT persistence
        pendingSegments = new ArrayList<>(unbuilt);
        guard.setWallSegments(pendingSegments);

        // 9. Store gate reservation positions (1 per face) on the guard
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
            if (isGatePosition(pos) || isPlacedWallBlock(world.getBlockState(pos))) {
                currentSegmentIndex++;
                continue;
            }
            break;
        }

        if (currentSegmentIndex >= pendingSegments.size()) {
            stage = Stage.DONE;
            guard.clearWallSegments();
            // Invalidate cached rect so next cycle recomputes with updated village bounds
            cachedWallRect = null;
            cachedWallRectAnchor = null;
            cachedPoiFootprintSignature = null;
            LOGGER.info("MasonWallBuilder {}: build cycle complete", guard.getUuidAsString());
            return;
        }

        BlockPos target = pendingSegments.get(currentSegmentIndex);
        BlockPos navigationTarget = resolveSegmentNavigationTarget(world, target);
        if (!target.equals(activeMoveTarget)) {
            activeMoveTarget = target;
            activeMoveTargetTicks = 0;
            lastMoveDistSq = Double.MAX_VALUE;
        }
        double distSq = guard.squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);

        if (distSq <= REACH_SQ) {
            activeMoveTarget = null;
            activeMoveTargetTicks = 0;
            lastMoveDistSq = Double.MAX_VALUE;
            stage = Stage.PLACE_BLOCK;
        } else {
            boolean pathStarted = guard.getNavigation().startMovingTo(
                    navigationTarget.getX() + 0.5,
                    navigationTarget.getY() + 0.5,
                    navigationTarget.getZ() + 0.5,
                    MOVE_SPEED
            );
            if (!pathStarted) {
                LOGGER.info("MasonWallBuilder {}: skipping unreachable segment {} (path not found, navTarget={})",
                        guard.getUuidAsString(), target.toShortString(), navigationTarget.toShortString());
                currentSegmentIndex++;
                activeMoveTarget = null;
                activeMoveTargetTicks = 0;
                lastMoveDistSq = Double.MAX_VALUE;
                return;
            }

            activeMoveTargetTicks++;
            if (distSq < lastMoveDistSq - 0.01D) {
                lastMoveDistSq = distSq;
                activeMoveTargetTicks = 0;
            } else if (activeMoveTargetTicks > 200) {
                LOGGER.info("MasonWallBuilder {}: skipping stalled segment {} after {} ticks (distSq={})",
                        guard.getUuidAsString(), target.toShortString(), activeMoveTargetTicks, String.format("%.2f", distSq));
                currentSegmentIndex++;
                activeMoveTarget = null;
                activeMoveTargetTicks = 0;
                lastMoveDistSq = Double.MAX_VALUE;
            }
        }
    }

    /**
     * Wall segments are frequently air blocks one block above terrain. Pathfinding directly
     * to those coordinates often fails because entities cannot stand in that space. Route
     * navigation to a nearby standable position instead, while still placing on the original
     * segment when the guard is within placement reach.
     */
    private BlockPos resolveSegmentNavigationTarget(ServerWorld world, BlockPos segmentPos) {
        BlockPos below = segmentPos.down();
        if (world.getBlockState(below).isSolidBlock(world, below)) {
            return below;
        }
        return segmentPos;
    }

    private void tickPlaceBlock(ServerWorld world) {
        if (currentSegmentIndex >= pendingSegments.size()) {
            stage = Stage.DONE;
            guard.clearWallSegments();
            cachedWallRect = null;
            cachedWallRectAnchor = null;
            cachedPoiFootprintSignature = null;
            return;
        }

        BlockPos target = pendingSegments.get(currentSegmentIndex);

        // Verify still unbuilt
        if (isPlacedWallBlock(world.getBlockState(target)) || isGatePosition(target)) {
            currentSegmentIndex++;
            stage = Stage.MOVE_TO_SEGMENT;
            return;
        }

        // Check we still have stone in our chest
        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos == null || countWallMaterialUnitsInChest(world, chestPos) < 1) {
            LOGGER.debug("MasonWallBuilder {}: ran out of wall material mid-build", guard.getUuidAsString());
            stage = Stage.DONE;
            return;
        }

        // Consume 1 unit of wall material from chest (prefer pre-crafted wall blocks).
        WallPlacementMaterial placementMaterial = consumeWallMaterialFromChest(world, chestPos);
        if (placementMaterial == null) {
            stage = Stage.DONE;
            return;
        }

        // Place the block
        world.setBlockState(target, placementMaterial.blockState());
        LOGGER.debug("MasonWallBuilder {}: placed {} at {}",
                guard.getUuidAsString(), placementMaterial.item().toString(), target.toShortString());

        currentSegmentIndex++;
        maybeLogPlacementProgress();
        stage = Stage.MOVE_TO_SEGMENT;
    }

    private void maybeLogPlacementProgress() {
        if (!isElectedBuilder || pendingSegments.isEmpty()) return;
        int placed = currentSegmentIndex;
        int total = pendingSegments.size();
        if (placed >= total || placed <= 10 || placed - lastProgressLoggedSegmentCount >= 25) {
            lastProgressLoggedSegmentCount = placed;
            int percent = (int) Math.floor((placed * 100.0) / total);
            LOGGER.info("MasonWallBuilder {}: placement progress {}/{} ({}%)",
                    guard.getUuidAsString(), placed, total, percent);
        }
    }

    // -------------------------------------------------------------------------
    // Wall geometry
    // -------------------------------------------------------------------------

    /**
     * Scans the configured POI subset within BELL_EFFECT_RANGE of the QM chest anchor,
     * computes their bounding box, expands by WALL_EXPAND, and returns a rectangle.
     */
    private Optional<PoiFootprintSignature> computePoiFootprintSignature(ServerWorld world, BlockPos anchorPos) {
        int range = VillageGuardStandManager.BELL_EFFECT_RANGE;
        Box searchBox = new Box(anchorPos).expand(range);

        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int count = 0;
        int hash = 1;
        boolean found = false;

        GuardVillagersConfig.MasonWallPoiMode poiMode = resolveWallPoiMode();

        // Scan configured POI set via the POI storage. This is O(POI count), not O(volume).
        PointOfInterestStorage poiStorage = world.getPointOfInterestStorage();
        Stream<BlockPos> poiStream = poiStorage.getInSquare(
                type -> switch (poiMode) {
                    case JOB_SITES_ONLY -> type.isIn(PointOfInterestTypeTags.ACQUIRABLE_JOB_SITE);
                    case JOBS_AND_BEDS -> type.isIn(PointOfInterestTypeTags.ACQUIRABLE_JOB_SITE) || type.matchesKey(PointOfInterestTypes.HOME);
                    case ALL_POIS -> true;
                },
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
            count++;
            int posHash = 31 * (31 * pos.getX() + pos.getY()) + pos.getZ();
            hash = 31 * hash + posHash;
            found = true;
        }

        if (!found) {
            return Optional.empty();
        }

        return Optional.of(new PoiFootprintSignature(minX, minZ, maxX, maxZ, count, hash));
    }

    private WallRect computeWallRect(BlockPos anchorPos, PoiFootprintSignature signature) {
        // Expand by WALL_EXPAND and snap to a rectangle
        int wallY = anchorPos.getY(); // wall is at anchor Y level

        return new WallRect(
                signature.minX() - WALL_EXPAND,
                signature.minZ() - WALL_EXPAND,
                signature.maxX() + WALL_EXPAND,
                signature.maxZ() + WALL_EXPAND,
                wallY
        );
    }

    private GuardVillagersConfig.MasonWallPoiMode resolveWallPoiMode() {
        GuardVillagersConfig.MasonWallPoiMode configured = GuardVillagersConfig.masonWallPoiMode;
        return configured != null ? configured : GuardVillagersConfig.MasonWallPoiMode.JOBS_AND_BEDS;
    }

    /**
     * Returns all planned wall block placements around the rectangle perimeter with terrain adaptation:
     * <ul>
     *   <li>Skip DIRT_PATH positions (natural gaps / roads)</li>
     *   <li>Skip positions where {@code |surfaceY - bellY| > STEEP_SKIP_THRESHOLD} (too steep)</li>
     *   <li>Skip positions where the dip depth exceeds {@code MAX_FILL_DEPTH} (ravine guard)</li>
     *   <li>For positions above ground level: place wall block on top of the surface
     *       (wall sits on hill rather than being buried inside it)</li>
     *   <li>For positions below anchor level: emit a column of blocks from surfaceY+1
     *       down to bellY, filling the dip so the wall has no gap underneath</li>
     *   <li>Force at least 1 gap so the wall is never fully enclosed</li>
     *   <li>Gate positions (1 per face) are included in the list but tagged separately</li>
     * </ul>
     */
    private List<BlockPos> computeWallSegments(ServerWorld world, WallRect rect) {
        List<BlockPos> segments = new ArrayList<>();
        int bellY = rect.y();

        for (BlockPos perimeterPos : computePerimeterTraversal(rect.minX(), rect.minZ(), rect.maxX(), rect.maxZ(), bellY)) {
            resolveWallColumn(world, perimeterPos.getX(), perimeterPos.getZ(), bellY, segments);
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

    static List<BlockPos> computePerimeterTraversal(int minX, int minZ, int maxX, int maxZ, int y) {
        List<BlockPos> perimeter = new ArrayList<>();

        // North face: minX -> maxX at minZ
        for (int x = minX; x <= maxX; x++) {
            perimeter.add(new BlockPos(x, y, minZ));
        }

        // East face: minZ+1 -> maxZ-1 at maxX (corners already included)
        for (int z = minZ + 1; z < maxZ; z++) {
            perimeter.add(new BlockPos(maxX, y, z));
        }

        // South face: maxX -> minX at maxZ
        for (int x = maxX; x >= minX; x--) {
            perimeter.add(new BlockPos(x, y, maxZ));
        }

        // West face: maxZ-1 -> minZ+1 at minX (corners already included)
        for (int z = maxZ - 1; z > minZ; z--) {
            perimeter.add(new BlockPos(minX, y, z));
        }

        return perimeter;
    }

    /**
     * Resolves planned wall block placement(s) for one perimeter (x, z) position, adapting to terrain:
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
     *       gap underneath the one-thick wall line.</li>
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
     * Picks one gate position per wall face (N/S/E/W) — the unbuilt segment closest to the
     * midpoint of each face.  Actual segment Y varies with terrain (surfaceY + 1), so we
     * search by X/Z proximity rather than constructing a fixed-Y position that would never
     * match any computed segment.
     *
     * These are stored on the guard entity for lumberjack fence gate placement later.
     */
    private List<BlockPos> pickGatePositions(WallRect rect, List<BlockPos> unbuiltSegments) {
        List<BlockPos> gates = new ArrayList<>();

        // Midpoint X and Z coordinates for each face
        int northMidX = (rect.minX() + rect.maxX()) / 2;
        int southMidX = northMidX;
        int westMidZ  = (rect.minZ() + rect.maxZ()) / 2;
        int eastMidZ  = westMidZ;

        // For each face, find the segment whose (X,Z) is closest to the face midpoint
        BlockPos northGate = findClosestSegmentOnFace(unbuiltSegments, northMidX, rect.minZ(), true);
        BlockPos southGate = findClosestSegmentOnFace(unbuiltSegments, southMidX, rect.maxZ(), true);
        BlockPos westGate  = findClosestSegmentOnFace(unbuiltSegments, rect.minX(), westMidZ,  false);
        BlockPos eastGate  = findClosestSegmentOnFace(unbuiltSegments, rect.maxX(), eastMidZ,  false);

        if (northGate != null) gates.add(northGate);
        if (southGate != null) gates.add(southGate);
        if (westGate  != null) gates.add(westGate);
        if (eastGate  != null) gates.add(eastGate);

        return gates;
    }

    /**
     * Finds the segment in {@code segments} that lies on the face defined by the fixed
     * coordinate ({@code fixedZ} when {@code fixedIsZ} is true, otherwise {@code fixedX})
     * and whose variable coordinate is closest to the midpoint value.
     *
     * @param segments    list of unbuilt wall segment positions
     * @param midX        target X coordinate (used as midpoint when fixedIsZ = true)
     * @param midZ        target Z coordinate (used as midpoint when fixedIsZ = false)
     * @param fixedIsZ    true → match segments where Z == midZ; false → match where X == midX
     */
    private BlockPos findClosestSegmentOnFace(List<BlockPos> segments, int midX, int midZ, boolean fixedIsZ) {
        BlockPos best = null;
        int bestDist = Integer.MAX_VALUE;
        for (BlockPos pos : segments) {
            if (fixedIsZ) {
                if (pos.getZ() != midZ) continue;
                int dist = Math.abs(pos.getX() - midX);
                if (dist < bestDist) { bestDist = dist; best = pos; }
            } else {
                if (pos.getX() != midX) continue;
                int dist = Math.abs(pos.getZ() - midZ);
                if (dist < bestDist) { bestDist = dist; best = pos; }
            }
        }
        return best;
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

    private int countWallMaterialUnitsInChest(ServerWorld world, BlockPos chestPos) {
        Optional<Inventory> inv = getInventory(world, chestPos);
        if (inv.isEmpty()) return 0;
        int count = 0;
        Inventory inventory = inv.get();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && isWallMaterialItem(stack.getItem())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private WallPlacementMaterial consumeWallMaterialFromChest(ServerWorld world, BlockPos chestPos) {
        Optional<Inventory> inv = getInventory(world, chestPos);
        if (inv.isEmpty()) return null;
        Inventory inventory = inv.get();
        for (WallPlacementMaterial material : WallPlacementMaterial.values()) {
            for (int i = 0; i < inventory.size(); i++) {
                ItemStack stack = inventory.getStack(i);
                if (!stack.isEmpty() && stack.isOf(material.item())) {
                    stack.decrement(1);
                    if (stack.isEmpty()) inventory.setStack(i, ItemStack.EMPTY);
                    inventory.markDirty();
                    return material;
                }
            }
        }
        return null;
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
            if (stack.isEmpty() || !isWallMaterialItem(stack.getItem())) continue;

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
            LOGGER.debug("MasonWallBuilder {}: transferred {} wall material from {} to {}",
                    guard.getUuidAsString(), transferred, sourcePos.toShortString(), destPos.toShortString());
        }
    }

    private boolean isPlacedWallBlock(BlockState state) {
        return state.isOf(Blocks.COBBLESTONE) || state.isOf(Blocks.COBBLESTONE_WALL);
    }

    private boolean isWallMaterialItem(net.minecraft.item.Item item) {
        return item == Items.COBBLESTONE || item == Items.COBBLESTONE_WALL;
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

    private record PoiFootprintSignature(int minX, int minZ, int maxX, int maxZ, int poiCount, int poiHash) {}

    private record TransferTask(BlockPos sourceChestPos, BlockPos destChestPos, int amount) {}

    private enum WallPlacementMaterial {
        COBBLESTONE_WALL(Items.COBBLESTONE_WALL, Blocks.COBBLESTONE_WALL.getDefaultState()),
        COBBLESTONE(Items.COBBLESTONE, Blocks.COBBLESTONE.getDefaultState());

        private final net.minecraft.item.Item item;
        private final BlockState blockState;

        WallPlacementMaterial(net.minecraft.item.Item item, BlockState blockState) {
            this.item = item;
            this.blockState = blockState;
        }

        net.minecraft.item.Item item() {
            return item;
        }

        BlockState blockState() {
            return blockState;
        }
    }
}
