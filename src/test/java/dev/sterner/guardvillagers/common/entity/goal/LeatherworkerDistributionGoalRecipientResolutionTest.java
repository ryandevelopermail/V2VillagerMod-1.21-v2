package dev.sterner.guardvillagers.common.entity.goal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeatherworkerDistributionGoalRecipientResolutionTest {
    private static final UUID NON_V2 = UUID.fromString("7a000000-0000-0000-0000-000000000001");
    private static final UUID V2 = UUID.fromString("7a000000-0000-0000-0000-000000000002");
    private static final UUID LIBRARIAN = UUID.fromString("7a000000-0000-0000-0000-000000000003");

    @Test
    void resolveItemFrameRecipients_selectsV2CartographerFirst() {
        List<UUID> resolved = resolve(
                List.of(NON_V2, V2),
                List.of(V2),
                List.of(LIBRARIAN),
                new ArrayList<>());
        assertEquals(List.of(V2, LIBRARIAN), resolved);
    }

    @Test
    void resolveItemFrameRecipients_excludesAndReportsNonV2Cartographers() {
        List<UUID> rejected = new ArrayList<>();
        List<UUID> resolved = resolve(
                List.of(NON_V2, V2),
                List.of(V2),
                List.of(LIBRARIAN),
                rejected);
        assertEquals(List.of(V2, LIBRARIAN), resolved);
        assertEquals(List.of(NON_V2), rejected);
    }

    @Test
    void resolveItemFrameRecipients_fallsBackToLibrariansWhenNoV2Exists() {
        List<UUID> rejected = new ArrayList<>();
        List<UUID> resolved = resolve(
                List.of(NON_V2),
                List.of(),
                List.of(LIBRARIAN),
                rejected);
        assertEquals(List.of(LIBRARIAN), resolved);
        assertEquals(List.of(NON_V2), rejected);
    }

    @Test
    void nonFrameGoodsUseLibrariansOnly() {
        List<UUID> resolved = LeatherworkerDistributionGoal.resolveItemFrameRecipients(
                false,
                List.of(NON_V2, V2),
                List.of(V2),
                List.of(LIBRARIAN),
                id -> id,
                ignored -> {
                });
        assertEquals(List.of(LIBRARIAN), resolved);
    }

    private static List<UUID> resolve(
            List<UUID> allCartographers,
            List<UUID> v2Cartographers,
            List<UUID> librarians,
            List<UUID> rejected
    ) {
        return LeatherworkerDistributionGoal.resolveItemFrameRecipients(
                true,
                allCartographers,
                v2Cartographers,
                librarians,
                id -> id,
                rejected::add);
    }
}
