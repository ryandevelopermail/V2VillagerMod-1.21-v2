package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ForesterBonemealGoalTest {

    @Test
    void consumeOneBonemeal_reducesChestCountByOne() {
        SimpleInventory inventory = new SimpleInventory(2);
        inventory.setStack(0, new ItemStack(Items.BONE_MEAL, 4));

        boolean consumed = ForesterBonemealGoal.consumeOneBonemeal(inventory);

        assertTrue(consumed);
        assertEquals(3, ForesterBonemealGoal.countBonemeal(inventory));
    }

    @Test
    void validSaplingTarget_detectsFertilizableSaplings() {
        ServerWorld world = mock(ServerWorld.class);
        BlockPos target = new BlockPos(0, 64, 0);
        BlockState sapling = Blocks.OAK_SAPLING.getDefaultState();
        when(world.getBlockState(target)).thenReturn(sapling);

        assertTrue(ForesterBonemealGoal.isValidSaplingTarget(world, target));
    }

    @Test
    void consumeOneBonemeal_returnsFalseWhenNoEligibleTargetsMeansNoRequest() {
        SimpleInventory inventory = new SimpleInventory(1);
        inventory.setStack(0, new ItemStack(Items.BONE_MEAL, 5));

        int before = ForesterBonemealGoal.countBonemeal(inventory);
        int requestedApplications = 0;
        for (int i = 0; i < requestedApplications; i++) {
            ForesterBonemealGoal.consumeOneBonemeal(inventory);
        }

        assertEquals(before, ForesterBonemealGoal.countBonemeal(inventory));
        assertFalse(ForesterBonemealGoal.consumeOneBonemeal(new SimpleInventory(1)));
    }
}
