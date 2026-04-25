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
import java.util.UUID;

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
    private static final String CURRENT_BUILDER_UUID_KEY = "CurrentBuilderUuid";
    private static final String ASSIGNMENT_START_TICK_KEY = "AssignmentStartTick";
    private static final String LAST_SEGMENT_PLACED_TICK_KEY = "LastSegmentPlacedTick";
    private static final String PARTICIPATION_KEY = "Participation";
    private static final String MASON_UUID_KEY = "MasonUuid";
    private static final String SEGMENTS_PLACED_KEY = "SegmentsPlaced";
    private static final String SESSIONS_RUN_KEY = "SessionsRun";

    private final Map<GlobalPos, WallProject> projects = new HashMap<>();
    private final Map<GlobalPos, Map<BlockPos, SegmentClaim>> segmentClaims = new HashMap<>();

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
                    row.getBoolean(COMPLETE_KEY),
                    row.contains(CURRENT_BUILDER_UUID_KEY, NbtElement.STRING_TYPE)
                            ? parseUuid(row.getString(CURRENT_BUILDER_UUID_KEY))
                            : null,
                    row.contains(ASSIGNMENT_START_TICK_KEY, NbtElement.LONG_TYPE) ? row.getLong(ASSIGNMENT_START_TICK_KEY) : -1L,
                    row.contains(LAST_SEGMENT_PLACED_TICK_KEY, NbtElement.LONG_TYPE) ? row.getLong(LAST_SEGMENT_PLACED_TICK_KEY) : -1L,
                    readParticipation(row)
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
            if (project.currentBuilderUuid() != null) {
                row.putString(CURRENT_BUILDER_UUID_KEY, project.currentBuilderUuid().toString());
            }
            row.putLong(ASSIGNMENT_START_TICK_KEY, project.assignmentStartTick());
            row.putLong(LAST_SEGMENT_PLACED_TICK_KEY, project.lastSegmentPlacedTick());
            row.put(PARTICIPATION_KEY, writeParticipation(project.participation()));
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
            projects.put(key, new WallProject(bounds, signature, false, false, false, false, null, -1L, -1L, Map.of()));
            markDirty();
            return true;
        }
        if (!current.bounds().equals(bounds) || !current.signature().equals(signature)) {
            projects.put(key, new WallProject(bounds, signature, false, false, false, false, null, -1L, -1L, current.participation()));
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
        projects.put(key, new WallProject(
                current.bounds(),
                current.signature(),
                true,
                true,
                true,
                true,
                null,
                -1L,
                current.lastSegmentPlacedTick(),
                current.participation()
        ));
        markDirty();
    }

    public Optional<ProjectAssignmentSnapshot> getAssignmentSnapshot(RegistryKey<net.minecraft.world.World> worldKey, BlockPos anchorPos) {
        GlobalPos key = GlobalPos.create(worldKey, anchorPos.toImmutable());
        WallProject project = projects.get(key);
        if (project == null || project.currentBuilderUuid() == null) {
            return Optional.empty();
        }
        return Optional.of(new ProjectAssignmentSnapshot(
                project.currentBuilderUuid(),
                project.assignmentStartTick(),
                project.lastSegmentPlacedTick()
        ));
    }

    public Map<UUID, ParticipationStats> getParticipationStats(RegistryKey<net.minecraft.world.World> worldKey, BlockPos anchorPos) {
        GlobalPos key = GlobalPos.create(worldKey, anchorPos.toImmutable());
        WallProject project = projects.get(key);
        return project == null ? Map.of() : Map.copyOf(project.participation());
    }

    public void assignBuilder(RegistryKey<net.minecraft.world.World> worldKey, BlockPos anchorPos, UUID builderUuid, long nowTick) {
        GlobalPos key = GlobalPos.create(worldKey, anchorPos.toImmutable());
        WallProject current = projects.get(key);
        if (current == null) return;
        Map<UUID, ParticipationStats> participation = new HashMap<>(current.participation());
        participation.compute(builderUuid, (uuid, existing) -> {
            if (existing == null) return new ParticipationStats(0, 1);
            return new ParticipationStats(existing.segmentsPlaced(), existing.sessionsRun() + 1);
        });
        projects.put(key, current.withAssignment(builderUuid, nowTick, nowTick, participation));
        markDirty();
    }

    public void clearAssignment(RegistryKey<net.minecraft.world.World> worldKey, BlockPos anchorPos) {
        GlobalPos key = GlobalPos.create(worldKey, anchorPos.toImmutable());
        WallProject current = projects.get(key);
        if (current == null) return;
        if (current.currentBuilderUuid() == null && current.assignmentStartTick() < 0L) return;
        projects.put(key, current.withAssignment(null, -1L, current.lastSegmentPlacedTick(), current.participation()));
        markDirty();
    }

    public void markBuilderProgress(RegistryKey<net.minecraft.world.World> worldKey, BlockPos anchorPos, UUID builderUuid, long nowTick) {
        GlobalPos key = GlobalPos.create(worldKey, anchorPos.toImmutable());
        WallProject current = projects.get(key);
        if (current == null) return;
        Map<UUID, ParticipationStats> participation = new HashMap<>(current.participation());
        participation.compute(builderUuid, (uuid, existing) -> {
            if (existing == null) return new ParticipationStats(1, 0);
            return new ParticipationStats(existing.segmentsPlaced() + 1, existing.sessionsRun());
        });
        UUID assigned = current.currentBuilderUuid();
        long assignmentStart = current.assignmentStartTick();
        if (assigned == null || !assigned.equals(builderUuid)) {
            assigned = builderUuid;
            assignmentStart = nowTick;
        }
        projects.put(key, current.withAssignment(assigned, assignmentStart, nowTick, participation));
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

    public boolean isSegmentClaimedByOther(RegistryKey<net.minecraft.world.World> worldKey,
                                           BlockPos anchorPos,
                                           BlockPos segmentPos,
                                           UUID guardId,
                                           long currentTick) {
        GlobalPos key = GlobalPos.create(worldKey, anchorPos.toImmutable());
        Map<BlockPos, SegmentClaim> claims = segmentClaims.get(key);
        if (claims == null || claims.isEmpty()) {
            return false;
        }
        purgeExpiredClaims(claims, currentTick);
        SegmentClaim claim = claims.get(segmentPos);
        return claim != null && !claim.guardId().equals(guardId);
    }

    public boolean claimSegment(RegistryKey<net.minecraft.world.World> worldKey,
                                BlockPos anchorPos,
                                BlockPos segmentPos,
                                UUID guardId,
                                long currentTick,
                                long claimDurationTicks) {
        GlobalPos key = GlobalPos.create(worldKey, anchorPos.toImmutable());
        Map<BlockPos, SegmentClaim> claims = segmentClaims.computeIfAbsent(key, ignored -> new HashMap<>());
        purgeExpiredClaims(claims, currentTick);

        SegmentClaim existing = claims.get(segmentPos);
        long expiresAtTick = currentTick + Math.max(1L, claimDurationTicks);
        if (existing == null || existing.guardId().equals(guardId)) {
            claims.put(segmentPos.toImmutable(), new SegmentClaim(guardId, expiresAtTick));
            return true;
        }
        return false;
    }

    public void releaseSegmentClaim(RegistryKey<net.minecraft.world.World> worldKey,
                                    BlockPos anchorPos,
                                    BlockPos segmentPos,
                                    UUID guardId) {
        GlobalPos key = GlobalPos.create(worldKey, anchorPos.toImmutable());
        Map<BlockPos, SegmentClaim> claims = segmentClaims.get(key);
        if (claims == null || claims.isEmpty()) {
            return;
        }
        SegmentClaim existing = claims.get(segmentPos);
        if (existing != null && existing.guardId().equals(guardId)) {
            claims.remove(segmentPos);
        }
        if (claims.isEmpty()) {
            segmentClaims.remove(key);
        }
    }

    public void releaseClaimsForGuard(RegistryKey<net.minecraft.world.World> worldKey,
                                      BlockPos anchorPos,
                                      UUID guardId) {
        GlobalPos key = GlobalPos.create(worldKey, anchorPos.toImmutable());
        Map<BlockPos, SegmentClaim> claims = segmentClaims.get(key);
        if (claims == null || claims.isEmpty()) {
            return;
        }
        claims.entrySet().removeIf(entry -> entry.getValue().guardId().equals(guardId));
        if (claims.isEmpty()) {
            segmentClaims.remove(key);
        }
    }

    private void purgeExpiredClaims(Map<BlockPos, SegmentClaim> claims, long currentTick) {
        claims.entrySet().removeIf(entry -> entry.getValue().expiresAtTick() <= currentTick);
    }

    public record PerimeterBounds(int minX, int maxX, int minZ, int maxZ) {
        public boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }
    }

    public record PerimeterSignature(int poiCount, int poiHash) {}

    public record ParticipationStats(int segmentsPlaced, int sessionsRun) {}

    public record ProjectAssignmentSnapshot(UUID builderUuid, long assignmentStartTick, long lastSegmentPlacedTick) {}

    private record WallProject(PerimeterBounds bounds,
                               PerimeterSignature signature,
                               boolean layer1Complete,
                               boolean layer2Complete,
                               boolean layer3Complete,
                               boolean complete,
                               UUID currentBuilderUuid,
                               long assignmentStartTick,
                               long lastSegmentPlacedTick,
                               Map<UUID, ParticipationStats> participation) {
        private WallProject withAssignment(UUID builderUuid,
                                           long startTick,
                                           long progressTick,
                                           Map<UUID, ParticipationStats> updatedParticipation) {
            return new WallProject(
                    bounds,
                    signature,
                    layer1Complete,
                    layer2Complete,
                    layer3Complete,
                    complete,
                    builderUuid,
                    startTick,
                    progressTick,
                    Map.copyOf(updatedParticipation)
            );
        }
    }

    private record SegmentClaim(UUID guardId, long expiresAtTick) {}

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Map<UUID, ParticipationStats> readParticipation(NbtCompound row) {
        if (!row.contains(PARTICIPATION_KEY, NbtElement.LIST_TYPE)) {
            return Map.of();
        }
        NbtList list = row.getList(PARTICIPATION_KEY, NbtElement.COMPOUND_TYPE);
        Map<UUID, ParticipationStats> stats = new HashMap<>();
        for (NbtElement element : list) {
            if (!(element instanceof NbtCompound participationRow)) continue;
            if (!participationRow.contains(MASON_UUID_KEY, NbtElement.STRING_TYPE)) continue;
            UUID uuid = parseUuid(participationRow.getString(MASON_UUID_KEY));
            if (uuid == null) continue;
            int segmentsPlaced = participationRow.getInt(SEGMENTS_PLACED_KEY);
            int sessionsRun = participationRow.getInt(SESSIONS_RUN_KEY);
            stats.put(uuid, new ParticipationStats(Math.max(0, segmentsPlaced), Math.max(0, sessionsRun)));
        }
        return Map.copyOf(stats);
    }

    private static NbtList writeParticipation(Map<UUID, ParticipationStats> participation) {
        NbtList list = new NbtList();
        for (Map.Entry<UUID, ParticipationStats> entry : participation.entrySet()) {
            NbtCompound row = new NbtCompound();
            row.putString(MASON_UUID_KEY, entry.getKey().toString());
            row.putInt(SEGMENTS_PLACED_KEY, Math.max(0, entry.getValue().segmentsPlaced()));
            row.putInt(SESSIONS_RUN_KEY, Math.max(0, entry.getValue().sessionsRun()));
            list.add(row);
        }
        return list;
    }
}
