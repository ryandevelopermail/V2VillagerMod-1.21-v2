package dev.sterner.guardvillagers.common.util;

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
    private static final int BELL_TRACKING_RANGE = 100;

    private VillagerBellTracker() {
    }

    public static void logBellVillagerStats(ServerWorld world, BlockPos bellPos) {
        Box searchBox = new Box(bellPos).expand(BELL_TRACKING_RANGE);
        var villagers = world.getEntitiesByClass(VillagerEntity.class, searchBox, Entity::isAlive);

        int villagersWithBeds = 0;
        int villagersWithoutBeds = 0;
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
            } else {
                villagersWithoutBeds++;
            }

            if (hasJob) {
                villagersWithJobs++;
            } else {
                villagersWithoutJobs++;
            }

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
        }

        int ironGolems = world.getEntitiesByClass(IronGolemEntity.class, searchBox, Entity::isAlive).size();
        int guardVillagers = world.getEntitiesByClass(GuardEntity.class, searchBox, Entity::isAlive).size();

        LOGGER.info("Bell at [{}] triggered villager summary ({} block radius)", bellPos.toShortString(), BELL_TRACKING_RANGE);
        LOGGER.info("Villagers with beds: {}, without beds: {}", villagersWithBeds, villagersWithoutBeds);
        LOGGER.info("Villagers with job blocks: {}, without job blocks: {}", villagersWithJobs, villagersWithoutJobs);
        LOGGER.info("Iron golems: {}, Guard villagers: {}", ironGolems, guardVillagers);

        logByProfession("Profession counts", professionCounts);
        logByProfession("Profession with paired chests", professionWithChests);
        logByProfession("Profession with paired crafting tables", professionWithCraftingTables);
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

    private static void logByProfession(String header, Map<VillagerProfession, Integer> counts) {
        LOGGER.info("{}:", header);
        counts.entrySet().stream()
                .sorted(Comparator.comparing(entry -> Registries.VILLAGER_PROFESSION.getId(entry.getKey()).toString()))
                .forEach(entry -> {
                    Identifier professionId = Registries.VILLAGER_PROFESSION.getId(entry.getKey());
                    LOGGER.info("- {}: {}", professionId, entry.getValue());
                });
    }

    private static void incrementCount(Map<VillagerProfession, Integer> map, VillagerProfession profession) {
        map.merge(profession, 1, Integer::sum);
    }
}
