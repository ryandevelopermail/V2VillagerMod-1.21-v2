package dev.sterner.guardvillagers.common.professionalstorage;

import dev.sterner.guardvillagers.common.entity.goal.FarmerHarvestGoal;
import dev.sterner.guardvillagers.common.villager.behavior.FarmerBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Two-tab Farmer profile assembled from persisted career totals and read-only live goal state. */
public final class FarmerProfessionalStorageProfileProvider implements ProfessionalStorageProfileProvider {
    @Override
    public Optional<List<ProfessionalStorageTab>> createTabs(
            ServerWorld world,
            StorageIdentity storage,
            List<ProfessionalStorageResolution> resolutions
    ) {
        if (resolutions.isEmpty()) {
            return Optional.empty();
        }
        List<FarmerWorkerView> workers = new ArrayList<>(resolutions.size());
        for (ProfessionalStorageResolution resolution : resolutions) {
            ProfessionalStoragePairing pairing = resolution.pairing();
            FarmerHarvestGoal.FarmerLiveSnapshot liveSnapshot = null;
            if (resolution.workerAvailability() == ProfessionalStorageResolution.WorkerAvailability.LOADED) {
                Entity entity = world.getEntity(pairing.workerUuid());
                if (!(entity instanceof VillagerEntity villager)) {
                    return Optional.empty();
                }
                Optional<FarmerHarvestGoal.FarmerLiveSnapshot> live =
                        FarmerBehavior.getLiveStorageSnapshot(world, villager);
                if (live.isEmpty()) {
                    return Optional.empty();
                }
                liveSnapshot = live.get();
            }
            workers.add(new FarmerWorkerView(
                    pairing.workerUuid(),
                    resolution.workerAvailability(),
                    pairing.roleAnchorPos(),
                    liveSnapshot));
        }

        ProfessionalRoleId farmerRole = resolutions.getFirst().pairing().role();
        List<UUID> workerUuids = resolutions.stream()
                .map(resolution -> resolution.pairing().workerUuid())
                .toList();
        ProfessionalWorkStatsState stats = ProfessionalWorkStatsState.get(world.getServer());
        FarmerCareerTotals totals = aggregateCareerTotals(stats, workerUuids, farmerRole);
        return Optional.of(buildTabs(workers, storageHasUsableHoe(world, storage), totals));
    }

    static FarmerCareerTotals aggregateCareerTotals(
            ProfessionalWorkStatsState stats,
            List<UUID> workerUuids,
            ProfessionalRoleId farmerRole
    ) {
        return new FarmerCareerTotals(
                stats.aggregate(workerUuids, farmerRole, FarmerWorkMetrics.CROPS_HARVESTED),
                stats.aggregate(workerUuids, farmerRole, FarmerWorkMetrics.CROPS_PLANTED),
                stats.aggregate(workerUuids, farmerRole, FarmerWorkMetrics.GROUND_TILLED),
                stats.aggregate(workerUuids, farmerRole, FarmerWorkMetrics.MATERIALS_CRAFTED));
    }

