package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.goal.LumberjackChestTriggerController;
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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class LumberjackUpgradeState extends PersistentState {
    private static final String STATE_ID = GuardVillagers.MODID + "_lumberjack_upgrade_state";

    private static final String ENTRIES_KEY = "Entries";
    private static final String DIMENSION_KEY = "Dimension";
    private static final String JOB_POS_KEY = "JobPos";
    private static final String VILLAGER_ID_KEY = "VillagerId";
    private static final String STAGE_KEY = "Stage";
    private static final String CHEST_PAIRED_TICK_KEY = "ChestPairedTick";

    private final Map<EntryKey, EntryValue> entries = new HashMap<>();

    public static LumberjackUpgradeState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(getType(), STATE_ID);
    }

    private static Type<LumberjackUpgradeState> getType() {
        return new Type<>(LumberjackUpgradeState::new, LumberjackUpgradeState::fromNbt, null);
    }

    private static LumberjackUpgradeState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        LumberjackUpgradeState state = new LumberjackUpgradeState();
        NbtList list = nbt.getList(ENTRIES_KEY, NbtElement.COMPOUND_TYPE);
        for (NbtElement element : list) {
            if (!(element instanceof NbtCompound row)) {
                continue;
            }
            if (!row.contains(DIMENSION_KEY, NbtElement.STRING_TYPE)
                    || !row.contains(JOB_POS_KEY, NbtElement.COMPOUND_TYPE)
                    || !row.contains(VILLAGER_ID_KEY, NbtElement.STRING_TYPE)
                    || !row.contains(STAGE_KEY, NbtElement.STRING_TYPE)
                    || !row.contains(CHEST_PAIRED_TICK_KEY, NbtElement.LONG_TYPE)) {
                continue;
            }

            Identifier dimensionId = Identifier.tryParse(row.getString(DIMENSION_KEY));
            if (dimensionId == null) {
                continue;
            }

            Optional<BlockPos> jobPos = NbtHelper.toBlockPos(row, JOB_POS_KEY);
            if (jobPos.isEmpty()) {
                continue;
            }

            UUID villagerId;
            try {
                villagerId = UUID.fromString(row.getString(VILLAGER_ID_KEY));
            } catch (IllegalArgumentException ex) {
                continue;
            }

            LumberjackChestTriggerController.UpgradeStage stage;
            try {
                stage = LumberjackChestTriggerController.UpgradeStage.valueOf(row.getString(STAGE_KEY));
            } catch (IllegalArgumentException ex) {
                continue;
            }

            RegistryKey<net.minecraft.world.World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimensionId);
            EntryKey key = new EntryKey(villagerId, GlobalPos.create(worldKey, jobPos.get().toImmutable()));
            state.entries.put(key, new EntryValue(stage, row.getLong(CHEST_PAIRED_TICK_KEY)));
        }
        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        NbtList list = new NbtList();
        for (Map.Entry<EntryKey, EntryValue> entry : entries.entrySet()) {
            NbtCompound row = new NbtCompound();
            row.putString(DIMENSION_KEY, entry.getKey().jobSite().dimension().getValue().toString());
            row.put(JOB_POS_KEY, NbtHelper.fromBlockPos(entry.getKey().jobSite().pos()));
            row.putString(VILLAGER_ID_KEY, entry.getKey().villagerId().toString());
            row.putString(STAGE_KEY, entry.getValue().stage().name());
            row.putLong(CHEST_PAIRED_TICK_KEY, entry.getValue().chestPairedTick());
            list.add(row);
        }
        nbt.put(ENTRIES_KEY, list);
        return nbt;
    }

    public Optional<EntryValue> getEntry(ServerWorld world, UUID villagerId, BlockPos jobPos) {
        return Optional.ofNullable(entries.get(new EntryKey(villagerId,
                GlobalPos.create(world.getRegistryKey(), jobPos.toImmutable()))));
    }

    public void putEntry(ServerWorld world,
                         UUID villagerId,
                         BlockPos jobPos,
                         LumberjackChestTriggerController.UpgradeStage stage,
                         long chestPairedTick) {
        entries.put(new EntryKey(villagerId, GlobalPos.create(world.getRegistryKey(), jobPos.toImmutable())),
                new EntryValue(stage, chestPairedTick));
        markDirty();
    }

    public void removeEntry(ServerWorld world, UUID villagerId, BlockPos jobPos) {
        if (entries.remove(new EntryKey(villagerId, GlobalPos.create(world.getRegistryKey(), jobPos.toImmutable()))) != null) {
            markDirty();
        }
    }

    public record EntryValue(LumberjackChestTriggerController.UpgradeStage stage, long chestPairedTick) {
    }

    private record EntryKey(UUID villagerId, GlobalPos jobSite) {
    }
}
