package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.block.BellBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class VillageGuardStandManager {
    private static final int BELL_SEARCH_RANGE = 32;
    private static final int VILLAGE_ENTITY_RANGE = 32;
    private static final int ARMOR_STAND_SEARCH_RANGE = 6;
    private static final String GUARD_STAND_TAG = "guardvillagers:auto_armor_stand";

    private static final Map<GlobalPos, Integer> GUARD_COUNTS = new HashMap<>();

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

    private static void ensureArmorStands(ServerWorld world, BlockPos bellPos, int guardCount) {
        Box searchBox = new Box(bellPos).expand(ARMOR_STAND_SEARCH_RANGE);
        int existingStands = world.getEntitiesByClass(ArmorStandEntity.class, searchBox,
                stand -> stand.isAlive() && stand.getCommandTags().contains(GUARD_STAND_TAG)).size();

        int standsToSpawn = guardCount - existingStands;
        if (standsToSpawn <= 0) {
            return;
        }

        List<BlockPos> openPositions = findNearestOpenBlocks(world, bellPos);
        for (BlockPos position : openPositions) {
            if (standsToSpawn <= 0) {
                break;
            }

            ArmorStandEntity armorStand = new ArmorStandEntity(world, position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D);
            armorStand.addCommandTag(GUARD_STAND_TAG);
            if (world.spawnEntity(armorStand)) {
                standsToSpawn--;
            }
        }
    }

    private static List<BlockPos> findNearestOpenBlocks(ServerWorld world, BlockPos bellPos) {
        List<BlockPos> openBlocks = new ArrayList<>();

        for (BlockPos pos : BlockPos.iterateOutwards(bellPos, ARMOR_STAND_SEARCH_RANGE, ARMOR_STAND_SEARCH_RANGE, ARMOR_STAND_SEARCH_RANGE)) {
            if (!bellPos.isWithinDistance(pos, ARMOR_STAND_SEARCH_RANGE)) {
                continue;
            }

            if (isOpenForArmorStand(world, pos)) {
                openBlocks.add(pos.toImmutable());
            }
        }

        openBlocks.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(Vec3d.ofCenter(bellPos))));
        return openBlocks;
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
