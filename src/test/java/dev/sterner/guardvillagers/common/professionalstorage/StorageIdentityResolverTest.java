package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.block.enums.ChestType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageIdentityResolverTest {
    @Test
    void singleChestAndBarrelUseTheirOwnPositions() {
        BlockPos chestPos = new BlockPos(4, 64, 7);
        BlockPos barrelPos = new BlockPos(9, 64, 7);
        Map<BlockPos, StorageIdentityResolver.StorageMember> states = Map.of(
                chestPos, chest(false, ChestType.SINGLE, Direction.NORTH),
                barrelPos, StorageIdentityResolver.StorageMember.barrel());

        assertEquals(chestPos, resolve(chestPos, states).orElseThrow().canonicalPos());
        assertEquals(barrelPos, resolve(barrelPos, states).orElseThrow().canonicalPos());
    }

    @Test
    void bothHalvesOfDoubleChestResolveToSameCanonicalIdentity() {
        BlockPos left = new BlockPos(10, 64, 10);
        BlockPos right = left.east();
        Map<BlockPos, StorageIdentityResolver.StorageMember> states = Map.of(
                left, chest(false, ChestType.LEFT, Direction.NORTH),
                right, chest(false, ChestType.RIGHT, Direction.NORTH));

        StorageIdentity fromLeft = resolve(left, states).orElseThrow();
        StorageIdentity fromRight = resolve(right, states).orElseThrow();

        assertEquals(fromLeft, fromRight);
        assertEquals(left, fromLeft.canonicalPos());
    }

    @Test
    void unrelatedNeighboringContainersRemainSeparate() {
        BlockPos first = new BlockPos(0, 64, 0);
        BlockPos second = first.east();
        Map<BlockPos, StorageIdentityResolver.StorageMember> states = Map.of(
                first, chest(false, ChestType.SINGLE, Direction.NORTH),
                second, chest(false, ChestType.SINGLE, Direction.SOUTH));

        assertNotEquals(resolve(first, states), resolve(second, states));
    }

    @Test
    void normalAndTrappedChestHalvesNeverCombine() {
        BlockPos normal = new BlockPos(0, 64, 0);
        BlockPos trapped = normal.east();
        Map<BlockPos, StorageIdentityResolver.StorageMember> states = Map.of(
                normal, chest(false, ChestType.LEFT, Direction.NORTH),
                trapped, chest(true, ChestType.RIGHT, Direction.NORTH));

        assertTrue(resolve(normal, states).isEmpty());
        assertTrue(resolve(trapped, states).isEmpty());
    }

    private static Optional<StorageIdentity> resolve(
            BlockPos pos,
            Map<BlockPos, StorageIdentityResolver.StorageMember> states
    ) {
        return StorageIdentityResolver.resolveMembers(
                World.OVERWORLD,
                pos,
                lookup -> states.getOrDefault(lookup, StorageIdentityResolver.StorageMember.invalid()));
    }

    private static StorageIdentityResolver.StorageMember chest(
            boolean trapped,
            ChestType type,
            Direction facing
    ) {
        return StorageIdentityResolver.StorageMember.chest(
                trapped
                        ? StorageIdentityResolver.StorageKind.TRAPPED_CHEST
                        : StorageIdentityResolver.StorageKind.NORMAL_CHEST,
                type,
                facing);
    }
}
