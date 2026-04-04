package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MasonWallBuilderGoalPlacementMaterialTest {

    @Test
    void defaultPlacementSelection_prefersCobblestoneWallAndFallsBackToCobblestone() {
        assertEquals(Items.COBBLESTONE_WALL, MasonWallBuilderGoal.selectPlacementItemForCounts(4, 64));
        assertEquals(Items.COBBLESTONE, MasonWallBuilderGoal.selectPlacementItemForCounts(0, 64));
    }
}
