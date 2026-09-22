package dev.sterner.guardvillagers.common.professionalstorage;

import dev.sterner.guardvillagers.common.entity.goal.ClericBrewingGoal;
import dev.sterner.guardvillagers.common.entity.goal.ClericDistributionGoal;
import dev.sterner.guardvillagers.common.villager.behavior.ClericBehavior;
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

/** Four-tab native-Cleric profile assembled from one read-only open-time snapshot. */
public final class ClericProfessionalStorageProfileProvider implements ProfessionalStorageProfileProvider {
    @Override
    public Optional<List<ProfessionalStorageTab>> createTabs(
            ServerWorld world,
            StorageIdentity storage,
            List<ProfessionalStorageResolution> resolutions
    ) {
        if (resolutions.isEmpty()
                || resolutions.stream().anyMatch(resolution ->
                        !resolution.pairing().role().equals(ClericWorkMetrics.CLERIC_ROLE))) {
            return Optional.empty();
        }
        Inventory inventory = resolveStorageInventory(world, storage);
        if (inventory == null) {
            return Optional.empty();
        }

        ClericStorageCounts storageCounts = countResolvedStorageContents(() -> storageViews(inventory));
        List<ClericWorkerView> workers = new ArrayList<>(resolutions.size());
        for (ProfessionalStorageResolution resolution : resolutions) {
            UUID workerUuid = resolution.pairing().workerUuid();
            if (resolution.workerAvailability() == ProfessionalStorageResolution.WorkerAvailability.UNLOADED) {
                workers.add(ClericWorkerView.unloaded(workerUuid, resolution.workerAvailability()));
                continue;
            }
            Entity entity = world.getEntity(workerUuid);
            if (!(entity instanceof VillagerEntity villager)) {
                return Optional.empty();
            }
            Optional<ClericBehavior.ClericLiveSnapshot> live = ClericBehavior.getLiveStorageSnapshot(
                    world,
                    villager,
                    inventory,
                    storageCounts.storedHealingReserve());
            if (live.isEmpty()) {
                return Optional.empty();
            }
            workers.add(new ClericWorkerView(
                    workerUuid,
                    resolution.workerAvailability(),
                    live.get().craftingTableReady(),
                    live.get().brewingStandReady(),
                    live.get().bottleStage(),
                    live.get().brewTarget(),
                    live.get().reachablePotions(),
                    live.get().craftableBrewingStands(),
                    live.get().healingReserve(),
                    live.get().injuredGuards()));
        }

        UUID representativeUuid = selectRepresentative(workers).orElse(null);
        ClericWorkerView representative = workers.stream()
                .filter(worker -> worker.loaded() && worker.workerUuid().equals(representativeUuid))
                .findFirst()
                .orElse(null);
        List<UUID> workerUuids = resolutions.stream()
                .map(resolution -> resolution.pairing().workerUuid())
                .toList();
        ClericCareerTotals totals = aggregateCareerTotals(
                ProfessionalWorkStatsState.get(world.getServer()),
                workerUuids,
                ClericWorkMetrics.CLERIC_ROLE);
        return Optional.of(buildTabs(
                workers,
                storageCounts,
                representative == null ? null : representative.bottleStage(),
                representative == null ? null : representative.brewTarget(),
                representative == null ? null : representative.reachablePotions(),
                representative == null ? null : representative.craftableBrewingStands(),
                representative == null ? null : representative.healingReserve(),
                representative == null ? null : representative.injuredGuards(),
                totals));
    }

    static ClericCareerTotals aggregateCareerTotals(
            ProfessionalWorkStatsState stats,
            List<UUID> workerUuids,
            ProfessionalRoleId role
    ) {
        return new ClericCareerTotals(
                stats.aggregate(workerUuids, role, ClericWorkMetrics.POTIONS_COMPLETED),
                stats.aggregate(workerUuids, role, ClericWorkMetrics.BREWING_STANDS_CRAFTED),
                stats.aggregate(workerUuids, role, ClericWorkMetrics.HEALING_POTIONS_THROWN),
                stats.aggregate(workerUuids, role, ClericWorkMetrics.POTIONS_DELIVERED));
    }

