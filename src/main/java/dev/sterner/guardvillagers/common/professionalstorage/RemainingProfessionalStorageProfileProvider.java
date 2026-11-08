package dev.sterner.guardvillagers.common.professionalstorage;

import dev.sterner.guardvillagers.common.entity.goal.ButcherMeatDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.ButcherGuardSmokerGoal;
import dev.sterner.guardvillagers.common.entity.goal.ButcherSmokerGoal;
import dev.sterner.guardvillagers.common.villager.behavior.LibrarianBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.MasonBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.ShepherdBehavior;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
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
import java.util.function.Predicate;

/** Read-only, open-time profiles for the remaining native and converted worker roles. */
public final class RemainingProfessionalStorageProfileProvider implements ProfessionalStorageProfileProvider {
    @Override
    public Optional<List<ProfessionalStorageTab>> createTabs(ServerWorld world, StorageIdentity storage,
                                                              List<ProfessionalStorageResolution> resolutions) {
        if (resolutions.isEmpty()) return Optional.empty();
        ProfessionalRoleId role = resolutions.getFirst().pairing().role();
        if (resolutions.stream().anyMatch(r -> !role.equals(r.pairing().role()))) return Optional.empty();
        BlockPos position = storage.canonicalPos();
        BlockState state = world.getBlockState(position);
        Inventory inventory = state.getBlock() instanceof ChestBlock chest
                ? ChestBlock.getInventory(chest, state, world, position, false)
                : world.getBlockEntity(position) instanceof Inventory inv ? inv : null;
        if (inventory == null) return Optional.empty();

        List<UUID> workers = resolutions.stream().map(r -> r.pairing().workerUuid()).distinct().toList();
        ProfessionalWorkStatsState stats = ProfessionalWorkStatsState.get(world.getServer());
        boolean partial = resolutions.stream().anyMatch(r -> r.workerAvailability()
                == ProfessionalStorageResolution.WorkerAvailability.UNLOADED);
        List<ProfessionalStorageRow> overview = new ArrayList<>();
        overview.add(row("Status", partial ? "Worker unavailable" : "Paired", partial));
        if (workers.size() > 1) overview.add(number("Workers", workers.size()));
        overview.add(worksite(world, resolutions, role));
        if (role.equals(RemainingProfessionWorkMetrics.SHEPHERD)
                || role.equals(RemainingProfessionWorkMetrics.LIBRARIAN)
                || role.equals(RemainingProfessionWorkMetrics.MASON)) {
            overview.add(craftingTable(world, resolutions, role));
        }
        ProfessionalStorageTab first = new ProfessionalStorageTab("overview", "Overview", overview);
        VillagerEntity representative = resolutions.stream()
                .filter(r -> r.workerAvailability() == ProfessionalStorageResolution.WorkerAvailability.LOADED)
                .sorted(java.util.Comparator.comparing(r -> r.pairing().workerUuid()))
                .map(r -> world.getEntity(r.pairing().workerUuid()))
                .filter(VillagerEntity.class::isInstance).map(VillagerEntity.class::cast)
                .findFirst().orElse(null);
        if (role.equals(RemainingProfessionWorkMetrics.SHEPHERD)) {
            return Optional.of(List.of(first,
                    tab("flock", "Flock", count("Shears stored", inventory, s -> s.isOf(Items.SHEARS)),
                            count("Wheat stored", inventory, s -> s.isOf(Items.WHEAT)),
                            count("Wool stored", inventory, s -> s.isIn(ItemTags.WOOL)),
                            metric("Sheep sheared", stats, workers, role, RemainingProfessionWorkMetrics.SHEEP_SHEARED)),
                    tab("crafting", "Crafting", measured("Banner recipes with inputs", representative == null ? null
                                    : ShepherdBehavior.countRecipesReadOnly(world, representative, inventory), partial),
                            count("Beds stored", inventory, s -> s.isIn(ItemTags.BEDS)),
                            count("Fences stored", inventory, s -> s.isIn(ItemTags.FENCES)),
                            count("Gates stored", inventory, s -> s.isIn(ItemTags.FENCE_GATES)),
                            count("Banners stored", inventory, s -> s.isIn(ItemTags.BANNERS)),
                            metric("Items crafted", stats, workers, role, RemainingProfessionWorkMetrics.SHEPHERD_CRAFTED),
                            metric("Beds placed", stats, workers, role, RemainingProfessionWorkMetrics.BEDS_PLACED)),
                    tab("distribution", "Distribution", count("String stored", inventory, s -> s.isOf(Items.STRING)),
                            metric("Direct items delivered", stats, workers, role, RemainingProfessionWorkMetrics.SHEPHERD_DELIVERED))));
        }
        if (role.equals(RemainingProfessionWorkMetrics.LIBRARIAN)) {
            return Optional.of(List.of(first,
                    tab("crafting", "Crafting", measured("Recipes with inputs", representative == null ? null
                                    : LibrarianBehavior.countRecipesReadOnly(world, representative, inventory), partial),
                            count("Books stored", inventory, s -> s.isOf(Items.BOOK)),
                            count("Paper stored", inventory, s -> s.isOf(Items.PAPER)),
                            count("Leather stored", inventory, s -> s.isOf(Items.LEATHER)),
                            count("Bookshelves stored", inventory, s -> s.isOf(Items.BOOKSHELF)),
                            metric("Items crafted", stats, workers, role, RemainingProfessionWorkMetrics.LIBRARIAN_CRAFTED)),
                    tab("distribution", "Distribution", count("Items stored", inventory, s -> true),
                            metric("Direct items delivered", stats, workers, role, RemainingProfessionWorkMetrics.LIBRARIAN_DELIVERED))));
        }
        if (role.equals(RemainingProfessionWorkMetrics.MASON) || role.equals(ProfessionalRoleId.MASON_GUARD)) {
            List<ProfessionalStorageTab> tabs = new ArrayList<>();
            tabs.add(first);
            tabs.add(tab("stonecutting", "Stonecutting", count("Stone materials", inventory, RemainingProfessionalStorageProfileProvider::isStone),
                    count("Clay stored", inventory, s -> s.isOf(Items.CLAY) || s.isOf(Items.CLAY_BALL)),
                    metric("Stonecut output", stats, workers, role, RemainingProfessionWorkMetrics.MASON_CUT)));
            if (role.equals(ProfessionalRoleId.MASON_GUARD)) {
                tabs.add(tab("construction", "Construction", count("Cobblestone walls", inventory, s -> s.isOf(Items.COBBLESTONE_WALL)),
                        metric("Blocks mined", stats, workers, role, RemainingProfessionWorkMetrics.MASON_MINED),
                        metric("Wall blocks placed", stats, workers, role, RemainingProfessionWorkMetrics.MASON_WALLS),
                        metric("Tools crafted earlier", stats, workers, role, RemainingProfessionWorkMetrics.MASON_TOOLS)));
            } else {
                tabs.add(tab("crafting", "Crafting", count("Planks stored", inventory, s -> s.isIn(ItemTags.PLANKS)),
                        count("Sticks stored", inventory, s -> s.isOf(Items.STICK)),
                        metric("Tools crafted", stats, workers, role, RemainingProfessionWorkMetrics.MASON_TOOLS)));
            }
            tabs.add(tab("distribution", "Distribution", metric("Items delivered", stats, workers, role, RemainingProfessionWorkMetrics.MASON_DELIVERED)));
            return Optional.of(List.copyOf(tabs));
        }
        if (role.equals(ProfessionalRoleId.BUTCHER_GUARD)) {
            return Optional.of(List.of(first,
                    tab("cooking", "Cooking", smokerState(world, resolutions, partial),
                            count("Smokable food", inventory, s -> ButcherGuardSmokerGoal.isSmokable(world, s)),
                            count("Fuel stored", inventory, ButcherGuardSmokerGoal::isFuel),
                            count("Cooked meat stored", inventory, ButcherMeatDistributionGoal::isCookedMeat),
                            metric("Cooked food collected", stats, workers, role, ButcherWorkMetrics.COOKED_FOOD_COLLECTED)),
                    tab("hunting", "Hunting", metric("Animals hunted", stats, workers, role, RemainingProfessionWorkMetrics.BUTCHER_GUARD_HUNTED),
                            metric("Loot stored", stats, workers, role, RemainingProfessionWorkMetrics.BUTCHER_GUARD_LOOT)),
                    tab("career", "Earlier career", metric("Smokers crafted", stats, workers, role, ButcherWorkMetrics.SMOKERS_CRAFTED),
                            metric("Meals delivered", stats, workers, role, ButcherWorkMetrics.MEALS_DELIVERED),
                            metric("Leather/hide delivered", stats, workers, role, ButcherWorkMetrics.LEATHER_HIDE_DELIVERED))));
        }
        if (role.equals(ProfessionalRoleId.LUMBERJACK)) {
            return Optional.of(List.of(first,
                    tab("forestry", "Forestry", count("Logs stored", inventory, s -> s.isIn(ItemTags.LOGS)),
                            count("Saplings stored", inventory, s -> s.isIn(ItemTags.SAPLINGS)),
                            metric("Logs harvested", stats, workers, role, RemainingProfessionWorkMetrics.LUMBERJACK_LOGS),
                            metric("Items deposited", stats, workers, role, RemainingProfessionWorkMetrics.LUMBERJACK_STORED)),
                    tab("materials", "Materials", count("Planks stored", inventory, s -> s.isIn(ItemTags.PLANKS)),
                            count("Sticks stored", inventory, s -> s.isOf(Items.STICK)),
                            count("Charcoal stored", inventory, s -> s.isOf(Items.CHARCOAL))),
                    tab("distribution", "Distribution", metric("Items delivered", stats, workers, role, RemainingProfessionWorkMetrics.LUMBERJACK_DELIVERED))));
        }
        if (role.equals(ProfessionalRoleId.QUARTERMASTER)) {
            return Optional.of(List.of(first,
                    tab("storage", "Storage", count("Items stored", inventory, s -> true),
                            number("Occupied slots", occupiedSlots(inventory)), number("Total slots", inventory.size()),
                            count("Logs stored", inventory, s -> s.isIn(ItemTags.LOGS)),
                            count("Planks stored", inventory, s -> s.isIn(ItemTags.PLANKS)),
                            count("Iron ingots stored", inventory, s -> s.isOf(Items.IRON_INGOT))),
                    tab("distribution", "Distribution", metric("Items delivered", stats, workers, role, RemainingProfessionWorkMetrics.QUARTERMASTER_DELIVERED))));
        }
        return Optional.empty();
    }

