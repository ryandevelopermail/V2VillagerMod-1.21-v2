package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager.GuardStandAssignment;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager.GuardStandPairingReport;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
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
    private static final int REPORT_CHEST_SEARCH_RADIUS = 2;
    private static final String REPORT_BOOK_AUTHOR = "Village Bell Tracker";
    private static final String REPORT_BOOK_TITLE_PREFIX = "Bell Report ";
    private static final Map<UUID, ReportAssignment> REPORTING_VILLAGERS = new HashMap<>();
    private static final Map<GlobalPos, Long> LAST_BOOK_WRITE_TICK = new HashMap<>();

    private VillagerBellTracker() {
    }

    public static void logBellVillagerStats(ServerWorld world, BlockPos bellPos) {
        VillageGuardStandManager.refreshBellInventory(world, bellPos);
        BellReportSummary summary = collectBellReportSummary(world, bellPos);
        GuardStandPairingReport pairingReport = VillageGuardStandManager.pairGuardsWithStands(world, bellPos);
        List<String> reportLines = formatBellReportSections(bellPos, summary, pairingReport);

        for (String line : reportLines) {
            LOGGER.info("{}", line);
        }

        resetGuardsToWander(world, bellPos);
    }

    public static List<String> buildBellReportBookPages(ServerWorld world, BlockPos bellPos) {
        BellReportSummary summary = collectBellReportSummary(world, bellPos);
        GuardStandPairingReport pairingReport = VillageGuardStandManager.pairGuardsWithStands(world, bellPos);
        List<String> reportLines = formatBellReportSections(bellPos, summary, pairingReport);
        return splitLinesIntoWrittenBookPages(reportLines);
    }

    public static void writeBellReportBooks(ServerWorld world, BlockPos bellPos) {
        GlobalPos reportKey = GlobalPos.create(world.getRegistryKey(), bellPos.toImmutable());
        long gameTime = world.getTime();
        Long lastWriteTick = LAST_BOOK_WRITE_TICK.get(reportKey);
        if (lastWriteTick != null && gameTime - lastWriteTick < 20L) {
            return;
        }

        LAST_BOOK_WRITE_TICK.put(reportKey, gameTime);
        List<String> pages = buildBellReportBookPages(world, bellPos);
        Optional<Inventory> chestInventory = findBellReportChestInventory(world, bellPos);
        if (chestInventory.isEmpty()) {
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
                .map(page -> RawFilteredPair.of(Text.literal(page)))
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
        Optional<BlockPos> nearestChestPos = BlockPos.streamOutwards(bellPos, REPORT_CHEST_SEARCH_RADIUS, 1, REPORT_CHEST_SEARCH_RADIUS)
                .filter(pos -> world.getBlockState(pos).isOf(Blocks.CHEST))
                .min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(bellPos)));

        if (nearestChestPos.isEmpty()) {
            return Optional.empty();
        }

        BlockPos chestPos = nearestChestPos.get();
        BlockState chestState = world.getBlockState(chestPos);
        if (!(chestState.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }

        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, chestState, world, chestPos, true));
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

    private record BellVillageReport(List<String> orderedLines) {
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

    private record BellReportSummary(
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
