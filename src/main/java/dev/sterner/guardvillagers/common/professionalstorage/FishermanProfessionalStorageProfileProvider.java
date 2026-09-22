package dev.sterner.guardvillagers.common.professionalstorage;

import dev.sterner.guardvillagers.common.entity.FishermanGuardEntity;
import dev.sterner.guardvillagers.common.entity.goal.FishermanDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.FishermanGuardFishingGoal;
import dev.sterner.guardvillagers.common.villager.behavior.FishermanBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Read-only native-Fisherman and converted Fisherman Guard professional-storage profile. */
public final class FishermanProfessionalStorageProfileProvider implements ProfessionalStorageProfileProvider {
    @Override
    public Optional<List<ProfessionalStorageTab>> createTabs(
            ServerWorld world, StorageIdentity storage, List<ProfessionalStorageResolution> resolutions
    ) {
        if (resolutions.isEmpty()) return Optional.empty();
        ProfessionalRoleId role = resolutions.getFirst().pairing().role();
        if (!isSupportedRole(role)
                || resolutions.stream().anyMatch(r -> !r.pairing().role().equals(role))) return Optional.empty();
        Inventory inventory = resolveStorageInventory(world, storage);
        if (inventory == null) return Optional.empty();

        FishermanStorageCounts storageCounts = countResolvedStorageContents(() -> storageViews(inventory));
        List<FishermanWorkerView> workers = new ArrayList<>(resolutions.size());
        for (ProfessionalStorageResolution resolution : resolutions) {
            UUID uuid = resolution.pairing().workerUuid();
            if (resolution.workerAvailability() == ProfessionalStorageResolution.WorkerAvailability.UNLOADED) {
                workers.add(FishermanWorkerView.unloaded(uuid, resolution.workerAvailability()));
                continue;
            }
            Entity entity = world.getEntity(uuid);
            if (role.equals(FishermanWorkMetrics.FISHERMAN_ROLE)) {
                if (!(entity instanceof VillagerEntity villager)) return Optional.empty();
                Optional<FishermanBehavior.FishermanLiveSnapshot> snapshot =
                        FishermanBehavior.getLiveStorageSnapshot(world, villager, inventory);
                if (snapshot.isEmpty()) return Optional.empty();
                FishermanBehavior.FishermanLiveSnapshot live = snapshot.get();
                workers.add(new FishermanWorkerView(uuid, resolution.workerAvailability(), live.craftingTableReady(),
                        live.barrelReady(), live.craftableRecipes(), live.eligibleButchers()));
            } else {
                if (!(entity instanceof FishermanGuardEntity guard)) return Optional.empty();
                BlockPos chestPos = guard.getPairedChestPos();
                BlockPos jobPos = guard.getPairedJobPos();
                boolean barrelReady = jobPos != null && world.getBlockState(jobPos).isOf(Blocks.BARREL);
                boolean chestMatches = chestPos != null && StorageIdentityResolver.resolve(world, chestPos)
                        .filter(storage::equals).isPresent();
                boolean barrelMatches = barrelReady && StorageIdentityResolver.resolve(world, jobPos)
                        .filter(storage::equals).isPresent();
                if (!chestMatches && !barrelMatches) return Optional.empty();
                workers.add(new FishermanWorkerView(uuid, resolution.workerAvailability(), null,
                        barrelReady, null,
                        FishermanGuardFishingGoal.countEligibleButcherRecipientsReadOnly(world, guard)));
            }
        }
        FishermanWorkerView representative = selectRepresentative(workers).flatMap(uuid -> workers.stream()
                .filter(worker -> worker.loaded() && worker.workerUuid().equals(uuid)).findFirst()).orElse(null);
        FishermanCareerTotals totals = aggregateCareerTotals(ProfessionalWorkStatsState.get(world.getServer()),
                resolutions.stream().map(r -> r.pairing().workerUuid()).toList(), role);
        return Optional.of(buildTabs(workers, storageCounts,
                representative == null ? null : representative.craftableRecipes(),
                representative == null ? null : representative.eligibleButchers(), totals));
    }

    static boolean isSupportedRole(ProfessionalRoleId role) {
        return role.equals(FishermanWorkMetrics.FISHERMAN_ROLE)
                || role.equals(ProfessionalRoleId.FISHERMAN_GUARD);
    }