    private static ProfessionalStorageRow worksite(ServerWorld world, List<ProfessionalStorageResolution> workers,
                                                   ProfessionalRoleId role) {
        Block expected = role.equals(RemainingProfessionWorkMetrics.SHEPHERD) ? Blocks.LOOM
                : role.equals(RemainingProfessionWorkMetrics.LIBRARIAN) || role.equals(ProfessionalRoleId.QUARTERMASTER) ? Blocks.LECTERN
                : role.equals(RemainingProfessionWorkMetrics.MASON) || role.equals(ProfessionalRoleId.MASON_GUARD) ? Blocks.STONECUTTER
                : role.equals(ProfessionalRoleId.BUTCHER_GUARD) ? Blocks.SMOKER : Blocks.CRAFTING_TABLE;
        String label = expected == Blocks.LOOM ? "Loom" : expected == Blocks.LECTERN ? "Lectern"
                : expected == Blocks.STONECUTTER ? "Stonecutter" : expected == Blocks.SMOKER ? "Smoker" : "Crafting table";
        int loaded = 0, ready = 0;
        for (ProfessionalStorageResolution resolution : workers) {
            if (resolution.workerAvailability() != ProfessionalStorageResolution.WorkerAvailability.LOADED) continue;
            loaded++;
            BlockPos pos = resolution.pairing().roleAnchorPos();
            if (pos != null && world.getBlockState(pos).isOf(expected)) ready++;
        }
        return availability(label, ready, loaded, workers.size());
    }

