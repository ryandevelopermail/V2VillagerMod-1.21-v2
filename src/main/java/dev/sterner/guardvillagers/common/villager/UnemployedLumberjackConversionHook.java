package dev.sterner.guardvillagers.common.villager;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.util.ConvertedWorkerJobSiteReservationManager;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.VillagerProfession;
import net.minecraft.entity.ai.pathing.Path;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public final class UnemployedLumberjackConversionHook {
    private static final double CRAFTING_TABLE_SEARCH_RANGE = JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE;
    private static final double CONVERSION_CANDIDATE_SCAN_RANGE = 8.0D;

    private UnemployedLumberjackConversionHook() {
    }

    public static void tryConvertUnemployedVillagersNearCraftingTables(ServerWorld world) {
        Set<VillagerEntity> candidates = new LinkedHashSet<>();
        Box worldBounds = JobBlockPairingHelper.getWorldBounds(world);

        for (PlayerEntity player : world.getPlayers()) {
            candidates.addAll(world.getEntitiesByClass(
                    VillagerEntity.class,
                    new Box(player.getBlockPos()).expand(CONVERSION_CANDIDATE_SCAN_RANGE),
                    UnemployedLumberjackConversionHook::isEligibleUnemployed
            ));
        }

        if (candidates.isEmpty()) {
            candidates.addAll(world.getEntitiesByClass(VillagerEntity.class, worldBounds, UnemployedLumberjackConversionHook::isEligibleUnemployed));
        }

        for (VillagerEntity villager : candidates) {
            if (!LumberjackPopulationBalancingService.shouldAllowCreationAttempts(world, villager.getBlockPos(), "scheduled-unemployed-conversion")) {
                continue;
            }

            if (!isEligibleUnemployed(villager) || villager.getWorld() != world) {
                continue;
            }

            Optional<BlockPos> craftingTablePos = findReachableCraftingTable(world, villager);
            if (craftingTablePos.isEmpty()) {
                continue;
            }

            BlockPos tablePos = craftingTablePos.get();
            if (!LumberjackPopulationBalancingService.shouldAllowCreationAttempts(world, tablePos, "crafting-table-candidate")) {
                continue;
            }
            if (ConvertedWorkerJobSiteReservationManager.isReservedForAnyConvertedWorker(world, tablePos)) {
                continue;
            }

            convert(world, villager, tablePos);
        }
    }

    private static boolean isEligibleUnemployed(VillagerEntity villager) {
        if (!villager.isAlive() || villager.isRemoved() || villager.isBaby()) {
            return false;
        }
        VillagerProfession profession = villager.getVillagerData().getProfession();
        if (profession == VillagerProfession.NITWIT) {
            return false;
        }
        return profession == VillagerProfession.NONE;
    }

    private static Optional<BlockPos> findReachableCraftingTable(ServerWorld world, VillagerEntity villager) {
        BlockPos center = villager.getBlockPos();
        int range = (int) Math.ceil(CRAFTING_TABLE_SEARCH_RANGE);

        for (BlockPos checkPos : BlockPos.iterate(center.add(-range, -range, -range), center.add(range, range, range))) {
            if (!center.isWithinDistance(checkPos, CRAFTING_TABLE_SEARCH_RANGE)) {
                continue;
            }

            BlockPos immutableCheckPos = checkPos.toImmutable();
            if (!world.getBlockState(immutableCheckPos).isOf(Blocks.CRAFTING_TABLE)) {
                continue;
            }

            if (isCraftingTableAlreadyPaired(world, immutableCheckPos)) {
                continue;
            }

            if (!isReachable(villager, immutableCheckPos)) {
                continue;
            }

            return Optional.of(immutableCheckPos);
        }

        return Optional.empty();
    }

    private static boolean isReachable(VillagerEntity villager, BlockPos targetPos) {
        Path path = villager.getNavigation().findPathTo(targetPos, 0);
        return path != null && path.reachesTarget();
    }

    private static boolean isCraftingTableAlreadyPaired(ServerWorld world, BlockPos tablePos) {
        Box scanBox = new Box(tablePos).expand(300.0D);

        for (LumberjackGuardEntity lumberjack : world.getEntitiesByClass(LumberjackGuardEntity.class, scanBox, LumberjackGuardEntity::isAlive)) {
            if (tablePos.equals(lumberjack.getPairedCraftingTablePos())) {
                return true;
            }
        }

        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, scanBox, UnemployedLumberjackConversionHook::isEmployedVillager)) {
            BlockPos jobPos = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE)
                    .filter(globalPos -> globalPos.dimension() == world.getRegistryKey())
                    .map(GlobalPos::pos)
                    .orElse(null);
            if (jobPos == null || JobBlockPairingHelper.findNearbyChest(world, jobPos).isEmpty()) {
                continue;
            }

            BlockPos pairedCrafting = findNearbyCraftingTable(world, jobPos);
            if (tablePos.equals(pairedCrafting)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isEmployedVillager(VillagerEntity villager) {
        VillagerProfession profession = villager.getVillagerData().getProfession();
        return villager.isAlive() && !villager.isBaby() && profession != VillagerProfession.NONE && profession != VillagerProfession.NITWIT;
    }

    private static BlockPos findNearbyCraftingTable(ServerWorld world, BlockPos center) {
        int range = (int) Math.ceil(JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE);
        for (BlockPos checkPos : BlockPos.iterate(center.add(-range, -range, -range), center.add(range, range, range))) {
            if (center.isWithinDistance(checkPos, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE)
                    && world.getBlockState(checkPos).isOf(Blocks.CRAFTING_TABLE)) {
                return checkPos.toImmutable();
            }
        }
        return null;
    }

    private static void convert(ServerWorld world, VillagerEntity villager, BlockPos tablePos) {
        LumberjackGuardEntity guard = GuardVillagers.LUMBERJACK_GUARD_VILLAGER.create(world);
        if (guard == null) {
            return;
        }

        GuardConversionHelper.initializeConvertedGuard(world, villager, guard, tablePos);
        GuardConversionHelper.applyStandardEquipmentDropChances(guard);
        clearAllEquipment(guard);
        guard.setPairedCraftingTablePos(tablePos);
        JobBlockPairingHelper.findNearbyChest(world, tablePos).ifPresent(guard::setPairedChestPos);
        guard.startChopCountdown(world.getTime(), 0L);

        ConvertedWorkerJobSiteReservationManager.reserve(world, tablePos, guard.getUuid(), VillagerProfession.NONE, "unemployed lumberjack conversion");

        world.spawnEntityAndPassengers(guard);
        JobBlockPairingHelper.playPairingAnimation(world, tablePos, villager, tablePos);
        VillageGuardStandManager.handleGuardSpawn(world, guard, villager);
        GuardConversionHelper.cleanupVillagerAfterConversion(villager);
    }

    private static void clearAllEquipment(LumberjackGuardEntity guard) {
        guard.equipStack(EquipmentSlot.HEAD, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.CHEST, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.LEGS, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.FEET, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        guard.equipStack(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
    }
}
