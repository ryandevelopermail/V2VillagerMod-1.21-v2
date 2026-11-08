package dev.sterner.guardvillagers.common.professionalstorage;

import dev.sterner.guardvillagers.common.entity.goal.FletcherDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.FletcherFletchingTableGoal;
import dev.sterner.guardvillagers.common.villager.behavior.FletcherBehavior;
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

/** Three-tab Fletcher profile assembled from one read-only open-time snapshot. */
public final class FletcherProfessionalStorageProfileProvider implements ProfessionalStorageProfileProvider {
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

        List<FletcherWorkerView> workers = new ArrayList<>(resolutions.size());
        for (ProfessionalStorageResolution resolution : resolutions) {
            UUID workerUuid = resolution.pairing().workerUuid();
            if (resolution.workerAvailability() == ProfessionalStorageResolution.WorkerAvailability.UNLOADED) {
                workers.add(new FletcherWorkerView(workerUuid, resolution.workerAvailability(), null, null));
                continue;
            }
            Entity entity = world.getEntity(workerUuid);
            if (!(entity instanceof VillagerEntity villager)) {
                return Optional.empty();
            }
            Optional<FletcherBehavior.FletcherLiveSnapshot> live =
                    FletcherBehavior.getLiveStorageSnapshot(world, villager, inventory);
            if (live.isEmpty()) {
                return Optional.empty();
            }
            workers.add(new FletcherWorkerView(
                    workerUuid,
                    resolution.workerAvailability(),
                    live.get().craftingTableReady(),
                    live.get().craftableOutputs()));
        }