    private static ProfessionalStorageRow craftingTable(ServerWorld world, List<ProfessionalStorageResolution> workers,
                                                        ProfessionalRoleId role) {
        int loaded = 0, ready = 0;
        for (ProfessionalStorageResolution resolution : workers) {
            if (resolution.workerAvailability() != ProfessionalStorageResolution.WorkerAvailability.LOADED) continue;
            Entity entity = world.getEntity(resolution.pairing().workerUuid());
            if (!(entity instanceof VillagerEntity villager)) continue;
            loaded++;
            BlockPos pos = role.equals(RemainingProfessionWorkMetrics.SHEPHERD) ? ShepherdBehavior.getCraftingTablePos(villager)
                    : role.equals(RemainingProfessionWorkMetrics.MASON) ? MasonBehavior.getCraftingTablePos(villager)
                    : LibrarianBehavior.getCraftingTablePos(villager);
            if (pos != null && world.getBlockState(pos).isOf(Blocks.CRAFTING_TABLE)) ready++;
        }
        return availability("Crafting table", ready, loaded, workers.size());
    }

    private static ProfessionalStorageRow availability(String label, int ready, int loaded, int total) {
        if (loaded == 0) return row(label, "Unknown", true);
        String value = loaded == 1 ? (ready == 1 ? "Yes" : "No") : ready + " / " + loaded + " ready";
        if (loaded < total) value = "Partial: " + value;
        return row(label, value, ready != loaded || loaded != total);
    }

