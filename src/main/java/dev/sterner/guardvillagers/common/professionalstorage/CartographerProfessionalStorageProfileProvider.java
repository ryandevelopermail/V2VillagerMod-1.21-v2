package dev.sterner.guardvillagers.common.professionalstorage;

import dev.sterner.guardvillagers.common.entity.goal.CartographerMapExplorationGoal;
import dev.sterner.guardvillagers.common.entity.goal.CartographerMapWallGoal;
import dev.sterner.guardvillagers.common.util.CartographerMapChestUtil;
import dev.sterner.guardvillagers.common.villager.behavior.CartographerBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Four-tab native-Cartographer profile assembled from one read-only open-time snapshot. */
public final class CartographerProfessionalStorageProfileProvider implements ProfessionalStorageProfileProvider {
    @Override
    public Optional<List<ProfessionalStorageTab>> createTabs(
            ServerWorld world,
            StorageIdentity storage,
            List<ProfessionalStorageResolution> resolutions
    ) {
        if (resolutions.isEmpty()
                || resolutions.stream().anyMatch(resolution ->
                        !resolution.pairing().role().equals(CartographerWorkMetrics.CARTOGRAPHER_ROLE))) {
            return Optional.empty();
        }
        Inventory inventory = resolveStorageInventory(world, storage);
        if (inventory == null) {
            return Optional.empty();
        }

        CartographerStorageCounts storageCounts = countResolvedStorageContents(
                () -> storageViews(world, inventory));
        List<CartographerWorkerView> workers = new ArrayList<>(resolutions.size());
        for (ProfessionalStorageResolution resolution : resolutions) {
            UUID workerUuid = resolution.pairing().workerUuid();
            if (resolution.workerAvailability() == ProfessionalStorageResolution.WorkerAvailability.UNLOADED) {
                workers.add(CartographerWorkerView.unloaded(workerUuid, resolution.workerAvailability()));
                continue;
            }
            Entity entity = world.getEntity(workerUuid);
            if (!(entity instanceof VillagerEntity villager)) {
                return Optional.empty();
            }
            Optional<CartographerBehavior.CartographerLiveSnapshot> live =
                    CartographerBehavior.getLiveStorageSnapshot(world, villager, inventory);
            if (live.isEmpty()) {
                return Optional.empty();
            }
            workers.add(new CartographerWorkerView(
                    workerUuid,
                    resolution.workerAvailability(),
                    live.get().craftingTableReady(),
                    live.get().cartographyTableReady(),
                    live.get().craftableRecipes()));
        }

        UUID representativeUuid = selectRepresentative(workers).orElse(null);
        CartographerWorkerView representative = workers.stream()
                .filter(worker -> worker.loaded() && worker.workerUuid().equals(representativeUuid))
                .findFirst()
                .orElse(null);
        List<UUID> workerUuids = resolutions.stream()
                .map(resolution -> resolution.pairing().workerUuid())
                .toList();
        CartographerCareerTotals totals = aggregateCareerTotals(
                ProfessionalWorkStatsState.get(world.getServer()),
                workerUuids,
                CartographerWorkMetrics.CARTOGRAPHER_ROLE);
        return Optional.of(buildTabs(
                workers,
                storageCounts,
                representative == null ? null : representative.craftableRecipes(),
                totals));
    }

    static CartographerCareerTotals aggregateCareerTotals(
            ProfessionalWorkStatsState stats,
            List<UUID> workerUuids,
            ProfessionalRoleId role
    ) {
        return new CartographerCareerTotals(
                stats.aggregate(workerUuids, role, CartographerWorkMetrics.MAPS_COMPLETED),
                stats.aggregate(workerUuids, role, CartographerWorkMetrics.MAPS_COPIED),
                stats.aggregate(workerUuids, role, CartographerWorkMetrics.MATERIALS_CRAFTED),
                stats.aggregate(workerUuids, role, CartographerWorkMetrics.MAPS_DISPLAYED));
    }

