package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClericCompletionSeamTest {
    @Test
    void everyBottleStageUsesTheRequiredDisplayMapping() {
        assertEquals("Missing", ClericBrewingGoal.BottleStage.MISSING.displayName());
        assertEquals("Empty", ClericBrewingGoal.BottleStage.EMPTY.displayName());
        assertEquals("Water partial", ClericBrewingGoal.BottleStage.WATER_PARTIAL.displayName());
        assertEquals("Water ready", ClericBrewingGoal.BottleStage.WATER_READY.displayName());
        assertEquals("Awkward ready", ClericBrewingGoal.BottleStage.AWKWARD_READY.displayName());
        assertEquals("Potion ready", ClericBrewingGoal.BottleStage.POTION_READY.displayName());
        assertEquals("Splash ready", ClericBrewingGoal.BottleStage.SPLASH_READY.displayName());
        assertEquals("Invalid", ClericBrewingGoal.BottleStage.INVALID.displayName());
    }

    @Test
    void preferredTargetUsesSplashHealingThenRegularHealingThenDeterministicFallback() {
        Target splashHealing = new Target("minecraft:healing", true, true);
        Target regularHealing = new Target("minecraft:healing", false, true);
        Target alphaSplash = new Target("minecraft:alpha", true, false);
        Target alphaRegular = new Target("minecraft:alpha", false, false);
        Target beta = new Target("minecraft:beta", false, false);

        assertEquals(splashHealing, select(List.of(beta, regularHealing, splashHealing)));
        assertEquals(regularHealing, select(List.of(beta, regularHealing)));
        assertEquals(alphaRegular, select(List.of(beta, alphaSplash, alphaRegular)));
        assertNull(select(List.of()));
    }

    @Test
    void reachableRecipeTraversalUsesTheWholeGraphWithoutMutatingInputs() {
        Set<String> ingredients = new LinkedHashSet<>(List.of("wart", "melon", "gunpowder"));
        Set<String> original = Set.copyOf(ingredients);
        Map<String, List<String>> paths = ClericBrewingGoal.traceReachablePaths(
                "water",
                ingredients,
                (potion, ingredient) -> switch (potion + "+" + ingredient) {
                    case "water+wart" -> "awkward";
                    case "awkward+melon" -> "healing";
                    case "healing+gunpowder" -> "splash_healing";
                    default -> null;
                });
        assertEquals(Set.of("water", "awkward", "healing", "splash_healing"), paths.keySet());
        assertEquals(List.of("wart", "melon"), paths.get("healing"));
        assertEquals(List.of("wart", "melon", "gunpowder"), paths.get("splash_healing"));
        assertEquals(original, ingredients);
    }

    @Test
    void potionClassificationMatchesDistributionItemRules() {
        assertTrue(ClericDistributionGoal.isSupportedPotionShape(true, true));
        assertFalse(ClericDistributionGoal.isSupportedPotionShape(false, true));
        assertFalse(ClericDistributionGoal.isSupportedPotionShape(true, false));
        assertTrue(ClericDistributionGoal.isHealingSplashPotionShape(true, true, true));
        assertFalse(ClericDistributionGoal.isHealingSplashPotionShape(true, false, true));
        assertFalse(ClericDistributionGoal.isHealingSplashPotionShape(true, true, false));
    }

    @Test
    void configuredBrewingStandRecipeRequiresOneBlazeRodAndThreeCobblestone() {
        assertTrue(ClericCraftingGoal.isConfiguredBrewingStandRecipeCraftable(1, 3));
        assertTrue(ClericCraftingGoal.isConfiguredBrewingStandRecipeCraftable(2, 6));
        assertFalse(ClericCraftingGoal.isConfiguredBrewingStandRecipeCraftable(0, 3));
        assertFalse(ClericCraftingGoal.isConfiguredBrewingStandRecipeCraftable(1, 2));
    }

    @Test
    void confirmedCraftRecordsOnceAfterConsumptionAndCompleteInsertion() {
        List<String> order = new ArrayList<>();
        assertTrue(ClericCraftingGoal.executeConfirmedBrewingStandCraft(
                () -> {
                    order.add("consume");
                    return true;
                },
                () -> {
                    order.add("insert");
                    return true;
                },
                () -> order.add("metric")));
        assertEquals(List.of("consume", "insert", "metric"), order);
    }

    @Test
    void failedIngredientsOrOutputInsertionDoesNotRecordCraft() {
        AtomicInteger insertions = new AtomicInteger();
        AtomicInteger records = new AtomicInteger();
        assertFalse(ClericCraftingGoal.executeConfirmedBrewingStandCraft(
                () -> false,
                () -> {
                    insertions.incrementAndGet();
                    return true;
                },
                records::incrementAndGet));
        assertEquals(0, insertions.get());
        assertFalse(ClericCraftingGoal.executeConfirmedBrewingStandCraft(
                () -> true,
                () -> false,
                records::incrementAndGet));
        assertEquals(0, records.get());
    }

    @Test
    void finishedPotionMovementCountsChestFallbackAndPartialButNotBlockedOrIntermediate() {
        AtomicInteger recorded = new AtomicInteger();
        assertEquals(3, ClericBrewingGoal.recordFinishedPotionMovement(true, 3, 0, recorded::addAndGet));
        assertEquals(2, ClericBrewingGoal.recordFinishedPotionMovement(true, 3, 1, recorded::addAndGet));
        assertEquals(1, ClericBrewingGoal.recordFinishedPotionMovement(true, 1, 0, recorded::addAndGet));
        assertEquals(0, ClericBrewingGoal.recordFinishedPotionMovement(true, 3, 3, recorded::addAndGet));
        assertEquals(0, ClericBrewingGoal.recordFinishedPotionMovement(false, 3, 0, recorded::addAndGet));
        assertEquals(6, recorded.get());
    }

    @Test
    void injuredGuardPredicateAndSearchRangesMatchTheInstalledGoal() {
        assertTrue(HealGuardAndPlayerGoal.isEligibleInjuredGuardShape(true, true, 9.0F, 10.0F));
        assertFalse(HealGuardAndPlayerGoal.isEligibleInjuredGuardShape(false, true, 9.0F, 10.0F));
        assertFalse(HealGuardAndPlayerGoal.isEligibleInjuredGuardShape(true, false, 9.0F, 10.0F));
        assertFalse(HealGuardAndPlayerGoal.isEligibleInjuredGuardShape(true, true, 10.0F, 10.0F));
        assertEquals(14.0D, HealGuardAndPlayerGoal.GUARD_SCAN_HORIZONTAL_RANGE);
        assertEquals(4.0D, HealGuardAndPlayerGoal.GUARD_SCAN_VERTICAL_RANGE);
    }

    @Test
    void healingThrowRecordsOnlyAfterConsumptionValidGuardAndSuccessfulSpawn() {
        AtomicInteger records = new AtomicInteger();
        assertTrue(HealGuardAndPlayerGoal.recordConfirmedHealingThrow(
                true, true, true, records::incrementAndGet));
        assertFalse(HealGuardAndPlayerGoal.recordConfirmedHealingThrow(
                false, true, true, records::incrementAndGet));
        assertFalse(HealGuardAndPlayerGoal.recordConfirmedHealingThrow(
                true, false, true, records::incrementAndGet));
        assertFalse(HealGuardAndPlayerGoal.recordConfirmedHealingThrow(
                true, true, false, records::incrementAndGet));
        assertEquals(1, records.get());
    }

    @Test
    void awaitingDeliveryReservesExactlyOneHealingSplashPotion() {
        assertEquals(0, ClericDistributionGoal.countAwaitingPotionUnits(0, 1));
        assertEquals(2, ClericDistributionGoal.countAwaitingPotionUnits(0, 3));
        assertEquals(7, ClericDistributionGoal.countAwaitingPotionUnits(5, 3));
        assertEquals(5, ClericDistributionGoal.countAwaitingPotionUnits(5, 0));
        assertEquals(Long.MAX_VALUE, ClericDistributionGoal.countAwaitingPotionUnits(Long.MAX_VALUE, 2));
    }

    @Test
    void deliveryMetricRequiresDirectValidLibrarianStorageAndReserve() {
        assertTrue(ClericDistributionGoal.isConfirmedDirectLibrarianDelivery(
                AbstractInventoryDistributionGoal.TransferRoute.DIRECT, true, true, true, true));
        assertFalse(ClericDistributionGoal.isConfirmedDirectLibrarianDelivery(
                AbstractInventoryDistributionGoal.TransferRoute.OVERFLOW, true, true, true, true));
        assertFalse(ClericDistributionGoal.isConfirmedDirectLibrarianDelivery(
                AbstractInventoryDistributionGoal.TransferRoute.UNIVERSAL, true, true, true, true));
        assertFalse(ClericDistributionGoal.isConfirmedDirectLibrarianDelivery(
                AbstractInventoryDistributionGoal.TransferRoute.DIRECT, false, true, true, true));
        assertFalse(ClericDistributionGoal.isConfirmedDirectLibrarianDelivery(
                AbstractInventoryDistributionGoal.TransferRoute.DIRECT, true, false, true, true));
        assertFalse(ClericDistributionGoal.isConfirmedDirectLibrarianDelivery(
                AbstractInventoryDistributionGoal.TransferRoute.DIRECT, true, true, false, true));
        assertFalse(ClericDistributionGoal.isConfirmedDirectLibrarianDelivery(
                AbstractInventoryDistributionGoal.TransferRoute.DIRECT, true, true, true, false));
    }

    private static Target select(List<Target> targets) {
        return ClericBrewingGoal.selectPreferredTarget(
                targets,
                target -> target.healing() && target.splash(),
                target -> target.healing() && !target.splash(),
                Target::id,
                Target::splash);
    }

    private record Target(String id, boolean splash, boolean healing) {
    }
}