    private static ProfessionalStorageRow smokerState(ServerWorld world, List<ProfessionalStorageResolution> workers,
                                                        boolean partial) {
        Optional<ProfessionalStorageResolution> representative = workers.stream()
                .filter(r -> r.workerAvailability() == ProfessionalStorageResolution.WorkerAvailability.LOADED)
                .min(java.util.Comparator.comparing(r -> r.pairing().workerUuid()));
        if (representative.isEmpty()) return row("Smoker state", "Unknown", true);
        ButcherSmokerGoal.SmokerState state = ButcherSmokerGoal.inspectSmokerStateReadOnly(
                world, representative.get().pairing().roleAnchorPos());
        boolean warning = partial || state == ButcherSmokerGoal.SmokerState.MISSING
                || state == ButcherSmokerGoal.SmokerState.NEEDS_FUEL;
        return row("Smoker state", (partial ? "Partial: " : "") + state.displayName(), warning);
    }

    private static ProfessionalStorageRow measured(String label, @Nullable Integer value, boolean partial) {
        if (value == null) return row(label, "Not measured", true);
        return row(label, (partial ? "Partial: " : "") + value, partial);
    }

    private static boolean isStone(ItemStack stack) {
        return stack.isOf(Items.STONE) || stack.isOf(Items.COBBLESTONE)
                || stack.isOf(Items.DEEPSLATE) || stack.isOf(Items.COBBLED_DEEPSLATE)
                || stack.isOf(Items.ANDESITE) || stack.isOf(Items.DIORITE)
                || stack.isOf(Items.GRANITE) || stack.isOf(Items.TUFF);
    }

    private static ProfessionalStorageRow count(String label, Inventory inventory, Predicate<ItemStack> matches) {
        long result = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && matches.test(stack)) result += stack.getCount();
        }
        return number(label, result);
    }

    private static int occupiedSlots(Inventory inventory) {
        int occupied = 0;
        for (int i = 0; i < inventory.size(); i++) if (!inventory.getStack(i).isEmpty()) occupied++;
        return occupied;
    }

    private static ProfessionalStorageRow metric(String label, ProfessionalWorkStatsState stats, List<UUID> workers,
                                                 ProfessionalRoleId role, ProfessionalMetricId metric) {
        return number(label, stats.aggregate(workers, role, metric));
    }

    private static ProfessionalStorageTab tab(String id, String title, ProfessionalStorageRow... rows) {
        return new ProfessionalStorageTab(id, title, List.of(rows));
    }

    private static ProfessionalStorageRow number(String label, long value) {
        return new ProfessionalStorageRow(label, Long.toString(value), ProfessionalStorageRow.Tone.NORMAL);
    }

    private static ProfessionalStorageRow row(String label, String value, boolean warning) {
        return new ProfessionalStorageRow(label, value, warning ? ProfessionalStorageRow.Tone.WARNING : ProfessionalStorageRow.Tone.PAIRED);
    }
}
