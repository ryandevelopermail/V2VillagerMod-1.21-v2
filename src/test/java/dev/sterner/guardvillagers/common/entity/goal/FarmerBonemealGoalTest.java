package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
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

class FarmerBonemealGoalTest {

    @Test
    void consumeOneBonemeal_reducesChestCountByOne() {
        SimpleInventory inventory = new SimpleInventory(1);
        inventory.setStack(0, new ItemStack(Items.BONE_MEAL, 2));

        assertTrue(FarmerBonemealGoal.consumeOneBonemeal(inventory));
        assertEquals(1, FarmerBonemealGoal.countBonemeal(inventory));
    }

    @Test
    void validCropTarget_prefersImmatureCropAndRejectsMature() {
        ServerWorld world = mock(ServerWorld.class);
        BlockPos pos = new BlockPos(3, 64, 3);
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        BlockState immature = wheat.withAge(2);
        BlockState mature = wheat.withAge(wheat.getMaxAge());

        when(world.getBlockState(pos)).thenReturn(immature);
        assertTrue(FarmerBonemealGoal.isValidCropTarget(world, pos));

        when(world.getBlockState(pos)).thenReturn(mature);
        assertFalse(FarmerBonemealGoal.isValidCropTarget(world, pos));
    }

    @Test
    void noEligibleTargets_keepsBonemealUnchanged() {
        SimpleInventory inventory = new SimpleInventory(1);
        inventory.setStack(0, new ItemStack(Items.BONE_MEAL, 6));

        int before = FarmerBonemealGoal.countBonemeal(inventory);
        int eligibleTargets = 0;
        for (int i = 0; i < eligibleTargets; i++) {
            FarmerBonemealGoal.consumeOneBonemeal(inventory);
        }

        assertEquals(before, FarmerBonemealGoal.countBonemeal(inventory));
    }
}
