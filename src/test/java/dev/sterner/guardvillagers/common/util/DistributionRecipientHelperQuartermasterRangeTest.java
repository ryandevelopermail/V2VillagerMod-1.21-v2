package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DistributionRecipientHelperQuartermasterRangeTest {

    private final int originalQuartermasterRange = GuardVillagersConfig.quartermasterScanRange;
    private final int originalProfessionalRange = GuardVillagersConfig.professionalRecipientScanRange;
    private final int originalProfessionalWideRange = GuardVillagersConfig.professionalRecipientWideScanRange;

    @AfterEach
    void restoreConfig() {
        GuardVillagersConfig.quartermasterScanRange = originalQuartermasterRange;
        GuardVillagersConfig.professionalRecipientScanRange = originalProfessionalRange;
        GuardVillagersConfig.professionalRecipientWideScanRange = originalProfessionalWideRange;
    }

    @Test
    void activeQuartermasterDiscovery_usesQuartermasterOperatingRange() {
        GuardVillagersConfig.quartermasterScanRange = 96;
        GuardVillagersConfig.professionalRecipientScanRange = 32;
        GuardVillagersConfig.professionalRecipientWideScanRange = 64;

        ServerWorld world = mock(ServerWorld.class);
        VillagerEntity source = mock(VillagerEntity.class);
        BlockPos sourcePos = new BlockPos(10, 64, -5);
        when(source.isAlive()).thenReturn(true);
        when(source.getBlockPos()).thenReturn(sourcePos);
        when(world.getEntitiesByClass(eq(VillagerEntity.class), any(Box.class), any(Predicate.class)))
                .thenReturn(List.of());

        DistributionRecipientHelper.findEligibleQuartermasterRecipients(world, source, 24.0D);

        ArgumentCaptor<Box> scanBoxes = ArgumentCaptor.forClass(Box.class);
        verify(world, times(2)).getEntitiesByClass(eq(VillagerEntity.class), scanBoxes.capture(), any(Predicate.class));
        Box box = scanBoxes.getAllValues().getLast();
        assertEquals(sourcePos.getX() - 96.0D, box.minX);
        assertEquals(sourcePos.getX() + 1.0D + 96.0D, box.maxX);
        assertEquals(sourcePos.getZ() - 96.0D, box.minZ);
        assertEquals(sourcePos.getZ() + 1.0D + 96.0D, box.maxZ);
    }
}
