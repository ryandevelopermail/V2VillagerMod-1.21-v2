package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.util.ConvertedWorkerJobSiteReservationManager;
import net.minecraft.entity.ai.brain.task.TakeJobSiteTask;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.poi.PointOfInterestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TakeJobSiteTask.class)
public class TakeJobSiteTaskMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger(TakeJobSiteTaskMixin.class);
    private static boolean guardvillagers$canUseJobSiteInjectObserved;
    private static boolean guardvillagers$missingInjectWarningLogged;

    @Inject(
            method = "canUseJobSite(Lnet/minecraft/registry/entry/RegistryEntry;Lnet/minecraft/entity/passive/VillagerEntity;Lnet/minecraft/util/math/BlockPos;)Z",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private static void guardvillagers$rejectReservedConvertedWorkerJobSite(RegistryEntry<PointOfInterestType> poiType, VillagerEntity villager, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        guardvillagers$canUseJobSiteInjectObserved = true;

        if (!(villager.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        if (ConvertedWorkerJobSiteReservationManager.isReservedForAnyConvertedWorker(serverWorld, pos)) {
            LOGGER.debug("pre-claim reservation reject: villager={} jobSite={} poiType={}",
                    villager.getUuidAsString(),
                    pos.toShortString(),
                    String.valueOf(poiType));
            cir.setReturnValue(false);
        }
    }

    public static void guardvillagers$warnIfInjectMissing(Iterable<ServerWorld> worlds) {
        if (guardvillagers$canUseJobSiteInjectObserved || guardvillagers$missingInjectWarningLogged) {
            return;
        }

        int villagerCount = 0;
        long maxWorldTime = 0L;
        for (ServerWorld world : worlds) {
            maxWorldTime = Math.max(maxWorldTime, world.getTime());
            villagerCount += world.getEntitiesByClass(VillagerEntity.class, world.getWorldBorder().getBound(), villager -> true).size();
        }

        if (villagerCount > 0 && maxWorldTime >= 200L) {
            guardvillagers$missingInjectWarningLogged = true;
            LOGGER.warn("TakeJobSiteTask pre-claim inject has not executed; signature may have shifted. " +
                    "Fallback reserved-POI cleanup in VillagerEntityMixin remains active.");
        }
    }
}
