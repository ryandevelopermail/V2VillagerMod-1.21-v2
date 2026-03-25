package dev.sterner.guardvillagers.common.util;

import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.village.VillagerProfession;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniversalDistributionRouterTest {

    @Test
    void routeRules_includeFenceAndFenceGateRoutesToShepherdLoom() {
        Optional<UniversalDistributionRouter.DistributionRouteRule> fencesRule = UniversalDistributionRouter.routeRules().stream()
                .filter(rule -> rule.id().equals("fences-to-shepherd-v2"))
                .findFirst();
        Optional<UniversalDistributionRouter.DistributionRouteRule> fenceGatesRule = UniversalDistributionRouter.routeRules().stream()
                .filter(rule -> rule.id().equals("fence-gates-to-shepherd-v2"))
                .findFirst();

        assertTrue(fencesRule.isPresent());
        assertTrue(fenceGatesRule.isPresent());
        assertEquals(VillagerProfession.SHEPHERD, fencesRule.get().targets().getFirst().profession());
        assertEquals(Blocks.LOOM, fencesRule.get().targets().getFirst().expectedJobBlock());
        assertEquals(VillagerProfession.SHEPHERD, fenceGatesRule.get().targets().getFirst().profession());
        assertEquals(Blocks.LOOM, fenceGatesRule.get().targets().getFirst().expectedJobBlock());
    }

    @Test
    void routeRuleMatchers_matchFenceAndFenceGateItems() {
        UniversalDistributionRouter.DistributionRouteRule fencesRule = UniversalDistributionRouter.routeRules().stream()
                .filter(rule -> rule.id().equals("fences-to-shepherd-v2"))
                .findFirst()
                .orElseThrow();
        UniversalDistributionRouter.DistributionRouteRule fenceGatesRule = UniversalDistributionRouter.routeRules().stream()
                .filter(rule -> rule.id().equals("fence-gates-to-shepherd-v2"))
                .findFirst()
                .orElseThrow();

        assertTrue(fencesRule.matcher().test(new ItemStack(Items.OAK_FENCE)));
        assertTrue(fenceGatesRule.matcher().test(new ItemStack(Items.OAK_FENCE_GATE)));
    }

    @Test
    void requiresV2ShepherdPairing_requiresShepherdAtLoomOnly() {
        UniversalDistributionRouter.RecipientTarget shepherdAtLoom =
                UniversalDistributionRouter.RecipientTarget.strictlyPaired(VillagerProfession.SHEPHERD, Blocks.LOOM);
        UniversalDistributionRouter.RecipientTarget farmerAtComposter =
                UniversalDistributionRouter.RecipientTarget.strictlyPaired(VillagerProfession.FARMER, Blocks.COMPOSTER);

        assertTrue(UniversalDistributionRouter.requiresV2ShepherdPairing(shepherdAtLoom));
        assertFalse(UniversalDistributionRouter.requiresV2ShepherdPairing(farmerAtComposter));
    }
}
