package dev.sterner.guardvillagers.common.util;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageWallProjectStateSchedulerTest {

    private static final RegistryKey<World> OVERWORLD = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"));

    @Test
    void scheduler_persistsAssignmentAndParticipationAcrossNbtRoundTrip() throws Exception {
        VillageWallProjectState state = new VillageWallProjectState();
        BlockPos anchor = new BlockPos(10, 64, 10);
        state.upsertProject(
                OVERWORLD,
                anchor,
                new VillageWallProjectState.PerimeterBounds(0, 20, 0, 20),
                new VillageWallProjectState.PerimeterSignature(8, 12345)
        );

        UUID masonA = UUID.randomUUID();
        state.assignBuilder(OVERWORLD, anchor, masonA, 100L);
        state.markBuilderProgress(OVERWORLD, anchor, masonA, 140L);
        state.markBuilderProgress(OVERWORLD, anchor, masonA, 180L);

        NbtCompound out = state.writeNbt(new NbtCompound(), null);

        Method fromNbt = VillageWallProjectState.class.getDeclaredMethod("fromNbt", NbtCompound.class, RegistryWrapper.WrapperLookup.class);
        fromNbt.setAccessible(true);
        VillageWallProjectState restored = (VillageWallProjectState) fromNbt.invoke(null, out, null);

        VillageWallProjectState.ProjectAssignmentSnapshot assignment = restored.getAssignmentSnapshot(OVERWORLD, anchor).orElseThrow();
        assertEquals(masonA, assignment.builderUuid());
        assertEquals(100L, assignment.assignmentStartTick());
        assertEquals(180L, assignment.lastSegmentPlacedTick());

        VillageWallProjectState.ParticipationStats stats = restored.getParticipationStats(OVERWORLD, anchor).get(masonA);
        assertEquals(2, stats.segmentsPlaced());
        assertEquals(1, stats.sessionsRun());
    }

    @Test
    void scheduler_tracksMultiMasonParticipationAcrossRepeatedSessions() {
        VillageWallProjectState state = new VillageWallProjectState();
        BlockPos anchor = new BlockPos(0, 70, 0);
        state.upsertProject(
                OVERWORLD,
                anchor,
                new VillageWallProjectState.PerimeterBounds(-5, 5, -5, 5),
                new VillageWallProjectState.PerimeterSignature(4, 77)
        );

        UUID masonA = UUID.randomUUID();
        UUID masonB = UUID.randomUUID();

        state.assignBuilder(OVERWORLD, anchor, masonA, 20L);
        state.markBuilderProgress(OVERWORLD, anchor, masonA, 25L);
        state.clearAssignment(OVERWORLD, anchor);

        state.assignBuilder(OVERWORLD, anchor, masonB, 40L);
        state.markBuilderProgress(OVERWORLD, anchor, masonB, 46L);
        state.markBuilderProgress(OVERWORLD, anchor, masonB, 49L);

        Map<UUID, VillageWallProjectState.ParticipationStats> stats = state.getParticipationStats(OVERWORLD, anchor);
        assertEquals(1, stats.get(masonA).sessionsRun());
        assertEquals(1, stats.get(masonA).segmentsPlaced());
        assertEquals(1, stats.get(masonB).sessionsRun());
        assertEquals(2, stats.get(masonB).segmentsPlaced());
        assertTrue(state.getAssignmentSnapshot(OVERWORLD, anchor).isPresent());
    }
}
