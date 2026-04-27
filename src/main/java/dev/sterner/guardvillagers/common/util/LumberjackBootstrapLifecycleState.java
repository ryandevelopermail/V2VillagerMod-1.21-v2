package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.GuardVillagers;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistent lifecycle state for village-scoped unemployed lumberjack bootstrap selection.
 */
public class LumberjackBootstrapLifecycleState extends PersistentState {
    private static final String STATE_ID = GuardVillagers.MODID + "_lumberjack_bootstrap_lifecycle";

    private static final String ENTRIES_KEY = "Entries";
    private static final String DIMENSION_KEY = "Dimension";
    private static final String VILLAGE_KIND_KEY = "VillageKind";
    private static final String VILLAGE_PACKED_KEY = "VillagePacked";
    private static final String CANDIDATE_UUID_KEY = "CandidateUuid";
    private static final String ANCHOR_POS_KEY = "AnchorPos";
    private static final String STAGE_KEY = "Stage";
    private static final String CREATED_TICK_KEY = "CreatedTick";
    private static final String UPDATED_TICK_KEY = "UpdatedTick";
    private static final String RETRY_COUNT_KEY = "RetryCount";
    private static final String CALLBACK_COUNT_KEY = "CallbackCount";
    private static final String PLACED_TABLE_POS_KEY = "PlacedTablePos";
    private static final String NEXT_RETRY_TICK_KEY = "NextRetryTick";
    private static final String LAST_FAILURE_KEY = "LastFailureKey";
    private static final String AUTO_SPAWNED_EVER_BY_BELL_KEY = "AutoSpawnedEverByBell";
    private static final String BELL_POS_KEY = "BellPos";
    private static final String SPAWNED_EVER_KEY = "SpawnedEver";
    private static final String SPAWNED_UUID_KEY = "SpawnedUuid";
    private static final String SPAWNED_TICK_KEY = "SpawnedTick";
    private static final String SPAWN_RETRY_COUNT_BY_BELL_KEY = "SpawnRetryCountByBell";
    private static final String RETRY_COUNT_VALUE_KEY = "RetryCountValue";

    private final Map<EntryKey, EntryValue> entries = new HashMap<>();
    private final Map<BellKey, AutoSpawnMarker> autoSpawnedEverByBell = new HashMap<>();
    private final Map<BellKey, Integer> spawnRetryCountByBell = new HashMap<>();

