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

public class VillageWallProjectState extends PersistentState {
    private static final String STATE_ID = GuardVillagers.MODID + "_village_wall_projects";
    private static final String PROJECTS_KEY = "Projects";
    private static final String DIMENSION_KEY = "Dimension";
    private static final String ANCHOR_POS_KEY = "AnchorPos";
    private static final String MIN_X_KEY = "MinX";
    private static final String MAX_X_KEY = "MaxX";
    private static final String MIN_Z_KEY = "MinZ";
    private static final String MAX_Z_KEY = "MaxZ";
    private static final String POI_COUNT_KEY = "PoiCount";
    private static final String POI_HASH_KEY = "PoiHash";
    private static final String LAYER1_COMPLETE_KEY = "Layer1Complete";
    private static final String LAYER2_COMPLETE_KEY = "Layer2Complete";
    private static final String LAYER3_COMPLETE_KEY = "Layer3Complete";
    private static final String COMPLETE_KEY = "Complete";

    private final Map<GlobalPos, WallProject> projects = new HashMap<>();

    public static VillageWallProjectState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(getType(), STATE_ID);
    }

    private static Type<VillageWallProjectState> getType() {
        return new Type<>(VillageWallProjectState::new, VillageWallProjectState::fromNbt, null);
    }

    private static VillageWallProjectState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        VillageWallProjectState state = new VillageWallProjectState();
        NbtList list = nbt.getList(PROJECTS_KEY, NbtElement.COMPOUND_TYPE);
        for (NbtElement element : list) {
            if (!(element instanceof NbtCompound row)) continue;
            if (!row.contains(DIMENSION_KEY, NbtElement.STRING_TYPE)
                    || !row.contains(ANCHOR_POS_KEY, NbtElement.COMPOUND_TYPE)) {
                continue;
            }

            Identifier dimId = Identifier.tryParse(row.getString(DIMENSION_KEY));
            if (dimId == null) continue;
            Optional<BlockPos> anchorPos = NbtHelper.toBlockPos(row, ANCHOR_POS_KEY);
            if (anchorPos.isEmpty()) continue;

            RegistryKey<net.minecraft.world.World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimId);
            GlobalPos key = GlobalPos.create(worldKey, anchorPos.get().toImmutable());
            WallProject project = new WallProject(
                    new PerimeterBounds(row.getInt(MIN_X_KEY), row.getInt(MAX_X_KEY), row.getInt(MIN_Z_KEY), row.getInt(MAX_Z_KEY)),
                    new PerimeterSignature(row.getInt(POI_COUNT_KEY), row.getInt(POI_HASH_KEY)),
                    row.getBoolean(LAYER1_COMPLETE_KEY),
                    row.getBoolean(LAYER2_COMPLETE_KEY),
                    row.getBoolean(LAYER3_COMPLETE_KEY),
                    row.getBoolean(COMPLETE_KEY)
            );
            state.projects.put(key, project);
        }
        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        NbtList list = new NbtList();
        for (Map.Entry<GlobalPos, WallProject> entry : projects.entrySet()) {
            NbtCompound row = new NbtCompound();
            row.putString(DIMENSION_KEY, entry.getKey().dimension().getValue().toString());
            row.put(ANCHOR_POS_KEY, NbtHelper.fromBlockPos(entry.getKey().pos()));
            WallProject project = entry.getValue();
            row.putInt(MIN_X_KEY, project.bounds().minX());
            row.putInt(MAX_X_KEY, project.bounds().maxX());
            row.putInt(MIN_Z_KEY, project.bounds().minZ());
            row.putInt(MAX_Z_KEY, project.bounds().maxZ());
            row.putInt(POI_COUNT_KEY, project.signature().poiCount());
            row.putInt(POI_HASH_KEY, project.signature().poiHash());
            row.putBoolean(LAYER1_COMPLETE_KEY, project.layer1Complete());
            row.putBoolean(LAYER2_COMPLETE_KEY, project.layer2Complete());
            row.putBoolean(LAYER3_COMPLETE_KEY, project.layer3Complete());
            row.putBoolean(COMPLETE_KEY, project.complete());
            list.add(row);
        }
        nbt.put(PROJECTS_KEY, list);
        return nbt;
    }

    public boolean upsertProject(RegistryKey<net.minecraft.world.World> worldKey,
                                 BlockPos anchorPos,
                                 PerimeterBounds bounds,
                                 PerimeterSignature signature) {
        GlobalPos key = GlobalPos.create(worldKey, anchorPos.toImmutable());
        WallProject current = projects.get(key);
        if (current == null) {
            projects.put(key, new WallProject(bounds, signature, false, false, false, false));
            markDirty();
            return true;
        }
        if (!current.bounds().equals(bounds) || !current.signature().equals(signature)) {
            projects.put(key, new WallProject(bounds, signature, false, false, false, false));
            markDirty();
            return true;
        }
        return false;
    }

    public void markAllLayersComplete(RegistryKey<net.minecraft.world.World> worldKey, BlockPos anchorPos) {
        GlobalPos key = GlobalPos.create(worldKey, anchorPos.toImmutable());
        WallProject current = projects.get(key);
        if (current == null) return;
        if (current.complete() && current.layer1Complete() && current.layer2Complete() && current.layer3Complete()) {
            return;
        }
        projects.put(key, new WallProject(current.bounds(), current.signature(), true, true, true, true));
        markDirty();
    }


    public boolean hasProject(RegistryKey<net.minecraft.world.World> worldKey, BlockPos anchorPos) {
        GlobalPos key = GlobalPos.create(worldKey, anchorPos.toImmutable());
        return projects.containsKey(key);
    }

    public Optional<PerimeterBounds> getProjectBounds(RegistryKey<net.minecraft.world.World> worldKey, BlockPos anchorPos) {
        GlobalPos key = GlobalPos.create(worldKey, anchorPos.toImmutable());
        WallProject project = projects.get(key);
        return project == null ? Optional.empty() : Optional.of(project.bounds());
    }

    public boolean isProjectComplete(RegistryKey<net.minecraft.world.World> worldKey, BlockPos anchorPos) {
        GlobalPos key = GlobalPos.create(worldKey, anchorPos.toImmutable());
        WallProject project = projects.get(key);
        return project != null && project.complete();
    }

    public boolean isCompletedProjectContaining(RegistryKey<net.minecraft.world.World> worldKey, BlockPos pos) {
        for (Map.Entry<GlobalPos, WallProject> entry : projects.entrySet()) {
            if (!entry.getKey().dimension().equals(worldKey)) continue;
            WallProject project = entry.getValue();
            if (project.complete() && project.bounds().contains(pos)) {
                return true;
            }
        }
        return false;
    }

    public record PerimeterBounds(int minX, int maxX, int minZ, int maxZ) {
        public boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }
    }

    public record PerimeterSignature(int poiCount, int poiHash) {}

    private record WallProject(PerimeterBounds bounds,
                               PerimeterSignature signature,
                               boolean layer1Complete,
                               boolean layer2Complete,
                               boolean layer3Complete,
                               boolean complete) {}
}
