package dev.sterner.guardvillagers.common.util;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TakeJobSiteInjectDiagnostics {
    private static final Logger LOGGER = LoggerFactory.getLogger(TakeJobSiteInjectDiagnostics.class);
    private static boolean canUseJobSiteInjectObserved;
    private static boolean missingInjectWarningLogged;

    private TakeJobSiteInjectDiagnostics() {
    }

    public static void markCanUseJobSiteInjectObserved() {
        canUseJobSiteInjectObserved = true;
    }

    public static void warnIfInjectMissing(Iterable<ServerWorld> worlds) {
        if (canUseJobSiteInjectObserved || missingInjectWarningLogged) {
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
            LOGGER.warn("TakeJobSiteTask pre-claim inject has not executed; signature may have shifted. " +
                    "Fallback reserved-POI cleanup in VillagerEntityMixin remains active.");
        }
    }
}
