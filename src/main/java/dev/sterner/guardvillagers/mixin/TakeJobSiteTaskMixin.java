package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.util.ConvertedWorkerJobSiteReservationManager;
import dev.sterner.guardvillagers.common.util.TakeJobSiteInjectDiagnostics;
import net.minecraft.entity.ai.brain.task.TakeJobSiteTask;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.poi.PointOfInterestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TakeJobSiteTask.class)
public class TakeJobSiteTaskMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger(TakeJobSiteTaskMixin.class);
    @Inject(
            method = "canUseJobSite(Lnet/minecraft/registry/entry/RegistryEntry;Lnet/minecraft/entity/passive/VillagerEntity;Lnet/minecraft/util/math/BlockPos;)Z",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private static void guardvillagers$rejectReservedConvertedWorkerJobSite(RegistryEntry<PointOfInterestType> poiType, VillagerEntity villager, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        TakeJobSiteInjectDiagnostics.markCanUseJobSiteInjectObserved();

        if (!(villager.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        if (ConvertedWorkerJobSiteReservationManager.isReservedForAnyConvertedWorker(serverWorld, pos)) {
            LOGGER.debug("claim rejected (reserved): villager={} jobSite={} poiType={}",
                    villager.getUuidAsString(),
                    pos.toShortString(),
                    String.valueOf(poiType));
            cir.setReturnValue(false);
        }
    }

    @Inject(
            method = "run(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/entity/passive/VillagerEntity;J)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void guardvillagers$rejectReservedConvertedWorkerJobSiteAtClaim(ServerWorld world, VillagerEntity villager, long time, CallbackInfo cir) {
        villager.getBrain().getOptionalMemory(MemoryModuleType.POTENTIAL_JOB_SITE)
                .filter(globalPos -> globalPos.dimension() == world.getRegistryKey())
                .map(GlobalPos::pos)
                .filter(jobSite -> ConvertedWorkerJobSiteReservationManager.isReservedForAnyConvertedWorker(world, jobSite))
                .ifPresent(jobSite -> {
                    LOGGER.debug("claim rejected (reserved): villager={} jobSite={} phase=run",
                            villager.getUuidAsString(),
                            jobSite.toShortString());
                    villager.getBrain().forget(MemoryModuleType.POTENTIAL_JOB_SITE);
                    cir.cancel();
                });
    }


}
