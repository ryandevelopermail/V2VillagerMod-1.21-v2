package dev.sterner.guardvillagers.common.villager;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class CraftingCheckLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger(CraftingCheckLogger.class);
    public static final int MATERIAL_CHECK_INTERVAL_TICKS = 1200;
    private static final Map<String, VillageSnapshot> SNAPSHOTS = new LinkedHashMap<>();

    private CraftingCheckLogger() {
    }

    public static void report(VillagerEntity villager, String profession, String result) {
        report(villager, profession, null, 0, result);
    }

    public static void report(VillagerEntity villager, String profession, String triggerSource, int intervalTicks, String result) {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return;
        }

        long tick = world.getTime();
        String villageKey = resolveVillageKey(villager, world);
        VillageSnapshot villageSnapshot = SNAPSHOTS.computeIfAbsent(villageKey, ignored -> new VillageSnapshot());
        villageSnapshot.lastSeenTick = tick;

        String logResult = (triggerSource == null || triggerSource.isBlank())
                ? result
                : "[" + formatTrigger(triggerSource, intervalTicks) + "] " + result;
        villageSnapshot.professionStatuses.put(profession, logResult);

        if (GuardVillagersConfig.villageWorkflowVerboseLogging) {
            LOGGER.info("Village workflow detail [{}] {} -> {}", villageKey, profession, logResult);
        }

        long pulseInterval = Math.max(20, GuardVillagersConfig.villageWorkflowPulseIntervalTicks);
        if (tick - villageSnapshot.lastLoggedTick >= pulseInterval) {
            logVillagePulse(villageKey, villageSnapshot, tick);
        }

        pruneStaleSnapshots(tick, pulseInterval * 4L);
    }

    public static void report(VillagerEntity villager, String profession, String triggerContext, String result) {
        report(villager, profession, triggerContext, 0, result);
    }

    private static void logVillagePulse(String villageKey, VillageSnapshot snapshot, long tick) {
        if (snapshot.professionStatuses.isEmpty()) {
            return;
        }

        StringBuilder builder = new StringBuilder("Village workflow pulse [")
                .append(villageKey)
                .append("] tick=")
                .append(tick)
                .append(':');

        for (Map.Entry<String, String> entry : snapshot.professionStatuses.entrySet()) {
            builder.append("\n     ").append(entry.getKey()).append(" - ").append(entry.getValue());
        }

        LOGGER.info(builder.toString());
        snapshot.lastLoggedTick = tick;
    }

    private static void pruneStaleSnapshots(long tick, long staleAfterTicks) {
        SNAPSHOTS.entrySet().removeIf(entry -> tick - entry.getValue().lastSeenTick > staleAfterTicks);
    }

    private static String resolveVillageKey(VillagerEntity villager, ServerWorld world) {
        return villager.getBrain().getOptionalMemory(MemoryModuleType.MEETING_POINT)
                .map(GlobalPos::pos)
                .map(BlockPos::toImmutable)
                .map(pos -> "bell@" + formatBlockPos(pos))
                .orElseGet(() -> "unassigned@" + world.getRegistryKey().getValue());
    }

    private static String formatBlockPos(Vec3i pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String formatTrigger(String triggerSource, int intervalTicks) {
        String normalized = triggerSource.toLowerCase(Locale.ROOT).replace('_', ' ');
        if (intervalTicks > 0) {
            return normalized + " (" + intervalTicks + " ticks)";
        }
        return normalized + " (immediate)";
    }

    private static final class VillageSnapshot {
        private final Map<String, String> professionStatuses = new TreeMap<>();
        private long lastLoggedTick = Long.MIN_VALUE / 4;
        private long lastSeenTick;
    }
}
