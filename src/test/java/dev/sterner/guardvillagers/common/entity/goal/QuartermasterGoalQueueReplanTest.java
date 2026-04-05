package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.RecipeType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuartermasterGoalQueueReplanTest {

    @Test
    void demandQueue_rebuildsAfterMutationSignalAndReflectsNewInventory() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        RecipeManager recipeManager = mock(RecipeManager.class);
        when(world.getRecipeManager()).thenReturn(recipeManager);
        when(recipeManager.listAllOfType(RecipeType.CRAFTING)).thenReturn(List.of());
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        when(world.getTime()).thenReturn(1000L, 1001L);
        JobBlockPairingHelper.clearWorldCaches(world);

        BlockPos qmJob = new BlockPos(0, 64, 0);
        BlockPos qmChest = new BlockPos(1, 64, 0);
        VillagerEntity quartermaster = villagerWithJobSite(world, UUID.randomUUID(), VillagerProfession.LIBRARIAN, qmJob, qmChest);

        BlockPos farmerJob = new BlockPos(6, 64, 6);
        BlockPos farmerChest = new BlockPos(7, 64, 6);
        VillagerEntity farmer = villagerWithJobSite(world, UUID.randomUUID(), VillagerProfession.FARMER, farmerJob, farmerChest);
        JobBlockPairingHelper.cacheVillagerChestPairing(world, farmer, farmerJob, farmerChest);

        TestQuartermasterGoal goal = new TestQuartermasterGoal(quartermaster, qmJob, qmChest);
        SimpleInventory qmInventory = new SimpleInventory(3);
        qmInventory.setStack(0, new ItemStack(Items.OAK_PLANKS, 64));
        qmInventory.setStack(1, new ItemStack(Items.STICK, 64));
        goal.setInventory(qmChest, qmInventory);
        goal.setInventory(farmerChest, new SimpleInventory(3));

        Method rebuildDemandQueue = QuartermasterGoal.class.getDeclaredMethod("rebuildDemandQueue", ServerWorld.class, BlockPos.class);
        rebuildDemandQueue.setAccessible(true);

        rebuildDemandQueue.invoke(goal, world, qmChest);
        List<?> firstQueue = QuartermasterGoal.getDemandQueueForTest(goal);
        assertFalse(firstQueue.isEmpty());

        // Mid-cycle inventory mutation: farmer is filled up, queue must replan to empty.
        SimpleInventory fullFarmerInventory = new SimpleInventory(3);
        fullFarmerInventory.setStack(0, new ItemStack(Items.OAK_PLANKS, 32));
        fullFarmerInventory.setStack(1, new ItemStack(Items.STICK, 32));
        goal.setInventory(farmerChest, fullFarmerInventory);
        goal.requestImmediateDemandReplan();

        rebuildDemandQueue.invoke(goal, world, qmChest);
        List<?> secondQueue = QuartermasterGoal.getDemandQueueForTest(goal);
        assertTrue(secondQueue.isEmpty());

        JobBlockPairingHelper.clearWorldCaches(world);
    }

    @SuppressWarnings("unchecked")
    private static VillagerEntity villagerWithJobSite(ServerWorld world,
                                                      UUID uuid,
                                                      VillagerProfession profession,
                                                      BlockPos jobSite,
                                                      BlockPos chestPos) {
        VillagerEntity villager = mock(VillagerEntity.class);
        VillagerData data = mock(VillagerData.class);
        Brain<VillagerEntity> brain = (Brain<VillagerEntity>) mock(Brain.class);
        BlockState chestState = mock(BlockState.class);

        when(villager.isAlive()).thenReturn(true);
        when(villager.isRemoved()).thenReturn(false);
        when(villager.getUuid()).thenReturn(uuid);
        when(villager.getVillagerData()).thenReturn(data);
        when(villager.getBrain()).thenReturn(brain);
        when(data.getProfession()).thenReturn(profession);
        when(brain.getOptionalMemory(MemoryModuleType.JOB_SITE)).thenReturn(Optional.of(GlobalPos.create(world.getRegistryKey(), jobSite)));

        when(world.getEntity(uuid)).thenReturn(villager);
        when(world.getBlockState(chestPos)).thenReturn(chestState);
        when(chestState.getBlock()).thenReturn(Blocks.BARREL);

        return villager;
    }

    private static final class TestQuartermasterGoal extends QuartermasterGoal {
        private final Map<BlockPos, Inventory> inventories = new HashMap<>();

        private TestQuartermasterGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
            super(villager, jobPos, chestPos);
        }

        void setInventory(BlockPos pos, Inventory inventory) {
            inventories.put(pos, inventory);
        }

        @Override
        protected Optional<Inventory> getInventory(ServerWorld world, BlockPos pos) {
            return Optional.ofNullable(inventories.get(pos));
        }
    }
}
