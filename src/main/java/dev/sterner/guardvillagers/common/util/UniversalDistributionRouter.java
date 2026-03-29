package dev.sterner.guardvillagers.common.util;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

public final class UniversalDistributionRouter {
    private static final Comparator<DistributionRouteRule> RULE_ORDER =
            Comparator.comparingInt(DistributionRouteRule::priority).reversed()
                    .thenComparing(DistributionRouteRule::id);

    private UniversalDistributionRouter() {
    }

    public static Optional<ResolvedRoute> resolve(
            ServerWorld world,
            VillagerEntity source,
            Inventory sourceInventory,
            double sourceFullness,
            double recipientRange
    ) {
        List<DistributionRouteRule> rules = routeRules();
        for (DistributionRouteRule rule : rules) {
            if (!rule.mode().isEligible(sourceFullness)) {
                continue;
            }

            List<DistributionRecipientHelper.RecipientRecord> recipients = resolveEligibleRecipients(world, source, recipientRange, rule);
            if (recipients.isEmpty()) {
                continue;
            }

            for (int slot = 0; slot < sourceInventory.size(); slot++) {
                ItemStack stack = sourceInventory.getStack(slot);
                if (stack.isEmpty() || !rule.matcher().test(stack)) {
                    continue;
                }

                SplitPlan splitPlan = SplitPlanner.plan(stack.getCount(), recipients, recipient -> recipientWeight(rule, recipient));
                return Optional.of(new ResolvedRoute(rule, slot, recipients, splitPlan));
            }
        }

        return Optional.empty();
    }

    public static Optional<ResolvedRecipients> resolveRecipientsForItem(
            ServerWorld world,
            VillagerEntity source,
            ItemStack pendingItem,
            double sourceFullness,
            double recipientRange
    ) {
        if (pendingItem.isEmpty()) {
            return Optional.empty();
        }

        for (DistributionRouteRule rule : routeRules()) {
            if (!rule.mode().isEligible(sourceFullness) || !rule.matcher().test(pendingItem)) {
                continue;
            }

            List<DistributionRecipientHelper.RecipientRecord> recipients = resolveEligibleRecipients(world, source, recipientRange, rule);
            if (!recipients.isEmpty()) {
                SplitPlan splitPlan = SplitPlanner.plan(pendingItem.getCount(), recipients, recipient -> recipientWeight(rule, recipient));
                return Optional.of(new ResolvedRecipients(rule, recipients, splitPlan));
            }
        }

        return Optional.empty();
    }

    public static List<DistributionRouteRule> routeRules() {
        return List.of(
                new DistributionRouteRule(
                        "plantable-seeds-to-farmer",
                        stack -> stack.isIn(ItemTags.VILLAGER_PLANTABLE_SEEDS),
                        List.of(RecipientTarget.of(VillagerProfession.FARMER, Blocks.COMPOSTER)),
                        100,
                        RoutingMode.ALWAYS
                ),
                new DistributionRouteRule(
                        "charcoal-to-smithing-and-cooking-professions",
                        stack -> stack.isOf(Items.CHARCOAL),
                        List.of(
                                RecipientTarget.withCapability(VillagerProfession.BUTCHER, Blocks.SMOKER, RecipientCapability.PAIRED_FURNACE_MODIFIER),
                                RecipientTarget.withCapability(VillagerProfession.ARMORER, Blocks.BLAST_FURNACE, RecipientCapability.PAIRED_FURNACE_MODIFIER),
                                RecipientTarget.withCapability(VillagerProfession.TOOLSMITH, Blocks.SMITHING_TABLE, RecipientCapability.PAIRED_FURNACE_MODIFIER),
                                RecipientTarget.withCapability(VillagerProfession.WEAPONSMITH, Blocks.GRINDSTONE, RecipientCapability.PAIRED_FURNACE_MODIFIER)
                        ),
                        90,
                        RoutingMode.ALWAYS
                ),
                new DistributionRouteRule(
                        "fences-to-shepherd-v2",
                        stack -> stack.isIn(ItemTags.FENCES),
                        List.of(RecipientTarget.strictlyPaired(VillagerProfession.SHEPHERD, Blocks.LOOM)),
                        86,
                        RoutingMode.ALWAYS
                ),
                new DistributionRouteRule(
                        "fence-gates-to-shepherd-v2",
                        stack -> stack.isIn(ItemTags.FENCE_GATES),
                        List.of(RecipientTarget.strictlyPaired(VillagerProfession.SHEPHERD, Blocks.LOOM)),
                        86,
                        RoutingMode.ALWAYS
                ),
                new DistributionRouteRule(
                        "sticks-to-stick-consuming-professions",
                        stack -> stack.isOf(Items.STICK),
                        List.of(
                                RecipientTarget.strictlyPaired(VillagerProfession.FARMER, Blocks.COMPOSTER),
                                RecipientTarget.strictlyPaired(VillagerProfession.SHEPHERD, Blocks.LOOM),
                                RecipientTarget.strictlyPaired(VillagerProfession.FLETCHER, Blocks.FLETCHING_TABLE),
                                RecipientTarget.strictlyPaired(VillagerProfession.LEATHERWORKER, Blocks.CAULDRON),
                                RecipientTarget.strictlyPaired(VillagerProfession.TOOLSMITH, Blocks.SMITHING_TABLE),
                                RecipientTarget.strictlyPaired(VillagerProfession.WEAPONSMITH, Blocks.GRINDSTONE),
                                RecipientTarget.strictlyPaired(VillagerProfession.FISHERMAN, Blocks.BARREL)
                        ),
                        85,
                        RoutingMode.ALWAYS
                ),
                new DistributionRouteRule(
                        "planks-to-plank-consuming-professions",
                        stack -> stack.isIn(ItemTags.PLANKS),
                        WoodRecipientRegistry.plankRecipients().stream()
                                .map(profile -> RecipientTarget.of(profile.profession(), profile.expectedJobBlock()))
                                .toList(),
                        80,
                        RoutingMode.ALWAYS
                ),
                new DistributionRouteRule(
                        "logs-to-furnace-capable-cooking-professions",
                        stack -> stack.isIn(ItemTags.LOGS),
                        List.of(
                                RecipientTarget.withCapability(VillagerProfession.BUTCHER, Blocks.SMOKER, RecipientCapability.PAIRED_FURNACE_MODIFIER)
                        ),
                        78,
                        RoutingMode.ALWAYS
                )
        ).stream().sorted(RULE_ORDER).toList();
    }

