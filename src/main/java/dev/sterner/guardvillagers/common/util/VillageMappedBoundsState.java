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
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.PersistentState;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Stores the world-coordinate bounding box that a village cartographer has fully mapped.
 * Keyed by the primary bell GlobalPos. Once a cartographer completes all 4 map tiles,
 * the bounds are written here. The lumberjack reads these bounds to restrict tree harvesting
 * to the mapped village territory.
 */
public class VillageMappedBoundsState extends PersistentState {
    private static final String STATE_ID = GuardVillagers.MODID + "_village_mapped_bounds";
    private static final String BOUNDS_KEY = "Bounds";
    private static final String DIMENSION_KEY = "Dimension";
    private static final String BELL_POS_KEY = "BellPos";
    private static final String MIN_X_KEY = "MinX";
    private static final String MAX_X_KEY = "MaxX";
    private static final String MIN_Z_KEY = "MinZ";
    private static final String MAX_Z_KEY = "MaxZ";

    /**
     * Axis-aligned bounding box of a mapped region (world coordinates, inclusive).
     */
    public record MappedBounds(int minX, int maxX, int minZ, int maxZ) {
        /** Returns true if the given block position falls within the mapped region. */
        public boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }
    }

    private final Map<GlobalPos, MappedBounds> bellToBounds = new HashMap<>();

    public static VillageMappedBoundsState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(getType(), STATE_ID);
    }

    private static Type<VillageMappedBoundsState> getType() {
        return new Type<>(VillageMappedBoundsState::new, VillageMappedBoundsState::fromNbt, null);
    }

    private static VillageMappedBoundsState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        VillageMappedBoundsState state = new VillageMappedBoundsState();

        NbtList bounds = nbt.getList(BOUNDS_KEY, NbtElement.COMPOUND_TYPE);
        for (NbtElement element : bounds) {
            if (!(element instanceof NbtCompound entry)) {
                continue;
            }
            if (!entry.contains(DIMENSION_KEY, NbtElement.STRING_TYPE)
                    || !entry.contains(BELL_POS_KEY, NbtElement.COMPOUND_TYPE)) {
                continue;
            }
            Identifier dimId = Identifier.tryParse(entry.getString(DIMENSION_KEY));
            if (dimId == null) {
                continue;
            }
            Optional<BlockPos> bellPos = NbtHelper.toBlockPos(entry, BELL_POS_KEY);
            if (bellPos.isEmpty()) {
                continue;
            }
            int minX = entry.getInt(MIN_X_KEY);
            int maxX = entry.getInt(MAX_X_KEY);
            int minZ = entry.getInt(MIN_Z_KEY);
            int maxZ = entry.getInt(MAX_Z_KEY);

            RegistryKey<net.minecraft.world.World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimId);
            GlobalPos key = GlobalPos.create(worldKey, bellPos.get().toImmutable());
            state.bellToBounds.put(key, new MappedBounds(minX, maxX, minZ, maxZ));
        }

        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        NbtList list = new NbtList();
        for (Map.Entry<GlobalPos, MappedBounds> entry : bellToBounds.entrySet()) {
            NbtCompound row = new NbtCompound();
            row.putString(DIMENSION_KEY, entry.getKey().dimension().getValue().toString());
            row.put(BELL_POS_KEY, NbtHelper.fromBlockPos(entry.getKey().pos()));
            MappedBounds b = entry.getValue();
            row.putInt(MIN_X_KEY, b.minX());
            row.putInt(MAX_X_KEY, b.maxX());
            row.putInt(MIN_Z_KEY, b.minZ());
            row.putInt(MAX_Z_KEY, b.maxZ());
            list.add(row);
        }
        nbt.put(BOUNDS_KEY, list);
        return nbt;
    }

    /**
     * Stores mapped bounds for the bell at {@code bellPos} in the overworld (or any dimension
     * represented by {@code worldKey}).
     */
    public void putBounds(RegistryKey<net.minecraft.world.World> worldKey, BlockPos bellPos, MappedBounds bounds) {
        GlobalPos key = GlobalPos.create(worldKey, bellPos.toImmutable());
        bellToBounds.put(key, bounds);
        markDirty();
    }

    /**
     * Returns the mapped bounds for the nearest registered bell within {@code searchRadius}
     * horizontal blocks of {@code pos}, if any. Returns empty if no cartographer has completed
     * mapping for any nearby bell.
     */
    public Optional<MappedBounds> getBoundsNear(RegistryKey<net.minecraft.world.World> worldKey, BlockPos pos, int searchRadius) {
        long radiusSq = (long) searchRadius * searchRadius;
        MappedBounds nearest = null;
        long nearestDistSq = Long.MAX_VALUE;

        for (Map.Entry<GlobalPos, MappedBounds> entry : bellToBounds.entrySet()) {
            if (!entry.getKey().dimension().equals(worldKey)) {
                continue;
            }
            BlockPos bellPos = entry.getKey().pos();
            long dx = bellPos.getX() - pos.getX();
            long dz = bellPos.getZ() - pos.getZ();
            long distSq = dx * dx + dz * dz;
            if (distSq <= radiusSq && distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = entry.getValue();
            }
        }

        return Optional.ofNullable(nearest);
    }
}