        UUID representativeUuid = selectRepresentative(workers).orElse(null);
        Integer craftableOutputs = workers.stream()
                .filter(worker -> worker.workerUuid().equals(representativeUuid))
                .map(FletcherWorkerView::craftableOutputs)
                .findFirst()
                .orElse(null);
        FletcherStorageCounts counts = countResolvedStorageContents(() -> storageViews(inventory));
        List<UUID> workerUuids = resolutions.stream()
                .map(resolution -> resolution.pairing().workerUuid())
                .toList();
        FletcherCareerTotals totals = aggregateCareerTotals(
                ProfessionalWorkStatsState.get(world.getServer()),
                workerUuids,
                resolutions.getFirst().pairing().role());
        return Optional.of(buildTabs(
                workers,
                counts,
                craftableOutputs,
                FletcherFletchingTableGoal.isArrowBatchReady(inventory),
                totals));
    }

    static FletcherCareerTotals aggregateCareerTotals(
            ProfessionalWorkStatsState stats,
            List<UUID> workerUuids,
            ProfessionalRoleId role
    ) {
        return new FletcherCareerTotals(
                stats.aggregate(workerUuids, role, FletcherWorkMetrics.FLETCHING_GOODS_CRAFTED),
                stats.aggregate(workerUuids, role, FletcherWorkMetrics.RANGED_WEAPONS_EQUIPPED),
                stats.aggregate(workerUuids, role, FletcherWorkMetrics.ARROWS_DELIVERED),
                stats.aggregate(workerUuids, role, FletcherWorkMetrics.STICKS_DELIVERED));
    }

    static List<ProfessionalStorageTab> buildTabs(
            List<FletcherWorkerView> workers,
            FletcherStorageCounts storage,
            @Nullable Integer craftableOutputs,
            boolean arrowBatchReady,
            FletcherCareerTotals totals
    ) {
        boolean unavailable = workers.stream().anyMatch(worker -> !worker.loaded());
        TableDisplay table = tableDisplay(workers);
        MeasuredDisplay craftable = measuredDisplay(craftableOutputs);
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
                                        "Arrows in storage",
                                        Long.toString(storage.arrows()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Ranged weapons in storage",
                                        Long.toString(storage.rangedWeapons()),
                                        ProfessionalStorageRow.Tone.NORMAL))),
                new ProfessionalStorageTab(
                        "crafting",
                        "Crafting",
                        List.of(
                                new ProfessionalStorageRow(
                                        "Craftable outputs",
                                        craftable.value(),
                                        craftable.tone()),
                                new ProfessionalStorageRow(
                                        "Arrow batch ready",
                                        arrowBatchReady ? "Yes" : "No",
                                        arrowBatchReady
                                                ? ProfessionalStorageRow.Tone.PAIRED
                                                : ProfessionalStorageRow.Tone.WARNING),
                                new ProfessionalStorageRow(
                                        "Fletching goods crafted",
                                        Long.toString(totals.goodsCrafted()),
                                        ProfessionalStorageRow.Tone.NORMAL))),
                new ProfessionalStorageTab(
                        "distribution",
                        "Distribution",
                        List.of(
                                new ProfessionalStorageRow(
                                        "Ranged weapons equipped",
                                        Long.toString(totals.rangedWeaponsEquipped()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Arrows delivered",
                                        Long.toString(totals.arrowsDelivered()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Sticks delivered",
                                        Long.toString(totals.sticksDelivered()),
                                        ProfessionalStorageRow.Tone.NORMAL))));
    }

    static Optional<UUID> selectRepresentative(List<FletcherWorkerView> workers) {
        return workers.stream()
                .filter(FletcherWorkerView::loaded)
                .map(FletcherWorkerView::workerUuid)
                .min(UUID::compareTo);
    }

    static FletcherStorageCounts countStorageViews(List<FletcherStorageStackView> stacks) {
        long arrows = 0L;
        long rangedWeapons = 0L;
        for (FletcherStorageStackView stack : stacks) {
            if (stack.count() <= 0L) {
                continue;
            }
            if (stack.arrow()) {
                arrows = saturatingAdd(arrows, stack.count());
            }
            if (stack.rangedWeapon()) {
                rangedWeapons = saturatingAdd(rangedWeapons, stack.count());
            }
        }
        return new FletcherStorageCounts(arrows, rangedWeapons);
    }

    static FletcherStorageCounts countResolvedStorageContents(
            Supplier<List<FletcherStorageStackView>> resolvedContents
    ) {
        return countStorageViews(resolvedContents.get());
    }

    private static List<FletcherStorageStackView> storageViews(Inventory inventory) {
        List<FletcherStorageStackView> views = new ArrayList<>(inventory.size());
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            views.add(new FletcherStorageStackView(
                    !stack.isEmpty() && FletcherDistributionGoal.isArrow(stack),
                    !stack.isEmpty() && FletcherDistributionGoal.isRangedWeapon(stack),
                    stack.getCount()));
        }
        return views;
    }

    private static TableDisplay tableDisplay(List<FletcherWorkerView> workers) {
        List<FletcherWorkerView> loaded = workers.stream().filter(FletcherWorkerView::loaded).toList();
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

    record FletcherWorkerView(
            UUID workerUuid,
            ProfessionalStorageResolution.WorkerAvailability availability,
            @Nullable Boolean craftingTableReady,
            @Nullable Integer craftableOutputs
    ) {
        boolean loaded() {
            return availability == ProfessionalStorageResolution.WorkerAvailability.LOADED;
        }
    }

    record FletcherStorageStackView(boolean arrow, boolean rangedWeapon, long count) {
    }

    record FletcherStorageCounts(long arrows, long rangedWeapons) {
        FletcherStorageCounts {
            arrows = Math.max(0L, arrows);
            rangedWeapons = Math.max(0L, rangedWeapons);
        }
    }

    record FletcherCareerTotals(
            long goodsCrafted,
            long rangedWeaponsEquipped,
            long arrowsDelivered,
            long sticksDelivered
    ) {
        FletcherCareerTotals {
            if (goodsCrafted < 0L
                    || rangedWeaponsEquipped < 0L
                    || arrowsDelivered < 0L
                    || sticksDelivered < 0L) {
                throw new IllegalArgumentException("Fletcher career totals must be nonnegative");
            }
        }
    }

    private record TableDisplay(String value, ProfessionalStorageRow.Tone tone) {
    }

    private record MeasuredDisplay(String value, ProfessionalStorageRow.Tone tone) {
    }
}
