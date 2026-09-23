package dev.sterner.guardvillagers.common.professionalstorage;

import dev.sterner.guardvillagers.common.util.ToolsmithDemandPlanner;
import dev.sterner.guardvillagers.common.villager.behavior.ToolsmithBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.HoeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.ShearsItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Three-tab Toolsmith profile assembled from one read-only open-time snapshot. */
public final class ToolsmithProfessionalStorageProfileProvider implements ProfessionalStorageProfileProvider {
    @Override
    public Optional<List<ProfessionalStorageTab>> createTabs(
            ServerWorld world,
            StorageIdentity storage,
            List<ProfessionalStorageResolution> resolutions
    ) {
        if (resolutions.isEmpty()) {
            return Optional.empty();
        }
        Inventory storageInventory = resolveStorageInventory(world, storage);
        if (storageInventory == null) {
            return Optional.empty();
        }

        List<ToolsmithWorkerView> workers = new ArrayList<>(resolutions.size());
        List<LoadedWorker> loadedWorkers = new ArrayList<>();
        for (ProfessionalStorageResolution resolution : resolutions) {
            UUID workerUuid = resolution.pairing().workerUuid();
            if (resolution.workerAvailability() == ProfessionalStorageResolution.WorkerAvailability.UNLOADED) {
                workers.add(new ToolsmithWorkerView(workerUuid, resolution.workerAvailability(), null));
                continue;
            }
            Entity entity = world.getEntity(workerUuid);
            if (!(entity instanceof VillagerEntity villager)) {
                return Optional.empty();
            }
            Optional<ToolsmithBehavior.ToolsmithLiveSnapshot> live =
                    ToolsmithBehavior.getLiveStorageSnapshot(world, villager);
            if (live.isEmpty()) {
                return Optional.empty();
            }
            workers.add(new ToolsmithWorkerView(
                    workerUuid,
                    resolution.workerAvailability(),
                    live.get().craftingTableReady()));
            loadedWorkers.add(new LoadedWorker(workerUuid, villager));
        }

        UUID representativeUuid = selectRepresentative(workers).orElse(null);
        ToolsmithDemandView demand = loadedWorkers.stream()
                .filter(worker -> worker.workerUuid().equals(representativeUuid))
                .findFirst()
                .map(worker -> demandView(ToolsmithDemandPlanner.buildSnapshot(
                        world,
                        worker.villager(),
                        storageInventory)))
                .orElse(null);
        List<UUID> workerUuids = resolutions.stream()
                .map(resolution -> resolution.pairing().workerUuid())
                .toList();
        ToolsmithCareerTotals totals = aggregateCareerTotals(
                ProfessionalWorkStatsState.get(world.getServer()),
                workerUuids,
                resolutions.getFirst().pairing().role());
        return Optional.of(buildTabs(workers, countDisplayedTools(storageInventory), demand, totals));
    }

    static ToolsmithCareerTotals aggregateCareerTotals(
            ProfessionalWorkStatsState stats,
            List<UUID> workerUuids,
            ProfessionalRoleId role
    ) {
        return new ToolsmithCareerTotals(
                stats.aggregate(workerUuids, role, ToolsmithWorkMetrics.TOOLS_CRAFTED),
                stats.aggregate(workerUuids, role, ToolsmithWorkMetrics.SMITHING_JOBS_COMPLETED),
                stats.aggregate(workerUuids, role, ToolsmithWorkMetrics.TOOLS_DISTRIBUTED));
    }

