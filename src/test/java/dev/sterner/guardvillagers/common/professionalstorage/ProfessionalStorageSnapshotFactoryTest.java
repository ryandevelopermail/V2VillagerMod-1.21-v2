package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.block.enums.ChestType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfessionalStorageSnapshotFactoryTest {
    private static final StorageIdentity STORAGE = new StorageIdentity(World.OVERWORLD, new BlockPos(3, 64, 9));

    @Test
    void unpairedStorageProducesNoSnapshot() {
        assertTrue(create(List.of(), "Chest", false).isEmpty());
    }

    @Test
    void titleGrammarCoversSingularPluralMixedAndQuartermaster() {
        assertEquals("Farmer Storage", snapshot(worker("minecraft:farmer")).title());
        assertEquals("Farmers' Storage", snapshot(
                worker("minecraft:farmer"),
                worker("minecraft:farmer")).title());
        assertEquals("Fishermen's Storage", snapshot(
                worker("minecraft:fisherman"),
                worker("minecraft:fisherman")).title());
        assertEquals("Shared Professional Storage", snapshot(
                worker("minecraft:farmer"),
                worker("minecraft:librarian")).title());
        assertEquals("Quartermaster Bank", snapshot(worker("guardvillagers:quartermaster")).title());
    }

    @Test
    void customNameIsPreservedWithoutHidingProfessionRows() {
        ProfessionalStorageSnapshot snapshot = create(
                List.of(worker("minecraft:librarian")),
                "Rare Books",
                true).orElseThrow();

        assertEquals("Rare Books", snapshot.title());
        assertTrue(snapshot.customTitlePreserved());
        assertEquals("Profession", snapshot.rows().getFirst().label());
        assertEquals("Librarian", snapshot.rows().getFirst().value());
    }

    @Test
    void rowsAreOrderedBoundedAndUseSemanticTones() {
        ProfessionalStorageSnapshot paired = snapshot(worker("minecraft:farmer"));
        assertEquals(List.of("Profession", "Status"), paired.rows().stream().map(ProfessionalStorageRow::label).toList());
        assertEquals(ProfessionalStorageRow.Tone.PAIRED, paired.rows().getLast().tone());

        ProfessionalStorageSnapshot unavailable = snapshot(new ProfessionalStorageSnapshotFactory.WorkerView(
                role("minecraft:farmer"),
                ProfessionalStorageResolution.WorkerAvailability.UNLOADED,
                true));
        assertEquals("Worker unavailable", unavailable.rows().getLast().value());
        assertEquals(ProfessionalStorageRow.Tone.WARNING, unavailable.rows().getLast().tone());

        ProfessionalStorageSnapshot unsupported = snapshot(new ProfessionalStorageSnapshotFactory.WorkerView(
                role("example:beekeeper"),
                ProfessionalStorageResolution.WorkerAvailability.LOADED,
                false));
        assertEquals("No V2 behavior configured", unsupported.rows().getLast().value());
        assertEquals(ProfessionalStorageRow.Tone.BLOCKER, unsupported.rows().getLast().tone());
        assertTrue(unsupported.rows().size() <= ProfessionalStorageSnapshot.MAX_ROWS);
    }

    @Test
    void bothDoubleChestHalvesBuildTheSameSnapshotIdentity() {
        BlockPos left = new BlockPos(10, 64, 10);
        BlockPos right = left.east();
        Map<BlockPos, StorageIdentityResolver.StorageMember> states = Map.of(
                left, StorageIdentityResolver.StorageMember.chest(
                        StorageIdentityResolver.StorageKind.NORMAL_CHEST,
                        ChestType.LEFT,
                        Direction.NORTH),
                right, StorageIdentityResolver.StorageMember.chest(
                        StorageIdentityResolver.StorageKind.NORMAL_CHEST,
                        ChestType.RIGHT,
                        Direction.NORTH));
        StorageIdentity fromLeft = StorageIdentityResolver.resolveMembers(
                World.OVERWORLD,
                left,
                pos -> states.getOrDefault(pos, StorageIdentityResolver.StorageMember.invalid())).orElseThrow();
        StorageIdentity fromRight = StorageIdentityResolver.resolveMembers(
                World.OVERWORLD,
                right,
                pos -> states.getOrDefault(pos, StorageIdentityResolver.StorageMember.invalid())).orElseThrow();

        ProfessionalStorageSnapshot leftSnapshot = ProfessionalStorageSnapshotFactory.create(
                12,
                fromLeft,
                ProfessionalStorageSnapshot.StorageType.NORMAL_CHEST,
                List.of(worker("minecraft:farmer")),
                "Large Chest",
                false).orElseThrow();
        ProfessionalStorageSnapshot rightSnapshot = ProfessionalStorageSnapshotFactory.create(
                12,
                fromRight,
                ProfessionalStorageSnapshot.StorageType.NORMAL_CHEST,
                List.of(worker("minecraft:farmer")),
                "Large Chest",
                false).orElseThrow();

        assertEquals(leftSnapshot, rightSnapshot);
        assertEquals(left, leftSnapshot.canonicalPos());
        assertFalse(leftSnapshot.customTitlePreserved());
    }

    @Test
    void optionalProfessionRowsReplaceGenericRowsWithoutChangingTitleLogic() {
        List<ProfessionalStorageRow> farmerRows = List.of(
                new ProfessionalStorageRow("Status", "Idle", ProfessionalStorageRow.Tone.PAIRED),
                new ProfessionalStorageRow("Hoe available", "Yes", ProfessionalStorageRow.Tone.PAIRED));

        ProfessionalStorageSnapshot snapshot = ProfessionalStorageSnapshotFactory.create(
                44,
                STORAGE,
                ProfessionalStorageSnapshot.StorageType.NORMAL_CHEST,
                List.of(worker("minecraft:farmer")),
                "Chest",
                false,
                Optional.of(farmerRows)).orElseThrow();

        assertEquals(farmerRows, snapshot.rows());
        assertEquals("Farmer Storage", snapshot.title());
    }

    @Test
    void oversizedProfessionRowsFallBackToGenericProfile() {
        List<ProfessionalStorageRow> oversized = java.util.stream.IntStream.range(0, 7)
                .mapToObj(index -> new ProfessionalStorageRow(
                        "Metric " + index,
                        "0",
                        ProfessionalStorageRow.Tone.NORMAL))
                .toList();

        ProfessionalStorageSnapshot snapshot = ProfessionalStorageSnapshotFactory.create(
                45,
                STORAGE,
                ProfessionalStorageSnapshot.StorageType.NORMAL_CHEST,
                List.of(worker("minecraft:farmer")),
                "Chest",
                false,
                Optional.of(oversized)).orElseThrow();

        assertEquals(List.of("Profession", "Status"),
                snapshot.rows().stream().map(ProfessionalStorageRow::label).toList());
    }

    private static ProfessionalStorageSnapshot snapshot(ProfessionalStorageSnapshotFactory.WorkerView... workers) {
        return create(List.of(workers), "Chest", false).orElseThrow();
    }

    private static java.util.Optional<ProfessionalStorageSnapshot> create(
            List<ProfessionalStorageSnapshotFactory.WorkerView> workers,
            String originalTitle,
            boolean custom
    ) {
        return ProfessionalStorageSnapshotFactory.create(
                7,
                STORAGE,
                ProfessionalStorageSnapshot.StorageType.NORMAL_CHEST,
                workers,
                originalTitle,
                custom);
    }

    private static ProfessionalStorageSnapshotFactory.WorkerView worker(String id) {
        return new ProfessionalStorageSnapshotFactory.WorkerView(
                role(id),
                ProfessionalStorageResolution.WorkerAvailability.LOADED,
                true);
    }

    private static ProfessionalRoleId role(String id) {
        return new ProfessionalRoleId(Identifier.of(id));
    }
}
