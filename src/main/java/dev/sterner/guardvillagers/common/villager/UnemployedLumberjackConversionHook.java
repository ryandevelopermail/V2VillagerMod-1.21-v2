package dev.sterner.guardvillagers.common.villager;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.util.ConvertedWorkerJobSiteReservationManager;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import net.minecraft.block.BlockState;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public final class UnemployedLumberjackConversionHook {
    private static final double CRAFTING_TABLE_SEARCH_RANGE = JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE;
    private static final double CONVERSION_CANDIDATE_SCAN_RANGE = 8.0D;
    private static final Logger LOGGER = LoggerFactory.getLogger(UnemployedLumberjackConversionHook.class);

    private UnemployedLumberjackConversionHook() {
    }

    public static void tryConvertUnemployedVillagersNearCraftingTables(ServerWorld world) {
        Set<VillagerEntity> candidates = new LinkedHashSet<>();

        for (PlayerEntity player : world.getPlayers()) {
            candidates.addAll(world.getEntitiesByClass(
                    VillagerEntity.class,
                    new Box(player.getBlockPos()).expand(CONVERSION_CANDIDATE_SCAN_RANGE),
                    UnemployedLumberjackConversionHook::isEligibleUnemployed
            ));
        }

        // Player-proximity scan only. The world-bounds fallback was O(all entities) and fired
        // every 40 ticks even when players are far from any unemployed villager, causing a
        // constant server-tick performance penalty. Removed entirely: villagers outside player
        // proximity will be picked up on the next tick cycle when a player approaches them.
        // The VillagerConversionCandidateIndex and chunk-load hook handle the edge case of
        // loading chunks with unemployed villagers near crafting tables.

        for (VillagerEntity villager : candidates) {
            if (!LumberjackPopulationBalancingService.shouldAllowCreationAttempts(world, villager.getBlockPos(), "scheduled-unemployed-conversion")) {
                continue;
            }

            if (!isEligibleUnemployed(villager) || villager.getWorld() != world) {
                continue;
            }

            Optional<BlockPos> craftingTablePos = findReachableCraftingTable(world, villager);
            if (craftingTablePos.isEmpty()) {
                LumberjackBootstrapCoordinator.markNeedsTable(world, villager);
                continue;
            }

            BlockPos tablePos = craftingTablePos.get();
            if (!LumberjackPopulationBalancingService.shouldAllowCreationAttempts(world, tablePos, "crafting-table-candidate")) {
                continue;
            }
            if (ConvertedWorkerJobSiteReservationManager.isReservedForAnyConvertedWorker(world, tablePos)) {
                continue;
            }

            LumberjackBootstrapCoordinator.markReadyToConvert(world, villager);
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
        if (profession != VillagerProfession.NONE) {
            return false;
        }
        // Do NOT grab a villager that is already heading toward a non-crafting-table job site.
        // Vanilla sets POTENTIAL_JOB_SITE in the brain when a villager starts walking to claim
        // a job block. If that target is anything other than a CRAFTING_TABLE, this villager is
        // in the process of adopting a different profession — hands off.
        if (villager.getWorld() instanceof ServerWorld serverWorld) {
            GlobalPos potentialJobSite = villager.getBrain()
                    .getOptionalMemory(MemoryModuleType.POTENTIAL_JOB_SITE)
                    .orElse(null);
            if (potentialJobSite != null
                    && potentialJobSite.dimension() == serverWorld.getRegistryKey()) {
                BlockState targetState =
                        serverWorld.getBlockState(potentialJobSite.pos());
                if (!targetState.isOf(Blocks.CRAFTING_TABLE)) {
                    // Villager is already trying to claim a different job block — leave it alone.
                    return false;
                }
            }
        }
        return true;
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
        // Lumberjacks roam widely so we need a broad box to find the one whose
        // pairedCraftingTablePos points to this exact table. Keep it at BELL_EFFECT_RANGE.
        // The reservation manager is the primary guard; this scan is a secondary safeguard
        // for cases where the reservation wasn't persisted (e.g., server restart before persist).
        Box lumberjackScanBox = new Box(tablePos).expand(VillageGuardStandManager.BELL_EFFECT_RANGE);

        for (LumberjackGuardEntity lumberjack : world.getEntitiesByClass(LumberjackGuardEntity.class, lumberjackScanBox, LumberjackGuardEntity::isAlive)) {
            if (tablePos.equals(lumberjack.getPairedCraftingTablePos())) {
                return true;
            }
        }

        // Any villager whose job site is paired to this table must be within JOB_BLOCK_PAIRING_RANGE
        // of it (by definition of the pairing geometry). No need to scan further.
        Box villagerScanBox = new Box(tablePos).expand(JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE + 1.0D);

        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, villagerScanBox, UnemployedLumberjackConversionHook::isEmployedVillager)) {
            BlockPos jobPos = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE)
                    .filter(globalPos -> globalPos.dimension() == world.getRegistryKey())
                    .map(GlobalPos::pos)
                    .orElse(null);
            if (jobPos == null) {
                continue;
            }

            BlockPos chestPos = JobBlockPairingHelper.findNearbyChest(world, jobPos).orElse(null);
            if (chestPos == null) {
                continue;
            }

            // Only treat this as an already-paired table when it satisfies the same pairing geometry:
            // table must be near both the villager's job block and paired chest.
            if (!jobPos.isWithinDistance(tablePos, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE)
                    || !chestPos.isWithinDistance(tablePos, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE)) {
                continue;
            }

            BlockPos pairedCrafting = findNearbyCraftingTable(world, jobPos, chestPos);
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

    private static BlockPos findNearbyCraftingTable(ServerWorld world, BlockPos jobPos, BlockPos chestPos) {
        int range = (int) Math.ceil(JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE);
        for (BlockPos checkPos : BlockPos.iterate(jobPos.add(-range, -range, -range), jobPos.add(range, range, range))) {
            if (jobPos.isWithinDistance(checkPos, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE)
                    && chestPos.isWithinDistance(checkPos, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE)
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
        LOGGER.info("Converted unemployed villager {} into lumberjack guard {} at crafting table {}",
                villager.getUuidAsString(),
                guard.getUuidAsString(),
                tablePos.toShortString());
        JobBlockPairingHelper.playPairingAnimation(world, tablePos, villager, tablePos);
        VillageGuardStandManager.handleGuardSpawn(world, guard, villager);
        LumberjackBootstrapCoordinator.markDone(world, villager);
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
