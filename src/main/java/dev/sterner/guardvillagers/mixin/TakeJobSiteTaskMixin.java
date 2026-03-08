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

    @Inject(method = "canUseJobSite", at = @At("HEAD"), cancellable = true)
    private void guardvillagers$rejectReservedConvertedWorkerJobSite(RegistryEntry<PointOfInterestType> poiType, VillagerEntity villager, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
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
}
