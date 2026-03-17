package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.entity.goal.LumberjackGuardChopTreesGoal;
import dev.sterner.guardvillagers.common.villager.UnemployedLumberjackConversionHook;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Periodically scans all known village bells and ensures enough lumberjacks exist
 * relative to the village population.
 *
 * <p>Desired lumberjack count per bell:
 * <pre>
 *   max(ceil(professionals / 3), ceil(totalVillagers / 6))
 * </pre>
 * where "professionals" = non-nitwit, non-unemployed villagers within BELL_EFFECT_RANGE,
 * and "totalVillagers" = all non-nitwit (including unemployed, excluding babies) villagers.
 *
 * <p>If more lumberjacks are needed, a crafting table is placed 10–20 blocks from the bell
 * (horizontal ring, solid ground, minimum 5 blocks from any existing crafting table), then
 * {@link UnemployedLumberjackConversionHook} is nudged to pick it up on its next scheduled
 * pass — which it will, because it scans all unpairedcrafting tables world-wide.
 */
public final class VillageLumberjackSpawnManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(VillageLumberjackSpawnManager.class);

    /**
     * How often (in ticks) this manager runs the bell scan. 6000 = every 5 minutes.
     *
     * <p>This must be long enough for a placed crafting table to be picked up by the
     * conversion hook and for the resulting lumberjack to register as "existing" before
     * the next scan fires — otherwise the deficit stays non-zero and additional tables
     * keep getting placed every cycle.
     */
    private static final long SCAN_INTERVAL_TICKS = 6000L;

    /** Bell effect radius — mirrors VillageGuardStandManager.BELL_EFFECT_RANGE. */
    private static final int BELL_EFFECT_RANGE = VillageGuardStandManager.BELL_EFFECT_RANGE;

    /** Minimum horizontal distance from bell for a new crafting table. */
    private static final int TABLE_MIN_DIST = 10;

    /** Maximum horizontal distance from bell for a new crafting table. */
    private static final int TABLE_MAX_DIST = 20;

    /** Minimum distance (blocks) between any two crafting tables in the placement area. */
    private static final int TABLE_MIN_SPACING = 5;

    /**
     * Tree search radius used to score candidate positions.
     * Must match {@link LumberjackGuardChopTreesGoal}'s own TREE_SEARCH_RADIUS (20).
     */
    private static final int TREE_SEARCH_RADIUS = 20;
    private static final int TREE_SEARCH_HEIGHT = 10;

    /**
     * Minimum number of eligible tree roots a candidate position must see before it
     * is accepted as a viable placement site. Positions scoring below this threshold
     * are skipped entirely, so we never place a table in the middle of a courtyard.
     */
    private static final int MIN_TREES_NEAR_CANDIDATE = 1;

    /**
     * The top-scoring fraction of candidates we sample from.
     * 0.25 = pick randomly from the best 25 % by tree count, so placement still has
     * some variety but always biases toward tree-rich directions.
     */
    private static final double CANDIDATE_SCORE_TOP_FRACTION = 0.25;

    /** Ratio: one lumberjack per N professionals. */
    private static final int RATIO_PROFESSIONALS = 3;

    /** Ratio: one lumberjack per N total villagers (fallback lower bound). */
    private static final int RATIO_TOTAL = 6;

    private VillageLumberjackSpawnManager() {
    }

    /**
     * Main tick entry point. Called from {@code GuardVillagers} inside
     * {@code ServerTickEvents.END_SERVER_TICK}.
     */
    public static void tick(ServerWorld world) {
        if (world.getTime() % SCAN_INTERVAL_TICKS != 3L) {
            // Offset by 3 ticks from other services to avoid tick-spike clustering.
            return;
        }

        Set<BlockPos> bellPositions = BellChestMappingState.get(world.getServer()).getBellPositions(world);
        if (bellPositions.isEmpty()) {
            return;
        }

        for (BlockPos bellPos : bellPositions) {
            processBell(world, bellPos);
        }
    }

    // -------------------------------------------------------------------------
    // Per-bell logic
    // -------------------------------------------------------------------------

    private static void processBell(ServerWorld world, BlockPos bellPos) {
        Box bellBox = new Box(bellPos).expand(BELL_EFFECT_RANGE);

        List<VillagerEntity> allVillagers = world.getEntitiesByClass(
                VillagerEntity.class, bellBox, VillageLumberjackSpawnManager::isEligibleVillager);

        int totalVillagers = allVillagers.size();
        int professionals = (int) allVillagers.stream()
                .filter(VillageLumberjackSpawnManager::isProfessional)
                .count();

        int desired = desiredLumberjackCount(professionals, totalVillagers);

        // Count existing lumberjacks near this bell.
        int existing = world.getEntitiesByClass(
                LumberjackGuardEntity.class, bellBox, LumberjackGuardEntity::isAlive).size();

        if (existing >= desired) {
            return;
        }

        int deficit = desired - existing;
        LOGGER.debug("lumberjack-spawn-manager bell={} professionals={} totalVillagers={} desired={} existing={} deficit={}",
                bellPos.toShortString(), professionals, totalVillagers, desired, existing, deficit);

        // Place at most ONE crafting table per bell per scan cycle, regardless of deficit.
        // Placing multiple tables per cycle causes runaway table spawning: the conversion
        // hook needs time to pick up each table and spawn a lumberjack before the next
        // scan fires — if we race ahead of it, we flood the village with tables.
        boolean anyPlaced = tryPlaceCraftingTable(world, bellPos);
        if (!anyPlaced) {
            LOGGER.debug("lumberjack-spawn-manager bell={} could not find valid crafting table placement (deficit={})",
                    bellPos.toShortString(), deficit);
        }

        // Nudge the conversion hook once after all placements, but only if at least one
        // player is nearby — this avoids the expensive getWorldBounds() entity scan.
        // The hook also runs on its normal schedule so no conversion is lost.
        if (anyPlaced && !world.getPlayers().isEmpty()) {
            UnemployedLumberjackConversionHook.tryConvertUnemployedVillagersNearCraftingTables(world);
        }
    }

    // -------------------------------------------------------------------------
    // Crafting table placement
    // -------------------------------------------------------------------------

    private static boolean tryPlaceCraftingTable(ServerWorld world, BlockPos bellPos) {
        // Collect existing crafting tables near bell to enforce spacing.
        List<BlockPos> existingTables = findExistingCraftingTablesNear(world, bellPos, TABLE_MAX_DIST + TABLE_MIN_SPACING);

        // Build geometric candidates (ring, solid ground, air surface, spacing).
        List<BlockPos> candidates = buildRingCandidates(world, bellPos, existingTables);
        if (candidates.isEmpty()) {
            LOGGER.debug("lumberjack-spawn-manager bell={} no geometric candidates in placement ring",
                    bellPos.toShortString());
            return false;
        }

        // Score each candidate by number of eligible tree roots visible within TREE_SEARCH_RADIUS.
        // This biases placement toward the forest edge rather than the village courtyard.
        List<ScoredCandidate> scored = new ArrayList<>(candidates.size());
        for (BlockPos candidate : candidates) {
            int treeCount = countEligibleTreeRootsNear(world, candidate);
            if (treeCount >= MIN_TREES_NEAR_CANDIDATE) {
                scored.add(new ScoredCandidate(candidate, treeCount));
            }
        }

        if (scored.isEmpty()) {
            LOGGER.debug("lumberjack-spawn-manager bell={} no candidates with ≥{} trees nearby (checked {} candidates)",
                    bellPos.toShortString(), MIN_TREES_NEAR_CANDIDATE, candidates.size());
            return false;
        }

        // Sort descending by score, then sample from the top fraction for variety.
        scored.sort(Comparator.comparingInt(ScoredCandidate::treeCount).reversed());
        int topN = Math.max(1, (int) Math.ceil(scored.size() * CANDIDATE_SCORE_TOP_FRACTION));
        List<ScoredCandidate> topCandidates = scored.subList(0, Math.min(topN, scored.size()));

        ScoredCandidate chosen = topCandidates.get(world.getRandom().nextInt(topCandidates.size()));

        world.setBlockState(chosen.pos(), Blocks.CRAFTING_TABLE.getDefaultState());
        LOGGER.info("lumberjack-spawn-manager placed crafting table at {} near bell {} (treeScore={}, topN={}/{})",
                chosen.pos().toShortString(), bellPos.toShortString(),
                chosen.treeCount(), topN, scored.size());

        return true;
    }

    private record ScoredCandidate(BlockPos pos, int treeCount) {
    }

    /**
     * Builds a shuffled list of surface positions in the horizontal ring
     * [TABLE_MIN_DIST, TABLE_MAX_DIST] around {@code bellPos}, excluding positions
     * too close to existing crafting tables or non-solid ground.
     */
    private static List<BlockPos> buildRingCandidates(
            ServerWorld world, BlockPos bellPos, List<BlockPos> existingTables) {

        List<BlockPos> candidates = new ArrayList<>();

        for (int dx = -TABLE_MAX_DIST; dx <= TABLE_MAX_DIST; dx++) {
            for (int dz = -TABLE_MAX_DIST; dz <= TABLE_MAX_DIST; dz++) {
                double horiz = Math.sqrt((double) dx * dx + (double) dz * dz);
                if (horiz < TABLE_MIN_DIST || horiz > TABLE_MAX_DIST) {
                    continue;
                }

                int worldX = bellPos.getX() + dx;
                int worldZ = bellPos.getZ() + dz;

                // Surface detection via MOTION_BLOCKING_NO_LEAVES heightmap.
                int surfaceY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
                BlockPos candidate = new BlockPos(worldX, surfaceY, worldZ);

                // Must be on solid ground (block below must be solid).
                BlockPos below = candidate.down();
                if (!world.getBlockState(below).isSolidBlock(world, below)) {
                    continue;
                }

                // Space above must be air (where we'll place the table).
                if (!world.getBlockState(candidate).isAir()) {
                    continue;
                }

                // Minimum spacing from existing crafting tables.
                if (isTooCloseToExistingTable(candidate, existingTables)) {
                    continue;
                }

                candidates.add(candidate);
            }
        }

        // Shuffle so we don't always pick the same direction.
        java.util.Collections.shuffle(candidates, new java.util.Random(world.getTime()));
        return candidates;
    }

    private static boolean isTooCloseToExistingTable(BlockPos candidate, List<BlockPos> existingTables) {
        for (BlockPos table : existingTables) {
            if (candidate.isWithinDistance(table, TABLE_MIN_SPACING)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Counts eligible tree roots within {@value TREE_SEARCH_RADIUS} blocks of {@code center},
     * using the same eligibility criteria as {@link LumberjackGuardChopTreesGoal}.
     * A position with a high count is a good placement site for a lumberjack crafting table.
     */
    private static int countEligibleTreeRootsNear(ServerWorld world, BlockPos center) {
        // Collect bells once for the whole scan — avoids a 13×9×13 block scan per log candidate.
        int bellScanRadius = TREE_SEARCH_RADIUS + 6; // 6 = BELL_EXCLUSION_RADIUS
        Set<BlockPos> nearbyBells = new HashSet<>();
        for (BlockPos cursor : BlockPos.iterate(
                center.add(-bellScanRadius, -8, -bellScanRadius),
                center.add(bellScanRadius, 8, bellScanRadius))) {
            if (world.getBlockState(cursor).isOf(Blocks.BELL)) {
                nearbyBells.add(cursor.toImmutable());
            }
        }

        Set<BlockPos> uniqueRoots = new HashSet<>();
        BlockPos min = center.add(-TREE_SEARCH_RADIUS, -TREE_SEARCH_HEIGHT, -TREE_SEARCH_RADIUS);
        BlockPos max = center.add(TREE_SEARCH_RADIUS, TREE_SEARCH_HEIGHT, TREE_SEARCH_RADIUS);

        for (BlockPos cursor : BlockPos.iterate(min, max)) {
            BlockPos pos = cursor.toImmutable();
            if (!center.isWithinDistance(pos, TREE_SEARCH_RADIUS)) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (!state.isIn(BlockTags.LOGS)) {
                continue;
            }
            BlockPos root = normalizeRoot(world, pos);
            if (isEligibleTreeRoot(world, root, nearbyBells)) {
                uniqueRoots.add(root);
            }
        }

        return uniqueRoots.size();
    }

    /** Walk down to the base of a log column (same as the chop goal's normalizeRoot). */
    private static BlockPos normalizeRoot(ServerWorld world, BlockPos pos) {
        BlockPos.Mutable mutable = pos.mutableCopy();
        while (mutable.getY() > world.getBottomY() && world.getBlockState(mutable.down()).isIn(BlockTags.LOGS)) {
            mutable.move(0, -1, 0);
        }
        return mutable.toImmutable();
    }

    /**
     * Mirrors the eligibility check from {@link LumberjackGuardChopTreesGoal}:
     * natural ground, no bell nearby (checked against pre-collected {@code cachedBells}),
     * min structure (4+ logs + canopy).
     */
    private static boolean isEligibleTreeRoot(ServerWorld world, BlockPos pos, Set<BlockPos> cachedBells) {
        BlockState state = world.getBlockState(pos);
        if (!state.isIn(BlockTags.LOGS)) {
            return false;
        }
        BlockState below = world.getBlockState(pos.down());
        if (below.isIn(BlockTags.LOGS)) {
            return false;
        }
        if (!isNaturalGround(below)) {
            return false;
        }
        // Stilted log-cabin guard
        if (world.getBlockState(pos.down(2)).isIn(BlockTags.LOGS)) {
            return false;
        }
        // Bell-proximity guard — use cached set for O(bells) instead of O(volume) per root
        for (BlockPos bell : cachedBells) {
            int dx = Math.abs(bell.getX() - pos.getX());
            int dy = Math.abs(bell.getY() - pos.getY());
            int dz = Math.abs(bell.getZ() - pos.getZ());
            if (dx <= 6 && dy <= 4 && dz <= 6) {
                return false;
            }
        }
        // Minimum tree structure: ≥4 logs + natural leaves or log above
        return hasMinimumTreeStructure(world, pos);
    }

    private static boolean isNaturalGround(BlockState state) {
        return state.isOf(Blocks.DIRT)
                || state.isOf(Blocks.GRASS_BLOCK)
                || state.isOf(Blocks.PODZOL)
                || state.isOf(Blocks.COARSE_DIRT)
                || state.isOf(Blocks.ROOTED_DIRT)
                || state.isOf(Blocks.MOSS_BLOCK)
                || state.isOf(Blocks.MYCELIUM);
    }

    private static boolean hasMinimumTreeStructure(ServerWorld world, BlockPos root) {
        // Require crown-attached natural leaves — not just any leaves within flat radius.
        // This prevents house log pillars from qualifying: a pillar next to a real tree
        // no longer passes because the neighbouring tree's canopy is not attached to
        // the top of the pillar's own column.
        if (!hasCrownAttachedNaturalLeaves(world, root)) {
            return false;
        }

        Set<BlockPos> visited = new HashSet<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        int minY = root.getY();
        int maxY = root.getY() + 12; // ROOT_STRUCTURE_MAX_HEIGHT

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            if (visited.size() >= 4) { // MIN_ROOT_STRUCTURE_LOGS
                return true;
            }
            for (BlockPos adj : List.of(current.up(), current.down(), current.north(), current.south(), current.east(), current.west())) {
                if (adj.getY() < minY || adj.getY() > maxY) {
                    continue;
                }
                if (Math.abs(adj.getX() - root.getX()) > 2 || Math.abs(adj.getZ() - root.getZ()) > 2) {
                    continue;
                }
                if (!visited.contains(adj) && world.getBlockState(adj).isIn(BlockTags.LOGS)) {
                    queue.add(adj.toImmutable());
                }
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if natural (non-persistent) leaves are directly attached to the
     * crown of the log column rooted at {@code root}.
     *
     * <p>Walks up to the topmost log block of this column, then checks a tight neighbourhood
     * around that crown block. This means a house pillar next to a real tree does NOT qualify
     * — the neighbouring canopy is not attached to this column's own top.
     */
    private static boolean hasCrownAttachedNaturalLeaves(ServerWorld world, BlockPos root) {
        // Walk to the top of this log column (max 12 blocks up = ROOT_STRUCTURE_MAX_HEIGHT).
        BlockPos crown = root;
        for (int i = 0; i < 12; i++) {
            BlockPos above = crown.up();
            if (!world.getBlockState(above).isIn(BlockTags.LOGS)) {
                break;
            }
            crown = above;
        }

        // Check a tight box around the crown for non-persistent leaves.
        BlockPos min = crown.add(-3, -1, -3);
        BlockPos max = crown.add(3, 4, 3);
        for (BlockPos cursor : BlockPos.iterate(min, max)) {
            BlockState state = world.getBlockState(cursor);
            if (state.getBlock() instanceof LeavesBlock && !state.get(LeavesBlock.PERSISTENT)) {
                return true;
            }
        }
        return false;
    }

    private static List<BlockPos> findExistingCraftingTablesNear(ServerWorld world, BlockPos bellPos, int radius) {
        List<BlockPos> tables = new ArrayList<>();
        int y = bellPos.getY();
        for (BlockPos pos : BlockPos.iterate(
                bellPos.add(-radius, -8, -radius),
                bellPos.add(radius, 8, radius))) {
            if (pos.getSquaredDistanceFromCenter(bellPos.getX(), y, bellPos.getZ()) <= (double) radius * radius
                    && world.getBlockState(pos).isOf(Blocks.CRAFTING_TABLE)) {
                tables.add(pos.toImmutable());
            }
        }
        return tables;
    }

    // -------------------------------------------------------------------------
    // Population helpers
    // -------------------------------------------------------------------------

    /**
     * Desired lumberjack count:
     * {@code max(ceil(professionals / RATIO_PROFESSIONALS), ceil(totalVillagers / RATIO_TOTAL))}
     * with a minimum of 0 (never negative).
     */
    static int desiredLumberjackCount(int professionals, int totalVillagers) {
        int fromProfessionals = professionals > 0 ? (professionals + RATIO_PROFESSIONALS - 1) / RATIO_PROFESSIONALS : 0;
        int fromTotal = totalVillagers > 0 ? (totalVillagers + RATIO_TOTAL - 1) / RATIO_TOTAL : 0;
        return Math.max(fromProfessionals, fromTotal);
    }

    private static boolean isEligibleVillager(VillagerEntity villager) {
        if (!villager.isAlive() || villager.isRemoved() || villager.isBaby()) {
            return false;
        }
        return villager.getVillagerData().getProfession() != VillagerProfession.NITWIT;
    }

    private static boolean isProfessional(VillagerEntity villager) {
        VillagerProfession profession = villager.getVillagerData().getProfession();
        return profession != VillagerProfession.NONE && profession != VillagerProfession.NITWIT;
    }
}
