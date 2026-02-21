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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class BellChestMappingState extends PersistentState {
    private static final String STATE_ID = GuardVillagers.MODID + "_bell_chest_mapping";
    private static final String MAPPINGS_KEY = "Mappings";
    private static final String DIMENSION_KEY = "Dimension";
    private static final String BELL_POS_KEY = "BellPos";
    private static final String CHEST_POS_KEY = "ChestPos";

    private final Map<GlobalPos, BlockPos> bellToChest = new HashMap<>();

    public static BellChestMappingState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(getType(), STATE_ID);
    }

    private static Type<BellChestMappingState> getType() {
        return new Type<>(BellChestMappingState::new, BellChestMappingState::fromNbt, null);
    }

    private static BellChestMappingState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        BellChestMappingState state = new BellChestMappingState();
        NbtList mappings = nbt.getList(MAPPINGS_KEY, NbtElement.COMPOUND_TYPE);
        for (NbtElement element : mappings) {
            if (!(element instanceof NbtCompound entry)) {
                continue;
            }

            if (!entry.contains(DIMENSION_KEY, NbtElement.STRING_TYPE)
                    || !entry.contains(BELL_POS_KEY, NbtElement.COMPOUND_TYPE)
                    || !entry.contains(CHEST_POS_KEY, NbtElement.COMPOUND_TYPE)) {
                continue;
            }

            Identifier dimId = Identifier.tryParse(entry.getString(DIMENSION_KEY));
            if (dimId == null) {
                continue;
            }

            Optional<BlockPos> bellPos = NbtHelper.toBlockPos(entry, BELL_POS_KEY);
            Optional<BlockPos> chestPos = NbtHelper.toBlockPos(entry, CHEST_POS_KEY);
            if (bellPos.isEmpty() || chestPos.isEmpty()) {
                continue;
            }

            RegistryKey<net.minecraft.world.World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimId);
            state.bellToChest.put(GlobalPos.create(worldKey, bellPos.get().toImmutable()), chestPos.get().toImmutable());
        }

        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        NbtList mappings = new NbtList();
        for (Map.Entry<GlobalPos, BlockPos> entry : bellToChest.entrySet()) {
            NbtCompound row = new NbtCompound();
            row.putString(DIMENSION_KEY, entry.getKey().dimension().getValue().toString());
            row.put(BELL_POS_KEY, NbtHelper.fromBlockPos(entry.getKey().pos()));
            row.put(CHEST_POS_KEY, NbtHelper.fromBlockPos(entry.getValue()));
            mappings.add(row);
        }
        nbt.put(MAPPINGS_KEY, mappings);
        return nbt;
    }

    public void putMapping(ServerWorld world, BlockPos bellPos, BlockPos chestPos) {
        bellToChest.put(GlobalPos.create(world.getRegistryKey(), bellPos.toImmutable()), chestPos.toImmutable());
        markDirty();
    }

    public Optional<BlockPos> getChestPos(ServerWorld world, BlockPos bellPos) {
        return Optional.ofNullable(bellToChest.get(GlobalPos.create(world.getRegistryKey(), bellPos.toImmutable())))
                .map(BlockPos::toImmutable);
    }

    public void removeMapping(ServerWorld world, BlockPos bellPos) {
        if (bellToChest.remove(GlobalPos.create(world.getRegistryKey(), bellPos.toImmutable())) != null) {
            markDirty();
        }
    }

    public Set<BlockPos> getBellPositions(ServerWorld world) {
        Set<BlockPos> positions = new HashSet<>();
        for (GlobalPos globalPos : bellToChest.keySet()) {
            if (globalPos.dimension().equals(world.getRegistryKey())) {
                positions.add(globalPos.pos().toImmutable());
            }
        }
        return positions;
    }
}
