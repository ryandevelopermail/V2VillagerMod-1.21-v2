package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.GuardVillagers;
import net.minecraft.block.ChestBlock;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Persistent anchor registry for the mod's village economy.
 *
 * <p>The Quartermaster's paired double-chest is the village anchor — the geographic center
 * and item bank of the village. Every system that previously required a bell position
 * (mason wall builder, lumberjack pen builder, librarian distribution, guard stand platform,
 * cartographer bounds, pen registry) now resolves to the nearest QM chest instead.
 *
 * <p>API:
 * <ul>
 *   <li>{@link #register(ServerWorld, BlockPos)} — called when a QM pairs its chest.</li>
 *   <li>{@link #unregister(ServerWorld, BlockPos)} — called when QM is removed/unlinked.</li>
 *   <li>{@link #getNearestQmChest(ServerWorld, BlockPos, int)} — primary lookup for all systems.</li>
 *   <li>{@link #getAllQmChests(ServerWorld)} — returns all registered QM chests in a world.</li>
 * </ul>
 *
 * <p>Degrades gracefully: all callers return {@link Optional#empty()} or {@code null} when no
 * QM chest is registered, allowing systems to skip their work until a QM is placed.
 */
public class VillageAnchorState extends PersistentState {

    private static final Logger LOGGER = LoggerFactory.getLogger(VillageAnchorState.class);
    private static final String STATE_ID = GuardVillagers.MODID + "_village_anchor";
    private static final String ANCHORS_KEY = "Anchors";
    private static final String DIMENSION_KEY = "Dimension";
    private static final String CHEST_POS_KEY = "ChestPos";

    /** All registered QM chest positions, keyed by world. */
    private final java.util.Map<RegistryKey<net.minecraft.world.World>, Set<BlockPos>> anchorsByWorld =
            new java.util.HashMap<>();

    // -------------------------------------------------------------------------
    // Static accessors
    // -------------------------------------------------------------------------

    public static VillageAnchorState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(getType(), STATE_ID);
    }

    private static Type<VillageAnchorState> getType() {
        return new Type<>(VillageAnchorState::new, VillageAnchorState::fromNbt, null);
    }

    // -------------------------------------------------------------------------
    // NBT
    // -------------------------------------------------------------------------

    private static VillageAnchorState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        VillageAnchorState state = new VillageAnchorState();
        NbtList list = nbt.getList(ANCHORS_KEY, NbtElement.COMPOUND_TYPE);
        for (NbtElement element : list) {
            if (!(element instanceof NbtCompound entry)) continue;
            if (!entry.contains(DIMENSION_KEY, NbtElement.STRING_TYPE)) continue;
            Identifier dimId = Identifier.tryParse(entry.getString(DIMENSION_KEY));
            if (dimId == null) continue;
            Optional<BlockPos> pos = NbtHelper.toBlockPos(entry, CHEST_POS_KEY);
            if (pos.isEmpty()) continue;
            RegistryKey<net.minecraft.world.World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimId);
            state.anchorsByWorld.computeIfAbsent(worldKey, k -> new HashSet<>()).add(pos.get().toImmutable());
        }
        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        NbtList list = new NbtList();
        for (java.util.Map.Entry<RegistryKey<net.minecraft.world.World>, Set<BlockPos>> worldEntry : anchorsByWorld.entrySet()) {
            for (BlockPos pos : worldEntry.getValue()) {
                NbtCompound entry = new NbtCompound();
                entry.putString(DIMENSION_KEY, worldEntry.getKey().getValue().toString());
                entry.put(CHEST_POS_KEY, NbtHelper.fromBlockPos(pos));
                list.add(entry);
            }
        }
        nbt.put(ANCHORS_KEY, list);
        return nbt;
    }

    // -------------------------------------------------------------------------
    // Registration API
    // -------------------------------------------------------------------------

    /**
     * Registers a QM chest position as the village anchor for the given world.
     * Called when a Quartermaster pairs its chest.
     */
    public void register(ServerWorld world, BlockPos qmChestPos) {
        Set<BlockPos> set = anchorsByWorld.computeIfAbsent(world.getRegistryKey(), k -> new HashSet<>());
        if (set.add(qmChestPos.toImmutable())) {
            markDirty();
            LOGGER.info("[VillageAnchorState] registered QM chest at {} (world: {})",
                    qmChestPos.toShortString(), world.getRegistryKey().getValue());
        }
    }

    /**
     * Unregisters a QM chest position when the QM is removed or loses pairing.
     */
    public void unregister(ServerWorld world, BlockPos qmChestPos) {
        Set<BlockPos> set = anchorsByWorld.get(world.getRegistryKey());
        if (set != null && set.remove(qmChestPos.toImmutable())) {
            if (set.isEmpty()) {
                anchorsByWorld.remove(world.getRegistryKey());
            }
            markDirty();
            LOGGER.info("[VillageAnchorState] unregistered QM chest at {} (world: {})",
                    qmChestPos.toShortString(), world.getRegistryKey().getValue());
        }
    }

    /**
     * Removes stale anchors in the provided world.
     *
     * <p>An anchor is stale when its position no longer contains a chest block.
     * This method is safe to call frequently and is idempotent.
     */
    public void pruneInvalidAnchors(ServerWorld world) {
        Set<BlockPos> set = anchorsByWorld.get(world.getRegistryKey());
        if (set == null || set.isEmpty()) return;

        boolean changed = set.removeIf(pos -> !isValidAnchorBlock(world, pos));
        if (changed) {
            if (set.isEmpty()) {
                anchorsByWorld.remove(world.getRegistryKey());
            }
            markDirty();
            LOGGER.info("[VillageAnchorState] pruned stale QM anchors (world: {})", world.getRegistryKey().getValue());
        }
    }

    // -------------------------------------------------------------------------
    // Query API
    // -------------------------------------------------------------------------

    /**
     * Returns the nearest registered QM chest within {@code radius} blocks of {@code origin},
     * or {@link Optional#empty()} if none is registered nearby.
     *
     * <p>All callers should treat empty as "no QM in this village yet" and skip their work
     * gracefully rather than throwing.
     */
    public Optional<BlockPos> getNearestQmChest(ServerWorld world, BlockPos origin, int radius) {
        pruneInvalidAnchors(world);
        Set<BlockPos> set = anchorsByWorld.get(world.getRegistryKey());
        if (set == null || set.isEmpty()) return Optional.empty();

        long radiusSq = (long) radius * radius;
        BlockPos nearest = null;
        long nearestDist = Long.MAX_VALUE;

        for (BlockPos pos : set) {
            long dx = pos.getX() - origin.getX();
            long dy = pos.getY() - origin.getY();
            long dz = pos.getZ() - origin.getZ();
            long distSq = dx * dx + dy * dy + dz * dz;
            if (distSq <= radiusSq && distSq < nearestDist) {
                nearestDist = distSq;
                nearest = pos;
            }
        }
        return Optional.ofNullable(nearest);
    }

    /**
     * Returns all registered QM chest positions in the given world (unmodifiable).
     */
    public Set<BlockPos> getAllQmChests(ServerWorld world) {
        pruneInvalidAnchors(world);
        Set<BlockPos> set = anchorsByWorld.get(world.getRegistryKey());
        return set == null ? Collections.emptySet() : Collections.unmodifiableSet(set);
    }

    private boolean isValidAnchorBlock(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).getBlock() instanceof ChestBlock;
    }
}