    static FishermanCareerTotals aggregateCareerTotals(
            ProfessionalWorkStatsState stats, List<UUID> uuids, ProfessionalRoleId role
    ) {
        return new FishermanCareerTotals(
                stats.aggregate(uuids, role, FishermanWorkMetrics.FISHING_RODS_CRAFTED),
                stats.aggregate(uuids, role, FishermanWorkMetrics.BUCKETS_CRAFTED),
                stats.aggregate(uuids, role, FishermanWorkMetrics.BOATS_CRAFTED),
                stats.aggregate(uuids, role, FishermanWorkMetrics.FISH_DELIVERED));
    }

    static List<ProfessionalStorageTab> buildTabs(
            List<FishermanWorkerView> workers, FishermanStorageCounts storage,
            @Nullable Integer craftableRecipes, @Nullable Integer eligibleButchers, FishermanCareerTotals totals
    ) {
        boolean partial = workers.stream().anyMatch(worker -> !worker.loaded());
        WorksiteDisplay table = worksiteDisplay(workers, true);
        WorksiteDisplay barrel = worksiteDisplay(workers, false);
        MeasuredDisplay craftable = measuredDisplay(craftableRecipes, partial);
        MeasuredDisplay recipients = measuredDisplay(eligibleButchers, partial);
        return List.of(
                new ProfessionalStorageTab("overview", "Overview", List.of(
                        row("Status", partial ? "Worker unavailable" : "Ready", partial ? toneWarn() : tonePaired()),
                        row("Crafting table", table.value(), table.tone()),
                        row("Barrel", barrel.value(), barrel.tone()),
                        row("Fishing rod ready", storage.fishingRods() > 0 ? "Yes" : "No",
                                storage.fishingRods() > 0 ? tonePaired() : toneNormal()))),
                new ProfessionalStorageTab("crafting", "Crafting", List.of(
                        row("Craftable recipes", craftable.value(), craftable.tone()),
                        normal("Fishing rods stored", storage.fishingRods()),
                        normal("Buckets stored", storage.emptyBuckets()),
                        normal("Boats stored", storage.boats()),
                        normal("Rods crafted", totals.fishingRodsCrafted()),
                        normal("Buckets crafted", totals.bucketsCrafted()),
                        normal("Boats crafted", totals.boatsCrafted()))),
                new ProfessionalStorageTab("distribution", "Distribution", List.of(
                        normal("Fish stored", storage.rawFish()),
                        row("Eligible Butchers", recipients.value(), recipients.tone()),
                        normal("Fish delivered", totals.fishDelivered()))));
    }

    static Optional<UUID> selectRepresentative(List<FishermanWorkerView> workers) {
        return workers.stream().filter(FishermanWorkerView::loaded).map(FishermanWorkerView::workerUuid).min(UUID::compareTo);
    }

    static FishermanStorageCounts countStorageViews(List<FishermanStorageStackView> stacks) {
        long rods = 0, buckets = 0, boats = 0, fish = 0;
        for (FishermanStorageStackView stack : stacks) {
            if (stack.count() <= 0) continue;
            if (stack.fishingRod()) rods = add(rods, stack.count());
            if (stack.emptyBucket()) buckets = add(buckets, stack.count());
            if (stack.boat()) boats = add(boats, stack.count());
            if (stack.rawFish()) fish = add(fish, stack.count());
        }
        return new FishermanStorageCounts(rods, buckets, boats, fish);
    }

    static FishermanStorageCounts countResolvedStorageContents(Supplier<List<FishermanStorageStackView>> contents) {
        return countStorageViews(contents.get());
    }

