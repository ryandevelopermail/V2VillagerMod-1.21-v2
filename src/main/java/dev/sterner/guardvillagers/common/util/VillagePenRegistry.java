package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.GuardVillagers;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Geometry-only pen registry that replaces the old banner-based pen detection.
 * <p>
 * Rescans the world every {@value #RESCAN_INTERVAL_TICKS} ticks (same cadence as the
 * lumberjack spawn manager) to detect fence-gate-enclosed pens near each registered bell.
 * Results are cached per-bell and survive server restarts via {@link PersistentState}.
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
    private static final String BELLS_KEY = "Bells";
    private static final String DIMENSION_KEY = "Dimension";
    private static final String BELL_POS_KEY = "BellPos";
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

    /** bell GlobalPos → list of detected pens. */
    private final Map<GlobalPos, List<PenEntry>> bellToPens = new HashMap<>();

    /** Tick of last rescan. Persisted so we don't rescan on first tick after restart. */
    private long lastRescanTick = Long.MIN_VALUE;

    // -------------------------------------------------------------------------
    // Static accessors
    // -------------------------------------------------------------------------

    public static VillagePenRegistry get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(getType(), STATE_ID);
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

        NbtList bellList = nbt.getList(BELLS_KEY, NbtElement.COMPOUND_TYPE);
        for (NbtElement element : bellList) {
            if (!(element instanceof NbtCompound bellEntry)) {
                continue;
            }
            if (!bellEntry.contains(DIMENSION_KEY, NbtElement.STRING_TYPE)
                    || !bellEntry.contains(BELL_POS_KEY, NbtElement.COMPOUND_TYPE)) {
                continue;
            }
            Identifier dimId = Identifier.tryParse(bellEntry.getString(DIMENSION_KEY));
            if (dimId == null) {
                continue;
            }
            Optional<BlockPos> bellPos = NbtHelper.toBlockPos(bellEntry, BELL_POS_KEY);
            if (bellPos.isEmpty()) {
                continue;
            }

            RegistryKey<net.minecraft.world.World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimId);
            GlobalPos bellKey = GlobalPos.create(worldKey, bellPos.get().toImmutable());

            NbtList penList = bellEntry.getList(PENS_KEY, NbtElement.COMPOUND_TYPE);
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
            state.bellToPens.put(bellKey, pens);
        }

        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        nbt.putLong("LastRescanTick", lastRescanTick);

        NbtList bellList = new NbtList();
        for (Map.Entry<GlobalPos, List<PenEntry>> entry : bellToPens.entrySet()) {
            NbtCompound bellEntry = new NbtCompound();
            bellEntry.putString(DIMENSION_KEY, entry.getKey().dimension().getValue().toString());
            bellEntry.put(BELL_POS_KEY, NbtHelper.fromBlockPos(entry.getKey().pos()));

            NbtList penList = new NbtList();
            for (PenEntry pen : entry.getValue()) {
                NbtCompound penEntry = new NbtCompound();
                penEntry.put(GATE_POS_KEY, NbtHelper.fromBlockPos(pen.gate()));
                penEntry.put(CENTER_KEY, NbtHelper.fromBlockPos(pen.center()));
                penEntry.put(FOOT_POS_KEY, NbtHelper.fromBlockPos(pen.foot()));
                penList.add(penEntry);
            }
            bellEntry.put(PENS_KEY, penList);
            bellList.add(bellEntry);
        }
        nbt.put(BELLS_KEY, bellList);
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
        if (now - registry.lastRescanTick < RESCAN_INTERVAL_TICKS) {
            return;
        }
        registry.rescan(world);
    }

    private void rescan(ServerWorld world) {
        lastRescanTick = world.getTime();

        // Find all bells in the world
        BellChestMappingState bellState = BellChestMappingState.get(world.getServer());
        java.util.Set<BlockPos> bells = bellState.getBellPositions(world);
        if (bells.isEmpty()) {
            return;
        }

        int totalPens = 0;
        for (BlockPos bellPos : bells) {
            GlobalPos key = GlobalPos.create(world.getRegistryKey(), bellPos.toImmutable());
            List<PenEntry> pens = scanPensNearBell(world, bellPos);
            bellToPens.put(key, pens);
            totalPens += pens.size();
        }

        markDirty();
        LOGGER.info("[VillagePenRegistry] rescan complete: {} bell(s), {} pen(s) detected", bells.size(), totalPens);
    }

    // -------------------------------------------------------------------------
    // Consumer API
    // -------------------------------------------------------------------------

    /**
     * Returns the list of detected pens associated with the given bell position.
     * Returns an empty list if no pens have been detected yet.
     */
    public List<PenEntry> getPens(ServerWorld world, BlockPos bellPos) {
        GlobalPos key = GlobalPos.create(world.getRegistryKey(), bellPos.toImmutable());
        return bellToPens.getOrDefault(key, List.of());
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

        for (GlobalPos key : bellToPens.keySet()) {
            if (!key.dimension().equals(world.getRegistryKey())) {
                continue;
            }
            BlockPos bellPos = key.pos();
            long dx = bellPos.getX() - entityPos.getX();
            long dz = bellPos.getZ() - entityPos.getZ();
            long dist = dx * dx + dz * dz;
            if (dist <= radiusSq && dist < nearestDist) {
                nearestDist = dist;
                nearest = key;
            }
        }

        if (nearest == null) {
            return List.of();
        }
        return bellToPens.getOrDefault(nearest, List.of());
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
        return getNearestPen(world, anchor, radius, 300);
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
            // No bell-registered pens — fall back to a live scan around the job-site position
            pens = scanPensNearJobSite(world, anchor, jobSiteFallbackRadius);
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
        List<PenEntry> pens = getNearestBellPens(world, anchor, radius);
        if (pens.isEmpty()) {
            pens = scanPensNearJobSite(world, anchor, 300);
        }
        return pens;
    }

    /**
     * Performs a live geometry scan for fence-gate-enclosed pens within {@code radius} blocks
     * of {@code jobSitePos}. Used as a fallback when no bell is registered nearby.
     * Results are NOT cached — this is intentionally a one-shot scan for rare/fallback use.
     */
    private List<PenEntry> scanPensNearJobSite(ServerWorld world, BlockPos jobSitePos, int radius) {
        LOGGER.debug("[VillagePenRegistry] job-site fallback scan around {} r={}", jobSitePos.toShortString(), radius);
        return scanPensNearBell(world, jobSitePos, radius);
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
            BlockState state = world.getBlockState(gatePos);
            if (!(state.getBlock() instanceof FenceGateBlock)) {
                continue;
            }
            BlockPos interior = resolveInterior(world, gatePos, state);
            if (interior == null) {
                continue;
            }
            if (!isInsideFencePen(world, interior)) {
                continue;
            }
            BlockPos center = getPenCenter(world, interior);
            if (center == null) {
                continue;
            }
            // Dedup by center proximity
            boolean duplicate = pens.stream().anyMatch(existing ->
                    existing.gate().equals(gatePos)
                    || existing.center().getSquaredDistance(center) <= 9.0D);
            if (duplicate) {
                continue;
            }
            pens.add(new PenEntry(gatePos.toImmutable(), center.toImmutable(), gatePos.toImmutable()));
        }
        return pens;
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
}
