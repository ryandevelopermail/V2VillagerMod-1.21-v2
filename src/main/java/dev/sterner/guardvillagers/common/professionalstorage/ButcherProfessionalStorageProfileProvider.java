package dev.sterner.guardvillagers.common.professionalstorage;

import dev.sterner.guardvillagers.common.entity.goal.ButcherMeatDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.ButcherSmokerGoal;
import dev.sterner.guardvillagers.common.entity.goal.ButcherToLeatherworkerDistributionGoal;
import dev.sterner.guardvillagers.common.villager.behavior.ButcherBehavior;
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

/** Four-tab native-Butcher profile assembled from one read-only open-time snapshot. */
public final class ButcherProfessionalStorageProfileProvider implements ProfessionalStorageProfileProvider {
    @Override
    public Optional<List<ProfessionalStorageTab>> createTabs(
            ServerWorld world,
            StorageIdentity storage,
            List<ProfessionalStorageResolution> resolutions
    ) {
        if (resolutions.isEmpty()
                || resolutions.stream().anyMatch(resolution ->
                        !resolution.pairing().role().equals(ButcherWorkMetrics.BUTCHER_ROLE))) {
            return Optional.empty();
        }
        Inventory inventory = resolveStorageInventory(world, storage);
        if (inventory == null) {
            return Optional.empty();
        }

        ButcherStorageCounts storageCounts = countResolvedStorageContents(
                () -> storageViews(world, inventory));
        List<ButcherWorkerView> workers = new ArrayList<>(resolutions.size());
        for (ProfessionalStorageResolution resolution : resolutions) {
            UUID workerUuid = resolution.pairing().workerUuid();
            if (resolution.workerAvailability() == ProfessionalStorageResolution.WorkerAvailability.UNLOADED) {
                workers.add(ButcherWorkerView.unloaded(workerUuid, resolution.workerAvailability()));
                continue;
            }
            Entity entity = world.getEntity(workerUuid);
            if (!(entity instanceof VillagerEntity villager)) {
                return Optional.empty();
            }
            Optional<ButcherBehavior.ButcherLiveSnapshot> live =
                    ButcherBehavior.getLiveStorageSnapshot(world, villager, inventory);
            if (live.isEmpty()) {
                return Optional.empty();
            }
            workers.add(new ButcherWorkerView(
                    workerUuid,
                    resolution.workerAvailability(),
                    live.get().craftingTableReady(),
                    live.get().smokerReady(),
                    live.get().smokerState(),
                    live.get().craftableSmokers()));
        }

        UUID representativeUuid = selectRepresentative(workers).orElse(null);
        ButcherWorkerView representative = workers.stream()
                .filter(worker -> worker.loaded() && worker.workerUuid().equals(representativeUuid))
                .findFirst()
                .orElse(null);
        List<UUID> workerUuids = resolutions.stream()
                .map(resolution -> resolution.pairing().workerUuid())
                .toList();
        ButcherCareerTotals totals = aggregateCareerTotals(
                ProfessionalWorkStatsState.get(world.getServer()),
                workerUuids,
                ButcherWorkMetrics.BUTCHER_ROLE);
        return Optional.of(buildTabs(
                workers,
                storageCounts,
                representative == null ? null : representative.smokerState(),
                representative == null ? null : representative.craftableSmokers(),
                totals));
    }

    static ButcherCareerTotals aggregateCareerTotals(
            ProfessionalWorkStatsState stats,
            List<UUID> workerUuids,
            ProfessionalRoleId role
    ) {
        return new ButcherCareerTotals(
                stats.aggregate(workerUuids, role, ButcherWorkMetrics.COOKED_FOOD_COLLECTED),
                stats.aggregate(workerUuids, role, ButcherWorkMetrics.SMOKERS_CRAFTED),
                stats.aggregate(workerUuids, role, ButcherWorkMetrics.MEALS_DELIVERED),
                stats.aggregate(workerUuids, role, ButcherWorkMetrics.LEATHER_HIDE_DELIVERED));
    }