    public static LumberjackBootstrapLifecycleState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(getType(), STATE_ID);
    }

    private static Type<LumberjackBootstrapLifecycleState> getType() {
        return new Type<>(LumberjackBootstrapLifecycleState::new, LumberjackBootstrapLifecycleState::fromNbt, null);
    }

    private static LumberjackBootstrapLifecycleState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        LumberjackBootstrapLifecycleState state = new LumberjackBootstrapLifecycleState();
        NbtList list = nbt.getList(ENTRIES_KEY, NbtElement.COMPOUND_TYPE);
        for (NbtElement element : list) {
            if (!(element instanceof NbtCompound row)) {
                continue;
            }
            if (!row.contains(DIMENSION_KEY, NbtElement.STRING_TYPE)
                    || !row.contains(VILLAGE_KIND_KEY, NbtElement.STRING_TYPE)
                    || !row.contains(VILLAGE_PACKED_KEY, NbtElement.LONG_TYPE)
                    || !row.contains(CANDIDATE_UUID_KEY, NbtElement.STRING_TYPE)
                    || !row.contains(ANCHOR_POS_KEY, NbtElement.COMPOUND_TYPE)
                    || !row.contains(STAGE_KEY, NbtElement.STRING_TYPE)) {
                continue;
            }

            Identifier dimensionId = Identifier.tryParse(row.getString(DIMENSION_KEY));
            if (dimensionId == null) {
                continue;
            }

            Optional<BlockPos> anchorPos = NbtHelper.toBlockPos(row, ANCHOR_POS_KEY);
            if (anchorPos.isEmpty()) {
                continue;
            }

            UUID candidateUuid;
            try {
                candidateUuid = UUID.fromString(row.getString(CANDIDATE_UUID_KEY));
            } catch (IllegalArgumentException ex) {
                continue;
            }

            Stage stage;
            try {
                stage = Stage.valueOf(row.getString(STAGE_KEY));
            } catch (IllegalArgumentException ex) {
                continue;
            }

            RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimensionId);
            VillageKind kind;
            try {
                kind = VillageKind.valueOf(row.getString(VILLAGE_KIND_KEY));
            } catch (IllegalArgumentException ex) {
                continue;
            }

            EntryKey key = new EntryKey(worldKey, kind, row.getLong(VILLAGE_PACKED_KEY));
            EntryValue value = new EntryValue(
                    candidateUuid,
                    GlobalPos.create(worldKey, anchorPos.get().toImmutable()),
                    stage,
                    row.getLong(CREATED_TICK_KEY),
                    row.getLong(UPDATED_TICK_KEY),
                    row.getInt(RETRY_COUNT_KEY),
                    row.getInt(CALLBACK_COUNT_KEY),
                    NbtHelper.toBlockPos(row, PLACED_TABLE_POS_KEY).orElse(null),
                    row.contains(NEXT_RETRY_TICK_KEY, NbtElement.LONG_TYPE) ? row.getLong(NEXT_RETRY_TICK_KEY) : 0L,
                    row.contains(LAST_FAILURE_KEY, NbtElement.STRING_TYPE) ? row.getString(LAST_FAILURE_KEY) : ""
            );
            state.entries.put(key, value);
        }
        NbtList autoSpawnedList = nbt.getList(AUTO_SPAWNED_EVER_BY_BELL_KEY, NbtElement.COMPOUND_TYPE);
        for (NbtElement element : autoSpawnedList) {
            if (!(element instanceof NbtCompound row)) {
                continue;
            }
            if (!row.contains(DIMENSION_KEY, NbtElement.STRING_TYPE)
                    || !row.contains(BELL_POS_KEY, NbtElement.COMPOUND_TYPE)
                    || !row.contains(SPAWNED_EVER_KEY, NbtElement.BYTE_TYPE)) {
                continue;
            }
            Identifier dimensionId = Identifier.tryParse(row.getString(DIMENSION_KEY));
            if (dimensionId == null) {
                continue;
            }
            Optional<BlockPos> bellPos = NbtHelper.toBlockPos(row, BELL_POS_KEY);
            if (bellPos.isEmpty()) {
                continue;
            }

            RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimensionId);
            UUID spawnedUuid = null;
            if (row.contains(SPAWNED_UUID_KEY, NbtElement.STRING_TYPE)) {
                try {
                    spawnedUuid = UUID.fromString(row.getString(SPAWNED_UUID_KEY));
                } catch (IllegalArgumentException ignored) {
                    spawnedUuid = null;
                }
            }
            long spawnedTick = row.contains(SPAWNED_TICK_KEY, NbtElement.LONG_TYPE) ? row.getLong(SPAWNED_TICK_KEY) : 0L;
            state.autoSpawnedEverByBell.put(
                    new BellKey(worldKey, bellPos.get().toImmutable()),
                    new AutoSpawnMarker(row.getBoolean(SPAWNED_EVER_KEY), spawnedUuid, spawnedTick));
        }
        NbtList retryCountList = nbt.getList(SPAWN_RETRY_COUNT_BY_BELL_KEY, NbtElement.COMPOUND_TYPE);
        for (NbtElement element : retryCountList) {
            if (!(element instanceof NbtCompound row)) {
                continue;
            }
            if (!row.contains(DIMENSION_KEY, NbtElement.STRING_TYPE)
                    || !row.contains(BELL_POS_KEY, NbtElement.COMPOUND_TYPE)
                    || !row.contains(RETRY_COUNT_VALUE_KEY, NbtElement.INT_TYPE)) {
                continue;
            }
            Identifier dimensionId = Identifier.tryParse(row.getString(DIMENSION_KEY));
            if (dimensionId == null) {
                continue;
            }
            Optional<BlockPos> bellPos = NbtHelper.toBlockPos(row, BELL_POS_KEY);
            if (bellPos.isEmpty()) {
                continue;
            }
            int retryCount = Math.max(0, row.getInt(RETRY_COUNT_VALUE_KEY));
            RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimensionId);
            state.spawnRetryCountByBell.put(new BellKey(worldKey, bellPos.get().toImmutable()), retryCount);
        }
        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        NbtList list = new NbtList();
        for (Map.Entry<EntryKey, EntryValue> entry : entries.entrySet()) {
            NbtCompound row = new NbtCompound();
            row.putString(DIMENSION_KEY, entry.getKey().worldKey().getValue().toString());
            row.putString(VILLAGE_KIND_KEY, entry.getKey().kind().name());
            row.putLong(VILLAGE_PACKED_KEY, entry.getKey().packed());
            row.putString(CANDIDATE_UUID_KEY, entry.getValue().candidateUuid().toString());
            row.put(ANCHOR_POS_KEY, NbtHelper.fromBlockPos(entry.getValue().anchor().pos()));
            row.putString(STAGE_KEY, entry.getValue().stage().name());
            row.putLong(CREATED_TICK_KEY, entry.getValue().createdTick());
            row.putLong(UPDATED_TICK_KEY, entry.getValue().updatedTick());
            row.putInt(RETRY_COUNT_KEY, entry.getValue().retryCount());
            row.putInt(CALLBACK_COUNT_KEY, entry.getValue().callbackCount());
            if (entry.getValue().placedTablePos() != null) {
                row.put(PLACED_TABLE_POS_KEY, NbtHelper.fromBlockPos(entry.getValue().placedTablePos()));
            }
            row.putLong(NEXT_RETRY_TICK_KEY, entry.getValue().nextRetryTick());
            if (!entry.getValue().lastFailureKey().isEmpty()) {
                row.putString(LAST_FAILURE_KEY, entry.getValue().lastFailureKey());
            }
            list.add(row);
        }
        nbt.put(ENTRIES_KEY, list);

        NbtList autoSpawnedList = new NbtList();
        for (Map.Entry<BellKey, AutoSpawnMarker> entry : autoSpawnedEverByBell.entrySet()) {
            NbtCompound row = new NbtCompound();
            row.putString(DIMENSION_KEY, entry.getKey().worldKey().getValue().toString());
            row.put(BELL_POS_KEY, NbtHelper.fromBlockPos(entry.getKey().bellPos()));
            row.putBoolean(SPAWNED_EVER_KEY, entry.getValue().spawnedEver());
            if (entry.getValue().spawnedUuid() != null) {
                row.putString(SPAWNED_UUID_KEY, entry.getValue().spawnedUuid().toString());
            }
            row.putLong(SPAWNED_TICK_KEY, entry.getValue().spawnedTick());
            autoSpawnedList.add(row);
        }
        nbt.put(AUTO_SPAWNED_EVER_BY_BELL_KEY, autoSpawnedList);

        NbtList retryCountList = new NbtList();
        for (Map.Entry<BellKey, Integer> entry : spawnRetryCountByBell.entrySet()) {
            NbtCompound row = new NbtCompound();
            row.putString(DIMENSION_KEY, entry.getKey().worldKey().getValue().toString());
            row.put(BELL_POS_KEY, NbtHelper.fromBlockPos(entry.getKey().bellPos()));
            row.putInt(RETRY_COUNT_VALUE_KEY, Math.max(0, entry.getValue()));
            retryCountList.add(row);
        }
        nbt.put(SPAWN_RETRY_COUNT_BY_BELL_KEY, retryCountList);
        return nbt;
    }

    public boolean hasAutoLumberjackSpawnedEver(ServerWorld world, BlockPos bellPos) {
        AutoSpawnMarker marker = autoSpawnedEverByBell.get(new BellKey(world.getRegistryKey(), bellPos.toImmutable()));
        return marker != null && marker.spawnedEver();
    }

    public Optional<AutoSpawnMarker> getAutoLumberjackSpawnMarker(ServerWorld world, BlockPos bellPos) {
        return Optional.ofNullable(autoSpawnedEverByBell.get(new BellKey(world.getRegistryKey(), bellPos.toImmutable())));
    }

    public void markAutoLumberjackSpawnedEver(ServerWorld world, BlockPos bellPos, UUID spawnedUuid, long nowTick) {
        BellKey key = new BellKey(world.getRegistryKey(), bellPos.toImmutable());
        AutoSpawnMarker existing = autoSpawnedEverByBell.get(key);
        if (existing != null && existing.spawnedEver() && existing.spawnedUuid() != null && existing.spawnedUuid().equals(spawnedUuid)) {
            return;
        }
        autoSpawnedEverByBell.put(key, new AutoSpawnMarker(true, spawnedUuid, nowTick));
        markDirty();
    }

    public int incrementSpawnRetryCount(ServerWorld world, BlockPos bellPos) {
        BellKey key = new BellKey(world.getRegistryKey(), bellPos.toImmutable());
        int nextCount = spawnRetryCountByBell.getOrDefault(key, 0) + 1;
        spawnRetryCountByBell.put(key, nextCount);
        markDirty();
        return nextCount;
    }

    public int getSpawnRetryCount(ServerWorld world, BlockPos bellPos) {
        return spawnRetryCountByBell.getOrDefault(new BellKey(world.getRegistryKey(), bellPos.toImmutable()), 0);
    }

    public void clearSpawnRetryCount(ServerWorld world, BlockPos bellPos) {
        if (spawnRetryCountByBell.remove(new BellKey(world.getRegistryKey(), bellPos.toImmutable())) != null) {
            markDirty();
        }
    }

    public Optional<EntryValue> getEntry(ServerWorld world, VillageKind kind, long packed) {
        return Optional.ofNullable(entries.get(new EntryKey(world.getRegistryKey(), kind, packed)));
    }

    public EntryValue selectOrRefresh(ServerWorld world,
                                      VillageKind kind,
                                      long packed,
                                      UUID candidateUuid,
                                      BlockPos anchorPos,
                                      long nowTick) {
        EntryKey key = new EntryKey(world.getRegistryKey(), kind, packed);
        EntryValue existing = entries.get(key);
        if (existing != null && existing.stage().isTerminal()) {
            return existing;
        }

        EntryValue next;
        if (existing != null && existing.candidateUuid().equals(candidateUuid)
                && existing.anchor().pos().equals(anchorPos)
                && existing.stage().atLeast(Stage.SELECTED)) {
            next = existing.withUpdatedTick(nowTick)
                    .withCallbackCount(existing.callbackCount() + 1);
        } else {
            long createdTick = existing != null ? existing.createdTick() : nowTick;
            int retries = existing != null ? existing.retryCount() : 0;
            int callbacks = existing != null ? existing.callbackCount() + 1 : 1;
            next = new EntryValue(
                    candidateUuid,
                    GlobalPos.create(world.getRegistryKey(), anchorPos.toImmutable()),
                    Stage.SELECTED,
                    createdTick,
                    nowTick,
                    retries,
                    callbacks,
                    existing != null ? existing.placedTablePos() : null,
                    existing != null ? existing.nextRetryTick() : 0L,
                    existing != null ? existing.lastFailureKey() : ""
            );
        }

        entries.put(key, next);
        markDirty();
        return next;
    }

    public EntryValue advanceStage(ServerWorld world,
                                   VillageKind kind,
                                   long packed,
                                   Stage requestedStage,
                                   long nowTick) {
        EntryKey key = new EntryKey(world.getRegistryKey(), kind, packed);
        EntryValue existing = entries.get(key);
        if (existing == null) {
            return null;
        }

        Stage nextStage = existing.stage().atLeast(requestedStage) ? existing.stage() : requestedStage;
        EntryValue next = existing.withStage(nextStage).withUpdatedTick(nowTick);
        if (next.stage() == existing.stage() && next.updatedTick() == existing.updatedTick()) {
            return existing;
        }

        entries.put(key, next);
        markDirty();
        return next;
    }

    public void markRetry(ServerWorld world, VillageKind kind, long packed, long nowTick) {
        EntryKey key = new EntryKey(world.getRegistryKey(), kind, packed);
        EntryValue existing = entries.get(key);
        if (existing == null || existing.stage().isTerminal()) {
            return;
        }
        entries.put(key, existing.withRetryCount(existing.retryCount() + 1).withUpdatedTick(nowTick));
        markDirty();
    }

    public FailureResult applyFailure(ServerWorld world,
                                      VillageKind kind,
                                      long packed,
                                      String failureKey,
                                      int delayTicks,
                                      int maxAttempts,
                                      boolean terminalFail,
                                      long nowTick) {
        EntryKey key = new EntryKey(world.getRegistryKey(), kind, packed);
        EntryValue existing = entries.get(key);
        if (existing == null) {
            return FailureResult.MISSING;
        }
        if (existing.stage().isTerminal()) {
            return FailureResult.TERMINAL;
        }

        int nextRetries = existing.retryCount() + 1;
        boolean reachedMaxAttempts = nextRetries >= Math.max(1, maxAttempts);
        boolean failedTerminally = terminalFail && reachedMaxAttempts;
        Stage nextStage = failedTerminally ? Stage.FAILED : existing.stage();
        long nextRetryTick = nowTick + Math.max(1, delayTicks);

        EntryValue next = existing
                .withRetryCount(nextRetries)
                .withStage(nextStage)
                .withUpdatedTick(nowTick)
                .withNextRetryTick(nextRetryTick)
                .withLastFailureKey(failureKey == null ? "" : failureKey);
        entries.put(key, next);
        markDirty();
        return failedTerminally ? FailureResult.TERMINAL : FailureResult.RETRY_SCHEDULED;
    }

    public void setPlacedTablePos(ServerWorld world, VillageKind kind, long packed, BlockPos placedTablePos, long nowTick) {
        EntryKey key = new EntryKey(world.getRegistryKey(), kind, packed);
        EntryValue existing = entries.get(key);
        if (existing == null || existing.stage().isTerminal()) {
            return;
        }

        BlockPos immutablePlacedPos = placedTablePos.toImmutable();
        if (immutablePlacedPos.equals(existing.placedTablePos())) {
            return;
        }

        entries.put(key, existing.withPlacedTablePos(immutablePlacedPos).withUpdatedTick(nowTick));
        markDirty();
    }

    public boolean removeEntry(ServerWorld world, VillageKind kind, long packed) {
        EntryKey key = new EntryKey(world.getRegistryKey(), kind, packed);
        if (entries.remove(key) == null) {
            return false;
        }
        markDirty();
        return true;
    }

    public void removeWorld(RegistryKey<World> worldKey) {
        boolean changed = entries.entrySet().removeIf(entry -> entry.getKey().worldKey().equals(worldKey));
        changed |= autoSpawnedEverByBell.entrySet().removeIf(entry -> entry.getKey().worldKey().equals(worldKey));
        changed |= spawnRetryCountByBell.entrySet().removeIf(entry -> entry.getKey().worldKey().equals(worldKey));
        if (changed) {
            markDirty();
        }
    }

    public enum Stage {
        SELECTED,
        CHOPPING_ONE_TREE,
        NEEDS_TABLE,
        READY_TO_CONVERT,
        DONE,
        FAILED;

        public boolean atLeast(Stage other) {
            return this.ordinal() >= other.ordinal();
        }

        public boolean isTerminal() {
            return this == DONE || this == FAILED;
        }
    }

    public enum VillageKind {
        BELL,
        REGION
    }

    public record EntryValue(UUID candidateUuid,
                             GlobalPos anchor,
                             Stage stage,
                             long createdTick,
                             long updatedTick,
                             int retryCount,
                             int callbackCount,
                             BlockPos placedTablePos,
                             long nextRetryTick,
                             String lastFailureKey) {

        EntryValue withUpdatedTick(long tick) {
            return new EntryValue(candidateUuid, anchor, stage, createdTick, tick, retryCount, callbackCount, placedTablePos, nextRetryTick, lastFailureKey);
        }

        EntryValue withStage(Stage nextStage) {
            return new EntryValue(candidateUuid, anchor, nextStage, createdTick, updatedTick, retryCount, callbackCount, placedTablePos, nextRetryTick, lastFailureKey);
        }

        EntryValue withRetryCount(int retries) {
            return new EntryValue(candidateUuid, anchor, stage, createdTick, updatedTick, retries, callbackCount, placedTablePos, nextRetryTick, lastFailureKey);
        }

        EntryValue withCallbackCount(int callbacks) {
            return new EntryValue(candidateUuid, anchor, stage, createdTick, updatedTick, retryCount, callbacks, placedTablePos, nextRetryTick, lastFailureKey);
        }

        EntryValue withPlacedTablePos(BlockPos tablePos) {
            return new EntryValue(candidateUuid, anchor, stage, createdTick, updatedTick, retryCount, callbackCount, tablePos, nextRetryTick, lastFailureKey);
        }

        EntryValue withNextRetryTick(long retryTick) {
            return new EntryValue(candidateUuid, anchor, stage, createdTick, updatedTick, retryCount, callbackCount, placedTablePos, retryTick, lastFailureKey);
        }

        EntryValue withLastFailureKey(String failureKey) {
            return new EntryValue(candidateUuid, anchor, stage, createdTick, updatedTick, retryCount, callbackCount, placedTablePos, nextRetryTick, failureKey == null ? "" : failureKey);
        }
    }

    public enum FailureResult {
        RETRY_SCHEDULED,
        TERMINAL,
        MISSING
    }

    private record EntryKey(RegistryKey<World> worldKey, VillageKind kind, long packed) {
    }

    private record BellKey(RegistryKey<World> worldKey, BlockPos bellPos) {
    }

    public record AutoSpawnMarker(boolean spawnedEver, UUID spawnedUuid, long spawnedTick) {
    }
}
