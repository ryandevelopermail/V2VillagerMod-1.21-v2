package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.util.VillageAnchorState;
import net.minecraft.block.BlockState;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.PersistentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Geometry-only pen registry that replaces the old banner-based pen detection.
 * <p>
 * Rescans the world every {@value #RESCAN_INTERVAL_TICKS} ticks (same cadence as the
 * lumberjack spawn manager) to detect fence-gate-enclosed pens near each registered QM chest anchor.
 * Results are cached per-anchor and survive server restarts via {@link PersistentState}.
 * <p>
 * Consumer API:
 * <ul>
 *   <li>{@link #getPens(ServerWorld, BlockPos)} – returns cached pen list for a bell.</li>
 *   <li>{@link #getNearestBellPens(ServerWorld, BlockPos, int)} – finds the closest registered
 *       bell within {@code radius} blocks and returns its pen list.</li>
 * </ul>
 */
public class VillagePenRegistry extends PersistentState {
    private static final Logger LOGGER = LoggerFactory.getLogger(VillagePenRegistry.class);
    private static final String STATE_ID = GuardVillagers.MODID + "_village_pen_registry";
    private static final String ANCHORS_KEY = "Anchors";
    private static final String DIMENSION_KEY = "Dimension";
    private static final String ANCHOR_POS_KEY = "AnchorPos";
    private static final String PENS_KEY = "Pens";
    private static final String FOOT_POS_KEY = "FootPos";
    private static final String CENTER_KEY = "Center";
    private static final String GATE_POS_KEY = "GatePos";

    /** Rescan every 6000 ticks (≈5 min). Same cadence as lumberjack spawn manager. */
    static final long RESCAN_INTERVAL_TICKS = 6000L;

    /** Search radius for pens around each bell. */
    private static final int PEN_SCAN_RADIUS = 64;
    /** Max fence distance used for pen interior detection. */
    private static final int PEN_FENCE_RANGE = 64;
    /** Max gate candidates checked per bell per scan. */
    private static final int MAX_GATE_CANDIDATES = 64;
    /** Fallback cache key region size in blocks. */
    private static final int JOB_SITE_CACHE_REGION_SIZE = 64;
    /** Fallback cache TTL in ticks (short-lived by design to avoid stale geometry). */
    private static int JOB_SITE_FALLBACK_CACHE_TTL_TICKS = 400;
    /** Hard block-iteration budget for fallback scans per invocation. */
    private static final int JOB_SITE_SCAN_MAX_BLOCKS_PER_CALL = 2_048;
    /** Hard gate-processing budget for fallback scans per invocation. */
    private static final int JOB_SITE_SCAN_MAX_GATES_PER_CALL = 8;
    /** Startup warmup window where only cached pen results are used. */
    private static final long JOB_SITE_FALLBACK_WARMUP_TICKS = 200L;
    /** Max deferred fallback scans progressed per tick after warmup. */
    private static final int DEFERRED_SCAN_ENTRY_BUDGET_PER_TICK = 2;
    /**
     * Max horizontal blocks to BFS from gate into pen interior.
     * Raised to 16 so pens up to ~32×32 are reliably detected.
     * (Interior BFS visits nodes up to this distance from the gate, which covers
     * pens whose center is well beyond 8 blocks from the gate opening.)
     */
    private static final int GATE_INTERIOR_MAX_DISTANCE = 16;

    /**
     * A detected pen: the gate position, the interior center, and the foot (gate-level) position
     * used as a navigation anchor.
     */
    public record PenEntry(BlockPos gate, BlockPos center, BlockPos foot) {
        /** Convenience – true if the candidate position is geometrically inside this pen. */
        public boolean containsPos(ServerWorld world, BlockPos pos) {
            return VillagePenRegistry.isInsideSpecificPen(world, pos, center);
        }
    }

    /** QM chest GlobalPos → list of detected pens. */
    private final Map<GlobalPos, List<PenEntry>> anchorToPens = new HashMap<>();

    /** Tick of last rescan. Persisted so we don't rescan on first tick after restart. */
    private long lastRescanTick = Long.MIN_VALUE;
    /** Short-lived cache for job-site fallback scans keyed by (dimension, region cell). */
    private final Map<FallbackCacheKey, FallbackCacheEntry> fallbackScanCache = new HashMap<>();

    // -------------------------------------------------------------------------
    // Static accessors
    // -------------------------------------------------------------------------

    public static VillagePenRegistry get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(getType(), STATE_ID);
    }

    /**
     * Called when anchor registration changes. Clears fallback scan cache to avoid stale
     * geometry-driven routing around moved or removed anchors.
     */
    public static void invalidateFallbackCache(MinecraftServer server) {
        VillagePenRegistry registry = get(server);
        if (!registry.fallbackScanCache.isEmpty()) {
            registry.fallbackScanCache.clear();
            registry.markDirty();
        }
    }

    public static void setJobSiteFallbackCacheTtlTicks(int ttlTicks) {
        JOB_SITE_FALLBACK_CACHE_TTL_TICKS = Math.max(1, ttlTicks);
    }

    public static int getDeferredFallbackScanCount(MinecraftServer server) {
        VillagePenRegistry registry = get(server);
        int deferred = 0;
        for (FallbackCacheEntry entry : registry.fallbackScanCache.values()) {
            if (entry.continuation != null) {
                deferred++;
            }
        }
        return deferred;
    }

    private static Type<VillagePenRegistry> getType() {
        return new Type<>(VillagePenRegistry::new, VillagePenRegistry::fromNbt, null);
    }

    // -------------------------------------------------------------------------
    // NBT
    // -------------------------------------------------------------------------

    private static VillagePenRegistry fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        VillagePenRegistry state = new VillagePenRegistry();
        state.lastRescanTick = nbt.getLong("LastRescanTick");

        // Support both the new "Anchors" key and the legacy "Bells" key so old saves load cleanly.
        String listKey = nbt.contains(ANCHORS_KEY, NbtElement.LIST_TYPE) ? ANCHORS_KEY : "Bells";
        String posKey = listKey.equals(ANCHORS_KEY) ? ANCHOR_POS_KEY : "BellPos";

        NbtList anchorList = nbt.getList(listKey, NbtElement.COMPOUND_TYPE);
        for (NbtElement element : anchorList) {
            if (!(element instanceof NbtCompound anchorEntry)) {
                continue;
            }
            if (!anchorEntry.contains(DIMENSION_KEY, NbtElement.STRING_TYPE)
                    || !anchorEntry.contains(posKey, NbtElement.COMPOUND_TYPE)) {
                continue;
            }
            Identifier dimId = Identifier.tryParse(anchorEntry.getString(DIMENSION_KEY));
            if (dimId == null) {
                continue;
            }
            Optional<BlockPos> anchorPos = NbtHelper.toBlockPos(anchorEntry, posKey);
            if (anchorPos.isEmpty()) {
                continue;
            }

            RegistryKey<net.minecraft.world.World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimId);
            GlobalPos anchorKey = GlobalPos.create(worldKey, anchorPos.get().toImmutable());

            NbtList penList = anchorEntry.getList(PENS_KEY, NbtElement.COMPOUND_TYPE);
            List<PenEntry> pens = new ArrayList<>();
            for (NbtElement penElement : penList) {
                if (!(penElement instanceof NbtCompound penEntry)) {
                    continue;
                }
                Optional<BlockPos> gate = NbtHelper.toBlockPos(penEntry, GATE_POS_KEY);
                Optional<BlockPos> center = NbtHelper.toBlockPos(penEntry, CENTER_KEY);
                Optional<BlockPos> foot = NbtHelper.toBlockPos(penEntry, FOOT_POS_KEY);
                if (gate.isEmpty() || center.isEmpty() || foot.isEmpty()) {
                    continue;
                }
                pens.add(new PenEntry(gate.get().toImmutable(), center.get().toImmutable(), foot.get().toImmutable()));
            }
            state.anchorToPens.put(anchorKey, pens);
        }

        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        nbt.putLong("LastRescanTick", lastRescanTick);

        NbtList anchorList = new NbtList();
        for (Map.Entry<GlobalPos, List<PenEntry>> entry : anchorToPens.entrySet()) {
            NbtCompound anchorEntry = new NbtCompound();
            anchorEntry.putString(DIMENSION_KEY, entry.getKey().dimension().getValue().toString());
            anchorEntry.put(ANCHOR_POS_KEY, NbtHelper.fromBlockPos(entry.getKey().pos()));

            NbtList penList = new NbtList();
            for (PenEntry pen : entry.getValue()) {
                NbtCompound penEntry = new NbtCompound();
                penEntry.put(GATE_POS_KEY, NbtHelper.fromBlockPos(pen.gate()));
                penEntry.put(CENTER_KEY, NbtHelper.fromBlockPos(pen.center()));
                penEntry.put(FOOT_POS_KEY, NbtHelper.fromBlockPos(pen.foot()));
                penList.add(penEntry);
            }
            anchorEntry.put(PENS_KEY, penList);
            anchorList.add(anchorEntry);
        }
        nbt.put(ANCHORS_KEY, anchorList);
        return nbt;
    }

    // -------------------------------------------------------------------------
    // Tick / rescan
    // -------------------------------------------------------------------------

    /**
     * Called once per server tick for each world (wired in {@link GuardVillagers}).
     * Rescans the overworld every {@value #RESCAN_INTERVAL_TICKS} ticks.
     */
    public static void tick(ServerWorld world) {
        // Only update registry from the overworld (bells are tracked per primary bell there)
        if (!world.getRegistryKey().equals(net.minecraft.world.World.OVERWORLD)) {
            return;
        }
        VillagePenRegistry registry = get(world.getServer());
        long now = world.getTime();
        registry.progressDeferredFallbackScans(world, now);
        if (now - registry.lastRescanTick < RESCAN_INTERVAL_TICKS) {
            return;
        }
        registry.rescan(world);
    }

    private void rescan(ServerWorld world) {
        lastRescanTick = world.getTime();

        // Find all registered QM chests (village anchors) in this world
        VillageAnchorState anchorState = VillageAnchorState.get(world.getServer());
        java.util.Set<BlockPos> anchors = anchorState.getAllQmChests(world);
        if (anchors.isEmpty()) {
            return;
        }

        int totalPens = 0;
        for (BlockPos anchorPos : anchors) {
            GlobalPos key = GlobalPos.create(world.getRegistryKey(), anchorPos.toImmutable());
            List<PenEntry> pens = scanPensNearBell(world, anchorPos);
            anchorToPens.put(key, pens);
            totalPens += pens.size();
        }

        // Rescan completion means world geometry may have changed significantly.
        fallbackScanCache.clear();
        markDirty();
        LOGGER.info("[VillagePenRegistry] rescan complete: {} anchor(s), {} pen(s) detected", anchors.size(), totalPens);
    }

    // -------------------------------------------------------------------------
    // Consumer API
    // -------------------------------------------------------------------------

    /**
     * Returns the list of detected pens associated with the given bell position.
     * Returns an empty list if no pens have been detected yet.
     */
    public List<PenEntry> getPens(ServerWorld world, BlockPos anchorPos) {
        GlobalPos key = GlobalPos.create(world.getRegistryKey(), anchorPos.toImmutable());
        return anchorToPens.getOrDefault(key, List.of());
    }

    /**
     * Finds the closest registered bell within {@code radius} blocks of {@code entityPos}
     * and returns its pen list. Returns an empty list if no bell is nearby or no pens have been
     * detected.
     */
    public List<PenEntry> getNearestBellPens(ServerWorld world, BlockPos entityPos, int radius) {
        long radiusSq = (long) radius * radius;
        GlobalPos nearest = null;
        long nearestDist = Long.MAX_VALUE;

        for (GlobalPos key : anchorToPens.keySet()) {
            if (!key.dimension().equals(world.getRegistryKey())) {
                continue;
            }
            BlockPos anchorPos = key.pos();
            long dx = anchorPos.getX() - entityPos.getX();
            long dz = anchorPos.getZ() - entityPos.getZ();
            long dist = dx * dx + dz * dz;
            if (dist <= radiusSq && dist < nearestDist) {
                nearestDist = dist;
                nearest = key;
            }
        }

        if (nearest == null) {
            return List.of();
        }
        return anchorToPens.getOrDefault(nearest, List.of());
    }

    /**
     * Returns the nearest {@link PenEntry} to {@code anchor} among all pens registered for
     * the nearest bell within {@code radius} blocks. Useful for Farmer/Butcher single-pen
     * selection (they just want the closest pen).
     *
     * <p>If no registered bell exists within {@code radius} blocks, falls back to a live
     * geometry scan within {@code jobSiteFallbackRadius} blocks of {@code anchor} (i.e. the
     * villager's job-site position). Pass 0 to disable the fallback.
     */
    public Optional<PenEntry> getNearestPen(ServerWorld world, BlockPos anchor, int radius) {
        return getNearestPen(world, anchor, radius, 160);
    }

    /**
     * Returns the nearest {@link PenEntry} to {@code anchor} among all pens registered for
     * the nearest bell within {@code radius} blocks.
     *
     * <p>If no bell-registered pens are found AND {@code jobSiteFallbackRadius > 0}, performs
     * a live geometry scan within {@code jobSiteFallbackRadius} blocks of {@code anchor} so
     * that pens are visible even in worlds without a registered bell.
     */
    public Optional<PenEntry> getNearestPen(ServerWorld world, BlockPos anchor, int radius, int jobSiteFallbackRadius) {
        List<PenEntry> pens = getNearestBellPens(world, anchor, radius);
        if (pens.isEmpty() && jobSiteFallbackRadius > 0) {
            // No bell-registered pens — fall back to a budgeted short-TTL cache around job-site.
            pens = getJobSiteFallbackPens(world, anchor, jobSiteFallbackRadius);
        }
        if (pens.isEmpty()) {
            return Optional.empty();
        }
        PenEntry nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (PenEntry pen : pens) {
            double dist = anchor.getSquaredDistance(pen.gate());
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = pen;
            }
        }
        return Optional.ofNullable(nearest);
    }

    /**
     * Returns pen entries near {@code anchor} using bell registry first, then falling back
     * to a live job-site scan within 300 blocks if no bell is found. This is the preferred
     * call for shepherd-specific pen detection (needs a list, not just the nearest).
     */
    public List<PenEntry> getNearestBellPensWithJobSiteFallback(ServerWorld world, BlockPos anchor, int radius) {
        return getNearestBellPensWithJobSiteFallback(world, anchor, radius, 160);
    }

    public List<PenEntry> getNearestBellPensWithJobSiteFallback(ServerWorld world, BlockPos anchor, int radius, int jobSiteFallbackRadius) {
        List<PenEntry> pens = getNearestBellPens(world, anchor, radius);
        if (pens.isEmpty() && jobSiteFallbackRadius > 0) {
            pens = getJobSiteFallbackPens(world, anchor, jobSiteFallbackRadius);
        }
        return pens;
    }

    /**
     * Performs a live geometry scan for fence-gate-enclosed pens within {@code radius} blocks
     * of {@code jobSitePos}. Used as a fallback when no bell is registered nearby.
     * Results are cached with short TTL and scanned with per-call budgets to avoid
     * large single-tick geometry spikes.
     */
    private List<PenEntry> getJobSiteFallbackPens(ServerWorld world, BlockPos jobSitePos, int radius) {
        FallbackCacheKey key = FallbackCacheKey.of(world, jobSitePos);
        FallbackCacheEntry entry = fallbackScanCache.get(key);
        long now = world.getTime();
        boolean fresh = entry != null
                && (now - entry.scanTick) <= JOB_SITE_FALLBACK_CACHE_TTL_TICKS
                && entry.scanRadius == radius
                && entry.continuation == null;
        if (fresh) {
            return entry.pens;
        }

        if (entry == null || entry.scanRadius != radius || (now - entry.scanTick) > JOB_SITE_FALLBACK_CACHE_TTL_TICKS) {
            entry = new FallbackCacheEntry(radius);
            fallbackScanCache.put(key, entry);
        }
        entry.lastRequestedPos = jobSitePos.toImmutable();
        if (now < JOB_SITE_FALLBACK_WARMUP_TICKS) {
            entry.deferredDuringWarmup = true;
            if (entry.continuation == null) {
                entry.continuation = ScanContinuation.start(world, jobSitePos, entry.scanRadius);
                entry.pens = new ArrayList<>();
            }
            // Warmup mode: avoid broad geometry scans. We keep the scan queued so it can
            // resume as soon as warmup ends, while callers continue using registry/cached data.
            return entry.pens;
        }
        runFallbackScanStep(world, jobSitePos, entry, now);
        return entry.pens;
    }

    private void progressDeferredFallbackScans(ServerWorld world, long now) {
        if (now < JOB_SITE_FALLBACK_WARMUP_TICKS || fallbackScanCache.isEmpty()) {
            return;
        }

        int progressed = 0;
        for (FallbackCacheEntry entry : fallbackScanCache.values()) {
            if (progressed >= DEFERRED_SCAN_ENTRY_BUDGET_PER_TICK) {
                break;
            }
            if (!entry.deferredDuringWarmup || entry.lastRequestedPos == null || entry.continuation == null) {
                continue;
            }
            runFallbackScanStep(world, entry.lastRequestedPos, entry, now);
            if (entry.continuation == null) {
                entry.deferredDuringWarmup = false;
            }
            progressed++;
        }
    }

    private void runFallbackScanStep(ServerWorld world, BlockPos jobSitePos, FallbackCacheEntry entry, long now) {
        if (entry.continuation == null) {
            entry.continuation = ScanContinuation.start(world, jobSitePos, entry.scanRadius);
            entry.pens = new ArrayList<>();
            LOGGER.debug("[VillagePenRegistry] job-site fallback scan start around {} r={}",
                    jobSitePos.toShortString(), entry.scanRadius);
        }

        ScanContinuation continuation = entry.continuation;
        List<BlockPos> gateCandidates = new ArrayList<>();
        int blocksProcessed = 0;
        while (blocksProcessed < JOB_SITE_SCAN_MAX_BLOCKS_PER_CALL && continuation.hasNext()) {
            BlockPos cursor = continuation.next();
            blocksProcessed++;
            if (!jobSitePos.isWithinDistance(cursor, entry.scanRadius)) {
                continue;
            }
            if (world.getBlockState(cursor).getBlock() instanceof FenceGateBlock) {
                gateCandidates.add(cursor.toImmutable());
                if (gateCandidates.size() >= JOB_SITE_SCAN_MAX_GATES_PER_CALL) {
                    break;
                }
            }
        }

        int gatesProcessed = 0;
        for (BlockPos gatePos : gateCandidates) {
            if (gatesProcessed >= JOB_SITE_SCAN_MAX_GATES_PER_CALL) {
                break;
            }
            gatesProcessed++;
            addPenFromGate(world, gatePos, entry.pens);
        }

        if (!continuation.hasNext()) {
            entry.scanTick = now;
            entry.continuation = null;
            entry.pens = Collections.unmodifiableList(new ArrayList<>(entry.pens));
            markDirty();
            LOGGER.debug("[VillagePenRegistry] job-site fallback scan complete around {} r={} pens={}",
                    jobSitePos.toShortString(), entry.scanRadius, entry.pens.size());
        }
    }

    // -------------------------------------------------------------------------
    // Pen geometry detection
    // -------------------------------------------------------------------------

    private List<PenEntry> scanPensNearBell(ServerWorld world, BlockPos bellPos) {
        return scanPensNearBell(world, bellPos, PEN_SCAN_RADIUS);
    }

    private List<PenEntry> scanPensNearBell(ServerWorld world, BlockPos bellPos, int scanRadius) {
        int y = bellPos.getY();
        int minY = Math.max(world.getBottomY(), y - 16);
        int maxY = Math.min(world.getTopY(), y + 16);

        List<BlockPos> gateCandidates = new ArrayList<>();
        BlockPos scanMin = bellPos.add(-scanRadius, minY - bellPos.getY(), -scanRadius);
        BlockPos scanMax = bellPos.add(scanRadius, maxY - bellPos.getY(), scanRadius);

        for (BlockPos cursor : BlockPos.iterate(scanMin, scanMax)) {
            if (!bellPos.isWithinDistance(cursor, scanRadius)) {
                continue;
            }
            if (world.getBlockState(cursor).getBlock() instanceof FenceGateBlock) {
                gateCandidates.add(cursor.toImmutable());
                if (gateCandidates.size() >= MAX_GATE_CANDIDATES) {
                    break;
                }
            }
        }

        List<PenEntry> pens = new ArrayList<>();
        for (BlockPos gatePos : gateCandidates) {
            addPenFromGate(world, gatePos, pens);
        }
        return pens;
    }

    private void addPenFromGate(ServerWorld world, BlockPos gatePos, List<PenEntry> pens) {
        BlockState state = world.getBlockState(gatePos);
        if (!(state.getBlock() instanceof FenceGateBlock)) {
            return;
        }
        BlockPos interior = resolveInterior(world, gatePos, state);
        if (interior == null) {
            return;
        }
        if (!isInsideFencePen(world, interior)) {
            return;
        }
        BlockPos center = getPenCenter(world, interior);
        if (center == null) {
            return;
        }
        boolean duplicate = pens.stream().anyMatch(existing ->
                existing.gate().equals(gatePos)
                        || existing.center().getSquaredDistance(center) <= 9.0D);
        if (duplicate) {
            return;
        }
        pens.add(new PenEntry(gatePos.toImmutable(), center.toImmutable(), gatePos.toImmutable()));
    }

    private BlockPos resolveInterior(ServerWorld world, BlockPos gatePos, BlockState state) {
        if (!state.contains(FenceGateBlock.FACING)) {
            return null;
        }
        Direction facing = state.get(FenceGateBlock.FACING);
        BlockPos front = gatePos.offset(facing);
        BlockPos back = gatePos.offset(facing.getOpposite());

        BlockPos fromFront = findInteriorFromGateSide(world, front, gatePos);
        if (fromFront != null) {
            return fromFront;
        }
        return findInteriorFromGateSide(world, back, gatePos);
    }

    private BlockPos findInteriorFromGateSide(ServerWorld world, BlockPos startPos, BlockPos gatePos) {
        if (isInsideFencePen(world, startPos)) {
            return startPos.toImmutable();
        }
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        java.util.LinkedHashSet<BlockPos> visited = new java.util.LinkedHashSet<>();
        queue.add(startPos.toImmutable());
        visited.add(startPos.toImmutable());

        while (!queue.isEmpty() && visited.size() <= 1024) {
            BlockPos current = queue.poll();
            if (isInsideFencePen(world, current)) {
                return current.toImmutable();
            }
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos next = current.offset(dir);
                if (visited.contains(next)) {
                    continue;
                }
                if (!gatePos.isWithinDistance(next, GATE_INTERIOR_MAX_DISTANCE)) {
                    continue;
                }
                BlockState ns = world.getBlockState(next);
                if (ns.getBlock() instanceof FenceBlock || ns.getBlock() instanceof FenceGateBlock) {
                    continue;
                }
                if (!ns.isAir()) {
                    continue;
                }
                visited.add(next.toImmutable());
                queue.add(next.toImmutable());
            }
        }
        return null;
    }

    static boolean isInsideFencePen(ServerWorld world, BlockPos pos) {
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            if (!hasFenceInDirection(world, pos, dir, PEN_FENCE_RANGE)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasFenceInDirection(ServerWorld world, BlockPos start, Direction dir, int maxDist) {
        for (int i = 1; i <= maxDist; i++) {
            BlockPos pos = start.offset(dir, i);
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof FenceBlock || state.getBlock() instanceof FenceGateBlock) {
                return true;
            }
        }
        return false;
    }

    private BlockPos getPenCenter(ServerWorld world, BlockPos insidePos) {
        BlockPos west = findFenceInDirection(world, insidePos, Direction.WEST);
        BlockPos east = findFenceInDirection(world, insidePos, Direction.EAST);
        BlockPos north = findFenceInDirection(world, insidePos, Direction.NORTH);
        BlockPos south = findFenceInDirection(world, insidePos, Direction.SOUTH);
        if (west == null || east == null || north == null || south == null) {
            return null;
        }
        int minX = west.getX() + 1;
        int maxX = east.getX() - 1;
        int minZ = north.getZ() + 1;
        int maxZ = south.getZ() - 1;
        if (minX > maxX || minZ > maxZ) {
            return null;
        }
        return new BlockPos((minX + maxX) / 2, insidePos.getY(), (minZ + maxZ) / 2);
    }

    private BlockPos findFenceInDirection(ServerWorld world, BlockPos start, Direction dir) {
        for (int i = 1; i <= PEN_FENCE_RANGE; i++) {
            BlockPos pos = start.offset(dir, i);
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof FenceBlock || state.getBlock() instanceof FenceGateBlock) {
                return pos;
            }
        }
        return null;
    }

    /** Used by consumers who want to check if a position is inside a specific pen. */
    public static boolean isInsideSpecificPen(ServerWorld world, BlockPos pos, BlockPos expectedCenter) {
        if (expectedCenter == null || !isInsideFencePen(world, pos)) {
            return false;
        }
        VillagePenRegistry registry = get(world.getServer());
        BlockPos detected = registry.getPenCenter(world, pos);
        return detected != null && detected.getSquaredDistance(expectedCenter) <= 9.0D;
    }

    private record FallbackCacheKey(RegistryKey<net.minecraft.world.World> dimension, int regionX, int regionZ) {
        private static FallbackCacheKey of(ServerWorld world, BlockPos pos) {
            return new FallbackCacheKey(
                    world.getRegistryKey(),
                    Math.floorDiv(pos.getX(), JOB_SITE_CACHE_REGION_SIZE),
                    Math.floorDiv(pos.getZ(), JOB_SITE_CACHE_REGION_SIZE));
        }
    }

    private static final class FallbackCacheEntry {
        private final int scanRadius;
        private long scanTick = Long.MIN_VALUE;
        private List<PenEntry> pens = List.of();
        private ScanContinuation continuation;
        private BlockPos lastRequestedPos;
        private boolean deferredDuringWarmup;

        private FallbackCacheEntry(int scanRadius) {
            this.scanRadius = scanRadius;
        }
    }

    private static final class ScanContinuation {
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int width;
        private final int height;
        private final int depth;
        private final int total;
        private int index;

        private ScanContinuation(int minX, int minY, int minZ, int width, int height, int depth) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.total = width * height * depth;
        }

        private static ScanContinuation start(ServerWorld world, BlockPos center, int radius) {
            int y = center.getY();
            int minY = Math.max(world.getBottomY(), y - 16);
            int maxY = Math.min(world.getTopY(), y + 16);
            int width = radius * 2 + 1;
            int height = maxY - minY + 1;
            int depth = radius * 2 + 1;
            return new ScanContinuation(center.getX() - radius, minY, center.getZ() - radius, width, height, depth);
        }

        private boolean hasNext() {
            return index < total;
        }

        private BlockPos next() {
            int current = index++;
            int localX = current % width;
            int yz = current / width;
            int localY = yz % height;
            int localZ = yz / height;
            return new BlockPos(minX + localX, minY + localY, minZ + localZ);
        }
    }
}
