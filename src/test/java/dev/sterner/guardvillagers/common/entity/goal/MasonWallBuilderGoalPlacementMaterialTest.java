package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MasonWallBuilderGoalPlacementMaterialTest {

    @Test
    void defaultPlacementSelection_prefersCobblestoneWallAndDoesNotFallbackToCobblestone() {
        assertEquals(Items.COBBLESTONE_WALL, MasonWallBuilderGoal.selectPlacementItemForCounts(4, 64));
        assertNull(MasonWallBuilderGoal.selectPlacementItemForCounts(0, 64));
    }
}
