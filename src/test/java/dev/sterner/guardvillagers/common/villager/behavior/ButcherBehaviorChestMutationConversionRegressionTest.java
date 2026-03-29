package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import dev.sterner.guardvillagers.common.villager.VillagerConversionCandidateIndex;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ButcherBehaviorChestMutationConversionRegressionTest {

    @AfterEach
    void clearWatcherState() throws Exception {
        watcherMap().clear();
    }

    @Test
    void pairedButcherWithoutInitialWeapon_rechecksConversionWhenChestMutatesLater() throws Exception {
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity villager = mock(VillagerEntity.class);
        BlockPos chestPos = new BlockPos(3, 64, 7);

        when(villager.isAlive()).thenReturn(true);
        when(villager.getWorld()).thenReturn(world);

        // Simulate existing chest pairing while no weapon is present yet.
        watcherMap().computeIfAbsent(chestPos, ignored -> new HashSet<>()).add(villager);

        try (MockedStatic<VillagerConversionCandidateIndex> candidateIndex = Mockito.mockStatic(VillagerConversionCandidateIndex.class);
             MockedStatic<ProfessionDefinitions> professionDefinitions = Mockito.mockStatic(ProfessionDefinitions.class)) {

            // No reload/chunk scan: nothing happens until the later mutation event is fired.
            candidateIndex.verifyNoInteractions();
            professionDefinitions.verifyNoInteractions();

            // Simulate weapon insertion later by firing chest mutation for the already-paired butcher.
            ButcherBehavior.onChestInventoryMutated(world, chestPos);

            candidateIndex.verify(() -> VillagerConversionCandidateIndex.markCandidate(world, villager));
            professionDefinitions.verify(() -> ProfessionDefinitions.runConversionHooks(world));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<BlockPos, Set<VillagerEntity>> watcherMap() throws Exception {
        Field field = ButcherBehavior.class.getDeclaredField("CHEST_WATCHERS_BY_POS");
        field.setAccessible(true);
        return (Map<BlockPos, Set<VillagerEntity>>) field.get(null);
    }
}
