package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.block.BellBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class VillageGuardStandManager {
    private static final int BELL_SEARCH_RANGE = 300;
    private static final int VILLAGE_ENTITY_RANGE = 300;
    private static final int ARMOR_STAND_SEARCH_RANGE = 6;
    private static final int PLAYER_APPROACH_RANGE = 100;
    private static final int PLAYER_CHECK_INTERVAL_TICKS = 40;
    public static final String GUARD_STAND_TAG = "guardvillagers:auto_armor_stand";

    private static final Map<GlobalPos, Integer> GUARD_COUNTS = new HashMap<>();
    private static final Map<RegistryKey<World>, BlockPos> PRIMARY_BELLS = new HashMap<>();
    private static final Set<GlobalPos> INITIALIZED_BELLS = new HashSet<>();

    private VillageGuardStandManager() {
    }

    public static void handleGuardSpawn(ServerWorld world, GuardEntity guard, @Nullable VillagerEntity sourceVillager) {
        Optional<BlockPos> bellPos = findBell(world, guard, sourceVillager);
        if (bellPos.isEmpty()) {
            return;
        }

        GlobalPos globalBellPos = GlobalPos.create(world.getRegistryKey(), bellPos.get());
        int guardCount = countVillageGuards(world, bellPos.get());
        GUARD_COUNTS.put(globalBellPos, guardCount);

        ensureArmorStands(world, bellPos.get(), guardCount);
        pairGuardsWithArmorStands(world, bellPos.get());
    }

    public static void handlePlayerNearby(ServerWorld world, PlayerEntity player) {
        if (world.getTime() % PLAYER_CHECK_INTERVAL_TICKS != 0) {
            return;
        }

        Box searchBox = player.getBoundingBox().expand(PLAYER_APPROACH_RANGE);
        List<GuardEntity> guards = world.getEntitiesByClass(GuardEntity.class, searchBox, Entity::isAlive);
        for (GuardEntity guard : guards) {
            Optional<BlockPos> bellPos = findBell(world, guard, null);
            if (bellPos.isEmpty()) {
                continue;
            }

            GlobalPos globalBellPos = GlobalPos.create(world.getRegistryKey(), bellPos.get());
            if (INITIALIZED_BELLS.add(globalBellPos)) {
                int guardCount = countVillageGuards(world, bellPos.get());
                GUARD_COUNTS.put(globalBellPos, guardCount);
                ensureArmorStands(world, bellPos.get(), guardCount);
                pairGuardsWithArmorStands(world, bellPos.get());
            }
        }
    }

    private static Optional<BlockPos> findBell(ServerWorld world, GuardEntity guard, @Nullable VillagerEntity sourceVillager) {
        Optional<BlockPos> meetingPoint = getMeetingPoint(sourceVillager);
        if (meetingPoint.isPresent()) {
            return meetingPoint;
        }

        return findNearestBell(world, guard.getBlockPos());
    }

    private static Optional<BlockPos> getMeetingPoint(@Nullable VillagerEntity villager) {
        if (villager == null) {
            return Optional.empty();
        }

        return villager.getBrain().getOptionalMemory(MemoryModuleType.MEETING_POINT)
                .filter(globalPos -> Objects.equals(globalPos.dimension(), villager.getWorld().getRegistryKey()))
                .map(GlobalPos::pos)
                .filter(pos -> villager.getWorld().getBlockState(pos).getBlock() instanceof BellBlock);
    }

    private static Optional<BlockPos> findNearestBell(ServerWorld world, BlockPos center) {
        List<BlockPos> bellsInRange = new ArrayList<>();

        for (BlockPos pos : BlockPos.iterateOutwards(center, BELL_SEARCH_RANGE, BELL_SEARCH_RANGE, BELL_SEARCH_RANGE)) {
            if (!center.isWithinDistance(pos, BELL_SEARCH_RANGE)) {
                continue;
            }

            if (world.getBlockState(pos).getBlock() instanceof BellBlock) {
                bellsInRange.add(pos.toImmutable());
            }
        }

        if (bellsInRange.isEmpty()) {
            return Optional.empty();
        }

        RegistryKey<World> worldKey = world.getRegistryKey();
        BlockPos storedPrimary = PRIMARY_BELLS.get(worldKey);
        if (storedPrimary != null && bellsInRange.contains(storedPrimary)) {
            return Optional.of(storedPrimary);
        }

        BlockPos closest = bellsInRange.stream()
                .min(Comparator
                        .comparingDouble((BlockPos pos) -> pos.getSquaredDistance(Vec3d.ofCenter(center)))
                        .thenComparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getY)
                        .thenComparingInt(BlockPos::getZ))
                .orElse(null);

        if (closest != null) {
            PRIMARY_BELLS.put(worldKey, closest);
        }

        return Optional.ofNullable(closest);
    }

    private static int countVillageGuards(ServerWorld world, BlockPos bellPos) {
        Box searchBox = new Box(bellPos).expand(VILLAGE_ENTITY_RANGE);
        return world.getEntitiesByClass(GuardEntity.class, searchBox, Entity::isAlive).size();
    }

    private static void ensureArmorStands(ServerWorld world, BlockPos bellPos, int guardCount) {
        Box searchBox = new Box(bellPos).expand(ARMOR_STAND_SEARCH_RANGE);
        int existingStands = world.getEntitiesByClass(ArmorStandEntity.class, searchBox,
                stand -> stand.isAlive() && stand.getCommandTags().contains(GUARD_STAND_TAG)).size();

        int standsToSpawn = guardCount - existingStands;
        if (standsToSpawn <= 0) {
            return;
        }

        List<BlockPos> openPositions = findNearestFiveByFive(world, bellPos, standsToSpawn);
        spawnArmorStands(world, openPositions);
    }

    private static void pairGuardsWithArmorStands(ServerWorld world, BlockPos bellPos) {
        Box guardSearchBox = new Box(bellPos).expand(VILLAGE_ENTITY_RANGE);
        List<GuardEntity> guards = world.getEntitiesByClass(GuardEntity.class, guardSearchBox, Entity::isAlive);

        Box standSearchBox = new Box(bellPos).expand(ARMOR_STAND_SEARCH_RANGE);
        List<ArmorStandEntity> armorStands = world.getEntitiesByClass(ArmorStandEntity.class, standSearchBox,
                stand -> stand.isAlive() && stand.getCommandTags().contains(GUARD_STAND_TAG));

        Map<UUID, ArmorStandEntity> armorStandById = armorStands.stream()
                .collect(Collectors.toMap(ArmorStandEntity::getUuid, stand -> stand));
        Set<UUID> claimedStands = new HashSet<>();

        for (GuardEntity guard : guards) {
            Optional<UUID> currentStandId = guard.getArmorStandUuid();
            if (currentStandId.isPresent()) {
                Entity potentialStand = world.getEntity(currentStandId.get());
                ArmorStandEntity currentStand = potentialStand instanceof ArmorStandEntity armorStand
                        ? armorStand
                        : armorStandById.get(currentStandId.get());
                if (currentStand != null && currentStand.isAlive()) {
                    claimedStands.add(currentStand.getUuid());
                    continue;
                }
            }

            Optional<ArmorStandEntity> closestStand = armorStands.stream()
                    .filter(stand -> !claimedStands.contains(stand.getUuid()))
                    .min(Comparator.comparingDouble(stand -> stand.squaredDistanceTo(guard)));

            if (closestStand.isPresent()) {
                ArmorStandEntity stand = closestStand.get();
                claimedStands.add(stand.getUuid());
                stand.addCommandTag(GUARD_STAND_TAG);
                guard.setArmorStandUuid(stand.getUuid());
            } else {
                guard.removeGuardStatusDueToMissingStand();
            }
        }
    }

    private static List<BlockPos> findNearestFiveByFive(ServerWorld world, BlockPos bellPos, int needed) {
        List<BlockPos> spawnPositions = new ArrayList<>();

        List<BlockPos> candidateCenters = getCandidateCenters(world, bellPos);
        for (BlockPos center : candidateCenters) {
            List<BlockPos> areaPositions = collectAreaPositions(world, center, needed - spawnPositions.size());
            spawnPositions.addAll(areaPositions);
            if (spawnPositions.size() >= needed) {
                break;
            }
        }

        return spawnPositions;
    }

    private static List<BlockPos> getCandidateCenters(ServerWorld world, BlockPos bellPos) {
        List<BlockPos> centers = new ArrayList<>();
        int searchRadius = 10;
        double targetDistance = 5.0D;

        for (BlockPos pos : BlockPos.iterateOutwards(bellPos, searchRadius, 0, searchRadius)) {
            double horizontalDistance = Math.sqrt(pos.getSquaredDistance(bellPos));
            if (horizontalDistance < targetDistance) {
                continue;
            }

            centers.add(pos.toImmutable());
        }

        centers.sort(Comparator
                .comparingDouble((BlockPos pos) -> Math.abs(Math.sqrt(pos.getSquaredDistance(bellPos)) - targetDistance))
                .thenComparingDouble(pos -> pos.getSquaredDistance(bellPos)));
        return centers;
    }

    private static List<BlockPos> collectAreaPositions(ServerWorld world, BlockPos center, int needed) {
        List<BlockPos> openBlocks = new ArrayList<>();

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (openBlocks.size() >= needed) {
                    break;
                }

                BlockPos candidateBase = center.add(dx, 0, dz);
                findStandPosition(world, candidateBase).ifPresent(openBlocks::add);
            }
        }

        return openBlocks;
    }

    private static Optional<BlockPos> findStandPosition(ServerWorld world, BlockPos base) {
        BlockPos ground = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, base);
        BlockPos standPos = ground;

        if (isOpenForArmorStand(world, standPos)) {
            return Optional.of(standPos.toImmutable());
        }

        return Optional.empty();
    }

    private static void spawnArmorStands(ServerWorld world, List<BlockPos> positions) {
        for (BlockPos position : positions) {
            ArmorStandEntity armorStand = new ArmorStandEntity(world, position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D);
            armorStand.addCommandTag(GUARD_STAND_TAG);
            world.spawnEntity(armorStand);
        }
    }

    private static boolean isOpenForArmorStand(ServerWorld world, BlockPos pos) {
        boolean spaceIsClear = world.isAir(pos) && world.isAir(pos.up()) && world.isSpaceEmpty(new Box(
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                pos.getX() + 1,
                pos.getY() + 2,
                pos.getZ() + 1));
        if (!spaceIsClear) {
            return false;
        }

        BlockPos blockBelow = pos.down();
        return world.getBlockState(blockBelow).isOpaqueFullCube(world, blockBelow);
    }

    public static Optional<Integer> getGuardCount(World world, BlockPos bellPos) {
        GlobalPos globalPos = GlobalPos.create(world.getRegistryKey(), bellPos);
        return Optional.ofNullable(GUARD_COUNTS.get(globalPos));
    }
}
