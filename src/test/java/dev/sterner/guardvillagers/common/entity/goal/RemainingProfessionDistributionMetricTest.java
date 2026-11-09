package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.professionalstorage.RemainingProfessionWorkMetrics;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemainingProfessionDistributionMetricTest {
    @Test
    void masonCountsOnlyCompletedDirectDeliveries() {
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity mason = mock(VillagerEntity.class);
        UUID worker = UUID.randomUUID();
        when(mason.getUuid()).thenReturn(worker);
        MasonToLibrarianDistributionGoal goal = new MasonToLibrarianDistributionGoal(mason, BlockPos.ORIGIN, BlockPos.ORIGIN, null);
        ItemStack transferred = new ItemStack(Items.STONE);
        try (MockedStatic<RemainingProfessionWorkMetrics> metrics = Mockito.mockStatic(RemainingProfessionWorkMetrics.class)) {
            goal.onTransferCompleted(world, transferred, null, BlockPos.ORIGIN, AbstractInventoryDistributionGoal.TransferRoute.UNIVERSAL);
            metrics.verifyNoInteractions();
            goal.onTransferCompleted(world, transferred, null, BlockPos.ORIGIN, AbstractInventoryDistributionGoal.TransferRoute.DIRECT);
            metrics.verify(() -> RemainingProfessionWorkMetrics.record(world, worker, RemainingProfessionWorkMetrics.MASON,
                    RemainingProfessionWorkMetrics.MASON_DELIVERED, 1));
            metrics.verifyNoMoreInteractions();
        }
    }

    @Test
    void shepherdCountsOnlyCompletedDirectDeliveries() {
        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity shepherd = mock(VillagerEntity.class);
        UUID worker = UUID.randomUUID();
        when(shepherd.getUuid()).thenReturn(worker);
        ShepherdToLibrarianDistributionGoal goal = new ShepherdToLibrarianDistributionGoal(shepherd, BlockPos.ORIGIN, BlockPos.ORIGIN, null);
        ItemStack transferred = new ItemStack(Items.WHITE_WOOL);
        try (MockedStatic<RemainingProfessionWorkMetrics> metrics = Mockito.mockStatic(RemainingProfessionWorkMetrics.class)) {
            goal.onTransferCompleted(world, transferred, null, BlockPos.ORIGIN, AbstractInventoryDistributionGoal.TransferRoute.UNIVERSAL);
            metrics.verifyNoInteractions();
            goal.onTransferCompleted(world, transferred, null, BlockPos.ORIGIN, AbstractInventoryDistributionGoal.TransferRoute.DIRECT);
            metrics.verify(() -> RemainingProfessionWorkMetrics.record(world, worker, RemainingProfessionWorkMetrics.SHEPHERD,
                    RemainingProfessionWorkMetrics.SHEPHERD_DELIVERED, 1));
            metrics.verifyNoMoreInteractions();
        }
    }
}
