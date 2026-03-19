package dev.sterner.guardvillagers.common.util;

import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TakeJobSiteInjectDiagnostics {
    private static final Logger LOGGER = LoggerFactory.getLogger(TakeJobSiteInjectDiagnostics.class);
    private static boolean potentialJobSiteHookObserved;
    private static boolean claimJobSiteHookObserved;
    private static boolean missingInjectWarningLogged;

    private TakeJobSiteInjectDiagnostics() {
    }

    public static void markPotentialJobSiteHookObserved() {
        potentialJobSiteHookObserved = true;
    }

    public static void markClaimJobSiteHookObserved() {
        claimJobSiteHookObserved = true;
    }

    public static void warnIfInjectMissing(Iterable<ServerWorld> worlds) {
        if (missingInjectWarningLogged || (potentialJobSiteHookObserved && claimJobSiteHookObserved)) {
            return;
        }

        // Only check once the server has been running for at least 200 ticks.
        // Use world time directly — avoid getWorldBounds() (O(all entities) world-border scan) just for a diagnostic.
        // If neither hook has fired by tick 200, warn unconditionally; the hooks fire on any villager job-site
        // interaction and we don't need a villager count to log a meaningful warning.
        long maxWorldTime = 0L;
        for (ServerWorld world : worlds) {
            maxWorldTime = Math.max(maxWorldTime, world.getTime());
        }

        if (maxWorldTime >= 200L) {
            missingInjectWarningLogged = true;
            LOGGER.warn("Reserved job-site hooks observed? potential-site={}, claim-site={}. " +
                            "Expected both hooks to run; missing hook(s) may indicate signature drift.",
                    potentialJobSiteHookObserved,
                    claimJobSiteHookObserved);
        }
    }
}
