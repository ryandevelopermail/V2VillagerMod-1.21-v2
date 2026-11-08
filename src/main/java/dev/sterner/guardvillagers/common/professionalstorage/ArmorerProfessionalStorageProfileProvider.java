package dev.sterner.guardvillagers.common.professionalstorage;

import dev.sterner.guardvillagers.common.entity.goal.ArmorerBlastFurnaceGoal;
import dev.sterner.guardvillagers.common.entity.goal.ArmorerDistributionGoal;
import dev.sterner.guardvillagers.common.villager.behavior.ArmorerBehavior;
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

/** Four-tab Armorer profile assembled from one read-only open-time snapshot. */
public final class ArmorerProfessionalStorageProfileProvider implements ProfessionalStorageProfileProvider {
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

        ArmorerStorageCounts storageCounts = countResolvedStorageContents(
                () -> storageViews(world, inventory));
        List<ArmorerWorkerView> workers = new ArrayList<>(resolutions.size());
        for (ProfessionalStorageResolution resolution : resolutions) {
            UUID workerUuid = resolution.pairing().workerUuid();
            if (resolution.workerAvailability() == ProfessionalStorageResolution.WorkerAvailability.UNLOADED) {
                workers.add(ArmorerWorkerView.unloaded(workerUuid, resolution.workerAvailability()));
                continue;
            }
            Entity entity = world.getEntity(workerUuid);
            if (!(entity instanceof VillagerEntity villager)) {
                return Optional.empty();
            }
            Optional<ArmorerBehavior.ArmorerLiveSnapshot> live =
                    ArmorerBehavior.getLiveStorageSnapshot(world, villager, inventory);
            if (live.isEmpty()) {
                return Optional.empty();
            }
            workers.add(new ArmorerWorkerView(
                    workerUuid,
                    resolution.workerAvailability(),
                    live.get().craftingTableReady(),
                    live.get().blastFurnaceReady(),
                    live.get().furnaceState(),
                    live.get().craftableArmor(),
                    live.get().eligibleArmorSlots()));
        }

