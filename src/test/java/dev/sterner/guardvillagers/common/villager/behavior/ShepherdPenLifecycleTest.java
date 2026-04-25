package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.util.ShepherdPenStateHolder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class ShepherdPenLifecycleTest {

    @Test
    void nearbyForeignPenDoesNotBlockFirstBuild() {
        VillagerEntity villager = mockShepherd(false, new BlockPos(0, 64, 0));
        ServerWorld world = mock(ServerWorld.class);

        boolean blocked = ShepherdPenLifecycle.shouldBlockPenConstruction(villager, world, mock(Logger.class), "ShepherdFence");

        assertFalse(blocked);
    }

    @Test
    void shepherdBuildsExactlyOnePen_whenRebuildDisabled() {
        boolean original = GuardVillagersConfig.shepherdAllowPenRebuildIfMissing;
        GuardVillagersConfig.shepherdAllowPenRebuildIfMissing = false;
        try {
            BlockPos anchor = new BlockPos(4, 64, 4);
            VillagerEntity villager = mockShepherd(false, null);
            ShepherdPenLifecycle.markPenConstructed(villager, anchor);

            boolean blockedFirstRepeat = ShepherdPenLifecycle.shouldBlockPenConstruction(villager, mock(ServerWorld.class), mock(Logger.class), "ShepherdFence");
            boolean blockedSecondRepeat = ShepherdPenLifecycle.shouldBlockPenConstruction(villager, mock(ServerWorld.class), mock(Logger.class), "ShepherdFencePlacer");

            assertTrue(blockedFirstRepeat);
            assertTrue(blockedSecondRepeat);
        } finally {
            GuardVillagersConfig.shepherdAllowPenRebuildIfMissing = original;
        }
    }

    @Test
    void completionStatePreventsRepeatedFenceGateChurn() {
        boolean original = GuardVillagersConfig.shepherdAllowPenRebuildIfMissing;
        GuardVillagersConfig.shepherdAllowPenRebuildIfMissing = false;
        try {
            VillagerEntity villager = mockShepherd(true, new BlockPos(8, 64, 8));

            assertTrue(ShepherdPenLifecycle.shouldBlockPenConstruction(villager, mock(ServerWorld.class), mock(Logger.class), "ShepherdFence"));
            assertTrue(ShepherdPenLifecycle.shouldBlockPenConstruction(villager, mock(ServerWorld.class), mock(Logger.class), "ShepherdFencePlacer"));
        } finally {
            GuardVillagersConfig.shepherdAllowPenRebuildIfMissing = original;
        }
    }

    @Test
    void rebuildAllowed_whenOwnedPenAnchorMissing_clearsOwnershipState() {
        boolean original = GuardVillagersConfig.shepherdAllowPenRebuildIfMissing;
        GuardVillagersConfig.shepherdAllowPenRebuildIfMissing = true;
        try {
            BlockPos anchor = new BlockPos(2, 70, -3);
            VillagerEntity villager = mockShepherd(true, anchor);
            ServerWorld world = mock(ServerWorld.class);
            BlockState air = mock(BlockState.class);
            when(air.getBlock()).thenReturn(Blocks.AIR);
            when(world.getBlockState(anchor)).thenReturn(air);

            boolean blocked = ShepherdPenLifecycle.shouldBlockPenConstruction(villager, world, mock(Logger.class), "ShepherdFence");

            assertFalse(blocked);
            assertFalse(ShepherdPenLifecycle.hasConstructedPen(villager));
        } finally {
            GuardVillagersConfig.shepherdAllowPenRebuildIfMissing = original;
        }
    }

    private static VillagerEntity mockShepherd(boolean hasConstructedPen, BlockPos ownedAnchor) {
        AtomicBoolean constructed = new AtomicBoolean(hasConstructedPen);
        AtomicReference<BlockPos> anchor = new AtomicReference<>(ownedAnchor);

        VillagerEntity villager = mock(VillagerEntity.class, withSettings().extraInterfaces(ShepherdPenStateHolder.class));
        ShepherdPenStateHolder holder = (ShepherdPenStateHolder) villager;

        when(villager.getUuidAsString()).thenReturn("test-shepherd");
        when(holder.guardvillagers$hasConstructedPen()).thenAnswer(invocation -> constructed.get());
        doAnswer(invocation -> {
            constructed.set(invocation.getArgument(0));
            return null;
        }).when(holder).guardvillagers$setHasConstructedPen(anyBoolean());
        when(holder.guardvillagers$getOwnedPenAnchor()).thenAnswer(invocation -> anchor.get());
        doAnswer(invocation -> {
            anchor.set(invocation.getArgument(0));
            return null;
        }).when(holder).guardvillagers$setOwnedPenAnchor(any());

        return villager;
    }
}