    static List<ProfessionalStorageTab> buildTabs(
            List<ButcherWorkerView> workers,
            ButcherStorageCounts storage,
            @Nullable ButcherSmokerGoal.SmokerState smokerState,
            @Nullable Integer craftableSmokers,
            ButcherCareerTotals totals
    ) {
        boolean unavailable = workers.stream().anyMatch(worker -> !worker.loaded());
        WorksiteDisplay table = worksiteDisplay(workers, true);
        WorksiteDisplay smoker = worksiteDisplay(workers, false);
        MeasuredDisplay smokerStateDisplay = smokerStateDisplay(smokerState, unavailable);
        MeasuredDisplay craftable = measuredDisplay(craftableSmokers, unavailable);
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
                                new ProfessionalStorageRow("Smoker", smoker.value(), smoker.tone()))),
                new ProfessionalStorageTab(
                        "cooking",
                        "Cooking",
                        List.of(
                                new ProfessionalStorageRow(
                                        "Smoker state",
                                        smokerStateDisplay.value(),
                                        smokerStateDisplay.tone()),
                                new ProfessionalStorageRow(
                                        "Smokable food",
                                        Long.toString(storage.smokableFood()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Fuel stock",
                                        Long.toString(storage.fuelStock()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Cooked food collected",
                                        Long.toString(totals.cookedFoodCollected()),
                                        ProfessionalStorageRow.Tone.NORMAL))),
                new ProfessionalStorageTab(
                        "crafting",
                        "Crafting",
                        List.of(
                                new ProfessionalStorageRow(
                                        "Craftable smokers",
                                        craftable.value(),
                                        craftable.tone()),
                                new ProfessionalStorageRow(
                                        "Smokers crafted",
                                        Long.toString(totals.smokersCrafted()),
                                        ProfessionalStorageRow.Tone.NORMAL))),
                new ProfessionalStorageTab(
                        "distribution",
                        "Distribution",
                        List.of(
                                new ProfessionalStorageRow(
                                        "Meals awaiting",
                                        Long.toString(storage.mealsAwaiting()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Meals delivered",
                                        Long.toString(totals.mealsDelivered()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Leather/hide stock",
                                        Long.toString(storage.leatherHideStock()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Leather/hide delivered",
                                        Long.toString(totals.leatherHideDelivered()),
                                        ProfessionalStorageRow.Tone.NORMAL))));
    }

    static Optional<UUID> selectRepresentative(List<ButcherWorkerView> workers) {
        return workers.stream()
                .filter(ButcherWorkerView::loaded)
                .map(ButcherWorkerView::workerUuid)
                .min(UUID::compareTo);
    }

    static ButcherStorageCounts countStorageViews(List<ButcherStorageStackView> stacks) {
        long smokable = 0L;
        long fuel = 0L;
        long meals = 0L;
        long leatherHide = 0L;
        for (ButcherStorageStackView stack : stacks) {
            if (stack.count() <= 0L) {
                continue;
            }
            if (stack.smokable()) {
                smokable = saturatingAdd(smokable, stack.count());
            }
            if (stack.fuel()) {
                fuel = saturatingAdd(fuel, stack.count());
            }
            if (stack.meal()) {
                meals = saturatingAdd(meals, stack.count());
            }
            if (stack.leatherOrHide()) {
                leatherHide = saturatingAdd(leatherHide, stack.count());
            }
        }
        return new ButcherStorageCounts(smokable, fuel, meals, leatherHide);
    }

    static ButcherStorageCounts countResolvedStorageContents(
            Supplier<List<ButcherStorageStackView>> resolvedContents
    ) {
        return countStorageViews(resolvedContents.get());
    }

    private static List<ButcherStorageStackView> storageViews(ServerWorld world, Inventory inventory) {
        List<ButcherStorageStackView> views = new ArrayList<>(inventory.size());
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            views.add(new ButcherStorageStackView(
                    ButcherSmokerGoal.isSmokableFood(world, stack),
                    ButcherSmokerGoal.isFuelStack(stack),
                    ButcherMeatDistributionGoal.isCookedMeat(stack),
                    ButcherToLeatherworkerDistributionGoal.isLeatherOrHide(stack),
                    stack.getCount()));
        }
        return views;
    }

    private static WorksiteDisplay worksiteDisplay(List<ButcherWorkerView> workers, boolean craftingTable) {
        List<ButcherWorkerView> loaded = workers.stream().filter(ButcherWorkerView::loaded).toList();
        if (loaded.isEmpty()) {
            return new WorksiteDisplay("Unknown", ProfessionalStorageRow.Tone.WARNING);
        }
        long ready = loaded.stream()
                .filter(worker -> Boolean.TRUE.equals(craftingTable
                        ? worker.craftingTableReady()
                        : worker.smokerReady()))
                .count();
        String value = loaded.size() == 1
                ? (ready == 1 ? "Yes" : "No")
                : ready + " / " + loaded.size() + " ready";
        if (loaded.size() < workers.size()) {
            return new WorksiteDisplay("Partial: " + value, ProfessionalStorageRow.Tone.WARNING);
        }
        return new WorksiteDisplay(
                value,
                ready == loaded.size() ? ProfessionalStorageRow.Tone.PAIRED : ProfessionalStorageRow.Tone.WARNING);
    }

    private static MeasuredDisplay measuredDisplay(@Nullable Integer value, boolean partial) {
        if (value == null) {
            return new MeasuredDisplay("Not measured", ProfessionalStorageRow.Tone.WARNING);
        }
        String displayed = Integer.toString(Math.max(0, value));
        return partial
                ? new MeasuredDisplay("Partial: " + displayed, ProfessionalStorageRow.Tone.WARNING)
                : new MeasuredDisplay(displayed, ProfessionalStorageRow.Tone.NORMAL);
    }

    private static MeasuredDisplay smokerStateDisplay(
            @Nullable ButcherSmokerGoal.SmokerState state,
            boolean partial
    ) {
        if (state == null) {
            return new MeasuredDisplay("Not measured", ProfessionalStorageRow.Tone.WARNING);
        }
        if (partial) {
            return new MeasuredDisplay("Partial: " + state.displayName(), ProfessionalStorageRow.Tone.WARNING);
        }
        ProfessionalStorageRow.Tone tone = switch (state) {
            case MISSING, NEEDS_FUEL -> ProfessionalStorageRow.Tone.WARNING;
            case OUTPUT_READY, SMOKING -> ProfessionalStorageRow.Tone.PAIRED;
            case LOADED, IDLE -> ProfessionalStorageRow.Tone.NORMAL;
        };
        return new MeasuredDisplay(state.displayName(), tone);
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

    record ButcherWorkerView(
            UUID workerUuid,
            ProfessionalStorageResolution.WorkerAvailability availability,
            @Nullable Boolean craftingTableReady,
            @Nullable Boolean smokerReady,
            @Nullable ButcherSmokerGoal.SmokerState smokerState,
            @Nullable Integer craftableSmokers
    ) {
        static ButcherWorkerView unloaded(
                UUID workerUuid,
                ProfessionalStorageResolution.WorkerAvailability availability
        ) {
            return new ButcherWorkerView(workerUuid, availability, null, null, null, null);
        }

        boolean loaded() {
            return availability == ProfessionalStorageResolution.WorkerAvailability.LOADED;
        }
    }

    record ButcherStorageStackView(
            boolean smokable,
            boolean fuel,
            boolean meal,
            boolean leatherOrHide,
            long count
    ) {
    }

    record ButcherStorageCounts(long smokableFood, long fuelStock, long mealsAwaiting, long leatherHideStock) {
        ButcherStorageCounts {
            smokableFood = Math.max(0L, smokableFood);
            fuelStock = Math.max(0L, fuelStock);
            mealsAwaiting = Math.max(0L, mealsAwaiting);
            leatherHideStock = Math.max(0L, leatherHideStock);
        }
    }

    record ButcherCareerTotals(
            long cookedFoodCollected,
            long smokersCrafted,
            long mealsDelivered,
            long leatherHideDelivered
    ) {
        ButcherCareerTotals {
            if (cookedFoodCollected < 0L
                    || smokersCrafted < 0L
                    || mealsDelivered < 0L
                    || leatherHideDelivered < 0L) {
                throw new IllegalArgumentException("Butcher career totals must be nonnegative");
            }
        }
    }

    private record WorksiteDisplay(String value, ProfessionalStorageRow.Tone tone) {
    }

    private record MeasuredDisplay(String value, ProfessionalStorageRow.Tone tone) {
    }
}
