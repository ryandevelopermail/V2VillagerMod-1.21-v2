package dev.sterner.guardvillagers.common.professionalstorage;

import dev.sterner.guardvillagers.common.entity.goal.WeaponsmithDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.WeaponsmithRepairGoal;
import dev.sterner.guardvillagers.common.util.WeaponsmithStandManager;
import dev.sterner.guardvillagers.common.villager.behavior.WeaponsmithBehavior;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Four-tab Weaponsmith profile assembled from one read-only open-time snapshot. */
public final class WeaponsmithProfessionalStorageProfileProvider implements ProfessionalStorageProfileProvider {
    @Override
    public Optional<List<ProfessionalStorageTab>> createTabs(
            ServerWorld world,
            StorageIdentity storage,
            List<ProfessionalStorageResolution> resolutions
    ) {
        if (resolutions.isEmpty()) {
            return Optional.empty();
        }
        Inventory inventory = resolveStorageInventory(world, storage);
        if (inventory == null) {
            return Optional.empty();
        }

        StoredWeapons storedWeapons = scanStoredWeapons(inventory);
        List<WeaponsmithWorkerView> workers = new ArrayList<>(resolutions.size());
        List<LoadedWorker> loadedWorkers = new ArrayList<>();
        for (ProfessionalStorageResolution resolution : resolutions) {
            UUID workerUuid = resolution.pairing().workerUuid();
            if (resolution.workerAvailability() == ProfessionalStorageResolution.WorkerAvailability.UNLOADED) {
                workers.add(new WeaponsmithWorkerView(workerUuid, resolution.workerAvailability(), null, null));
                continue;
            }
            Entity entity = world.getEntity(workerUuid);
            if (!(entity instanceof VillagerEntity villager)) {
                return Optional.empty();
            }
            Optional<WeaponsmithBehavior.WeaponsmithLiveSnapshot> live =
                    WeaponsmithBehavior.getLiveStorageSnapshot(world, villager, inventory);
            if (live.isEmpty()) {
                return Optional.empty();
            }
            workers.add(new WeaponsmithWorkerView(
                    workerUuid,
                    resolution.workerAvailability(),
                    live.get().craftingTableReady(),
                    live.get().craftableWeapons()));
            loadedWorkers.add(new LoadedWorker(
                    workerUuid,
                    villager,
                    resolution.pairing().physicalStoragePos()));
        }

        UUID representativeUuid = selectRepresentative(workers).orElse(null);
        Integer eligibleStands = loadedWorkers.stream()
                .filter(worker -> worker.workerUuid().equals(representativeUuid))
                .findFirst()
                .map(worker -> WeaponsmithStandManager.countEligibleStandsReadOnly(
                        world,
                        worker.villager(),
                        worker.distributionCenter(),
                        storedWeapons.stacks()))
                .orElse(null);
        Integer craftableWeapons = workers.stream()
                .filter(worker -> worker.workerUuid().equals(representativeUuid))
                .map(WeaponsmithWorkerView::craftableWeapons)
                .findFirst()
                .orElse(null);

        List<UUID> workerUuids = resolutions.stream()
                .map(resolution -> resolution.pairing().workerUuid())
                .toList();
        WeaponsmithCareerTotals totals = aggregateCareerTotals(
                ProfessionalWorkStatsState.get(world.getServer()),
                workerUuids,
                resolutions.getFirst().pairing().role());
        return Optional.of(buildTabs(
                workers,
                storedWeapons.count(),
                craftableWeapons,
                WeaponsmithRepairGoal.countRepairPairs(inventory),
                eligibleStands,
                totals));
    }

    static WeaponsmithCareerTotals aggregateCareerTotals(
            ProfessionalWorkStatsState stats,
            List<UUID> workerUuids,
            ProfessionalRoleId role
    ) {
        return new WeaponsmithCareerTotals(
                stats.aggregate(workerUuids, role, WeaponsmithWorkMetrics.WEAPONS_CRAFTED),
                stats.aggregate(workerUuids, role, WeaponsmithWorkMetrics.WEAPONS_REPAIRED),
                stats.aggregate(workerUuids, role, WeaponsmithWorkMetrics.WEAPONS_EQUIPPED));
    }

    static List<ProfessionalStorageTab> buildTabs(
            List<WeaponsmithWorkerView> workers,
            long weaponsInStorage,
            @Nullable Integer craftableWeapons,
            int repairPairsReady,
            @Nullable Integer eligibleGuardStands,
            WeaponsmithCareerTotals totals
    ) {
        long loadedCount = workers.stream().filter(WeaponsmithWorkerView::loaded).count();
        boolean unavailable = loadedCount < workers.size();
        TableDisplay table = tableDisplay(workers);
        MeasuredDisplay craftable = measuredDisplay(craftableWeapons);
        MeasuredDisplay stands = measuredDisplay(eligibleGuardStands);

        return List.of(
                new ProfessionalStorageTab(
                        "overview",
                        "Overview",
                        List.of(
                                new ProfessionalStorageRow(
                                        "Status",
                                        unavailable ? "Worker unavailable" : "Ready",
                                        unavailable
                                                ? ProfessionalStorageRow.Tone.WARNING
                                                : ProfessionalStorageRow.Tone.PAIRED),
                                new ProfessionalStorageRow("Crafting table", table.value(), table.tone()),
                                new ProfessionalStorageRow(
                                        "Weapons in storage",
                                        Long.toString(Math.max(0L, weaponsInStorage)),
                                        ProfessionalStorageRow.Tone.NORMAL))),
                new ProfessionalStorageTab(
                        "crafting",
                        "Crafting",
                        List.of(
                                new ProfessionalStorageRow(
                                        "Craftable weapons",
                                        craftable.value(),
                                        craftable.tone()),
                                new ProfessionalStorageRow(
                                        "Weapons crafted",
                                        Long.toString(totals.weaponsCrafted()),
                                        ProfessionalStorageRow.Tone.NORMAL))),
                new ProfessionalStorageTab(
                        "repair",
                        "Repair",
                        List.of(
                                new ProfessionalStorageRow(
                                        "Repair pairs ready",
                                        Integer.toString(Math.max(0, repairPairsReady)),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Weapons repaired",
                                        Long.toString(totals.weaponsRepaired()),
                                        ProfessionalStorageRow.Tone.NORMAL))),
                new ProfessionalStorageTab(
                        "distribution",
                        "Distribution",
                        List.of(
                                new ProfessionalStorageRow(
                                        "Eligible guard stands",
                                        stands.value(),
                                        stands.tone()),
                                new ProfessionalStorageRow(
                                        "Weapons equipped",
                                        Long.toString(totals.weaponsEquipped()),
                                        ProfessionalStorageRow.Tone.NORMAL))));
    }