    static List<ProfessionalStorageTab> buildTabs(
            List<CartographerWorkerView> workers,
            CartographerStorageCounts storage,
            @Nullable Integer craftableRecipes,
            CartographerCareerTotals totals
    ) {
        boolean partial = workers.stream().anyMatch(worker -> !worker.loaded());
        WorksiteDisplay craftingTable = worksiteDisplay(workers, true);
        WorksiteDisplay cartographyTable = worksiteDisplay(workers, false);
        MeasuredDisplay craftable = numberDisplay(craftableRecipes, partial);
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
                                new ProfessionalStorageRow(
                                        "Crafting table",
                                        craftingTable.value(),
                                        craftingTable.tone()),
                                new ProfessionalStorageRow(
                                        "Cartography table",
                                        cartographyTable.value(),
                                        cartographyTable.tone()),
                                new ProfessionalStorageRow(
                                        "Cold protection",
                                        storage.coldProtection() ? "Yes" : "No",
                                        storage.coldProtection()
                                                ? ProfessionalStorageRow.Tone.PAIRED
                                                : ProfessionalStorageRow.Tone.NORMAL))),
                new ProfessionalStorageTab(
                        "exploration",
                        "Exploration",
                        List.of(
                                new ProfessionalStorageRow(
                                        "Empty map tokens",
                                        Long.toString(storage.emptyMapTokens()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Completed maps stored",
                                        Long.toString(storage.completedMapsStored()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Unique map regions",
                                        Integer.toString(storage.uniqueMapRegions()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Maps completed",
                                        Long.toString(totals.mapsCompleted()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Maps copied",
                                        Long.toString(totals.mapsCopied()),
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
                                        "Materials crafted",
                                        Long.toString(totals.materialsCrafted()),
                                        ProfessionalStorageRow.Tone.NORMAL))),
                new ProfessionalStorageTab(
                        "map_wall",
                        "Map Wall",
                        List.of(
                                new ProfessionalStorageRow(
                                        "Wall maps available",
                                        Long.toString(storage.wallMapsAvailable()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Item frames",
                                        Long.toString(storage.itemFrames()),
                                        ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Wall materials ready",
                                        storage.wallMaterialsReady() ? "Yes" : "No",
                                        storage.wallMaterialsReady()
                                                ? ProfessionalStorageRow.Tone.PAIRED
                                                : ProfessionalStorageRow.Tone.NORMAL),
                                new ProfessionalStorageRow(
                                        "Maps displayed",
                                        Long.toString(totals.mapsDisplayed()),
                                        ProfessionalStorageRow.Tone.NORMAL))));
    }

    static Optional<UUID> selectRepresentative(List<CartographerWorkerView> workers) {
        return workers.stream()
                .filter(CartographerWorkerView::loaded)
                .map(CartographerWorkerView::workerUuid)
                .min(UUID::compareTo);
    }

    static CartographerStorageCounts countStorageViews(List<CartographerStorageStackView> stacks) {
        boolean coldProtection = false;
        long emptyTokens = 0L;
        long completed = 0L;
        long wallMaps = 0L;
        long frames = 0L;
        Set<CartographerMapChestUtil.MapSignature> signatures = new HashSet<>();
        for (CartographerStorageStackView stack : stacks) {
            if (stack.count() <= 0L) {
                continue;
            }
            coldProtection |= stack.leatherBoots();
            if (stack.emptyMapToken()) {
                emptyTokens = saturatingAdd(emptyTokens, stack.count());
            }
            if (stack.completedMap()) {
                completed = saturatingAdd(completed, stack.count());
                if (stack.signature() != null) {
                    signatures.add(stack.signature());
                }
            }
            if (stack.wallMap()) {
                wallMaps = saturatingAdd(wallMaps, stack.count());
            }
            if (stack.itemFrame()) {
                frames = saturatingAdd(frames, stack.count());
            }
        }
        return new CartographerStorageCounts(
                coldProtection,
                emptyTokens,
                completed,
                CartographerMapChestUtil.countUniqueSignatures(List.copyOf(signatures)),
                wallMaps,
                frames,
                CartographerMapWallGoal.hasRequiredWallMaterials(wallMaps, frames));
    }

    static CartographerStorageCounts countResolvedStorageContents(
            Supplier<List<CartographerStorageStackView>> resolvedContents
    ) {
        return countStorageViews(resolvedContents.get());
    }

    private static List<CartographerStorageStackView> storageViews(ServerWorld world, Inventory inventory) {
        List<CartographerStorageStackView> views = new ArrayList<>(inventory.size());
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            MapState state = stack.isOf(Items.FILLED_MAP)
                    ? FilledMapItem.getMapState(stack, world)
                    : null;
            views.add(new CartographerStorageStackView(
                    CartographerMapExplorationGoal.isColdProtectionItem(stack),
                    CartographerMapExplorationGoal.isEmptyMap(stack, world),
                    stack.isOf(Items.FILLED_MAP) && state != null,
                    state == null ? null : CartographerMapChestUtil.signatureOf(state),
                    CartographerMapWallGoal.isWallMap(stack),
                    CartographerMapWallGoal.isWallItemFrame(stack),
                    stack.getCount()));
        }
        return views;
    }

    private static WorksiteDisplay worksiteDisplay(
            List<CartographerWorkerView> workers,
            boolean craftingTable
    ) {
        List<CartographerWorkerView> loaded = workers.stream().filter(CartographerWorkerView::loaded).toList();
        if (loaded.isEmpty()) {
            return new WorksiteDisplay("Unknown", ProfessionalStorageRow.Tone.WARNING);
        }
        long ready = loaded.stream()
                .filter(worker -> Boolean.TRUE.equals(craftingTable
                        ? worker.craftingTableReady()
                        : worker.cartographyTableReady()))
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

    private static MeasuredDisplay numberDisplay(@Nullable Integer value, boolean partial) {
        if (value == null) {
            return new MeasuredDisplay("Not measured", ProfessionalStorageRow.Tone.WARNING);
        }
        String displayed = Integer.toString(Math.max(0, value));
        return partial
                ? new MeasuredDisplay("Partial: " + displayed, ProfessionalStorageRow.Tone.WARNING)
                : new MeasuredDisplay(displayed, ProfessionalStorageRow.Tone.NORMAL);
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

    record CartographerWorkerView(
            UUID workerUuid,
            ProfessionalStorageResolution.WorkerAvailability availability,
            @Nullable Boolean craftingTableReady,
            @Nullable Boolean cartographyTableReady,
            @Nullable Integer craftableRecipes
    ) {
        static CartographerWorkerView unloaded(
                UUID workerUuid,
                ProfessionalStorageResolution.WorkerAvailability availability
        ) {
            return new CartographerWorkerView(workerUuid, availability, null, null, null);
        }

        boolean loaded() {
            return availability == ProfessionalStorageResolution.WorkerAvailability.LOADED;
        }
    }

    record CartographerStorageStackView(
            boolean leatherBoots,
            boolean emptyMapToken,
            boolean completedMap,
            @Nullable CartographerMapChestUtil.MapSignature signature,
            boolean wallMap,
            boolean itemFrame,
            long count
    ) {
    }

    record CartographerStorageCounts(
            boolean coldProtection,
            long emptyMapTokens,
            long completedMapsStored,
            int uniqueMapRegions,
            long wallMapsAvailable,
            long itemFrames,
            boolean wallMaterialsReady
    ) {
        CartographerStorageCounts {
            emptyMapTokens = Math.max(0L, emptyMapTokens);
            completedMapsStored = Math.max(0L, completedMapsStored);
            uniqueMapRegions = Math.max(0, uniqueMapRegions);
            wallMapsAvailable = Math.max(0L, wallMapsAvailable);
            itemFrames = Math.max(0L, itemFrames);
        }
    }

    record CartographerCareerTotals(
            long mapsCompleted,
            long mapsCopied,
            long materialsCrafted,
            long mapsDisplayed
    ) {
        CartographerCareerTotals {
            if (mapsCompleted < 0L || mapsCopied < 0L || materialsCrafted < 0L || mapsDisplayed < 0L) {
                throw new IllegalArgumentException("Cartographer career totals must be nonnegative");
            }
        }
    }

    private record WorksiteDisplay(String value, ProfessionalStorageRow.Tone tone) {
    }

    private record MeasuredDisplay(String value, ProfessionalStorageRow.Tone tone) {
    }
}
