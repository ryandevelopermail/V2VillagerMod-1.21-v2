package dev.sterner.guardvillagers.common.professionalstorage;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Builds the bounded, generic UI model without exposing worker internals to the client. */
public final class ProfessionalStorageSnapshotFactory {
    private ProfessionalStorageSnapshotFactory() {
    }

    public static Optional<ProfessionalStorageSnapshot> create(
            int syncId,
            StorageIdentity storage,
            ProfessionalStorageSnapshot.StorageType storageType,
            List<WorkerView> workers,
            String originalTitle,
            boolean preserveCustomTitle
    ) {
        if (workers.isEmpty()) {
            return Optional.empty();
        }

        Set<ProfessionalRoleId> roles = new LinkedHashSet<>();
        for (WorkerView worker : workers) {
            roles.add(worker.role());
        }
        ProfessionalStorageSnapshot.RoleState roleState = roles.size() > 1
                ? ProfessionalStorageSnapshot.RoleState.MIXED_ROLE_SHARED
                : workers.size() > 1
                ? ProfessionalStorageSnapshot.RoleState.SAME_ROLE_SHARED
                : ProfessionalStorageSnapshot.RoleState.SINGLE_WORKER;

        ProfessionalRoleId firstRole = workers.getFirst().role();
        String profession = roles.size() == 1 ? displayName(firstRole) : "Mixed professions";
        String generatedTitle = titleFor(firstRole, workers.size(), roles.size());
        String title = preserveCustomTitle ? originalTitle : generatedTitle;

        var rows = new java.util.ArrayList<ProfessionalStorageRow>();
        rows.add(new ProfessionalStorageRow("Profession", profession, ProfessionalStorageRow.Tone.NORMAL));
        if (workers.size() > 1) {
            rows.add(new ProfessionalStorageRow("Workers", Integer.toString(workers.size()), ProfessionalStorageRow.Tone.NORMAL));
        }

        boolean missingBehavior = workers.stream().anyMatch(worker -> !worker.behaviorConfigured());
        boolean unavailable = workers.stream().anyMatch(worker ->
                worker.availability() == ProfessionalStorageResolution.WorkerAvailability.UNLOADED);
        if (missingBehavior) {
            rows.add(new ProfessionalStorageRow(
                    "Status",
                    "No V2 behavior configured",
                    ProfessionalStorageRow.Tone.BLOCKER));
        } else if (unavailable) {
            rows.add(new ProfessionalStorageRow(
                    "Status",
                    "Worker unavailable",
                    ProfessionalStorageRow.Tone.WARNING));
        } else {
            rows.add(new ProfessionalStorageRow("Status", "Paired", ProfessionalStorageRow.Tone.PAIRED));
        }

        return Optional.of(new ProfessionalStorageSnapshot(
                syncId,
                storage.dimension().getValue().toString(),
                storage.canonicalPos(),
                storageType,
                roleState,
                workers.size(),
                title,
                preserveCustomTitle,
                rows));
    }

    static String titleFor(ProfessionalRoleId role, int workerCount, int distinctRoleCount) {
        if (distinctRoleCount > 1) {
            return "Shared Professional Storage";
        }
        if (role.equals(ProfessionalRoleId.QUARTERMASTER)) {
            return "Quartermaster Bank";
        }
        String roleName = displayName(role);
        if (workerCount == 1) {
            return roleName + " Storage";
        }
        String pluralRole = pluralize(roleName);
        return pluralRole + (pluralRole.endsWith("s") ? "' Storage" : "'s Storage");
    }

    static String displayName(ProfessionalRoleId role) {
        return switch (role.value().getPath()) {
            case "butcher_guard" -> "Butcher Guard";
            case "fisherman_guard" -> "Fisherman Guard";
            case "mason_guard" -> "Mason Guard";
            case "quartermaster" -> "Quartermaster";
            default -> titleCase(role.value().getPath());
        };
    }

    private static String titleCase(String path) {
        StringBuilder result = new StringBuilder();
        for (String word : path.split("_")) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            if (!word.isEmpty()) {
                result.append(word.substring(0, 1).toUpperCase(Locale.ROOT));
                result.append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return result.toString();
    }

    private static String pluralize(String roleName) {
        if (roleName.equals("Fisherman")) {
            return "Fishermen";
        }
        if (roleName.endsWith(" Guard")) {
            return roleName + "s";
        }
        if (roleName.endsWith("s") || roleName.endsWith("x") || roleName.endsWith("ch") || roleName.endsWith("sh")) {
            return roleName + "es";
        }
        return roleName + "s";
    }

    public record WorkerView(
            ProfessionalRoleId role,
            ProfessionalStorageResolution.WorkerAvailability availability,
            boolean behaviorConfigured
    ) {
    }
}
