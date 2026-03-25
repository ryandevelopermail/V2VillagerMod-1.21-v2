package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShepherdFenceCraftingGoalTableRequirementTest {

    @Test
    void requiresCraftingTable_isTrueForFenceCrafting() throws Exception {
        ShepherdFenceCraftingGoal goal = new ShepherdFenceCraftingGoal(null, null, null, null);
        Method method = ShepherdFenceCraftingGoal.class.getDeclaredMethod("requiresCraftingTable");
        method.setAccessible(true);

        boolean requiresTable = (boolean) method.invoke(goal);

        assertTrue(requiresTable);
    }
}
