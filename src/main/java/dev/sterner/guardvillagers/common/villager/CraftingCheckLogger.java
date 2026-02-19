package dev.sterner.guardvillagers.common.villager;

import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CraftingCheckLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger(CraftingCheckLogger.class);
    public static final int MATERIAL_CHECK_INTERVAL_TICKS = 1200;
    private static long lastTick = -1L;
    private static final Map<String, String> ENTRIES = new LinkedHashMap<>();

    private CraftingCheckLogger() {
    }

    public static void report(ServerWorld world, String profession, String result) {
        report(world, profession, null, result);
    }

    public static void report(ServerWorld world, String profession, String triggerContext, String result) {
        long tick = world.getTime();
        if (tick != lastTick) {
            clear();
            lastTick = tick;
        }

        String logResult = (triggerContext == null || triggerContext.isBlank())
                ? result
                : "[" + triggerContext + "] " + result;
        ENTRIES.put(profession, logResult);
        logCurrent(tick);
    }

    private static void clear() {
        if (ENTRIES.isEmpty()) {
            return;
        }
        ENTRIES.clear();
    }

    private static void logCurrent(long tick) {
        if (ENTRIES.isEmpty()) {
            return;
        }

        StringBuilder builder = new StringBuilder("Crafting villagers material/crafting check:");
        for (Map.Entry<String, String> entry : ENTRIES.entrySet()) {
            builder.append("\n     ").append(entry.getKey()).append(" - ").append(entry.getValue());
        }
        LOGGER.info(builder.toString());
    }
}
