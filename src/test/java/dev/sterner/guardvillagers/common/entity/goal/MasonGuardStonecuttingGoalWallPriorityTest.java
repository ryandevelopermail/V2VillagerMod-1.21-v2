package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MasonGuardStonecuttingGoalWallPriorityTest {

    @Test
    void wallBuildPending_prioritizesCobblestoneWallOutputFirst() {
        int selectedIndex = MasonGuardStonecuttingGoal.pickRecipeIndex(
                List.of(Items.STONE_BRICKS, Items.COBBLESTONE_WALL, Items.STONE_STAIRS),
                Items.STONE_BRICKS,
                0,
                true
        );

        assertEquals(1, selectedIndex);
    }
}
