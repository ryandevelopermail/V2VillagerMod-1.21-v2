package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager.GuardStandAssignment;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager.GuardStandPairingReport;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.BellBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.Entity;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.ai.brain.BlockPosLookTarget;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

public final class VillagerBellTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger(VillagerBellTracker.class);
    private static final int BELL_TRACKING_RANGE = VillageGuardStandManager.BELL_EFFECT_RANGE;
    private static final long JOB_REPORT_DURATION_TICKS = 20L * 30L;
    private static final float JOB_REPORT_SPEED = 0.7F;
    private static final int JOB_REPORT_COMPLETION_RANGE = 1;
    private static final int JOB_HIGHLIGHT_PARTICLE_COUNT = 12;
    private static final double JOB_REPORT_HOLD_DISTANCE_SQUARED = 2.25D;
    private static final int WRITTEN_BOOK_MAX_PAGE_LENGTH = 255;
    private static final int WRITTEN_BOOK_MAX_PAGE_COUNT = 100;
    private static final int REPORT_CHEST_PRIMARY_SEARCH_RADIUS = 2;
    private static final String REPORT_BOOK_AUTHOR = "Village Bell Tracker";
    private static final String REPORT_BOOK_TITLE_PREFIX = "Bell Report ";
    private static final Map<UUID, ReportAssignment> REPORTING_VILLAGERS = new HashMap<>();
    private static final Map<GlobalPos, Long> LAST_BOOK_WRITE_TICK = new HashMap<>();

    private VillagerBellTracker() {
    }

    public static void handleBellRung(ServerWorld world, BlockPos bellPos) {
        BellVillageReport report = snapshotBellVillageReport(world, bellPos);
        logBellVillagerStats(world, bellPos, report);
        writeBellReportBooks(world, bellPos, report);
        directEmployedVillagersAndGuardsToStations(world, bellPos);
    }

    public static BellVillageReport snapshotBellVillageReport(ServerWorld world, BlockPos bellPos) {
        BellReportSummary summary = collectBellReportSummary(world, bellPos);
        GuardStandPairingReport pairingReport = VillageGuardStandManager.pairGuardsWithStands(world, bellPos);
        List<String> orderedLines = formatBellReportSections(bellPos, summary, pairingReport);

        return new BellVillageReport(summary, pairingReport, orderedLines);
    }

    public static BellVillageReport logBellVillagerStats(ServerWorld world, BlockPos bellPos) {
        BellVillageReport report = snapshotBellVillageReport(world, bellPos);
        logBellVillagerStats(world, bellPos, report);
        return report;
    }

    public static List<String> logBellVillagerStats(ServerWorld world, BlockPos bellPos, BellVillageReport report) {
        VillageGuardStandManager.refreshBellInventory(world, bellPos);

        for (String line : report.orderedLines()) {
            LOGGER.info("{}", line);
        }

        resetGuardsToWander(world, bellPos);
        return report.orderedLines();
    }

    public static void writeBellReportBooks(ServerWorld world, BlockPos bellPos, BellVillageReport report) {
        GlobalPos reportKey = GlobalPos.create(world.getRegistryKey(), bellPos.toImmutable());
        long gameTime = world.getTime();
        Long lastWriteTick = LAST_BOOK_WRITE_TICK.get(reportKey);
        if (lastWriteTick != null && gameTime - lastWriteTick < 20L) {
            return;
        }

        LAST_BOOK_WRITE_TICK.put(reportKey, gameTime);
        List<String> pages = splitLinesIntoWrittenBookPages(report.orderedLines());
        Optional<Inventory> chestInventory = findBellReportChestInventory(world, bellPos);
        if (chestInventory.isEmpty()) {
            logBellReportChestLookupFailure(world, bellPos);
            LOGGER.info("Bell report book generation skipped at {}: no nearby chest found.", bellPos.toShortString());
            return;
        }

        ItemStack reportBook = createBellReportBookItem(pages, bellPos);
        if (!tryInsertIntoInventory(chestInventory.get(), reportBook)) {
            LOGGER.info("Bell report book generation skipped at {}: nearby chest had no free slot.", bellPos.toShortString());
            return;
        }

        LOGGER.info("Placed bell report book at {} with {} pages.", bellPos.toShortString(), pages.size());
    }

    private static ItemStack createBellReportBookItem(List<String> pages, BlockPos bellPos) {
        List<RawFilteredPair<Text>> bookPages = pages.stream()
                .map(page -> RawFilteredPair.<Text>of(Text.literal(page)))
                .toList();

        ItemStack reportBook = new ItemStack(Items.WRITTEN_BOOK);
        reportBook.set(DataComponentTypes.WRITTEN_BOOK_CONTENT, new WrittenBookContentComponent(
                RawFilteredPair.of(REPORT_BOOK_TITLE_PREFIX + "[" + bellPos.toShortString() + "]"),
                REPORT_BOOK_AUTHOR,
                0,
                bookPages,
                true
        ));
        return reportBook;
    }

    private static Optional<Inventory> findBellReportChestInventory(ServerWorld world, BlockPos bellPos) {
        if (LOGGER.isDebugEnabled()) {
            logExpectedGeneratedChestBlockState(world, bellPos);
        }

        LinkedHashSet<BlockPos> candidateChestPositions = new LinkedHashSet<>();
        VillageBellChestPlacementHelper.reconcileBellChestForBell(world, bellPos).ifPresent(candidateChestPositions::add);
        candidateChestPositions.addAll(findNearestBellReportChestCandidates(world, bellPos, REPORT_CHEST_PRIMARY_SEARCH_RADIUS));

        int fallbackRadius = Math.max(0, GuardVillagersConfig.bellReportChestFallbackRadius);
        if (candidateChestPositions.isEmpty() && fallbackRadius > REPORT_CHEST_PRIMARY_SEARCH_RADIUS) {
            LOGGER.info(
                    "Bell report chest lookup at {}: no chest found within primary radius {}; retrying with fallback radius {}.",
                    bellPos.toShortString(),
                    REPORT_CHEST_PRIMARY_SEARCH_RADIUS,
                    fallbackRadius
            );
            candidateChestPositions.addAll(findNearestBellReportChestCandidates(world, bellPos, fallbackRadius));
        }

        if (candidateChestPositions.isEmpty()) {
            return Optional.empty();
        }

        for (BlockPos chestPos : candidateChestPositions) {
            BlockState chestState = world.getBlockState(chestPos);
            if (!(chestState.getBlock() instanceof ChestBlock chestBlock)) {
                continue;
            }

            Inventory inventory = ChestBlock.getInventory(chestBlock, chestState, world, chestPos, true);
            if (inventory != null) {
                return Optional.of(inventory);
            }

            LOGGER.info(
                    "Bell report chest lookup at {}: chest-like block at {} has no accessible inventory (blocked or unavailable).",
                    bellPos.toShortString(),
                    chestPos.toShortString()
            );
        }

        return Optional.empty();
    }

    private static List<BlockPos> findNearestBellReportChestCandidates(ServerWorld world, BlockPos bellPos, int radius) {
        if (LOGGER.isDebugEnabled()) {
            logNearbyNonAirSample(world, bellPos, radius);
        }

        return BlockPos.streamOutwards(bellPos, radius, 1, radius)
                .filter(pos -> world.getBlockState(pos).getBlock() instanceof ChestBlock)
                .sorted(Comparator
                        .comparingDouble((BlockPos pos) -> pos.getSquaredDistance(bellPos))
                        .thenComparingInt(BlockPos::getY)
                        .thenComparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getZ))
                .toList();
    }

    private static void logBellReportChestLookupFailure(ServerWorld world, BlockPos bellPos) {
        int fallbackRadius = Math.max(0, GuardVillagersConfig.bellReportChestFallbackRadius);
        String dimension = world.getRegistryKey().getValue().toString();
        boolean bellChunkLoaded = world.isChunkLoaded(bellPos);
        String neighborChunkLoadStates = describeNeighborChunkLoadStates(world, bellPos);

        LOGGER.info(
                "Bell report chest lookup failed at {} in {}. primaryRadius={}, fallbackRadius={}, bellChunkLoaded={}, neighboringChunksLoaded={}",
                bellPos.toShortString(),
                dimension,
                REPORT_CHEST_PRIMARY_SEARCH_RADIUS,
                fallbackRadius,
                bellChunkLoaded,
                neighborChunkLoadStates
        );
    }

    private static String describeNeighborChunkLoadStates(ServerWorld world, BlockPos bellPos) {
        ChunkPos bellChunk = new ChunkPos(bellPos);
        List<String> states = new ArrayList<>();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }

                int chunkX = bellChunk.x + dx;
                int chunkZ = bellChunk.z + dz;
                states.add(chunkX + "," + chunkZ + "=" + world.isChunkLoaded(chunkX, chunkZ));
            }
        }

        return String.join(";", states);
    }

    private static void logNearbyNonAirSample(ServerWorld world, BlockPos bellPos, int radius) {
        List<BlockPos> nearbyNonAirBlocks = BlockPos.streamOutwards(bellPos, radius, 1, radius)
                .filter(pos -> !world.getBlockState(pos).isAir())
                .sorted(Comparator
                        .comparingDouble((BlockPos pos) -> pos.getSquaredDistance(bellPos))
                        .thenComparingInt(BlockPos::getY)
                        .thenComparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getZ))
                .limit(5)
                .toList();

        BlockPos leftOfBellPos = getExpectedGeneratedChestPos(world, bellPos);
        LOGGER.debug(
                "Bell chest scan sample at {} (radius={}): leftOfBell={}={} | nearestNonAirSample={}.",
                bellPos.toShortString(),
                radius,
                leftOfBellPos.toShortString(),
                describeBlockStateWithId(world.getBlockState(leftOfBellPos)),
                formatBlockSample(world, nearbyNonAirBlocks)
        );
    }

    private static void logExpectedGeneratedChestBlockState(ServerWorld world, BlockPos bellPos) {
        BlockPos expectedChestPos = getExpectedGeneratedChestPos(world, bellPos);
        BlockState expectedState = world.getBlockState(expectedChestPos);
        LOGGER.debug(
                "Expected generated chest location (left-of-bell convention) for bell {} is {} with exact state {}.",
                bellPos.toShortString(),
                expectedChestPos.toShortString(),
                describeBlockStateWithId(expectedState)
        );
    }

    private static BlockPos getExpectedGeneratedChestPos(ServerWorld world, BlockPos bellPos) {
        BlockState bellState = world.getBlockState(bellPos);
        Direction leftDirection = getLeftDirectionFromBellState(bellState);
        return bellPos.offset(leftDirection);
    }

    private static Direction getLeftDirectionFromBellState(BlockState bellState) {
        if (bellState.contains(BellBlock.FACING)) {
            Direction facing = bellState.get(BellBlock.FACING);
            if (facing.getAxis().isHorizontal()) {
                return facing.rotateYCounterclockwise();
            }
        }

        if (bellState.contains(BellBlock.ATTACHMENT)) {
            var attachment = bellState.get(BellBlock.ATTACHMENT);
            return switch (attachment) {
                case FLOOR -> Direction.WEST;
                case CEILING -> Direction.EAST;
                case SINGLE_WALL -> Direction.NORTH;
                case DOUBLE_WALL -> Direction.SOUTH;
            };
        }

        return Direction.NORTH;
    }

    private static String formatBlockSample(ServerWorld world, List<BlockPos> positions) {
        if (positions.isEmpty()) {
            return "[]";
        }

        List<String> entries = new ArrayList<>();
        for (BlockPos pos : positions) {
            entries.add(pos.toShortString() + "=" + describeBlockStateWithId(world.getBlockState(pos)));
        }
        return "[" + String.join(", ", entries) + "]";
    }

    private static String describeBlockStateWithId(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()) + " " + state;
    }

    private static boolean tryInsertIntoInventory(Inventory inventory, ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }

        for (int slot = 0; slot < inventory.size(); slot++) {
            if (!inventory.getStack(slot).isEmpty()) {
                continue;
            }

            inventory.setStack(slot, stack.copy());
            inventory.markDirty();
            return true;
        }

        return false;
    }

    private static BellReportSummary collectBellReportSummary(ServerWorld world, BlockPos bellPos) {
        Box searchBox = new Box(bellPos).expand(BELL_TRACKING_RANGE);
        var villagers = world.getEntitiesByClass(VillagerEntity.class, searchBox, Entity::isAlive);

        int villagersWithBeds = 0;
        int villagersWithJobs = 0;
        int villagersWithoutJobs = 0;

        Map<VillagerProfession, Integer> professionCounts = new HashMap<>();
        Map<VillagerProfession, Integer> professionWithChests = new HashMap<>();
        Map<VillagerProfession, Integer> professionWithCraftingTables = new HashMap<>();

        for (VillagerEntity villager : villagers) {
            boolean hasBed = villager.getBrain().getOptionalMemory(MemoryModuleType.HOME).isPresent();
            boolean isEmployed = isEmployedVillager(villager);
            Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);

            if (hasBed) {
                villagersWithBeds++;
            }

            if (isEmployed) {
                villagersWithJobs++;
                VillagerProfession profession = villager.getVillagerData().getProfession();
                incrementCount(professionCounts, profession);

                if (jobSite.isPresent() && Objects.equals(jobSite.get().dimension(), world.getRegistryKey())) {
                    BlockPos jobPos = jobSite.get().pos();
                    if (hasPairedBlock(world, jobPos, JobBlockPairingHelper::isPairingBlock)) {
                        incrementCount(professionWithChests, profession);
                    }
                    if (hasPairedBlock(world, jobPos, state -> state.isOf(Blocks.CRAFTING_TABLE))) {
                        incrementCount(professionWithCraftingTables, profession);
                    }
                }
            } else {
                villagersWithoutJobs++;
            }
        }

        int ironGolems = world.getEntitiesByClass(IronGolemEntity.class, searchBox, Entity::isAlive).size();
        int guardVillagers = world.getEntitiesByClass(GuardEntity.class, searchBox, Entity::isAlive).size();
        int totalVillagers = villagers.size() + guardVillagers;
        int armorStands = VillageGuardStandManager.getGuardArmorStands(world, bellPos).size();

        int totalPairedChests = professionWithChests.values().stream().mapToInt(Integer::intValue).sum();
        int totalPairedCraftingTables = professionWithCraftingTables.values().stream().mapToInt(Integer::intValue).sum();
        int totalEmployed = villagersWithJobs + guardVillagers;

        return new BellReportSummary(
                ironGolems,
                totalVillagers,
                guardVillagers,
                armorStands,
                totalEmployed,
                villagersWithoutJobs,
                villagersWithBeds,
                villagersWithJobs,
                totalPairedChests,
                totalPairedCraftingTables,
                professionCounts
        );
    }

    // NOTE: Do not duplicate report fields outside this formatter.
    // Keep both logger and written-book output sourced from this single field list.
    private static List<String> formatBellReportSections(BlockPos bellPos, BellReportSummary summary, GuardStandPairingReport pairingReport) {
        List<String> lines = new ArrayList<>();
        lines.add("Bell Report Location: [" + bellPos.toShortString() + "]");
        lines.add("Bell Report Radius: " + BELL_TRACKING_RANGE + " blocks");
        lines.add("Golems: " + summary.ironGolems());
        lines.add("Villagers (Total): " + summary.totalVillagers());
        lines.add("Villagers (Guards): " + summary.guardVillagers());
        lines.add("Armor Stands (Guard): " + summary.armorStands());
        lines.add("Employment (Employed): " + summary.totalEmployed());
        lines.add("Employment (Unemployed): " + summary.villagersWithoutJobs());
        lines.add("Beds Claimed: " + summary.villagersWithBeds());
        lines.add("Workstations Claimed: " + summary.villagersWithJobs());

        lines.add("Profession Counts:");
        summary.professionCounts().entrySet().stream()
                .sorted(Comparator.comparing(entry -> Registries.VILLAGER_PROFESSION.getId(entry.getKey()).toString()))
                .forEach(entry -> lines.add("- " + Registries.VILLAGER_PROFESSION.getId(entry.getKey()) + ": " + entry.getValue()));

        lines.add("Paired Chests: " + summary.totalPairedChests());
        lines.add("Paired Crafting Tables: " + summary.totalPairedCraftingTables());

        lines.add("Guard-Stand Pairings:");
        if (pairingReport.assignments().isEmpty()) {
            lines.add("- None");
        } else {
            for (GuardStandAssignment assignment : pairingReport.assignments()) {
                lines.add("- Guard " + assignment.guardPos().toShortString() + " -> Stand " + assignment.standPos().toShortString());
            }
        }

        lines.add("Guard Demotions: " + pairingReport.demotedGuards().size());
        pairingReport.demotedGuards().forEach(pos -> lines.add("- Demoted Guard Position: " + pos.toShortString()));
        return lines;
    }

    public static void directEmployedVillagersAndGuardsToStations(ServerWorld world, BlockPos bellPos) {
        Box searchBox = new Box(bellPos).expand(BELL_TRACKING_RANGE);
        var villagers = world.getEntitiesByClass(VillagerEntity.class, searchBox, Entity::isAlive);

        for (VillagerEntity villager : villagers) {
            if (!isEmployedVillager(villager)) {
                continue;
            }

            highlightEmployedVillager(world, villager);
            Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
            if (jobSite.isPresent() && Objects.equals(jobSite.get().dimension(), world.getRegistryKey())) {
                BlockPos jobPos = jobSite.get().pos();
                highlightJobSite(world, jobPos);
                startVillagerReport(world, villager, jobPos);
            }
        }

        var guards = world.getEntitiesByClass(GuardEntity.class, searchBox, Entity::isAlive);
        for (GuardEntity guard : guards) {
            Optional<BlockPos> standPos = getGuardReportPosition(world, guard);
            standPos.ifPresent(pos -> highlightGuardStand(world, pos));
            BlockPos targetPos = standPos.orElse(bellPos);
            guard.setTarget(null);
            guard.setHornTarget(targetPos, JOB_REPORT_DURATION_TICKS);
        }
    }

    public static void resetGuardsToWander(ServerWorld world, BlockPos bellPos) {
        Box searchBox = new Box(bellPos).expand(BELL_TRACKING_RANGE);
        var guards = world.getEntitiesByClass(GuardEntity.class, searchBox, Entity::isAlive);
        for (GuardEntity guard : guards) {
            guard.clearHornTarget();
            guard.getNavigation().stop();
        }
    }

    public static void tickVillagerReports(ServerWorld world) {
        if (REPORTING_VILLAGERS.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<UUID, ReportAssignment>> iterator = REPORTING_VILLAGERS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ReportAssignment> entry = iterator.next();
            ReportAssignment assignment = entry.getValue();
            if (!assignment.jobSite.dimension().equals(world.getRegistryKey())) {
                continue;
            }
            if (assignment.endTime <= world.getTime()) {
                iterator.remove();
                continue;
            }

            Entity entity = world.getEntity(entry.getKey());
            if (!(entity instanceof VillagerEntity villager) || !villager.isAlive()) {
                iterator.remove();
                continue;
            }

            BlockPos jobPos = assignment.jobSite.pos();
            forceVillagerToReport(villager, jobPos, assignment.endTime - world.getTime());
        }
    }

    private static void startVillagerReport(ServerWorld world, VillagerEntity villager, BlockPos jobPos) {
        long endTime = world.getTime() + JOB_REPORT_DURATION_TICKS;
        REPORTING_VILLAGERS.put(villager.getUuid(), new ReportAssignment(GlobalPos.create(world.getRegistryKey(), jobPos), endTime));
        forceVillagerToReport(villager, jobPos, JOB_REPORT_DURATION_TICKS);
    }

    private static void forceVillagerToReport(VillagerEntity villager, BlockPos jobPos, long remainingTicks) {
        var brain = villager.getBrain();
        brain.forget(MemoryModuleType.HURT_BY);
        brain.forget(MemoryModuleType.HURT_BY_ENTITY);
        brain.forget(MemoryModuleType.NEAREST_HOSTILE);
        brain.forget(MemoryModuleType.AVOID_TARGET);
        brain.forget(MemoryModuleType.ATTACK_TARGET);
        brain.remember(MemoryModuleType.LOOK_TARGET, new BlockPosLookTarget(jobPos), remainingTicks);
        brain.remember(MemoryModuleType.WALK_TARGET, new WalkTarget(jobPos, JOB_REPORT_SPEED, JOB_REPORT_COMPLETION_RANGE), remainingTicks);

        Vec3d jobCenter = Vec3d.ofCenter(jobPos);
        double distance = villager.squaredDistanceTo(jobCenter);
        if (distance <= JOB_REPORT_HOLD_DISTANCE_SQUARED) {
            villager.getNavigation().stop();
            villager.getMoveControl().strafeTo(0.0F, 0.0F);
            villager.getLookControl().lookAt(jobCenter.getX(), jobCenter.getY(), jobCenter.getZ());
        } else {
            villager.getNavigation().startMovingTo(jobCenter.getX(), jobCenter.getY(), jobCenter.getZ(), JOB_REPORT_SPEED);
        }
    }

    private static Optional<BlockPos> getGuardReportPosition(ServerWorld world, GuardEntity guard) {
        if (guard.getPairedStandUuid() == null) {
            return Optional.empty();
        }

        Entity standEntity = world.getEntity(guard.getPairedStandUuid());
        if (standEntity instanceof ArmorStandEntity armorStand
                && armorStand.isAlive()
                && armorStand.getCommandTags().contains(VillageGuardStandManager.GUARD_STAND_TAG)) {
            return Optional.of(armorStand.getBlockPos());
        }

        return Optional.empty();
    }

    private static void highlightEmployedVillager(ServerWorld world, VillagerEntity villager) {
        villager.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, (int) JOB_REPORT_DURATION_TICKS, 0, false, false, true));
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getBodyY(0.5D), villager.getZ(), JOB_HIGHLIGHT_PARTICLE_COUNT, 0.35D, 0.5D, 0.35D, 0.0D);
    }

    private static void highlightJobSite(ServerWorld world, BlockPos jobPos) {
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, jobPos.getX() + 0.5D, jobPos.getY() + 1.0D, jobPos.getZ() + 0.5D, JOB_HIGHLIGHT_PARTICLE_COUNT, 0.35D, 0.35D, 0.35D, 0.0D);
    }

    private static void highlightGuardStand(ServerWorld world, BlockPos standPos) {
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, standPos.getX() + 0.5D, standPos.getY() + 1.0D, standPos.getZ() + 0.5D, JOB_HIGHLIGHT_PARTICLE_COUNT, 0.35D, 0.35D, 0.35D, 0.0D);
        for (ArmorStandEntity stand : world.getEntitiesByClass(ArmorStandEntity.class, new Box(standPos).expand(0.5D), Entity::isAlive)) {
            if (stand.getBlockPos().equals(standPos) && stand.getCommandTags().contains(VillageGuardStandManager.GUARD_STAND_TAG)) {
                stand.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, (int) JOB_REPORT_DURATION_TICKS, 0, false, false, true));
            }
        }
    }

    private static boolean isEmployedVillager(VillagerEntity villager) {
        VillagerProfession profession = villager.getVillagerData().getProfession();
        return profession != VillagerProfession.NONE && profession != VillagerProfession.NITWIT && !villager.isBaby();
    }

    private record ReportAssignment(GlobalPos jobSite, long endTime) {
    }

    public record BellVillageReport(
            BellReportSummary summary,
            GuardStandPairingReport pairingReport,
            List<String> orderedLines
    ) {
    }

    private static boolean hasPairedBlock(ServerWorld world, BlockPos jobPos, Predicate<BlockState> predicate) {
        int range = (int) Math.ceil(JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE);
        for (BlockPos checkPos : BlockPos.iterate(jobPos.add(-range, -range, -range), jobPos.add(range, range, range))) {
            if (jobPos.isWithinDistance(checkPos, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE) && predicate.test(world.getBlockState(checkPos))) {
                return true;
            }
        }
        return false;
    }

    private static void incrementCount(Map<VillagerProfession, Integer> map, VillagerProfession profession) {
        map.merge(profession, 1, Integer::sum);
    }

    private static List<String> splitLinesIntoWrittenBookPages(List<String> lines) {
        List<String> pages = new ArrayList<>();
        StringBuilder currentPage = new StringBuilder();

        for (String line : lines) {
            for (String segment : splitLineIntoSegments(line)) {
                int separatorLength = currentPage.isEmpty() ? 0 : 1;
                if (currentPage.length() + separatorLength + segment.length() > WRITTEN_BOOK_MAX_PAGE_LENGTH) {
                    if (!currentPage.isEmpty()) {
                        pages.add(currentPage.toString());
                        if (pages.size() >= WRITTEN_BOOK_MAX_PAGE_COUNT) {
                            return pages;
                        }
                        currentPage = new StringBuilder();
                    }
                }

                if (!currentPage.isEmpty()) {
                    currentPage.append('\n');
                }
                currentPage.append(segment);
            }
        }

        if (!currentPage.isEmpty() && pages.size() < WRITTEN_BOOK_MAX_PAGE_COUNT) {
            pages.add(currentPage.toString());
        }

        if (pages.isEmpty()) {
            pages.add("Bell Report: No data available.");
        }

        return pages;
    }

    private static List<String> splitLineIntoSegments(String line) {
        if (line.isEmpty()) {
            return List.of("");
        }

        List<String> segments = new ArrayList<>();
        int start = 0;
        while (start < line.length()) {
            int end = Math.min(start + WRITTEN_BOOK_MAX_PAGE_LENGTH, line.length());
            segments.add(line.substring(start, end));
            start = end;
        }
        return segments;
    }

    public record BellReportSummary(
            int ironGolems,
            int totalVillagers,
            int guardVillagers,
            int armorStands,
            int totalEmployed,
            int villagersWithoutJobs,
            int villagersWithBeds,
            int villagersWithJobs,
            int totalPairedChests,
            int totalPairedCraftingTables,
            Map<VillagerProfession, Integer> professionCounts
    ) {
    }
}
