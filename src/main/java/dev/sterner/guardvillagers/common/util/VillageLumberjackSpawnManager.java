package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.villager.LumberjackPopulationBalancingService;
import dev.sterner.guardvillagers.common.villager.UnemployedLumberjackConversionHook;
import net.minecraft.block.Blocks;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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

    /** How often (in ticks) this manager runs the bell scan. 600 = every 30 s. */
    private static final long SCAN_INTERVAL_TICKS = 600L;

    /** Bell effect radius — mirrors VillageGuardStandManager.BELL_EFFECT_RANGE. */
    private static final int BELL_EFFECT_RANGE = VillageGuardStandManager.BELL_EFFECT_RANGE;

    /** Minimum horizontal distance from bell for a new crafting table. */
    private static final int TABLE_MIN_DIST = 10;

    /** Maximum horizontal distance from bell for a new crafting table. */
    private static final int TABLE_MAX_DIST = 20;

    /** Minimum distance (blocks) between any two crafting tables in the placement area. */
    private static final int TABLE_MIN_SPACING = 5;

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

        // Attempt to place one crafting table per missing lumberjack (cap at deficit).
        for (int i = 0; i < deficit; i++) {
            boolean placed = tryPlaceCraftingTable(world, bellPos);
            if (!placed) {
                LOGGER.debug("lumberjack-spawn-manager bell={} could not find valid crafting table placement (attempt {})",
                        bellPos.toShortString(), i + 1);
                break; // If one attempt fails, the rest will too in this tick.
            }
        }
    }

    // -------------------------------------------------------------------------
    // Crafting table placement
    // -------------------------------------------------------------------------

    private static boolean tryPlaceCraftingTable(ServerWorld world, BlockPos bellPos) {
        // Collect existing crafting tables near bell to enforce spacing.
        List<BlockPos> existingTables = findExistingCraftingTablesNear(world, bellPos, TABLE_MAX_DIST + TABLE_MIN_SPACING);

        // Candidate positions: horizontal ring 10–20 blocks from bell.
        List<BlockPos> candidates = buildRingCandidates(world, bellPos, existingTables);
        if (candidates.isEmpty()) {
            return false;
        }

        // Pick a random candidate (use world RNG for determinism with seed).
        BlockPos chosen = candidates.get(world.getRandom().nextInt(candidates.size()));

        world.setBlockState(chosen, Blocks.CRAFTING_TABLE.getDefaultState());
        LOGGER.info("lumberjack-spawn-manager placed crafting table at {} near bell {}",
                chosen.toShortString(), bellPos.toShortString());

        // Nudge the conversion hook immediately (it will find this table on its next scan).
        // The hook is also scheduled to run via runConversionHooksOnSchedule, but kicking it
        // here gets faster turnaround on first placement.
        UnemployedLumberjackConversionHook.tryConvertUnemployedVillagersNearCraftingTables(world);

        return true;
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