    private static List<DistributionRecipientHelper.RecipientRecord> resolveEligibleRecipients(
            ServerWorld world,
            VillagerEntity source,
            double recipientRange,
            DistributionRouteRule rule
    ) {
        List<DistributionRecipientHelper.RecipientRecord> recipients = new ArrayList<>();
        for (RecipientTarget target : rule.targets()) {
            recipients.addAll(DistributionRecipientHelper.findEligibleRecipients(
                    world,
                    source,
                    recipientRange,
                    target.profession(),
                    target.expectedJobBlock(),
                    recipient -> isStrictlyPaired(world, recipient, target.expectedJobBlock())
                            && isEligibleV2ShepherdRecipient(world, target, recipient)
                            && supportsCapabilities(world, recipient, target.requiredCapabilities())));
        }

        return recipients.stream().distinct().sorted(Comparator
                .comparingDouble(DistributionRecipientHelper.RecipientRecord::sourceSquaredDistance)
                .thenComparing(record -> record.recipient().getUuid(), java.util.UUID::compareTo)).toList();
    }

    private static double recipientWeight(DistributionRouteRule rule, DistributionRecipientHelper.RecipientRecord recipient) {
        if ("planks-to-plank-consuming-professions".equals(rule.id())) {
            return WoodRecipientRegistry.plankDemandWeight(recipient.recipient().getVillagerData().getProfession());
        }
        return 1.0D;
    }


    private static boolean isStrictlyPaired(ServerWorld world, DistributionRecipientHelper.RecipientRecord recipient, Block expectedJobBlock) {
        if (!world.getBlockState(recipient.jobPos()).isOf(expectedJobBlock)) {
            return false;
        }

        return JobBlockPairingHelper.findNearbyChest(world, recipient.jobPos())
                .map(pos -> pos.equals(recipient.chestPos()))
                .orElse(false);
    }

    static boolean isEligibleV2ShepherdRecipient(ServerWorld world,
                                                 RecipientTarget target,
                                                 DistributionRecipientHelper.RecipientRecord recipient) {
        if (!requiresV2ShepherdPairing(target)) {
            return true;
        }
        return hasNearbyPairedCraftingTableForJobSite(world, recipient);
    }

    static boolean requiresV2ShepherdPairing(RecipientTarget target) {
        return target.profession() == VillagerProfession.SHEPHERD && target.expectedJobBlock() == Blocks.LOOM;
    }

    private static boolean hasNearbyPairedCraftingTableForJobSite(ServerWorld world, DistributionRecipientHelper.RecipientRecord recipient) {
        Optional<BlockPos> craftingTable = JobBlockPairingHelper.findNearbyCraftingTable(world, recipient.jobPos());
        if (craftingTable.isEmpty()) {
            return false;
        }
        return JobBlockPairingHelper.findNearbyChest(world, craftingTable.get(), recipient.jobPos())
                .map(pos -> pos.equals(recipient.chestPos()))
                .orElse(false);
    }