    static Optional<UUID> selectRepresentative(List<WeaponsmithWorkerView> workers) {
        return workers.stream()
                .filter(WeaponsmithWorkerView::loaded)
                .map(WeaponsmithWorkerView::workerUuid)
                .min(UUID::compareTo);
    }

    static long countWeaponViews(List<WeaponStackView> stacks) {
        long count = 0L;
        for (WeaponStackView stack : stacks) {
            if (stack.supportedWeapon() && stack.count() > 0L) {
                count = saturatingAdd(count, stack.count());
            }
        }
        return count;
    }

    static long countResolvedStorageContents(Supplier<List<WeaponStackView>> resolvedContents) {
        return countWeaponViews(resolvedContents.get());
    }

    private static StoredWeapons scanStoredWeapons(Inventory inventory) {
        List<ItemStack> stacks = new ArrayList<>();
        List<WeaponStackView> views = new ArrayList<>(inventory.size());
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            boolean supported = !stack.isEmpty() && WeaponsmithDistributionGoal.isDistributableWeapon(stack);
            views.add(new WeaponStackView(supported, stack.getCount()));
            if (supported) {
                stacks.add(stack.copy());
            }
        }
        return new StoredWeapons(countResolvedStorageContents(() -> views), List.copyOf(stacks));
    }

    private static TableDisplay tableDisplay(List<WeaponsmithWorkerView> workers) {
        List<WeaponsmithWorkerView> loaded = workers.stream().filter(WeaponsmithWorkerView::loaded).toList();
        if (loaded.isEmpty()) {
            return new TableDisplay("Unknown", ProfessionalStorageRow.Tone.WARNING);
        }
        long ready = loaded.stream().filter(worker -> Boolean.TRUE.equals(worker.craftingTableReady())).count();
        String value = loaded.size() == 1
                ? (ready == 1 ? "Yes" : "No")
                : ready + " / " + loaded.size() + " ready";
        if (loaded.size() < workers.size()) {
            return new TableDisplay("Partial: " + value, ProfessionalStorageRow.Tone.WARNING);
        }
        return new TableDisplay(
                value,
                ready == loaded.size() ? ProfessionalStorageRow.Tone.PAIRED : ProfessionalStorageRow.Tone.WARNING);
    }

    private static MeasuredDisplay measuredDisplay(@Nullable Integer value) {
        return value == null
                ? new MeasuredDisplay("Not measured", ProfessionalStorageRow.Tone.WARNING)
                : new MeasuredDisplay(Integer.toString(Math.max(0, value)), ProfessionalStorageRow.Tone.NORMAL);
    }

    private static @Nullable Inventory resolveStorageInventory(ServerWorld world, StorageIdentity storage) {
        BlockPos pos = storage.canonicalPos();
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            return ChestBlock.getInventory(chestBlock, state, world, pos, false);
        }
        return world.getBlockEntity(pos) instanceof Inventory inventory ? inventory : null;
    }

    private static long saturatingAdd(long current, long amount) {
        return current >= Long.MAX_VALUE - amount ? Long.MAX_VALUE : current + amount;
    }

    record WeaponsmithWorkerView(
            UUID workerUuid,
            ProfessionalStorageResolution.WorkerAvailability availability,
            @Nullable Boolean craftingTableReady,
            @Nullable Integer craftableWeapons
    ) {
        boolean loaded() {
            return availability == ProfessionalStorageResolution.WorkerAvailability.LOADED;
        }
    }

    record WeaponsmithCareerTotals(long weaponsCrafted, long weaponsRepaired, long weaponsEquipped) {
        WeaponsmithCareerTotals {
            if (weaponsCrafted < 0L || weaponsRepaired < 0L || weaponsEquipped < 0L) {
                throw new IllegalArgumentException("Weaponsmith career totals must be nonnegative");
            }
        }
    }

    record WeaponStackView(boolean supportedWeapon, long count) {
    }

    private record StoredWeapons(long count, List<ItemStack> stacks) {
    }

    private record LoadedWorker(UUID workerUuid, VillagerEntity villager, BlockPos distributionCenter) {
        LoadedWorker {
            distributionCenter = distributionCenter.toImmutable();
        }
    }

    private record TableDisplay(String value, ProfessionalStorageRow.Tone tone) {
    }

    private record MeasuredDisplay(String value, ProfessionalStorageRow.Tone tone) {
    }
}
