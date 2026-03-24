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
    private static final String PRIMARY_BELLS_KEY = "PrimaryBells";
    private static final String PRIMARY_BELL_POS_KEY = "PrimaryBellPos";

    /** bell → chest for report books */
    private final Map<GlobalPos, BlockPos> bellToChest = new HashMap<>();

    /**
     * Primary bell registry: maps every bell GlobalPos to its elected primary bell GlobalPos.
     * A bell is its own primary when it has no other primary within 300 blocks.
     * Secondary bells (within 300 blocks of a primary) point to that primary.
     */
    private final Map<GlobalPos, GlobalPos> bellToPrimary = new HashMap<>();

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

        // Load primary bell registry
        NbtList primaryBells = nbt.getList(PRIMARY_BELLS_KEY, NbtElement.COMPOUND_TYPE);
        for (NbtElement element : primaryBells) {
            if (!(element instanceof NbtCompound entry)) {
                continue;
            }
            if (!entry.contains(DIMENSION_KEY, NbtElement.STRING_TYPE)
                    || !entry.contains(BELL_POS_KEY, NbtElement.COMPOUND_TYPE)
                    || !entry.contains(PRIMARY_BELL_POS_KEY, NbtElement.COMPOUND_TYPE)) {
                continue;
            }
            Identifier dimId = Identifier.tryParse(entry.getString(DIMENSION_KEY));
            if (dimId == null) {
                continue;
            }
            Optional<BlockPos> bellPos = NbtHelper.toBlockPos(entry, BELL_POS_KEY);
            Optional<BlockPos> primaryPos = NbtHelper.toBlockPos(entry, PRIMARY_BELL_POS_KEY);
            if (bellPos.isEmpty() || primaryPos.isEmpty()) {
                continue;
            }
            RegistryKey<net.minecraft.world.World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimId);
            GlobalPos bell = GlobalPos.create(worldKey, bellPos.get().toImmutable());
            GlobalPos primary = GlobalPos.create(worldKey, primaryPos.get().toImmutable());
            state.bellToPrimary.put(bell, primary);
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

        NbtList primaryBells = new NbtList();
        for (Map.Entry<GlobalPos, GlobalPos> entry : bellToPrimary.entrySet()) {
            NbtCompound row = new NbtCompound();
            row.putString(DIMENSION_KEY, entry.getKey().dimension().getValue().toString());
            row.put(BELL_POS_KEY, NbtHelper.fromBlockPos(entry.getKey().pos()));
            row.put(PRIMARY_BELL_POS_KEY, NbtHelper.fromBlockPos(entry.getValue().pos()));
            primaryBells.add(row);
        }
        nbt.put(PRIMARY_BELLS_KEY, primaryBells);

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

    // -------------------------------------------------------------------------
    // Primary bell registry
    // -------------------------------------------------------------------------

    /**
     * Registers {@code bellPos} in the given world and returns its elected primary bell position.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>If another already-registered primary bell exists within 300 blocks, that bell is the
     *       primary and {@code bellPos} is secondary.
     *   <li>Otherwise, {@code bellPos} becomes its own primary.
     * </ol>
     *
     * @param world  the server world
     * @param bellPos the bell that was just rung or discovered
     * @param primaryRadius the radius within which a secondary bell defers to a primary
     * @return the primary bell position that should handle this ring event
     */
    public BlockPos registerBellAndGetPrimary(ServerWorld world, BlockPos bellPos, int primaryRadius) {
        GlobalPos bellGlobalPos = GlobalPos.create(world.getRegistryKey(), bellPos.toImmutable());

        // Check if already registered
        GlobalPos existingPrimary = bellToPrimary.get(bellGlobalPos);
        if (existingPrimary != null) {
            return existingPrimary.pos();
        }

        // Look for a nearby primary bell in this dimension
        BlockPos nearbyPrimary = findNearbyPrimaryBell(world, bellPos, primaryRadius);
        GlobalPos primaryGlobalPos;
        if (nearbyPrimary != null) {
            // Defer to the existing primary
            primaryGlobalPos = GlobalPos.create(world.getRegistryKey(), nearbyPrimary);
        } else {
            // This bell becomes its own primary
            primaryGlobalPos = bellGlobalPos;
        }

        bellToPrimary.put(bellGlobalPos, primaryGlobalPos);
        markDirty();
        return primaryGlobalPos.pos();
    }

    /**
     * Returns the primary bell position for {@code bellPos}, or {@code bellPos} itself if not yet
     * registered.
     */
    public BlockPos getPrimaryBell(ServerWorld world, BlockPos bellPos) {
        GlobalPos key = GlobalPos.create(world.getRegistryKey(), bellPos.toImmutable());
        GlobalPos primary = bellToPrimary.get(key);
        return primary != null ? primary.pos() : bellPos;
    }

    /**
     * Returns {@code true} if {@code bellPos} is the primary (or unregistered) bell in its
     * region.
     */
    public boolean isPrimaryBell(ServerWorld world, BlockPos bellPos) {
        GlobalPos key = GlobalPos.create(world.getRegistryKey(), bellPos.toImmutable());
        GlobalPos primary = bellToPrimary.get(key);
        return primary == null || primary.pos().equals(bellPos);
    }

    private BlockPos findNearbyPrimaryBell(ServerWorld world, BlockPos bellPos, int radius) {
        RegistryKey<net.minecraft.world.World> dim = world.getRegistryKey();
        long radiusSq = (long) radius * radius;
        for (Map.Entry<GlobalPos, GlobalPos> entry : bellToPrimary.entrySet()) {
            GlobalPos candidate = entry.getKey();
            GlobalPos candidatePrimary = entry.getValue();
            // Only consider primaries (bells that are their own primary)
            if (!candidate.equals(candidatePrimary)) {
                continue;
            }
            if (!candidate.dimension().equals(dim)) {
                continue;
            }
            BlockPos candidatePos = candidate.pos();
            long dx = candidatePos.getX() - bellPos.getX();
            long dz = candidatePos.getZ() - bellPos.getZ();
            long dy = candidatePos.getY() - bellPos.getY();
            if (dx * dx + dz * dz + dy * dy <= radiusSq) {
                return candidatePos;
            }
        }
        return null;
    }
}
