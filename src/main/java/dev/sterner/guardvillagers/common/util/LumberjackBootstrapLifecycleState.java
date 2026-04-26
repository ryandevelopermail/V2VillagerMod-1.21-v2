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

    private final Map<EntryKey, EntryValue> entries = new HashMap<>();

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
                    NbtHelper.toBlockPos(row, PLACED_TABLE_POS_KEY).orElse(null)
            );
            state.entries.put(key, value);
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
            list.add(row);
        }
        nbt.put(ENTRIES_KEY, list);
        return nbt;
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
                    existing != null ? existing.placedTablePos() : null
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

    public void removeWorld(RegistryKey<World> worldKey) {
        if (entries.entrySet().removeIf(entry -> entry.getKey().worldKey().equals(worldKey))) {
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
                             BlockPos placedTablePos) {

        EntryValue withUpdatedTick(long tick) {
            return new EntryValue(candidateUuid, anchor, stage, createdTick, tick, retryCount, callbackCount, placedTablePos);
        }

        EntryValue withStage(Stage nextStage) {
            return new EntryValue(candidateUuid, anchor, nextStage, createdTick, updatedTick, retryCount, callbackCount, placedTablePos);
        }

        EntryValue withRetryCount(int retries) {
            return new EntryValue(candidateUuid, anchor, stage, createdTick, updatedTick, retries, callbackCount, placedTablePos);
        }

        EntryValue withCallbackCount(int callbacks) {
            return new EntryValue(candidateUuid, anchor, stage, createdTick, updatedTick, retryCount, callbacks, placedTablePos);
        }

        EntryValue withPlacedTablePos(BlockPos tablePos) {
            return new EntryValue(candidateUuid, anchor, stage, createdTick, updatedTick, retryCount, callbackCount, tablePos);
        }
    }

    private record EntryKey(RegistryKey<World> worldKey, VillageKind kind, long packed) {
    }
}
