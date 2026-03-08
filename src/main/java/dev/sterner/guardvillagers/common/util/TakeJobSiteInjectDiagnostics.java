package dev.sterner.guardvillagers.common.util;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TakeJobSiteInjectDiagnostics {
    private static final Logger LOGGER = LoggerFactory.getLogger(TakeJobSiteInjectDiagnostics.class);
    private static boolean potentialJobSiteHookObserved;
    private static boolean takeJobSiteHookObserved;
    private static boolean missingInjectWarningLogged;

    private TakeJobSiteInjectDiagnostics() {
    }

    public static void markPotentialJobSiteHookObserved() {
        potentialJobSiteHookObserved = true;
    }

    public static void markCanUseJobSiteInjectObserved() {
        takeJobSiteHookObserved = true;
    }

    public static void warnIfInjectMissing(Iterable<ServerWorld> worlds) {
        if (missingInjectWarningLogged || (potentialJobSiteHookObserved && takeJobSiteHookObserved)) {
            return;
        }

        int villagerCount = 0;
        long maxWorldTime = 0L;
        for (ServerWorld world : worlds) {
            maxWorldTime = Math.max(maxWorldTime, world.getTime());
            villagerCount += world.getEntitiesByClass(VillagerEntity.class, JobBlockPairingHelper.getWorldBounds(world), villager -> true).size();
        }

        if (villagerCount > 0 && maxWorldTime >= 200L) {
            missingInjectWarningLogged = true;
            LOGGER.warn("Reserved job-site hooks observed? potential-site={}, take-site={}. " +
                            "Expected both hooks to run; missing hook(s) may indicate signature drift.",
                    potentialJobSiteHookObserved,
                    takeJobSiteHookObserved);
        }
    }
}
