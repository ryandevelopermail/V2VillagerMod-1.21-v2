package dev.sterner.guardvillagers.common.professionalstorage;

import dev.sterner.guardvillagers.GuardVillagers;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Overworld-backed career totals keyed by worker, role, and stable metric ID. */
public final class ProfessionalWorkStatsState extends PersistentState {
    private static final String STATE_ID = GuardVillagers.MODID + "_professional_work_stats";
    private static final String ENTRIES_KEY = "Entries";
    private static final String WORKER_UUID_KEY = "WorkerUuid";
    private static final String ROLE_KEY = "Role";
    private static final String METRICS_KEY = "Metrics";

    private static final Comparator<Map.Entry<WorkerRoleKey, Map<ProfessionalMetricId, Long>>> ENTRY_ORDER =
            Comparator.comparing((Map.Entry<WorkerRoleKey, Map<ProfessionalMetricId, Long>> entry) ->
                            entry.getKey().workerUuid().toString())
                    .thenComparing(entry -> entry.getKey().role());

    private final Map<WorkerRoleKey, Map<ProfessionalMetricId, Long>> counters = new HashMap<>();

    public static ProfessionalWorkStatsState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(getType(), STATE_ID);
    }

    private static Type<ProfessionalWorkStatsState> getType() {
        return new Type<>(ProfessionalWorkStatsState::new, ProfessionalWorkStatsState::fromNbt, null);
    }

    static ProfessionalWorkStatsState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        ProfessionalWorkStatsState state = new ProfessionalWorkStatsState();
        NbtList entries = nbt.getList(ENTRIES_KEY, NbtElement.COMPOUND_TYPE);
        for (NbtElement element : entries) {
            if (!(element instanceof NbtCompound entry)) {
                continue;
            }
            Optional<WorkerRoleKey> key = readKey(entry);
            if (key.isEmpty() || !entry.contains(METRICS_KEY, NbtElement.COMPOUND_TYPE)) {
                continue;
            }
            NbtCompound metricsNbt = entry.getCompound(METRICS_KEY);
            Map<ProfessionalMetricId, Long> metrics = new HashMap<>();
            for (String rawMetricId : metricsNbt.getKeys()) {
                Optional<ProfessionalMetricId> metricId = ProfessionalMetricId.parse(rawMetricId);
                if (metricId.isEmpty() || !metricsNbt.contains(rawMetricId, NbtElement.NUMBER_TYPE)) {
                    continue;
                }
                long value = metricsNbt.getLong(rawMetricId);
                if (value > 0L) {
                    metrics.put(metricId.get(), value);
                }
            }
            if (!metrics.isEmpty()) {
                state.counters.put(key.get(), metrics);
            }
        }
        return state;
    }

    @Override
    public synchronized NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        NbtList entries = new NbtList();
        counters.entrySet().stream().sorted(ENTRY_ORDER).forEach(counterEntry -> {
            NbtCompound entry = new NbtCompound();
            entry.putString(WORKER_UUID_KEY, counterEntry.getKey().workerUuid().toString());
            entry.putString(ROLE_KEY, counterEntry.getKey().role().toString());
            NbtCompound metrics = new NbtCompound();
            counterEntry.getValue().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .filter(metric -> metric.getValue() > 0L)
                    .forEach(metric -> metrics.putLong(metric.getKey().value(), metric.getValue()));
            entry.put(METRICS_KEY, metrics);
            entries.add(entry);
        });
        nbt.put(ENTRIES_KEY, entries);
        return nbt;
    }

    public synchronized long increment(UUID workerUuid, ProfessionalRoleId role, ProfessionalMetricId metric) {
        return increment(workerUuid, role, metric, 1L);
    }

    public synchronized long increment(
            UUID workerUuid,
            ProfessionalRoleId role,
            ProfessionalMetricId metric,
            long amount
    ) {
        if (amount <= 0L) {
            return read(workerUuid, role, metric);
        }
        WorkerRoleKey key = new WorkerRoleKey(workerUuid, role);
        Map<ProfessionalMetricId, Long> workerCounters = counters.computeIfAbsent(key, ignored -> new HashMap<>());
        long current = workerCounters.getOrDefault(metric, 0L);
        long updated = saturatingAdd(current, amount);
        if (updated != current) {
            workerCounters.put(metric, updated);
            markDirty();
        }
        return updated;
    }

    public synchronized long read(UUID workerUuid, ProfessionalRoleId role, ProfessionalMetricId metric) {
        return counters.getOrDefault(new WorkerRoleKey(workerUuid, role), Map.of()).getOrDefault(metric, 0L);
    }

    public synchronized long aggregate(
            Collection<UUID> workerUuids,
            ProfessionalRoleId role,
            ProfessionalMetricId metric
    ) {
        long total = 0L;
        for (UUID workerUuid : workerUuids.stream().distinct().toList()) {
            total = saturatingAdd(total, read(workerUuid, role, metric));
        }
        return total;
    }

    /** Moves only the requested counters as one state mutation. */
    synchronized boolean transferMetrics(
            UUID sourceWorkerUuid,
            ProfessionalRoleId sourceRole,
            UUID destinationWorkerUuid,
            ProfessionalRoleId destinationRole,
            Collection<ProfessionalMetricId> metrics
    ) {
        WorkerRoleKey sourceKey = new WorkerRoleKey(sourceWorkerUuid, sourceRole);
        WorkerRoleKey destinationKey = new WorkerRoleKey(destinationWorkerUuid, destinationRole);
        if (sourceKey.equals(destinationKey)) {
            return false;
        }
        Map<ProfessionalMetricId, Long> sourceCounters = counters.get(sourceKey);
        if (sourceCounters == null) {
            return false;
        }

        Map<ProfessionalMetricId, Long> destinationCounters = null;
        boolean changed = false;
        for (ProfessionalMetricId metric : metrics) {
            Long sourceTotal = sourceCounters.remove(metric);
            if (sourceTotal == null) {
                continue;
            }
            changed = true;
            if (sourceTotal > 0L) {
                if (destinationCounters == null) {
                    destinationCounters = counters.computeIfAbsent(destinationKey, ignored -> new HashMap<>());
                }
                long destinationTotal = destinationCounters.getOrDefault(metric, 0L);
                destinationCounters.put(metric, saturatingAdd(destinationTotal, sourceTotal));
            }
        }
        if (!changed) {
            return false;
        }
        if (sourceCounters.isEmpty()) {
            counters.remove(sourceKey);
        }
        markDirty();
        return true;
    }

    private static Optional<WorkerRoleKey> readKey(NbtCompound entry) {
        if (!entry.contains(WORKER_UUID_KEY, NbtElement.STRING_TYPE)
                || !entry.contains(ROLE_KEY, NbtElement.STRING_TYPE)) {
            return Optional.empty();
        }
        UUID workerUuid;
        try {
            workerUuid = UUID.fromString(entry.getString(WORKER_UUID_KEY));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        return ProfessionalRoleId.parse(entry.getString(ROLE_KEY))
                .map(role -> new WorkerRoleKey(workerUuid, role));
    }

    private static long saturatingAdd(long current, long amount) {
        if (current >= Long.MAX_VALUE - amount) {
            return Long.MAX_VALUE;
        }
        return current + amount;
    }

    private record WorkerRoleKey(UUID workerUuid, ProfessionalRoleId role) {
    }
}
