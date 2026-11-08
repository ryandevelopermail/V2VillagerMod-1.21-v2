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
        assertEquals(List.of("Overview"), snapshot.tabs().stream().map(ProfessionalStorageTab::title).toList());
        assertEquals("Profession", rows(snapshot).getFirst().label());
        assertEquals("Librarian", rows(snapshot).getFirst().value());
    }

    @Test
    void rowsAreOrderedBoundedAndUseSemanticTones() {
        ProfessionalStorageSnapshot paired = snapshot(worker("minecraft:farmer"));
        assertEquals(List.of("Profession", "Status"), rows(paired).stream().map(ProfessionalStorageRow::label).toList());
        assertEquals(ProfessionalStorageRow.Tone.PAIRED, rows(paired).getLast().tone());

        ProfessionalStorageSnapshot unavailable = snapshot(new ProfessionalStorageSnapshotFactory.WorkerView(
                role("minecraft:farmer"),
                ProfessionalStorageResolution.WorkerAvailability.UNLOADED,
                true));
        assertEquals("Worker unavailable", rows(unavailable).getLast().value());
        assertEquals(ProfessionalStorageRow.Tone.WARNING, rows(unavailable).getLast().tone());

        ProfessionalStorageSnapshot unsupported = snapshot(new ProfessionalStorageSnapshotFactory.WorkerView(
                role("example:beekeeper"),
                ProfessionalStorageResolution.WorkerAvailability.LOADED,
                false));
        assertEquals("No V2 behavior configured", rows(unsupported).getLast().value());
        assertEquals(ProfessionalStorageRow.Tone.BLOCKER, rows(unsupported).getLast().tone());
        assertEquals(1, unsupported.tabs().size());
    }

    @Test
    void genericFallbackUsesOneOverviewTabAndIncludesSharedWorkerCount() {
        ProfessionalStorageSnapshot shared = snapshot(
                worker("minecraft:farmer"),
                worker("minecraft:farmer"));

        assertEquals(List.of("overview"), shared.tabs().stream().map(ProfessionalStorageTab::id).toList());
        assertEquals(List.of("Profession", "Workers", "Status"),
                rows(shared).stream().map(ProfessionalStorageRow::label).toList());
        assertEquals("2", rows(shared).get(1).value());
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
        List<ProfessionalStorageTab> farmerTabs = List.of(
                new ProfessionalStorageTab("overview", "Overview", List.of(
                        new ProfessionalStorageRow("Status", "Idle", ProfessionalStorageRow.Tone.PAIRED),
                        new ProfessionalStorageRow("Hoe available", "Yes", ProfessionalStorageRow.Tone.PAIRED))),
                new ProfessionalStorageTab("statistics", "Statistics", List.of(
                        new ProfessionalStorageRow("Crops harvested", "9", ProfessionalStorageRow.Tone.NORMAL))));

        ProfessionalStorageSnapshot snapshot = ProfessionalStorageSnapshotFactory.create(
                44,
                STORAGE,
                ProfessionalStorageSnapshot.StorageType.NORMAL_CHEST,
                List.of(worker("minecraft:farmer")),
                "Chest",
                false,
                Optional.of(farmerTabs)).orElseThrow();

        assertEquals(farmerTabs, snapshot.tabs());
        assertEquals("Farmer Storage", snapshot.title());
    }

    @Test
    void oversizedProfessionTabFallsBackToGenericProfile() {
        List<ProfessionalStorageRow> oversized = java.util.stream.IntStream
                .range(0, ProfessionalStorageSnapshot.MAX_ROWS_PER_TAB + 1)
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
                Optional.of(List.of(new ProfessionalStorageTab("oversized", "Oversized", oversized)))).orElseThrow();

        assertEquals(List.of("Profession", "Status"),
                rows(snapshot).stream().map(ProfessionalStorageRow::label).toList());
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

    private static List<ProfessionalStorageRow> rows(ProfessionalStorageSnapshot snapshot) {
        return snapshot.tabs().getFirst().rows();
    }

    private static ProfessionalRoleId role(String id) {
        return new ProfessionalRoleId(Identifier.of(id));
    }
}
