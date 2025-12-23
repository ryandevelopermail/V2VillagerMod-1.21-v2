package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.block.BellBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.ServerWorldAccess;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class VillageGuardStandManager {
    private static final int BELL_SEARCH_RANGE = 32;
    public static final int BELL_EFFECT_RANGE = 300;
    private static final int VILLAGE_ENTITY_RANGE = BELL_EFFECT_RANGE;
    private static final int ARMOR_STAND_SEARCH_RANGE = BELL_EFFECT_RANGE;
    private static final int ARMOR_STAND_SPAWN_RADIUS = 10;
    private static final int PLAYER_APPROACH_RANGE = 100;
    private static final int PLAYER_CHECK_INTERVAL_TICKS = 40;
    public static final String GUARD_STAND_TAG = "guardvillagers:auto_armor_stand";

    private static final Map<GlobalPos, Integer> GUARD_COUNTS = new HashMap<>();
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
        BlockPos closest = null;
        double distance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.iterateOutwards(center, BELL_SEARCH_RANGE, BELL_SEARCH_RANGE, BELL_SEARCH_RANGE)) {
            if (!center.isWithinDistance(pos, BELL_SEARCH_RANGE)) {
                continue;
            }

            if (world.getBlockState(pos).getBlock() instanceof BellBlock) {
                double currentDistance = pos.getSquaredDistance(Vec3d.ofCenter(center));
                if (currentDistance < distance) {
                    distance = currentDistance;
                    closest = pos.toImmutable();
                }
            }
        }

        return Optional.ofNullable(closest);
    }

    private static int countVillageGuards(ServerWorld world, BlockPos bellPos) {
        Box searchBox = new Box(bellPos).expand(VILLAGE_ENTITY_RANGE);
        return world.getEntitiesByClass(GuardEntity.class, searchBox, Entity::isAlive).size();
    }

    private static List<GuardEntity> getGuardsInRange(ServerWorld world, BlockPos bellPos) {
        Box searchBox = new Box(bellPos).expand(VILLAGE_ENTITY_RANGE);
        return world.getEntitiesByClass(GuardEntity.class, searchBox, Entity::isAlive);
    }

    public static List<ArmorStandEntity> getGuardArmorStands(ServerWorld world, BlockPos bellPos) {
        Box searchBox = new Box(bellPos).expand(ARMOR_STAND_SEARCH_RANGE);
        return world.getEntitiesByClass(ArmorStandEntity.class, searchBox,
                stand -> stand.isAlive() && stand.getCommandTags().contains(GUARD_STAND_TAG));
    }

    private static List<ArmorStandEntity> ensureArmorStands(ServerWorld world, BlockPos bellPos, int guardCount) {
        Optional<CobblePad> cobblePad = prepareCobblePad(world, bellPos);
        if (cobblePad.isEmpty()) {
            return List.of();
        }

        Box searchBox = new Box(bellPos).expand(ARMOR_STAND_SEARCH_RANGE);
        List<ArmorStandEntity> existingStands = world.getEntitiesByClass(ArmorStandEntity.class, searchBox, stand ->
                stand.isAlive() && stand.getCommandTags().contains(GUARD_STAND_TAG));

        List<BlockPos> padPositions = cobblePad.get().standPositions();
        Set<BlockPos> occupiedPositions = existingStands.stream()
                .map(Entity::getBlockPos)
                .collect(Collectors.toSet());

        int targetCount = Math.max(guardCount, existingStands.size());
        int availablePositions = Math.max(padPositions.size() - occupiedPositions.size(), 0);
        int spawnCount = Math.min(Math.max(targetCount - existingStands.size(), 0), availablePositions);

        if (spawnCount <= 0) {
            return existingStands;
        }

        List<BlockPos> spawnPositions = padPositions.stream()
                .filter(pos -> !occupiedPositions.contains(pos))
                .limit(spawnCount)
                .collect(Collectors.toList());

        List<ArmorStandEntity> newStands = spawnArmorStands(world, spawnPositions);
        List<ArmorStandEntity> allStands = new ArrayList<>(existingStands.size() + newStands.size());
        allStands.addAll(existingStands);
        allStands.addAll(newStands);

        return allStands;
    }

    private static List<BlockPos> getPadCenters(ServerWorld world, BlockPos bellPos) {
        List<BlockPos> centers = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterateOutwards(bellPos, ARMOR_STAND_SPAWN_RADIUS, 0, ARMOR_STAND_SPAWN_RADIUS)) {
            double horizontalDistance = Math.sqrt(pos.getSquaredDistance(bellPos));
            if (horizontalDistance < 5 || horizontalDistance > 10) {
                continue;
            }
            centers.add(pos.toImmutable());
        }
        centers.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(bellPos)));
        return centers;
    }

    private static List<ArmorStandEntity> spawnArmorStands(ServerWorld world, List<BlockPos> positions) {
        List<ArmorStandEntity> newStands = new ArrayList<>();
        for (BlockPos position : positions) {
            ArmorStandEntity armorStand = new ArmorStandEntity(world, position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D);
            armorStand.addCommandTag(GUARD_STAND_TAG);
            world.spawnEntity(armorStand);
            newStands.add(armorStand);
        }
        return newStands;
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

    public static GuardStandPairingReport pairGuardsWithStands(ServerWorld world, BlockPos bellPos) {
        Comparator<GuardEntity> guardComparator = Comparator
                .<GuardEntity>comparingDouble(guard -> guard.squaredDistanceTo(Vec3d.ofCenter(bellPos)))
                .thenComparing(guard -> guard.getBlockPos().getX())
                .thenComparing(guard -> guard.getBlockPos().getY())
                .thenComparing(guard -> guard.getBlockPos().getZ());
        List<GuardEntity> guards = getGuardsInRange(world, bellPos).stream()
                .sorted(guardComparator)
                .collect(Collectors.toList());

        Comparator<ArmorStandEntity> standComparator = Comparator
                .<ArmorStandEntity>comparingDouble(stand -> stand.squaredDistanceTo(Vec3d.ofCenter(bellPos)))
                .thenComparing(stand -> stand.getBlockPos().getX())
                .thenComparing(stand -> stand.getBlockPos().getY())
                .thenComparing(stand -> stand.getBlockPos().getZ());
        List<ArmorStandEntity> armorStands = ensureArmorStands(world, bellPos, guards.size()).stream()
                .sorted(standComparator)
                .collect(Collectors.toList());

        int pairCount = Math.min(guards.size(), armorStands.size());
        List<GuardStandAssignment> assignments = new ArrayList<>();
        for (int i = 0; i < pairCount; i++) {
            assignments.add(new GuardStandAssignment(guards.get(i).getBlockPos(), armorStands.get(i).getBlockPos()));
        }

        List<GuardEntity> toDemote = guards.subList(pairCount, guards.size());
        List<BlockPos> demotedPositions = demoteGuards(world, toDemote);

        return new GuardStandPairingReport(assignments, demotedPositions);
    }

    private static List<BlockPos> demoteGuards(ServerWorld world, List<GuardEntity> guards) {
        List<BlockPos> demoted = new ArrayList<>();
        for (GuardEntity guard : guards) {
            VillagerEntity villager = EntityType.VILLAGER.create(world);
            if (villager == null) {
                continue;
            }
            villager.refreshPositionAndAngles(guard.getX(), guard.getY(), guard.getZ(), guard.getYaw(), guard.getPitch());
            villager.initialize((ServerWorldAccess) world, world.getLocalDifficulty(villager.getBlockPos()), SpawnReason.CONVERSION, null);
            villager.setCustomName(guard.getCustomName());
            villager.setCustomNameVisible(guard.isCustomNameVisible());
            if (world.spawnEntity(villager)) {
                demoted.add(guard.getBlockPos());
                guard.discard();
            }
        }
        return demoted;
    }

    private static Optional<CobblePad> prepareCobblePad(ServerWorld world, BlockPos bellPos) {
        int[] padHeights = new int[]{bellPos.getY() - 1, bellPos.getY()};
        Optional<CobblePad> existing = findExistingPad(world, bellPos, padHeights);
        if (existing.isPresent()) {
            return existing;
        }

        for (int padY : padHeights) {
            for (BlockPos center : getPadCenters(world, bellPos)) {
                Optional<CobblePad> pad = tryCreateCobblePad(world, center, padY);
                if (pad.isPresent()) {
                    return pad;
                }
            }
        }
        return tryCreateFallbackPad(world, bellPos, padHeights);
    }

    private static Optional<CobblePad> findExistingPad(ServerWorld world, BlockPos bellPos, int[] padHeights) {
        for (int padY : padHeights) {
            for (BlockPos center : getPadCenters(world, bellPos)) {
                Optional<CobblePad> pad = detectPad(world, center, padY);
                if (pad.isPresent()) {
                    return pad;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<CobblePad> tryCreateCobblePad(ServerWorld world, BlockPos center, int padY) {
        List<BlockPos> groundPositions = new ArrayList<>();
        List<BlockPos> standPositions = new ArrayList<>();

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos column = center.add(dx, 0, dz);
                BlockPos ground = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column);
                if (ground.getY() > padY || ground.getY() < padY - 1) {
                    return Optional.empty();
                }

                BlockPos padTop = new BlockPos(ground.getX(), padY, ground.getZ());
                if (!world.isAir(padTop.up()) || !world.isAir(padTop.up(2))) {
                    return Optional.empty();
                }

                groundPositions.add(ground);
                standPositions.add(padTop.up());
            }
        }

        groundPositions.forEach(pos -> {
            BlockPos columnTop = new BlockPos(pos.getX(), pos.getY(), pos.getZ());
            for (int y = columnTop.getY(); y <= padY; y++) {
                BlockPos fillPos = new BlockPos(columnTop.getX(), y, columnTop.getZ());
                world.setBlockState(fillPos, net.minecraft.block.Blocks.COBBLESTONE.getDefaultState());
            }
        });

        return Optional.of(new CobblePad(Collections.unmodifiableList(standPositions)));
    }

    private static Optional<CobblePad> tryCreateFallbackPad(ServerWorld world, BlockPos bellPos, int[] padHeights) {
        BlockPos fallbackCenter = bellPos.add(5, 0, 0);
        for (int padY : padHeights) {
            Optional<CobblePad> pad = tryCreateCobblePad(world, fallbackCenter, padY);
            if (pad.isPresent()) {
                return pad;
            }
        }
        return Optional.empty();
    }

    private static Optional<CobblePad> detectPad(ServerWorld world, BlockPos center, int padY) {
        List<BlockPos> standPositions = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos padPos = new BlockPos(center.getX() + dx, padY, center.getZ() + dz);
                if (!world.getBlockState(padPos).isOf(net.minecraft.block.Blocks.COBBLESTONE)) {
                    return Optional.empty();
                }
                if (!world.isAir(padPos.up()) || !world.isAir(padPos.up(2))) {
                    return Optional.empty();
                }
                standPositions.add(padPos.up());
            }
        }
        return Optional.of(new CobblePad(Collections.unmodifiableList(standPositions)));
    }

    public record GuardStandAssignment(BlockPos guardPos, BlockPos standPos) {
    }

    public record GuardStandPairingReport(List<GuardStandAssignment> assignments, List<BlockPos> demotedGuards) {
    }

    private record CobblePad(List<BlockPos> standPositions) {
    }
}