    private static boolean supportsCapabilities(ServerWorld world, DistributionRecipientHelper.RecipientRecord recipient, Set<RecipientCapability> capabilities) {
        for (RecipientCapability capability : capabilities) {
            if (!supportsCapability(world, recipient, capability)) {
                return false;
            }
        }
        return true;
    }

    private static boolean supportsCapability(ServerWorld world, DistributionRecipientHelper.RecipientRecord recipient, RecipientCapability capability) {
        if (capability == RecipientCapability.PAIRED_FURNACE_MODIFIER) {
            return hasNearbyPairedFurnaceModifier(world, recipient.chestPos());
        }
        return true;
    }

    private static boolean hasNearbyPairedFurnaceModifier(ServerWorld world, BlockPos chestPos) {
        for (BlockPos candidate : BlockPos.iterate(chestPos.add(-3, -1, -3), chestPos.add(3, 1, 3))) {
            if (world.getBlockState(candidate).isOf(Blocks.FURNACE)
                    || world.getBlockState(candidate).isOf(Blocks.SMOKER)
                    || world.getBlockState(candidate).isOf(Blocks.BLAST_FURNACE)) {
                return true;
            }
        }
        return false;
    }

    private static final class SplitPlanner {
        private SplitPlanner() {
        }

        private static SplitPlan plan(int sourceStackCount,
                                      List<DistributionRecipientHelper.RecipientRecord> recipients,
                                      ToDoubleFunction<DistributionRecipientHelper.RecipientRecord> weightProvider) {
            if (sourceStackCount <= 0 || recipients.isEmpty()) {
                return new SplitPlan(List.of(), null, -1);
            }

            int recipientCount = recipients.size();
            int selectedIndex = Math.floorMod(sourceStackCount - 1, recipientCount);
            List<Double> weights = new ArrayList<>(recipientCount);
            double totalWeight = 0.0D;
            for (DistributionRecipientHelper.RecipientRecord recipient : recipients) {
                double weight = Math.max(0.01D, weightProvider.applyAsDouble(recipient));
                weights.add(weight);
                totalWeight += weight;
            }

            List<RecipientShare> shares = new ArrayList<>(recipientCount);
            int distributed = 0;
            for (int i = 0; i < recipientCount; i++) {
                int share;
                if (i == recipientCount - 1) {
                    share = Math.max(0, sourceStackCount - distributed);
                } else {
                    share = (int) Math.floor((weights.get(i) / totalWeight) * sourceStackCount);
                    distributed += share;
                }
                shares.add(new RecipientShare(recipients.get(i), share));
            }

            return new SplitPlan(List.copyOf(shares), recipients.get(selectedIndex), selectedIndex);
        }
    }

    public enum RoutingMode {
        ALWAYS,
        OVERFLOW_ONLY;

        public boolean isEligible(double sourceFullness) {
            return this == ALWAYS || sourceFullness >= 0.825D;
        }
    }

    public enum RecipientCapability {
        PAIRED_FURNACE_MODIFIER
    }

    public record RecipientTarget(
            VillagerProfession profession,
            Block expectedJobBlock,
            Set<RecipientCapability> requiredCapabilities
    ) {
        public RecipientTarget {
            requiredCapabilities = requiredCapabilities == null ? Set.of() : Set.copyOf(requiredCapabilities);
        }

        public static RecipientTarget of(VillagerProfession profession, Block expectedJobBlock) {
            return new RecipientTarget(profession, expectedJobBlock, Set.of());
        }

        public static RecipientTarget withCapability(VillagerProfession profession, Block expectedJobBlock, RecipientCapability capability) {
            return new RecipientTarget(profession, expectedJobBlock, EnumSet.of(capability));
        }

        public static RecipientTarget strictlyPaired(VillagerProfession profession, Block expectedJobBlock) {
            return of(profession, expectedJobBlock);
        }
    }

    public record DistributionRouteRule(
            String id,
            Predicate<ItemStack> matcher,
            List<RecipientTarget> targets,
            int priority,
            RoutingMode mode
    ) {
    }

    public record RecipientShare(
            DistributionRecipientHelper.RecipientRecord recipient,
            int share
    ) {
    }

    public record SplitPlan(
            List<RecipientShare> shares,
            DistributionRecipientHelper.RecipientRecord selectedRecipient,
            int selectedRecipientIndex
    ) {
    }

    public record ResolvedRoute(
            DistributionRouteRule rule,
            int sourceSlot,
            List<DistributionRecipientHelper.RecipientRecord> recipients,
            SplitPlan splitPlan
    ) {
    }

    public record ResolvedRecipients(
            DistributionRouteRule rule,
            List<DistributionRecipientHelper.RecipientRecord> recipients,
            SplitPlan splitPlan
    ) {
    }
}