    static List<ProfessionalStorageTab> buildTabs(
            List<FarmerWorkerView> workers,
            boolean storageHasHoe,
            FarmerCareerTotals totals
    ) {
        boolean unavailable = workers.stream().anyMatch(worker ->
                worker.availability() == ProfessionalStorageResolution.WorkerAvailability.UNLOADED);
        Set<FarmerHarvestGoal.FarmerActivity> activities = new LinkedHashSet<>();
        boolean hoeAvailable = storageHasHoe;
        for (FarmerWorkerView worker : workers) {
            if (worker.liveSnapshot() != null) {
                activities.add(worker.liveSnapshot().activity());
                hoeAvailable |= worker.liveSnapshot().hoeAvailable();
            }
        }

        String status;
        ProfessionalStorageRow.Tone statusTone;
        if (unavailable) {
            status = "Worker unavailable";
            statusTone = ProfessionalStorageRow.Tone.WARNING;
        } else if (activities.size() == 1) {
            status = activities.iterator().next().displayName();
            statusTone = ProfessionalStorageRow.Tone.PAIRED;
        } else if (activities.size() > 1) {
            status = "Multiple activities";
            statusTone = ProfessionalStorageRow.Tone.PAIRED;
        } else {
            status = "Idle";
            statusTone = ProfessionalStorageRow.Tone.PAIRED;
        }

        CoverageDisplay coverage = aggregateCoverage(workers);
        return List.of(
                new ProfessionalStorageTab(
                        "overview",
                        "Overview",
                        List.of(
                                new ProfessionalStorageRow("Status", status, statusTone),
                                new ProfessionalStorageRow(
                                        "Hoe available",
                                        hoeAvailable ? "Yes" : "No",
                                        hoeAvailable
                                                ? ProfessionalStorageRow.Tone.PAIRED
                                                : ProfessionalStorageRow.Tone.WARNING),
                                new ProfessionalStorageRow(
                                        "Farmland coverage",
                                        coverage.value(),
                                        coverage.tone()))),
                new ProfessionalStorageTab(
                        "statistics",
                        "Statistics",
                        List.of(
                                new ProfessionalStorageRow(
                                        "Crops harvested",
                                        Long.toString(totals.cropsHarvested()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Crops planted",
                                        Long.toString(totals.cropsPlanted()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Ground tilled",
                                        Long.toString(totals.groundTilled()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Materials crafted",
                                        Long.toString(totals.materialsCrafted()),
                                        ProfessionalStorageRow.Tone.NORMAL))));
    }

    private static CoverageDisplay aggregateCoverage(List<FarmerWorkerView> workers) {
        if (workers.stream().anyMatch(worker -> worker.farmIdentity() == null)) {
            return CoverageDisplay.notMeasured();
        }

        Map<BlockPos, FarmerHarvestGoal.FarmerCoverageSnapshot> knownByFarm = new HashMap<>();
        Set<BlockPos> conflictingFarms = new java.util.HashSet<>();
        Set<BlockPos> distinctFarms = new LinkedHashSet<>();
        for (FarmerWorkerView worker : workers) {
            BlockPos farm = worker.farmIdentity();
            distinctFarms.add(farm);
            FarmerHarvestGoal.FarmerCoverageSnapshot coverage = worker.liveSnapshot() == null
                    ? null
                    : worker.liveSnapshot().coverage();
            if (coverage == null || conflictingFarms.contains(farm)) {
                continue;
            }
            FarmerHarvestGoal.FarmerCoverageSnapshot previous = knownByFarm.putIfAbsent(farm, coverage);
            if (previous != null && !previous.equals(coverage)) {
                knownByFarm.remove(farm);
                conflictingFarms.add(farm);
            }
        }
        if (knownByFarm.isEmpty()) {
            return CoverageDisplay.notMeasured();
        }

        long seeded = 0L;
        long accessible = 0L;
        for (FarmerHarvestGoal.FarmerCoverageSnapshot coverage : knownByFarm.values()) {
            seeded += coverage.seededCells();
            accessible += coverage.accessibleCells();
        }
        String value = seeded + " / " + accessible;
        if (knownByFarm.size() < distinctFarms.size()) {
            value = "Partial: " + value;
        }
        return new CoverageDisplay(value, ProfessionalStorageRow.Tone.NORMAL);
    }

    private static boolean storageHasUsableHoe(ServerWorld world, StorageIdentity storage) {
        BlockPos pos = storage.canonicalPos();
        BlockState state = world.getBlockState(pos);
        Inventory inventory;
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            inventory = ChestBlock.getInventory(chestBlock, state, world, pos, false);
        } else if (world.getBlockEntity(pos) instanceof Inventory blockInventory) {
            inventory = blockInventory;
        } else {
            inventory = null;
        }
        if (inventory == null) {
            return false;
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (FarmerHarvestGoal.isUsableHoe(stack)) {
                return true;
            }
        }
        return false;
    }

    record FarmerWorkerView(
            UUID workerUuid,
            ProfessionalStorageResolution.WorkerAvailability availability,
            @Nullable BlockPos farmIdentity,
            @Nullable FarmerHarvestGoal.FarmerLiveSnapshot liveSnapshot
    ) {
        FarmerWorkerView {
            farmIdentity = farmIdentity == null ? null : farmIdentity.toImmutable();
        }
    }

    record FarmerCareerTotals(
            long cropsHarvested,
            long cropsPlanted,
            long groundTilled,
            long materialsCrafted
    ) {
        FarmerCareerTotals {
            if (cropsHarvested < 0L || cropsPlanted < 0L || groundTilled < 0L || materialsCrafted < 0L) {
                throw new IllegalArgumentException("Farmer career totals must be nonnegative");
            }
        }
    }

    private record CoverageDisplay(String value, ProfessionalStorageRow.Tone tone) {
        static CoverageDisplay notMeasured() {
            return new CoverageDisplay("Not measured", ProfessionalStorageRow.Tone.WARNING);
        }
    }
}
