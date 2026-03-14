package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LumberjackChestTriggerControllerTest {

    @Test
    void villageExpansionScanBox_prefersPairedJobPosWhenGuardIsFarAway() {
        BlockPos pairedJobPos = new BlockPos(1000, 64, 1000);
        BlockPos guardPos = new BlockPos(0, 64, 0);

        Box scanBox = LumberjackChestTriggerController.villageExpansionScanBox(pairedJobPos, guardPos);

        assertEquals(700.0D, scanBox.minX);
        assertEquals(364.0D, scanBox.minY);
        assertEquals(700.0D, scanBox.minZ);
        assertEquals(1301.0D, scanBox.maxX);
        assertEquals(665.0D, scanBox.maxY);
        assertEquals(1301.0D, scanBox.maxZ);
    }

    @Test
    void villageExpansionScanBox_fallsBackToGuardPosWhenPairedJobPosMissing() {
        BlockPos guardPos = new BlockPos(40, 70, -15);

        Box scanBox = LumberjackChestTriggerController.villageExpansionScanBox(null, guardPos);

        assertEquals(-260.0D, scanBox.minX);
        assertEquals(-230.0D, scanBox.minY);
        assertEquals(-315.0D, scanBox.minZ);
        assertEquals(341.0D, scanBox.maxX);
        assertEquals(371.0D, scanBox.maxY);
        assertEquals(286.0D, scanBox.maxZ);
    }
}
