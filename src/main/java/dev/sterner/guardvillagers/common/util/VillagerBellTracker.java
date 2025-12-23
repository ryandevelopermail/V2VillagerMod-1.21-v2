package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.common.util.VillageGuardStandManager.GuardStandAssignment;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager.GuardStandPairingReport;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.VillagerProfession;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

public final class VillagerBellTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger(VillagerBellTracker.class);
    private static final int BELL_TRACKING_RANGE = VillageGuardStandManager.BELL_EFFECT_RANGE;

    private VillagerBellTracker() {
    }

    public static void logBellVillagerStats(ServerWorld world, BlockPos bellPos) {
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
            boolean hasJob = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE).isPresent();

            if (hasBed) {
                villagersWithBeds++;
            }

            if (hasJob) {
                villagersWithJobs++;
                VillagerProfession profession = villager.getVillagerData().getProfession();
                incrementCount(professionCounts, profession);

                Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
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

        LOGGER.info("Bell at [{}] triggered villager summary ({} block radius)", bellPos.toShortString(), BELL_TRACKING_RANGE);
        LOGGER.info("Golems: {}", ironGolems);
        LOGGER.info("Total villagers: {}", totalVillagers);
        LOGGER.info("     Guards: {}", guardVillagers);
        LOGGER.info("     Armor Stands: {}", armorStands);
        LOGGER.info("     Employed: {}", villagersWithJobs);
        LOGGER.info("     Unemployed: {}", villagersWithoutJobs);
        LOGGER.info("Beds: {}", villagersWithBeds);
        LOGGER.info("Workstations: {}", villagersWithJobs);
        logByProfession(professionCounts);
        LOGGER.info("Paired Chests: {}", totalPairedChests);
        LOGGER.info("Paired Crafting Tables: {}", totalPairedCraftingTables);

        GuardStandPairingReport pairingReport = VillageGuardStandManager.pairGuardsWithStands(world, bellPos);
        logPairings(pairingReport);
    }

    public static void directVillagersToJobsOrBell(ServerWorld world, BlockPos bellPos) {
        Box searchBox = new Box(bellPos).expand(BELL_TRACKING_RANGE);
        var villagers = world.getEntitiesByClass(VillagerEntity.class, searchBox, Entity::isAlive);

        for (VillagerEntity villager : villagers) {
            Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
            BlockPos targetPos = jobSite
                    .filter(globalPos -> Objects.equals(globalPos.dimension(), world.getRegistryKey()))
                    .map(GlobalPos::pos)
                    .orElse(bellPos);

            villager.getNavigation().startMovingTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 0.7D);
        }
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

    private static void logByProfession(Map<VillagerProfession, Integer> counts) {
        LOGGER.info("     Workstations by profession:");
        counts.entrySet().stream()
                .sorted(Comparator.comparing(entry -> Registries.VILLAGER_PROFESSION.getId(entry.getKey()).toString()))
                .forEach(entry -> LOGGER.info("     {} - {}", Registries.VILLAGER_PROFESSION.getId(entry.getKey()), entry.getValue()));
    }

    private static void incrementCount(Map<VillagerProfession, Integer> map, VillagerProfession profession) {
        map.merge(profession, 1, Integer::sum);
    }

    private static void logPairings(GuardStandPairingReport pairingReport) {
        if (pairingReport.assignments().isEmpty()) {
            LOGGER.info("No guard to armor stand pairings were created.");
            return;
        }

        LOGGER.info("Guard and armor stand pairings:");
        for (GuardStandAssignment assignment : pairingReport.assignments()) {
            LOGGER.info("Guard: {}", assignment.guardPos().toShortString());
            LOGGER.info("Stand: {}", assignment.standPos().toShortString());
        }

        if (!pairingReport.demotedGuards().isEmpty()) {
            LOGGER.info("Demoted excess guards: {}", pairingReport.demotedGuards().size());
            pairingReport.demotedGuards()
                    .forEach(pos -> LOGGER.info("- {}", pos.toShortString()));
        }
    }
}
