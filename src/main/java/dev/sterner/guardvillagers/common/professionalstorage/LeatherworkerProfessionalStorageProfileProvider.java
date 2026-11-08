package dev.sterner.guardvillagers.common.professionalstorage;

import dev.sterner.guardvillagers.common.entity.goal.LeatherworkerDistributionGoal;
import dev.sterner.guardvillagers.common.villager.behavior.LeatherworkerBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Three-tab Leatherworker profile assembled from one read-only open-time snapshot. */
public final class LeatherworkerProfessionalStorageProfileProvider implements ProfessionalStorageProfileProvider {
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

        LeatherworkerStorageCounts storageCounts =
                countResolvedStorageContents(() -> storageViews(inventory));
        List<LeatherworkerWorkerView> workers = new ArrayList<>(resolutions.size());
        for (ProfessionalStorageResolution resolution : resolutions) {
            UUID workerUuid = resolution.pairing().workerUuid();
            if (resolution.workerAvailability() == ProfessionalStorageResolution.WorkerAvailability.UNLOADED) {
                workers.add(new LeatherworkerWorkerView(
                        workerUuid, resolution.workerAvailability(), null, null, null));
                continue;
            }
            Entity entity = world.getEntity(workerUuid);
            if (!(entity instanceof VillagerEntity villager)) {
                return Optional.empty();
            }
            Optional<LeatherworkerBehavior.LeatherworkerLiveSnapshot> live =
                    LeatherworkerBehavior.getLiveStorageSnapshot(world, villager, inventory);
            if (live.isEmpty()) {
                return Optional.empty();
            }
            workers.add(new LeatherworkerWorkerView(
                    workerUuid,
                    resolution.workerAvailability(),
                    live.get().craftingTableReady(),
                    live.get().craftableRecipes(),
                    live.get().frameDemand()));
        }

