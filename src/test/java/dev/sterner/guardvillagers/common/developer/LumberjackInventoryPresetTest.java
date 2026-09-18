package dev.sterner.guardvillagers.common.developer;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static dev.sterner.guardvillagers.common.developer.LumberjackInventoryPreset.Item.OAK_FENCE;
import static dev.sterner.guardvillagers.common.developer.LumberjackInventoryPreset.Item.OAK_FENCE_GATE;
import static dev.sterner.guardvillagers.common.developer.LumberjackInventoryPreset.Item.OAK_LOG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumberjackInventoryPresetTest {
    @Test
    void emptyPresetContainsNothing() {
        assertTrue(LumberjackInventoryPreset.EMPTY_NATURAL.contents().isEmpty());
    }

    @Test
    void charcoalPresetProvidesModestLogSupply() {
        assertEquals(Map.of(OAK_LOG, 16), LumberjackInventoryPreset.CHARCOAL_TEST.contents());
    }

    @Test
    void shepherdSupplyPresetMatchesCurrentDistributionBatchMaterials() {
        Map<LumberjackInventoryPreset.Item, Integer> contents = LumberjackInventoryPreset.SHEPHERD_SUPPLY_TEST.contents();
        assertEquals("Shepherd Supply Test", LumberjackInventoryPreset.SHEPHERD_SUPPLY_TEST.displayName());
        assertSame(
                LumberjackInventoryPreset.SHEPHERD_SUPPLY_TEST,
                LumberjackInventoryPreset.fromNetworkId(2).orElseThrow());
        assertEquals(8, contents.get(OAK_FENCE));
        assertEquals(1, contents.get(OAK_FENCE_GATE));
        assertFalse(contents.containsKey(OAK_LOG));
        assertTrue(contents.get(OAK_FENCE) < 20, "Supply batch must not trigger the Lumberjack pen builder.");
    }

    @Test
    void explicitOptionsEnsureMaterialsWithoutDuplicatingPresetCounts() {
        Map<LumberjackInventoryPreset.Item, Integer> plan = LumberjackInventoryPreset.createPlan(
                LumberjackInventoryPreset.GENERAL_MIXED_TEST,
                true,
                true
        );
        assertEquals(16, plan.get(OAK_LOG));
        assertEquals(8, plan.get(OAK_FENCE));
        assertEquals(1, plan.get(OAK_FENCE_GATE));
    }
}