    static List<ProfessionalStorageTab> buildTabs(
            List<ClericWorkerView> workers,
            ClericStorageCounts storage,
            @Nullable ClericBrewingGoal.BottleStage bottleStage,
            @Nullable String brewTarget,
            @Nullable Integer reachablePotions,
            @Nullable Integer craftableBrewingStands,
            @Nullable Long healingReserve,
            @Nullable Integer injuredGuards,
            ClericCareerTotals totals
    ) {
        boolean partial = workers.stream().anyMatch(worker -> !worker.loaded());
        WorksiteDisplay table = worksiteDisplay(workers, true);
        WorksiteDisplay stand = worksiteDisplay(workers, false);
        MeasuredDisplay bottle = bottleStageDisplay(bottleStage, partial);
        MeasuredDisplay target = textDisplay(brewTarget, partial);
        MeasuredDisplay reachable = numberDisplay(reachablePotions, partial);
        MeasuredDisplay craftable = numberDisplay(craftableBrewingStands, partial);
        MeasuredDisplay reserve = longDisplay(healingReserve, partial);
        MeasuredDisplay injured = numberDisplay(injuredGuards, partial);
        return List.of(
                new ProfessionalStorageTab(
                        "overview",
                        "Overview",
                        List.of(
                                new ProfessionalStorageRow(
                                        "Status",
                                        partial ? "Worker unavailable" : "Ready",
                                        partial
                                                ? ProfessionalStorageRow.Tone.WARNING
                                                : ProfessionalStorageRow.Tone.PAIRED),
                                new ProfessionalStorageRow("Crafting table", table.value(), table.tone()),
                                new ProfessionalStorageRow("Brewing stand", stand.value(), stand.tone()))),
                new ProfessionalStorageTab(
                        "brewing",
                        "Brewing",
                        List.of(
                                new ProfessionalStorageRow("Bottle stage", bottle.value(), bottle.tone()),
                                new ProfessionalStorageRow("Brew target", target.value(), target.tone()),
                                new ProfessionalStorageRow("Reachable potions", reachable.value(), reachable.tone()),
                                new ProfessionalStorageRow(
                                        "Potions in storage",
                                        Long.toString(storage.potionsInStorage()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Potions completed",
                                        Long.toString(totals.potionsCompleted()),
                                        ProfessionalStorageRow.Tone.NORMAL))),
                new ProfessionalStorageTab(
                        "crafting",
                        "Crafting",
                        List.of(
                                new ProfessionalStorageRow(
                                        "Craftable brewing stands",
                                        craftable.value(),
                                        craftable.tone()),
                                new ProfessionalStorageRow(
                                        "Brewing stands crafted",
                                        Long.toString(totals.brewingStandsCrafted()),
                                        ProfessionalStorageRow.Tone.NORMAL))),
                new ProfessionalStorageTab(
                        "support",
                        "Support",
                        List.of(
                                new ProfessionalStorageRow("Healing reserve", reserve.value(), reserve.tone()),
                                new ProfessionalStorageRow("Injured guards", injured.value(), injured.tone()),
                                new ProfessionalStorageRow(
                                        "Healing potions thrown",
                                        Long.toString(totals.healingPotionsThrown()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Potions awaiting delivery",
                                        Long.toString(storage.potionsAwaitingDelivery()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Potions delivered",
                                        Long.toString(totals.potionsDelivered()),
                                        ProfessionalStorageRow.Tone.NORMAL))));
    }

    static Optional<UUID> selectRepresentative(List<ClericWorkerView> workers) {
        return workers.stream()
                .filter(ClericWorkerView::loaded)
                .map(ClericWorkerView::workerUuid)
                .min(UUID::compareTo);
    }

    static ClericStorageCounts countStorageViews(List<ClericStorageStackView> stacks) {
        long potions = 0L;
        long healing = 0L;
        long other = 0L;
        for (ClericStorageStackView stack : stacks) {
            if (!stack.supportedPotion() || stack.count() <= 0L) {
                continue;
            }
            potions = saturatingAdd(potions, stack.count());
            if (stack.healingSplash()) {
                healing = saturatingAdd(healing, stack.count());
            } else {
                other = saturatingAdd(other, stack.count());
            }
        }
        return new ClericStorageCounts(
                potions,
                healing,
                ClericDistributionGoal.countAwaitingPotionUnits(other, healing));
    }

    static ClericStorageCounts countResolvedStorageContents(
            Supplier<List<ClericStorageStackView>> resolvedContents
    ) {
        return countStorageViews(resolvedContents.get());
    }

    private static List<ClericStorageStackView> storageViews(Inventory inventory) {
        List<ClericStorageStackView> views = new ArrayList<>(inventory.size());
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            views.add(new ClericStorageStackView(
                    ClericDistributionGoal.isSupportedPotion(stack),
                    ClericDistributionGoal.isHealingSplashPotion(stack),
                    stack.getCount()));
        }
        return views;
    }

    private static WorksiteDisplay worksiteDisplay(List<ClericWorkerView> workers, boolean craftingTable) {
        List<ClericWorkerView> loaded = workers.stream().filter(ClericWorkerView::loaded).toList();
        if (loaded.isEmpty()) {
            return new WorksiteDisplay("Unknown", ProfessionalStorageRow.Tone.WARNING);
        }
        long ready = loaded.stream()
                .filter(worker -> Boolean.TRUE.equals(craftingTable
                        ? worker.craftingTableReady()
                        : worker.brewingStandReady()))
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

    private static MeasuredDisplay bottleStageDisplay(
            @Nullable ClericBrewingGoal.BottleStage stage,
            boolean partial
    ) {
        if (stage == null) {
            return notMeasured();
        }
        if (partial) {
            return new MeasuredDisplay("Partial: " + stage.displayName(), ProfessionalStorageRow.Tone.WARNING);
        }
        ProfessionalStorageRow.Tone tone = switch (stage) {
            case MISSING, INVALID -> ProfessionalStorageRow.Tone.WARNING;
            case POTION_READY, SPLASH_READY -> ProfessionalStorageRow.Tone.PAIRED;
            case EMPTY, WATER_PARTIAL, WATER_READY, AWKWARD_READY -> ProfessionalStorageRow.Tone.NORMAL;
        };
        return new MeasuredDisplay(stage.displayName(), tone);
    }

    private static MeasuredDisplay textDisplay(@Nullable String value, boolean partial) {
        if (value == null) {
            return notMeasured();
        }
        return partial
                ? new MeasuredDisplay("Partial: " + value, ProfessionalStorageRow.Tone.WARNING)
                : new MeasuredDisplay(value, ProfessionalStorageRow.Tone.NORMAL);
    }

    private static MeasuredDisplay numberDisplay(@Nullable Integer value, boolean partial) {
        if (value == null) {
            return notMeasured();
        }
        return textDisplay(Integer.toString(Math.max(0, value)), partial);
    }

    private static MeasuredDisplay longDisplay(@Nullable Long value, boolean partial) {
        if (value == null) {
            return notMeasured();
        }
        return textDisplay(Long.toString(Math.max(0L, value)), partial);
    }

    private static MeasuredDisplay notMeasured() {
        return new MeasuredDisplay("Not measured", ProfessionalStorageRow.Tone.WARNING);
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

    record ClericWorkerView(
            UUID workerUuid,
            ProfessionalStorageResolution.WorkerAvailability availability,
            @Nullable Boolean craftingTableReady,
            @Nullable Boolean brewingStandReady,
            @Nullable ClericBrewingGoal.BottleStage bottleStage,
            @Nullable String brewTarget,
            @Nullable Integer reachablePotions,
            @Nullable Integer craftableBrewingStands,
            @Nullable Long healingReserve,
            @Nullable Integer injuredGuards
    ) {
        static ClericWorkerView unloaded(
                UUID workerUuid,
                ProfessionalStorageResolution.WorkerAvailability availability
        ) {
            return new ClericWorkerView(
                    workerUuid,
                    availability,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        boolean loaded() {
            return availability == ProfessionalStorageResolution.WorkerAvailability.LOADED;
        }
    }

    record ClericStorageStackView(boolean supportedPotion, boolean healingSplash, long count) {
    }

    record ClericStorageCounts(
            long potionsInStorage,
            long storedHealingReserve,
            long potionsAwaitingDelivery
    ) {
        ClericStorageCounts {
            potionsInStorage = Math.max(0L, potionsInStorage);
            storedHealingReserve = Math.max(0L, storedHealingReserve);
            potionsAwaitingDelivery = Math.max(0L, potionsAwaitingDelivery);
        }
    }

    record ClericCareerTotals(
            long potionsCompleted,
            long brewingStandsCrafted,
            long healingPotionsThrown,
            long potionsDelivered
    ) {
        ClericCareerTotals {
            if (potionsCompleted < 0L
                    || brewingStandsCrafted < 0L
                    || healingPotionsThrown < 0L
                    || potionsDelivered < 0L) {
                throw new IllegalArgumentException("Cleric career totals must be nonnegative");
            }
        }
    }

    private record WorksiteDisplay(String value, ProfessionalStorageRow.Tone tone) {
    }

    private record MeasuredDisplay(String value, ProfessionalStorageRow.Tone tone) {
    }
}
