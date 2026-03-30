package dev.sterner.guardvillagers.common.villager;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

public final class CraftingCheckLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger(CraftingCheckLogger.class);
    public static final int MATERIAL_CHECK_INTERVAL_TICKS = 1200;
    private static final int MIN_DIAGNOSTIC_INTERVAL_TICKS = 200;
    private static final int MAX_DIAGNOSTIC_INTERVAL_TICKS = 1200;
    private static final Map<Identifier, WorldDiagnostics> DIAGNOSTICS_BY_WORLD = new LinkedHashMap<>();

    private CraftingCheckLogger() {
    }

    public static boolean isEnabled() {
        return GuardVillagersConfig.enableCraftingDiagnostics;
    }

    public static void report(ServerWorld world, String profession, String result) {
        report(world, profession, null, 0, () -> result);
    }

    public static void report(ServerWorld world, String profession, String triggerSource, int intervalTicks, String result) {
        report(world, profession, triggerSource, intervalTicks, () -> result);
    }

    public static void report(ServerWorld world, String profession, String triggerContext, String result) {
        report(world, profession, triggerContext, 0, () -> result);
    }

    public static void report(ServerWorld world, String profession, String triggerContext, Supplier<String> resultSupplier) {
        report(world, profession, triggerContext, 0, resultSupplier);
    }

    public static void report(ServerWorld world, String profession, Supplier<String> resultSupplier) {
        report(world, profession, null, 0, resultSupplier);
    }

    public static void report(ServerWorld world, String profession, String triggerSource, int intervalTicks, Supplier<String> resultSupplier) {
        if (!isEnabled()) {
            return;
        }

        WorldDiagnostics diagnostics = DIAGNOSTICS_BY_WORLD.computeIfAbsent(world.getRegistryKey().getValue(), ignored -> new WorldDiagnostics());
        String result = resultSupplier.get();
        String logResult = (triggerSource == null || triggerSource.isBlank())
                ? result
                : "[" + formatTrigger(triggerSource, intervalTicks) + "] " + result;
        diagnostics.entries.put(profession, logResult);
        flushIfDue(world, diagnostics);
    }

    private static void flushIfDue(ServerWorld world, WorldDiagnostics diagnostics) {
        long tick = world.getTime();
        if (diagnostics.lastFlushTick < 0L) {
            diagnostics.lastFlushTick = tick;
            return;
        }

        if (tick - diagnostics.lastFlushTick < getFlushIntervalTicks()) {
            return;
        }

        flush(world, diagnostics, tick);
    }

    private static void flush(ServerWorld world, WorldDiagnostics diagnostics, long tick) {
        if (diagnostics.entries.isEmpty()) {
            diagnostics.lastFlushTick = tick;
            return;
        }

        StringBuilder builder = new StringBuilder("Crafting villagers material/crafting check")
                .append(" [world=")
                .append(world.getRegistryKey().getValue())
                .append(", tick=")
                .append(tick)
                .append("]:");
        for (Map.Entry<String, String> entry : diagnostics.entries.entrySet()) {
            builder.append("\n     ").append(entry.getKey()).append(" - ").append(entry.getValue());
        }
        LOGGER.debug(builder.toString());

        diagnostics.entries.clear();
        diagnostics.lastFlushTick = tick;
    }

    private static int getFlushIntervalTicks() {
        return Math.max(MIN_DIAGNOSTIC_INTERVAL_TICKS,
                Math.min(MAX_DIAGNOSTIC_INTERVAL_TICKS, GuardVillagersConfig.craftingDiagnosticsIntervalTicks));
    }

    private static String formatTrigger(String triggerSource, int intervalTicks) {
        String normalized = triggerSource.toLowerCase(Locale.ROOT).replace('_', ' ');
        if (intervalTicks > 0) {
            return normalized + " (" + intervalTicks + " ticks)";
        }
        return normalized + " (immediate)";
    }

    private static final class WorldDiagnostics {
        private long lastFlushTick = -1L;
        private final Map<String, String> entries = new LinkedHashMap<>();
    }
}