    static List<ProfessionalStorageTab> buildTabs(
            List<ToolsmithWorkerView> workers,
            long toolsInStorage,
            @Nullable ToolsmithDemandView demand,
            ToolsmithCareerTotals totals
    ) {
        long loadedCount = workers.stream().filter(ToolsmithWorkerView::loaded).count();
        boolean unavailable = loadedCount < workers.size();
        int totalDemand = demand == null ? 0 : demand.total();

        String status;
        ProfessionalStorageRow.Tone statusTone;
        if (unavailable) {
            status = "Worker unavailable";
            statusTone = ProfessionalStorageRow.Tone.WARNING;
        } else if (totalDemand > 0) {
            status = "Demand pending";
            statusTone = ProfessionalStorageRow.Tone.WARNING;
        } else {
            status = "Ready";
            statusTone = ProfessionalStorageRow.Tone.PAIRED;
        }

        TableDisplay table = tableDisplay(workers);
        DemandDisplay displayedDemand = demandDisplay(demand, unavailable);
        return List.of(
                new ProfessionalStorageTab(
                        "overview",
                        "Overview",
                        List.of(
                                new ProfessionalStorageRow("Status", status, statusTone),
                                new ProfessionalStorageRow("Crafting table", table.value(), table.tone()),
                                new ProfessionalStorageRow(
                                        "Tools in storage",
                                        Long.toString(Math.max(0L, toolsInStorage)),
                                        ProfessionalStorageRow.Tone.NORMAL))),
                new ProfessionalStorageTab(
                        "crafting",
                        "Crafting",
                        List.of(
                                new ProfessionalStorageRow(
                                        "Tools crafted",
                                        Long.toString(totals.toolsCrafted()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Smithing jobs completed",
                                        Long.toString(totals.smithingJobsCompleted()),
                                        ProfessionalStorageRow.Tone.NORMAL))),
                new ProfessionalStorageTab(
                        "distribution",
                        "Distribution",
                        List.of(
                                new ProfessionalStorageRow(
                                        "Recipient demand",
                                        displayedDemand.total(),
                                        displayedDemand.totalTone()),
                                new ProfessionalStorageRow(
                                        "Pickaxes needed",
                                        displayedDemand.pickaxes(),
                                        displayedDemand.detailTone()),
                                new ProfessionalStorageRow(
                                        "Hoes needed",
                                        displayedDemand.hoes(),
                                        displayedDemand.detailTone()),
                                new ProfessionalStorageRow(
                                        "Shears needed",
                                        displayedDemand.shears(),
                                        displayedDemand.detailTone()),
                                new ProfessionalStorageRow(
                                        "Fishing rods needed",
                                        displayedDemand.fishingRods(),
                                        displayedDemand.detailTone()),
                                new ProfessionalStorageRow(
                                        "Tools distributed",
                                        Long.toString(totals.toolsDistributed()),
                                        ProfessionalStorageRow.Tone.NORMAL))));
    }

    static Optional<UUID> selectRepresentative(List<ToolsmithWorkerView> workers) {
        return workers.stream()
                .filter(ToolsmithWorkerView::loaded)
                .map(ToolsmithWorkerView::workerUuid)
                .min(UUID::compareTo);
    }

    static long countDisplayedTools(List<StorageStackView> stacks) {
        long count = 0L;
        for (StorageStackView stack : stacks) {
            if (stack.kind() != StorageToolKind.OTHER && stack.count() > 0) {
                count = saturatingAdd(count, stack.count());
            }
        }
        return count;
    }

    static long countResolvedStorageContents(Supplier<List<StorageStackView>> resolvedContents) {
        return countDisplayedTools(resolvedContents.get());
    }

    private static long countDisplayedTools(Inventory inventory) {
        return countResolvedStorageContents(() -> {
            List<StorageStackView> stacks = new ArrayList<>(inventory.size());
            for (int slot = 0; slot < inventory.size(); slot++) {
                ItemStack stack = inventory.getStack(slot);
                stacks.add(new StorageStackView(classify(stack), stack.getCount()));
            }
            return stacks;
        });
    }

    private static StorageToolKind classify(ItemStack stack) {
        return classifyToolShape(
                stack.getItem() instanceof PickaxeItem,
                stack.getItem() instanceof HoeItem,
                stack.getItem() instanceof ShearsItem,
                stack.isOf(Items.FISHING_ROD));
    }

    static StorageToolKind classifyToolShape(
            boolean pickaxe,
            boolean hoe,
            boolean shears,
            boolean fishingRod
    ) {
        if (pickaxe) {
            return StorageToolKind.PICKAXE;
        }
        if (hoe) {
            return StorageToolKind.HOE;
        }
        if (shears) {
            return StorageToolKind.SHEARS;
        }
        if (fishingRod) {
            return StorageToolKind.FISHING_ROD;
        }
        return StorageToolKind.OTHER;
    }

    private static ToolsmithDemandView demandView(ToolsmithDemandPlanner.DemandSnapshot snapshot) {
        return new ToolsmithDemandView(
                snapshot.deficitFor(ToolsmithDemandPlanner.ToolType.PICKAXE),
                snapshot.deficitFor(ToolsmithDemandPlanner.ToolType.HOE),
                snapshot.deficitFor(ToolsmithDemandPlanner.ToolType.SHEARS),
                snapshot.deficitFor(ToolsmithDemandPlanner.ToolType.FISHING_ROD));
    }

    private static TableDisplay tableDisplay(List<ToolsmithWorkerView> workers) {
        List<ToolsmithWorkerView> loaded = workers.stream().filter(ToolsmithWorkerView::loaded).toList();
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

    private static DemandDisplay demandDisplay(@Nullable ToolsmithDemandView demand, boolean unavailable) {
        if (demand == null) {
            return new DemandDisplay(
                    "Not measured", "Not measured", "Not measured", "Not measured", "Not measured",
                    ProfessionalStorageRow.Tone.WARNING,
                    ProfessionalStorageRow.Tone.WARNING);
        }
        String total = Integer.toString(demand.total());
        ProfessionalStorageRow.Tone totalTone = demand.total() > 0
                ? ProfessionalStorageRow.Tone.WARNING
                : ProfessionalStorageRow.Tone.NORMAL;
        if (unavailable) {
            total = "Partial: " + total;
            totalTone = ProfessionalStorageRow.Tone.WARNING;
        }
        return new DemandDisplay(
                total,
                Integer.toString(demand.pickaxes()),
                Integer.toString(demand.hoes()),
                Integer.toString(demand.shears()),
                Integer.toString(demand.fishingRods()),
                totalTone,
                ProfessionalStorageRow.Tone.NORMAL);
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

    record ToolsmithWorkerView(
            UUID workerUuid,
            ProfessionalStorageResolution.WorkerAvailability availability,
            @Nullable Boolean craftingTableReady
    ) {
        boolean loaded() {
            return availability == ProfessionalStorageResolution.WorkerAvailability.LOADED;
        }
    }

    record ToolsmithDemandView(int pickaxes, int hoes, int shears, int fishingRods) {
        ToolsmithDemandView {
            pickaxes = Math.max(0, pickaxes);
            hoes = Math.max(0, hoes);
            shears = Math.max(0, shears);
            fishingRods = Math.max(0, fishingRods);
        }

        int total() {
            long sum = (long) pickaxes + hoes + shears + fishingRods;
            return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
        }
    }

    record ToolsmithCareerTotals(long toolsCrafted, long smithingJobsCompleted, long toolsDistributed) {
        ToolsmithCareerTotals {
            if (toolsCrafted < 0L || smithingJobsCompleted < 0L || toolsDistributed < 0L) {
                throw new IllegalArgumentException("Toolsmith career totals must be nonnegative");
            }
        }
    }

    enum StorageToolKind {
        PICKAXE,
        HOE,
        SHEARS,
        FISHING_ROD,
        OTHER
    }

    record StorageStackView(StorageToolKind kind, long count) {
    }

    private record LoadedWorker(UUID workerUuid, VillagerEntity villager) {
    }

    private record TableDisplay(String value, ProfessionalStorageRow.Tone tone) {
    }

    private record DemandDisplay(
            String total,
            String pickaxes,
            String hoes,
            String shears,
            String fishingRods,
            ProfessionalStorageRow.Tone totalTone,
            ProfessionalStorageRow.Tone detailTone
    ) {
    }
}
