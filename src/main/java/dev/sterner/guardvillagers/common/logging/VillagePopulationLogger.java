package dev.sterner.guardvillagers.common.logging;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class VillagePopulationLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger("GuardVillagers/VillagePopulationLogger");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int POPULATION_SCAN_RADIUS = 64;
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;
    private static Path logRoot;
    private static final Map<VillageKey, VillageLog> LOG_CACHE = new HashMap<>();
    private static final Set<VillageKey> DISCOVERED_VILLAGES = new HashSet<>();

    private VillagePopulationLogger() {
    }

    public static void init() {
        logRoot = FabricLoader.getInstance().getConfigDir()
                .resolve("guardvillagers")
                .resolve("village_logs");
        try {
            Files.createDirectories(logRoot);
            preloadLogs();
        } catch (IOException e) {
            LOGGER.error("Failed to prepare village log directory {}", logRoot, e);
        }
    }

    public static void onChunkLoaded(ServerWorld world, WorldChunk chunk) {
        Box chunkBox = createChunkBox(world, chunk.getPos());
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, chunkBox, LivingEntity::isAlive)) {
            villager.getBrain().getOptionalMemory(MemoryModuleType.MEETING_POINT).ifPresent(meetingPoint -> {
                if (meetingPoint.dimension().equals(world.getRegistryKey())) {
                    logVillageDiscovery(world, meetingPoint);
                }
            });
        }
    }

    public static void logVillagerDeath(VillagerEntity villager, DamageSource source) {
        if (!(villager.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }
        String reason = "Villager killed (" + source.getName() + "): " + villager.getName().getString();
        findAnchorForVillager(villager).ifPresent(anchor -> logSnapshot(serverWorld, anchor, reason));
    }

    public static Optional<GlobalPos> handleJobSiteUpdate(VillagerEntity villager, Optional<GlobalPos> previousJobSite) {
        if (!(villager.getWorld() instanceof ServerWorld serverWorld)) {
            return previousJobSite;
        }

        Optional<GlobalPos> currentJobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
        if (previousJobSite.isPresent() && currentJobSite.isEmpty()) {
            GlobalPos oldJobSite = previousJobSite.get();
            String reason = "Professional lost job block at " + formatPos(oldJobSite.pos());
            findAnchorForVillager(villager).ifPresent(anchor -> logSnapshot(serverWorld, anchor, reason));
        }

        return currentJobSite;
    }

    public static void logChestPairing(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        String reason = "Chest paired to " + villager.getName().getString() + " at job block " + formatPos(jobPos) + " (chest " + formatPos(chestPos) + ")";
        GlobalPos anchor = findAnchorForVillager(villager).orElseGet(() -> GlobalPos.create(world.getRegistryKey(), jobPos));
        logSnapshot(world, anchor, reason);
    }

    private static void logVillageDiscovery(ServerWorld world, GlobalPos meetingPoint) {
        VillageKey key = VillageKey.of(meetingPoint);
        if (DISCOVERED_VILLAGES.add(key)) {
            LOGGER.info("Discovered new village at {} in {}. Creating log file.", formatPos(meetingPoint.pos()), meetingPoint.dimension().getValue());
            logSnapshot(world, meetingPoint, "Village entered render distance");
        }
    }

    private static void logSnapshot(ServerWorld world, GlobalPos anchor, String reason) {
        if (!anchor.dimension().equals(world.getRegistryKey())) {
            return;
        }

        VillageKey key = VillageKey.of(anchor);
        VillageLog log = LOG_CACHE.computeIfAbsent(key, k -> loadLog(k));
        VillageSnapshot snapshot = captureSnapshot(world, anchor.pos(), reason);
        log.history.add(snapshot);
        writeLog(key, log);

        LOGGER.info("Village log update [{}]: {} (total villagers {}, employed {})",
                key.describe(), reason, snapshot.totalVillagers, snapshot.employedVillagers);
    }

    private static VillageSnapshot captureSnapshot(ServerWorld world, BlockPos anchor, String reason) {
        Vec3d anchorCenter = Vec3d.ofCenter(anchor);
        double diameter = POPULATION_SCAN_RADIUS * 2.0D;
        Box scanBox = Box.of(anchorCenter, diameter, diameter, diameter);
        List<VillagerEntity> villagers = world.getEntitiesByClass(VillagerEntity.class, scanBox, LivingEntity::isAlive);

        int employedVillagers = 0;
        Map<String, Integer> professionCounts = new TreeMap<>();
        List<JobBlockEntry> jobBlocks = new ArrayList<>();

        for (VillagerEntity villager : villagers) {
            VillagerProfession profession = villager.getVillagerData().getProfession();
            boolean employed = profession != VillagerProfession.NONE && profession != VillagerProfession.NITWIT;
            if (employed) {
                employedVillagers++;
                String professionId = Registries.VILLAGER_PROFESSION.getId(profession).toString();
                professionCounts.merge(professionId, 1, Integer::sum);
                villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE)
                        .filter(pos -> pos.dimension().equals(world.getRegistryKey()))
                        .ifPresent(jobSite -> jobBlocks.add(new JobBlockEntry(villager.getName().getString(), professionId, Position.from(jobSite.pos()))));
            }
        }

        return new VillageSnapshot(
                ISO_FORMATTER.format(Instant.now()),
                world.getTime(),
                reason,
                villagers.size(),
                employedVillagers,
                professionCounts,
                jobBlocks
        );
    }

    private static VillageLog loadLog(VillageKey key) {
        Path path = toPath(key);
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                VillageLog existing = GSON.fromJson(reader, VillageLog.class);
                if (existing != null) {
                    return existing;
                }
            } catch (IOException e) {
                LOGGER.error("Failed to read village log {}", path, e);
            }
        }

        VillageLog log = new VillageLog(new VillageDescriptor(key.dimension(), Position.from(key.meetingPoint())), new ArrayList<>());
        DISCOVERED_VILLAGES.add(key);
        return log;
    }

    private static void writeLog(VillageKey key, VillageLog log) {
        Path path = toPath(key);
        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(log, writer);
        } catch (IOException e) {
            LOGGER.error("Failed to write village log {}", path, e);
        }
    }

    private static void preloadLogs() throws IOException {
        if (!Files.exists(logRoot)) {
            return;
        }

        try (var stream = Files.list(logRoot)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> {
                        try (Reader reader = Files.newBufferedReader(path)) {
                            VillageLog log = GSON.fromJson(reader, VillageLog.class);
                            if (log != null && log.village != null) {
                                VillageKey key = VillageKey.of(log.village);
                                DISCOVERED_VILLAGES.add(key);
                                LOG_CACHE.putIfAbsent(key, log);
                            }
                        } catch (IOException e) {
                            LOGGER.error("Failed to load existing village log {}", path, e);
                        }
                    });
        }
    }

    private static Optional<GlobalPos> findAnchorForVillager(VillagerEntity villager) {
        Optional<GlobalPos> meetingPoint = villager.getBrain().getOptionalMemory(MemoryModuleType.MEETING_POINT);
        if (meetingPoint.isPresent()) {
            return meetingPoint;
        }
        Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
        if (jobSite.isPresent()) {
            return jobSite;
        }
        if (villager.getWorld() instanceof ServerWorld serverWorld) {
            return Optional.of(GlobalPos.create(serverWorld.getRegistryKey(), villager.getBlockPos()));
        }
        return Optional.empty();
    }

    private static Box createChunkBox(ServerWorld world, ChunkPos pos) {
        int minY = world.getDimension().minY();
        int maxY = minY + world.getDimension().height();
        return new Box(pos.getStartX(), minY, pos.getStartZ(), pos.getEndX() + 1, maxY, pos.getEndZ() + 1);
    }

    private static Path toPath(VillageKey key) {
        String safeDimension = key.dimension().replace(':', '_').replace('/', '_');
        String fileName = String.format("%s_%d_%d_%d.json", safeDimension, key.meetingPoint().getX(), key.meetingPoint().getY(), key.meetingPoint().getZ());
        return logRoot.resolve(fileName);
    }

    private static String formatPos(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private record VillageKey(String dimension, BlockPos meetingPoint) {
        static VillageKey of(GlobalPos pos) {
            return new VillageKey(pos.dimension().getValue().toString(), pos.pos());
        }

        static VillageKey of(VillageDescriptor descriptor) {
            return new VillageKey(descriptor.dimension(), descriptor.meetingPoint().toBlockPos());
        }

        String describe() {
            return dimension + " " + formatPos(meetingPoint);
        }
    }

    private record VillageLog(VillageDescriptor village, List<VillageSnapshot> history) {
    }

    private record VillageDescriptor(String dimension, Position meetingPoint) {
    }

    private record Position(int x, int y, int z) {
        static Position from(BlockPos pos) {
            return new Position(pos.getX(), pos.getY(), pos.getZ());
        }

        BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }
    }

    private record JobBlockEntry(String villager, String profession, Position jobSite) {
    }

    private record VillageSnapshot(String timestamp, long gameTime, String reason, int totalVillagers,
                                   int employedVillagers, Map<String, Integer> professionBreakdown,
                                   List<JobBlockEntry> jobBlocks) {
    }
}
