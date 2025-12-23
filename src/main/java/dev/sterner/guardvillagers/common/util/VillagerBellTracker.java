package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.common.util.VillageGuardStandManager.GuardStandAssignment;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager.GuardStandPairingReport;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.ai.brain.BlockPosLookTarget;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.particle.ParticleTypes;
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
    private static final long JOB_REPORT_DURATION_TICKS = 20L * 30L;
    private static final float JOB_REPORT_SPEED = 0.7F;
    private static final int JOB_REPORT_COMPLETION_RANGE = 1;
    private static final int JOB_HIGHLIGHT_PARTICLE_COUNT = 12;

    private VillagerBellTracker() {
    }

    public static void logBellVillagerStats(ServerWorld world, BlockPos bellPos) {
        VillageGuardStandManager.refreshBellInventory(world, bellPos);
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

        LOGGER.info("Bell at [{}] triggered villager summary ({} block radius)", bellPos.toShortString(), BELL_TRACKING_RANGE);
        LOGGER.info("Golems: {}", ironGolems);
        LOGGER.info("Total villagers: {}", totalVillagers);
        LOGGER.info("     Guards: {}", guardVillagers);
        LOGGER.info("     Armor Stands: {}", armorStands);
        LOGGER.info("     Employed: {}", totalEmployed);
        LOGGER.info("     Unemployed: {}", villagersWithoutJobs);
        LOGGER.info("Beds: {}", villagersWithBeds);
        LOGGER.info("Workstations: {}", villagersWithJobs);
        logByProfession(professionCounts);
        LOGGER.info("Paired Chests: {}", totalPairedChests);
        LOGGER.info("Paired Crafting Tables: {}", totalPairedCraftingTables);

        GuardStandPairingReport pairingReport = VillageGuardStandManager.pairGuardsWithStands(world, bellPos);
        logPairings(pairingReport);
        resetGuardsToWander(world, bellPos);
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
                forceVillagerToReport(villager, jobPos);
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

    private static void forceVillagerToReport(VillagerEntity villager, BlockPos jobPos) {
        var brain = villager.getBrain();
        brain.forget(MemoryModuleType.HURT_BY);
        brain.forget(MemoryModuleType.HURT_BY_ENTITY);
        brain.forget(MemoryModuleType.NEAREST_HOSTILE);
        brain.forget(MemoryModuleType.AVOID_TARGET);
        brain.forget(MemoryModuleType.ATTACK_TARGET);
        brain.remember(MemoryModuleType.LOOK_TARGET, new BlockPosLookTarget(jobPos), JOB_REPORT_DURATION_TICKS);
        brain.remember(MemoryModuleType.WALK_TARGET, new WalkTarget(jobPos, JOB_REPORT_SPEED, JOB_REPORT_COMPLETION_RANGE), JOB_REPORT_DURATION_TICKS);
        villager.getNavigation().startMovingTo(jobPos.getX() + 0.5D, jobPos.getY(), jobPos.getZ() + 0.5D, JOB_REPORT_SPEED);
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