        UUID representativeUuid = selectRepresentative(workers).orElse(null);
        ArmorerWorkerView representative = workers.stream()
                .filter(worker -> worker.loaded() && worker.workerUuid().equals(representativeUuid))
                .findFirst()
                .orElse(null);
        List<UUID> workerUuids = resolutions.stream()
                .map(resolution -> resolution.pairing().workerUuid())
                .toList();
        ArmorerCareerTotals totals = aggregateCareerTotals(
                ProfessionalWorkStatsState.get(world.getServer()),
                workerUuids,
                resolutions.getFirst().pairing().role());
        return Optional.of(buildTabs(
                workers,
                storageCounts,
                representative == null ? null : representative.furnaceState(),
                representative == null ? null : representative.craftableArmor(),
                representative == null ? null : representative.eligibleArmorSlots(),
                totals));
    }

    static ArmorerCareerTotals aggregateCareerTotals(
            ProfessionalWorkStatsState stats,
            List<UUID> workerUuids,
            ProfessionalRoleId role
    ) {
        return new ArmorerCareerTotals(
                stats.aggregate(workerUuids, role, ArmorerWorkMetrics.ARMOR_CRAFTED),
                stats.aggregate(workerUuids, role, ArmorerWorkMetrics.SMELTED_OUTPUT_COLLECTED),
                stats.aggregate(workerUuids, role, ArmorerWorkMetrics.ARMOR_EQUIPPED));
    }

    static List<ProfessionalStorageTab> buildTabs(
            List<ArmorerWorkerView> workers,
            ArmorerStorageCounts storage,
            @Nullable ArmorerBlastFurnaceGoal.FurnaceState furnaceState,
            @Nullable Integer craftableArmor,
            @Nullable Integer eligibleArmorSlots,
            ArmorerCareerTotals totals
    ) {
        boolean unavailable = workers.stream().anyMatch(worker -> !worker.loaded());
        WorksiteDisplay table = worksiteDisplay(workers, true);
        WorksiteDisplay furnace = worksiteDisplay(workers, false);
        MeasuredDisplay furnaceStateDisplay = furnaceStateDisplay(furnaceState, unavailable);
        MeasuredDisplay craftable = measuredDisplay(craftableArmor, unavailable);
        MeasuredDisplay eligible = measuredDisplay(eligibleArmorSlots, unavailable);
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
                                new ProfessionalStorageRow("Blast furnace", furnace.value(), furnace.tone()))),
                new ProfessionalStorageTab(
                        "smelting",
                        "Smelting",
                        List.of(
                                new ProfessionalStorageRow(
                                        "Furnace state",
                                        furnaceStateDisplay.value(),
                                        furnaceStateDisplay.tone()),
                                new ProfessionalStorageRow(
                                        "Processable materials",
                                        Long.toString(storage.processableMaterials()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Fuel stock",
                                        Long.toString(storage.fuelStock()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Smelted output collected",
                                        Long.toString(totals.smeltedOutputCollected()),
                                        ProfessionalStorageRow.Tone.NORMAL))),
                new ProfessionalStorageTab(
                        "crafting",
                        "Crafting",
                        List.of(
                                new ProfessionalStorageRow(
                                        "Craftable armor",
                                        craftable.value(),
                                        craftable.tone()),
                                new ProfessionalStorageRow(
                                        "Armor crafted",
                                        Long.toString(totals.armorCrafted()),
                                        ProfessionalStorageRow.Tone.NORMAL))),
                new ProfessionalStorageTab(
                        "distribution",
                        "Distribution",
                        List.of(
                                new ProfessionalStorageRow(
                                        "Armor awaiting placement",
                                        Long.toString(storage.armorAwaitingPlacement()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Eligible armor slots",
                                        eligible.value(),
                                        eligible.tone()),
                                new ProfessionalStorageRow(
                                        "Armor equipped",
                                        Long.toString(totals.armorEquipped()),
                                        ProfessionalStorageRow.Tone.NORMAL))));
    }

    static Optional<UUID> selectRepresentative(List<ArmorerWorkerView> workers) {
        return workers.stream()
                .filter(ArmorerWorkerView::loaded)
                .map(ArmorerWorkerView::workerUuid)
                .min(UUID::compareTo);
    }

    static ArmorerStorageCounts countStorageViews(List<ArmorerStorageStackView> stacks) {
        long processable = 0L;
        long fuel = 0L;
        long armor = 0L;
        for (ArmorerStorageStackView stack : stacks) {
            if (stack.count() <= 0L) {
                continue;
            }
            if (stack.processable()) {
                processable = saturatingAdd(processable, stack.count());
            }
            if (stack.fuel()) {
                fuel = saturatingAdd(fuel, stack.count());
            }
            if (stack.armor()) {
                armor = saturatingAdd(armor, stack.count());
            }
        }
        return new ArmorerStorageCounts(processable, fuel, armor);
    }

    static ArmorerStorageCounts countResolvedStorageContents(
            Supplier<List<ArmorerStorageStackView>> resolvedContents
    ) {
        return countStorageViews(resolvedContents.get());
    }

    private static List<ArmorerStorageStackView> storageViews(ServerWorld world, Inventory inventory) {
        List<ArmorerStorageStackView> views = new ArrayList<>(inventory.size());
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            views.add(new ArmorerStorageStackView(
                    ArmorerBlastFurnaceGoal.isProcessableMaterial(world, stack),
                    ArmorerBlastFurnaceGoal.isFuelStack(stack),
                    ArmorerDistributionGoal.isDistributableArmor(stack),
                    stack.getCount()));
        }
        return views;
    }

    private static WorksiteDisplay worksiteDisplay(List<ArmorerWorkerView> workers, boolean craftingTable) {
        List<ArmorerWorkerView> loaded = workers.stream().filter(ArmorerWorkerView::loaded).toList();
        if (loaded.isEmpty()) {
            return new WorksiteDisplay("Unknown", ProfessionalStorageRow.Tone.WARNING);
        }
        long ready = loaded.stream()
                .filter(worker -> Boolean.TRUE.equals(
                        craftingTable ? worker.craftingTableReady() : worker.blastFurnaceReady()))
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

    private static MeasuredDisplay furnaceStateDisplay(
            @Nullable ArmorerBlastFurnaceGoal.FurnaceState state,
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
            case OUTPUT_READY, SMELTING -> ProfessionalStorageRow.Tone.PAIRED;
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

    record ArmorerWorkerView(
            UUID workerUuid,
            ProfessionalStorageResolution.WorkerAvailability availability,
            @Nullable Boolean craftingTableReady,
            @Nullable Boolean blastFurnaceReady,
            @Nullable ArmorerBlastFurnaceGoal.FurnaceState furnaceState,
            @Nullable Integer craftableArmor,
            @Nullable Integer eligibleArmorSlots
    ) {
        static ArmorerWorkerView unloaded(
                UUID workerUuid,
                ProfessionalStorageResolution.WorkerAvailability availability
        ) {
            return new ArmorerWorkerView(workerUuid, availability, null, null, null, null, null);
        }

        boolean loaded() {
            return availability == ProfessionalStorageResolution.WorkerAvailability.LOADED;
        }
    }

    record ArmorerStorageStackView(boolean processable, boolean fuel, boolean armor, long count) {
    }

    record ArmorerStorageCounts(long processableMaterials, long fuelStock, long armorAwaitingPlacement) {
        ArmorerStorageCounts {
            processableMaterials = Math.max(0L, processableMaterials);
            fuelStock = Math.max(0L, fuelStock);
            armorAwaitingPlacement = Math.max(0L, armorAwaitingPlacement);
        }
    }

    record ArmorerCareerTotals(long armorCrafted, long smeltedOutputCollected, long armorEquipped) {
        ArmorerCareerTotals {
            if (armorCrafted < 0L || smeltedOutputCollected < 0L || armorEquipped < 0L) {
                throw new IllegalArgumentException("Armorer career totals must be nonnegative");
            }
        }
    }

    private record WorksiteDisplay(String value, ProfessionalStorageRow.Tone tone) {
    }

    private record MeasuredDisplay(String value, ProfessionalStorageRow.Tone tone) {
    }
}
