package dev.sterner.guardvillagers.common.villager;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumberjackBootstrapChopRunnerTest {

    @Test
    void isGatherableBootstrapTreeDrop_acceptsSameTreeDropCategoriesAsGuardGoal() {
        assertTrue(LumberjackBootstrapChopRunner.isGatherableBootstrapTreeDrop(new ItemStack(Items.OAK_LOG)));
        assertTrue(LumberjackBootstrapChopRunner.isGatherableBootstrapTreeDrop(new ItemStack(Items.OAK_PLANKS)));
        assertTrue(LumberjackBootstrapChopRunner.isGatherableBootstrapTreeDrop(new ItemStack(Items.STICK)));
        assertTrue(LumberjackBootstrapChopRunner.isGatherableBootstrapTreeDrop(new ItemStack(Items.CHARCOAL)));
        assertTrue(LumberjackBootstrapChopRunner.isGatherableBootstrapTreeDrop(new ItemStack(Items.OAK_SAPLING)));
        assertTrue(LumberjackBootstrapChopRunner.isGatherableBootstrapTreeDrop(new ItemStack(Items.APPLE)));
        assertFalse(LumberjackBootstrapChopRunner.isGatherableBootstrapTreeDrop(new ItemStack(Items.DIRT)));
    }
}
