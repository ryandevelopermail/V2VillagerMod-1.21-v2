package dev.sterner.guardvillagers.common.util;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.village.VillagerProfession;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

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

            List<DistributionRecipientHelper.RecipientRecord> recipients = DistributionRecipientHelper.findEligibleRecipients(
                    world,
                    source,
                    recipientRange,
                    rule.targetProfession(),
                    rule.expectedJobBlock());
            if (recipients.isEmpty()) {
                continue;
            }

            for (int slot = 0; slot < sourceInventory.size(); slot++) {
                ItemStack stack = sourceInventory.getStack(slot);
                if (stack.isEmpty() || !rule.matcher().test(stack)) {
                    continue;
                }
                return Optional.of(new ResolvedRoute(rule, slot, recipients));
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

            List<DistributionRecipientHelper.RecipientRecord> recipients = DistributionRecipientHelper.findEligibleRecipients(
                    world,
                    source,
                    recipientRange,
                    rule.targetProfession(),
                    rule.expectedJobBlock());
            if (!recipients.isEmpty()) {
                return Optional.of(new ResolvedRecipients(rule, recipients));
            }
        }

        return Optional.empty();
    }

    public static List<DistributionRouteRule> routeRules() {
        return List.of(
                new DistributionRouteRule(
                        "plantable-seeds-to-farmer",
                        stack -> stack.isIn(ItemTags.VILLAGER_PLANTABLE_SEEDS),
                        VillagerProfession.FARMER,
                        Blocks.COMPOSTER,
                        100,
                        RoutingMode.ALWAYS
                )
        ).stream().sorted(RULE_ORDER).toList();
    }

    public enum RoutingMode {
        ALWAYS,
        OVERFLOW_ONLY;

        public boolean isEligible(double sourceFullness) {
            return this == ALWAYS || sourceFullness >= 0.825D;
        }
    }

    public record DistributionRouteRule(
            String id,
            Predicate<ItemStack> matcher,
            VillagerProfession targetProfession,
            Block expectedJobBlock,
            int priority,
            RoutingMode mode
    ) {
    }

    public record ResolvedRoute(
            DistributionRouteRule rule,
            int sourceSlot,
            List<DistributionRecipientHelper.RecipientRecord> recipients
    ) {
    }

    public record ResolvedRecipients(
            DistributionRouteRule rule,
            List<DistributionRecipientHelper.RecipientRecord> recipients
    ) {
    }
}
