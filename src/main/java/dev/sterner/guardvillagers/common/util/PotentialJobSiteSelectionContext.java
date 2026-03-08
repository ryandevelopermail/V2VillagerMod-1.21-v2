package dev.sterner.guardvillagers.common.util;

import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.MemoryQueryResult;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.function.Predicate;

public final class PotentialJobSiteSelectionContext {
    private static final String MEMORY_FIELD_NAME = "memory";
    private static boolean reflectionFailureLogged;
    private static boolean fallbackUsageLogged;

    private PotentialJobSiteSelectionContext() {
    }

    public static boolean shouldApplyReservationFilter(
            Predicate<?> invokedPredicate,
            Predicate<?> taskPredicate,
            MemoryQueryResult<?, ?> queryResult,
            Optional<Byte> entityStatus,
            Logger logger
    ) {
        if (invokedPredicate == taskPredicate && entityStatus.isPresent()) {
            return true;
        }

        boolean memoryTypeMatch = isPotentialJobSiteMemory(queryResult, logger);
        if (memoryTypeMatch && !fallbackUsageLogged) {
            fallbackUsageLogged = true;
            logger.warn("Potential-job-site reservation filtering used memory-type fallback due to unexpected POI call context. This may indicate upstream method changes.");
        }

        return memoryTypeMatch;
    }

    private static boolean isPotentialJobSiteMemory(MemoryQueryResult<?, ?> queryResult, Logger logger) {
        try {
            Field memoryField = MemoryQueryResult.class.getDeclaredField(MEMORY_FIELD_NAME);
            memoryField.setAccessible(true);
            Object value = memoryField.get(queryResult);
            return value == MemoryModuleType.POTENTIAL_JOB_SITE;
        } catch (ReflectiveOperationException exception) {
            if (!reflectionFailureLogged) {
                reflectionFailureLogged = true;
                logger.warn("Unable to resolve MemoryQueryResult memory type via reflection; potential-job-site fallback disabled.", exception);
            }
            return false;
        }
    }
}