        UUID representativeUuid = selectRepresentative(workers).orElse(null);
        LeatherworkerWorkerView representative = workers.stream()
                .filter(worker -> worker.loaded() && worker.workerUuid().equals(representativeUuid))
                .findFirst()
                .orElse(null);
        Integer craftableRecipes = representative == null ? null : representative.craftableRecipes();
        Integer frameDemand = representative == null ? null : representative.frameDemand();
        List<UUID> workerUuids = resolutions.stream()
                .map(resolution -> resolution.pairing().workerUuid())
                .toList();
        LeatherworkerCareerTotals totals = aggregateCareerTotals(
                ProfessionalWorkStatsState.get(world.getServer()),
                workerUuids,
                resolutions.getFirst().pairing().role());
        return Optional.of(buildTabs(
                workers,
                storageCounts,
                craftableRecipes,
                frameDemand,
                totals));
    }

    static LeatherworkerCareerTotals aggregateCareerTotals(
            ProfessionalWorkStatsState stats,
            List<UUID> workerUuids,
            ProfessionalRoleId role
    ) {
        return new LeatherworkerCareerTotals(
                stats.aggregate(workerUuids, role, LeatherworkerWorkMetrics.LEATHER_GOODS_CRAFTED),
                stats.aggregate(workerUuids, role, LeatherworkerWorkMetrics.GOODS_DELIVERED));
    }

    static List<ProfessionalStorageTab> buildTabs(
            List<LeatherworkerWorkerView> workers,
            LeatherworkerStorageCounts storage,
            @Nullable Integer craftableRecipes,
            @Nullable Integer frameDemand,
            LeatherworkerCareerTotals totals
    ) {
        boolean unavailable = workers.stream().anyMatch(worker -> !worker.loaded());
        TableDisplay table = tableDisplay(workers);
        MeasuredDisplay craftable = measuredDisplay(craftableRecipes, false);
        MeasuredDisplay demand = measuredDisplay(frameDemand, unavailable && frameDemand != null);
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
                                        "Leather stock",
                                        Long.toString(storage.leatherStock()),
                                        ProfessionalStorageRow.Tone.NORMAL))),
                new ProfessionalStorageTab(
                        "crafting",
                        "Crafting",
                        List.of(
                                new ProfessionalStorageRow(
                                        "Craftable recipes",
                                        craftable.value(),
                                        craftable.tone()),
                                new ProfessionalStorageRow(
                                        "Frame demand",
                                        demand.value(),
                                        demand.tone()),
                                new ProfessionalStorageRow(
                                        "Leather goods crafted",
                                        Long.toString(totals.goodsCrafted()),
                                        ProfessionalStorageRow.Tone.NORMAL))),
                new ProfessionalStorageTab(
                        "distribution",
                        "Distribution",
                        List.of(
                                new ProfessionalStorageRow(
                                        "Goods awaiting delivery",
                                        Long.toString(storage.goodsAwaitingDelivery()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Goods delivered",
                                        Long.toString(totals.goodsDelivered()),
                                        ProfessionalStorageRow.Tone.NORMAL))));
    }

    static Optional<UUID> selectRepresentative(List<LeatherworkerWorkerView> workers) {
        return workers.stream()
                .filter(LeatherworkerWorkerView::loaded)
                .map(LeatherworkerWorkerView::workerUuid)
                .min(UUID::compareTo);
    }

    static LeatherworkerStorageCounts countStorageViews(List<LeatherworkerStorageStackView> stacks) {
        long leather = 0L;
        long awaiting = 0L;
        for (LeatherworkerStorageStackView stack : stacks) {
            if (stack.count() <= 0L) {
                continue;
            }
            if (stack.leather()) {
                leather = saturatingAdd(leather, stack.count());
            }
            if (stack.distributable()) {
                awaiting = saturatingAdd(awaiting, stack.count());
            }
        }
        return new LeatherworkerStorageCounts(leather, awaiting);
    }

    static LeatherworkerStorageCounts countResolvedStorageContents(
            Supplier<List<LeatherworkerStorageStackView>> resolvedContents
    ) {
        return countStorageViews(resolvedContents.get());
    }

    private static List<LeatherworkerStorageStackView> storageViews(Inventory inventory) {
        List<LeatherworkerStorageStackView> views = new ArrayList<>(inventory.size());
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            views.add(new LeatherworkerStorageStackView(
                    !stack.isEmpty() && stack.isOf(Items.LEATHER),
                    LeatherworkerDistributionGoal.isSupportedDistributionGood(stack),
                    stack.getCount()));
        }
        return views;
    }

    private static TableDisplay tableDisplay(List<LeatherworkerWorkerView> workers) {
        List<LeatherworkerWorkerView> loaded = workers.stream().filter(LeatherworkerWorkerView::loaded).toList();
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

    private static MeasuredDisplay measuredDisplay(@Nullable Integer value, boolean partial) {
        if (value == null) {
            return new MeasuredDisplay("Not measured", ProfessionalStorageRow.Tone.WARNING);
        }
        String displayed = Integer.toString(Math.max(0, value));
        if (partial) {
            return new MeasuredDisplay("Partial: " + displayed, ProfessionalStorageRow.Tone.WARNING);
        }
        return new MeasuredDisplay(displayed, ProfessionalStorageRow.Tone.NORMAL);
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

    record LeatherworkerWorkerView(
            UUID workerUuid,
            ProfessionalStorageResolution.WorkerAvailability availability,
            @Nullable Boolean craftingTableReady,
            @Nullable Integer craftableRecipes,
            @Nullable Integer frameDemand
    ) {
        boolean loaded() {
            return availability == ProfessionalStorageResolution.WorkerAvailability.LOADED;
        }
    }

    record LeatherworkerStorageStackView(boolean leather, boolean distributable, long count) {
    }

    record LeatherworkerStorageCounts(long leatherStock, long goodsAwaitingDelivery) {
        LeatherworkerStorageCounts {
            leatherStock = Math.max(0L, leatherStock);
            goodsAwaitingDelivery = Math.max(0L, goodsAwaitingDelivery);
        }
    }

    record LeatherworkerCareerTotals(long goodsCrafted, long goodsDelivered) {
        LeatherworkerCareerTotals {
            if (goodsCrafted < 0L || goodsDelivered < 0L) {
                throw new IllegalArgumentException("Leatherworker career totals must be nonnegative");
            }
        }
    }

    private record TableDisplay(String value, ProfessionalStorageRow.Tone tone) {
    }

    private record MeasuredDisplay(String value, ProfessionalStorageRow.Tone tone) {
    }
}
