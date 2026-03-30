package dev.sterner.guardvillagers.common.villager;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.village.VillagerProfession;

import java.util.*;

public final class VillagerConversionCandidateIndex {
    private static final Map<WorldKey, Map<VillagerProfession, Map<Long, Set<UUID>>>> CANDIDATES = new HashMap<>();
    private static final Map<WorldKey, Deque<Long>> PENDING_CHUNK_SCANS = new HashMap<>();
    private static final Map<WorldKey, Set<Long>> PENDING_CHUNK_SCAN_SET = new HashMap<>();

    private VillagerConversionCandidateIndex() {
    }

    public static void markCandidate(ServerWorld world, VillagerEntity villager) {
        if (!villager.isAlive()) {
            return;
        }

        VillagerProfession profession = villager.getVillagerData().getProfession();
        if (!ProfessionDefinitions.hasDefinition(profession)) {
            return;
        }

        WorldKey worldKey = WorldKey.of(world);
        long chunkKey = ChunkPos.toLong(villager.getBlockX() >> 4, villager.getBlockZ() >> 4);

        CANDIDATES
                .computeIfAbsent(worldKey, ignored -> new HashMap<>())
                .computeIfAbsent(profession, ignored -> new HashMap<>())
                .computeIfAbsent(chunkKey, ignored -> new HashSet<>())
                .add(villager.getUuid());
    }

    public static void markCandidatesInChunk(ServerWorld world, int chunkX, int chunkZ) {
        Box bounds = new Box(
                chunkX << 4,
                world.getBottomY(),
                chunkZ << 4,
                (chunkX << 4) + 16,
                world.getTopY(),
                (chunkZ << 4) + 16
        );

        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, bounds, VillagerEntity::isAlive)) {
            markCandidate(world, villager);
        }
    }

    public static void enqueueChunkScan(ServerWorld world, int chunkX, int chunkZ) {
        WorldKey worldKey = WorldKey.of(world);
        long chunkKey = ChunkPos.toLong(chunkX, chunkZ);

        Set<Long> pendingSet = PENDING_CHUNK_SCAN_SET.computeIfAbsent(worldKey, ignored -> new HashSet<>());
        if (!pendingSet.add(chunkKey)) {
            return;
        }

        PENDING_CHUNK_SCANS
                .computeIfAbsent(worldKey, ignored -> new ArrayDeque<>())
                .addLast(chunkKey);
    }

    public static int processQueuedChunkScans(ServerWorld world, int maxChunks) {
        if (maxChunks <= 0) {
            return 0;
        }

        WorldKey worldKey = WorldKey.of(world);
        Deque<Long> queue = PENDING_CHUNK_SCANS.get(worldKey);
        Set<Long> pendingSet = PENDING_CHUNK_SCAN_SET.get(worldKey);
        if (queue == null || queue.isEmpty() || pendingSet == null || pendingSet.isEmpty()) {
            return 0;
        }

        int processed = 0;
        while (processed < maxChunks && !queue.isEmpty()) {
            long chunkKey = queue.removeFirst();
            pendingSet.remove(chunkKey);
            markCandidatesInChunk(world, ChunkPos.getPackedX(chunkKey), ChunkPos.getPackedZ(chunkKey));
            processed++;
        }

        if (queue.isEmpty()) {
            PENDING_CHUNK_SCANS.remove(worldKey);
        }
        if (pendingSet.isEmpty()) {
            PENDING_CHUNK_SCAN_SET.remove(worldKey);
        }

        return processed;
    }

    public static int getQueuedChunkScanCount(ServerWorld world) {
        Deque<Long> queue = PENDING_CHUNK_SCANS.get(WorldKey.of(world));
        return queue == null ? 0 : queue.size();
    }

    public static void clearWorld(ServerWorld world) {
        WorldKey worldKey = WorldKey.of(world);
        CANDIDATES.remove(worldKey);
        PENDING_CHUNK_SCANS.remove(worldKey);
        PENDING_CHUNK_SCAN_SET.remove(worldKey);
    }

    public static void markCandidatesNear(ServerWorld world, BlockPos center, double range) {
        Box bounds = new Box(center).expand(range);
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, bounds, VillagerEntity::isAlive)) {
            markCandidate(world, villager);
        }
    }

    public static List<VillagerEntity> pollCandidates(ServerWorld world, VillagerProfession profession) {
        WorldKey worldKey = WorldKey.of(world);
        Map<VillagerProfession, Map<Long, Set<UUID>>> byProfession = CANDIDATES.get(worldKey);
        if (byProfession == null) {
            return List.of();
        }

        Map<Long, Set<UUID>> byChunk = byProfession.get(profession);
        if (byChunk == null || byChunk.isEmpty()) {
            return List.of();
        }

        List<VillagerEntity> candidates = new ArrayList<>();
        Iterator<Map.Entry<Long, Set<UUID>>> chunkIterator = byChunk.entrySet().iterator();
        while (chunkIterator.hasNext()) {
            Map.Entry<Long, Set<UUID>> chunkEntry = chunkIterator.next();
            Iterator<UUID> uuidIterator = chunkEntry.getValue().iterator();
            while (uuidIterator.hasNext()) {
                UUID uuid = uuidIterator.next();
                uuidIterator.remove();

                var entity = world.getEntity(uuid);
                if (entity instanceof VillagerEntity villager && villager.isAlive() && villager.getVillagerData().getProfession() == profession) {
                    candidates.add(villager);
                }
            }

            if (chunkEntry.getValue().isEmpty()) {
                chunkIterator.remove();
            }
        }

        if (byChunk.isEmpty()) {
            byProfession.remove(profession);
        }

        if (byProfession.isEmpty()) {
            CANDIDATES.remove(worldKey);
        }

        return candidates;
    }

    private record WorldKey(String dimensionId) {
        private static WorldKey of(ServerWorld world) {
            return new WorldKey(world.getRegistryKey().getValue().toString());
        }
    }
}