    private static List<FishermanStorageStackView> storageViews(Inventory inventory) {
        List<FishermanStorageStackView> views = new ArrayList<>(inventory.size());
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            views.add(new FishermanStorageStackView(stack.isOf(Items.FISHING_ROD), stack.isOf(Items.BUCKET),
                    stack.isIn(ItemTags.BOATS) || stack.isIn(ItemTags.CHEST_BOATS),
                    FishermanDistributionGoal.isDistributableFish(stack), stack.getCount()));
        }
        return views;
    }

    private static WorksiteDisplay worksiteDisplay(List<FishermanWorkerView> workers, boolean table) {
        List<FishermanWorkerView> loaded = workers.stream().filter(FishermanWorkerView::loaded).toList();
        if (loaded.isEmpty()) return new WorksiteDisplay("Unknown", toneWarn());
        List<Boolean> measured = loaded.stream().map(worker -> table
                ? worker.craftingTableReady() : worker.barrelReady()).filter(java.util.Objects::nonNull).toList();
        if (measured.isEmpty()) return new WorksiteDisplay("Not measured", toneWarn());
        long ready = measured.stream().filter(Boolean.TRUE::equals).count();
        String value = measured.size() == 1 ? (ready == 1 ? "Yes" : "No") : ready + " / " + measured.size() + " ready";
        if (measured.size() < loaded.size()) return new WorksiteDisplay("Partial: " + value, toneWarn());
        if (loaded.size() < workers.size()) return new WorksiteDisplay("Partial: " + value, toneWarn());
        return new WorksiteDisplay(value, ready == measured.size() ? tonePaired() : toneWarn());
    }

    private static MeasuredDisplay measuredDisplay(@Nullable Integer value, boolean partial) {
        if (value == null) return new MeasuredDisplay("Not measured", toneWarn());
        return new MeasuredDisplay((partial ? "Partial: " : "") + Math.max(0, value), partial ? toneWarn() : toneNormal());
    }

    private static @Nullable Inventory resolveStorageInventory(ServerWorld world, StorageIdentity storage) {
        BlockPos pos = storage.canonicalPos();
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chest) return ChestBlock.getInventory(chest, state, world, pos, false);
        return world.getBlockEntity(pos) instanceof Inventory inventory ? inventory : null;
    }

    private static ProfessionalStorageRow normal(String label, long value) { return row(label, Long.toString(value), toneNormal()); }
    private static ProfessionalStorageRow row(String label, String value, ProfessionalStorageRow.Tone tone) { return new ProfessionalStorageRow(label, value, tone); }
    private static ProfessionalStorageRow.Tone toneNormal() { return ProfessionalStorageRow.Tone.NORMAL; }
    private static ProfessionalStorageRow.Tone toneWarn() { return ProfessionalStorageRow.Tone.WARNING; }
    private static ProfessionalStorageRow.Tone tonePaired() { return ProfessionalStorageRow.Tone.PAIRED; }
    private static long add(long current, long amount) { return current >= Long.MAX_VALUE - amount ? Long.MAX_VALUE : current + amount; }

    record FishermanWorkerView(UUID workerUuid, ProfessionalStorageResolution.WorkerAvailability availability,
                               @Nullable Boolean craftingTableReady, @Nullable Boolean barrelReady,
                               @Nullable Integer craftableRecipes, @Nullable Integer eligibleButchers) {
        static FishermanWorkerView unloaded(UUID uuid, ProfessionalStorageResolution.WorkerAvailability availability) {
            return new FishermanWorkerView(uuid, availability, null, null, null, null);
        }
        boolean loaded() { return availability == ProfessionalStorageResolution.WorkerAvailability.LOADED; }
    }
    record FishermanStorageStackView(boolean fishingRod, boolean emptyBucket, boolean boat, boolean rawFish, long count) { }
    record FishermanStorageCounts(long fishingRods, long emptyBuckets, long boats, long rawFish) {
        FishermanStorageCounts { fishingRods = Math.max(0, fishingRods); emptyBuckets = Math.max(0, emptyBuckets); boats = Math.max(0, boats); rawFish = Math.max(0, rawFish); }
    }
    record FishermanCareerTotals(long fishingRodsCrafted, long bucketsCrafted, long boatsCrafted, long fishDelivered) {
        FishermanCareerTotals {
            if (fishingRodsCrafted < 0 || bucketsCrafted < 0 || boatsCrafted < 0 || fishDelivered < 0) throw new IllegalArgumentException("Fisherman career totals must be nonnegative");
        }
    }
    private record WorksiteDisplay(String value, ProfessionalStorageRow.Tone tone) { }
    private record MeasuredDisplay(String value, ProfessionalStorageRow.Tone tone) { }
}
