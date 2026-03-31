package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuartermasterGoalSurplusCacheTest {

    @Test
    @SuppressWarnings("unchecked")
    void findSurplusChest_usesCachedPairingsWithoutPerVillagerJobSiteWorldScans() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        when(world.getRegistryKey()).thenReturn(World.OVERWORLD);
        JobBlockPairingHelper.clearWorldCaches(world);

        BlockPos quartermasterJob = new BlockPos(0, 64, 0);
        BlockPos quartermasterChest = new BlockPos(1, 64, 0);
        VillagerEntity quartermaster = villagerWithJobSite(
                world,
                UUID.randomUUID(),
                VillagerProfession.LIBRARIAN,
                quartermasterJob,
                quartermasterChest
        );

        QuartermasterGoal goal = new QuartermasterGoal(quartermaster, quartermasterJob, quartermasterChest);

        for (int i = 0; i < 160; i++) {
            BlockPos workerJob = new BlockPos(16 + i, 64, 16 + i);
            BlockPos workerChest = workerJob.north();
            VillagerEntity worker = villagerWithJobSite(
                    world,
                    UUID.randomUUID(),
                    i % 2 == 0 ? VillagerProfession.FARMER : VillagerProfession.SHEPHERD,
                    workerJob,
                    workerChest
            );

            JobBlockPairingHelper.cacheVillagerChestPairing(world, worker, workerJob, workerChest);
        }

        Method findSurplusChest = QuartermasterGoal.class.getDeclaredMethod("findSurplusChest", ServerWorld.class, BlockPos.class);
        findSurplusChest.setAccessible(true);
        Optional<BlockPos> result = (Optional<BlockPos>) findSurplusChest.invoke(goal, world, quartermasterChest);

        assertTrue(result.isEmpty());

        for (int i = 0; i < 160; i++) {
            BlockPos workerJob = new BlockPos(16 + i, 64, 16 + i);
            verify(world, never()).getBlockState(workerJob);
        }
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
}
