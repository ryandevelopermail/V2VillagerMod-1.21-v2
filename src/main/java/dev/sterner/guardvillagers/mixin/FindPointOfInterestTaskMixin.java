package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.util.ConvertedWorkerJobSiteReservationManager;
import dev.sterner.guardvillagers.common.util.PotentialJobSiteSelectionContext;
import dev.sterner.guardvillagers.common.util.TakeJobSiteInjectDiagnostics;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.ai.brain.MemoryQueryResult;
import net.minecraft.entity.ai.brain.task.FindPointOfInterestTask;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;
import java.util.function.Predicate;

@Mixin(FindPointOfInterestTask.class)
public class FindPointOfInterestTaskMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger(FindPointOfInterestTaskMixin.class);

    @Redirect(
            method = "method_46880",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/function/Predicate;test(Ljava/lang/Object;)Z"
            ),
            require = 0
    )
    private static boolean guardvillagers$filterReservedPotentialSites$method46880(
            Predicate<BlockPos> originalPredicate,
            Object candidatePos,
            PointOfInterestStorage poiStorage,
            Predicate<BlockPos> poiPosPredicate,
            BlockPos pos,
            MemoryQueryResult<?, ?> queryResult,
            ServerWorld world,
            Optional<Byte> entityStatus,
            PathAwareEntity entity,
            it.unimi.dsi.fastutil.longs.Long2ObjectMap<?> retryMarkers,
            RegistryEntry<PointOfInterestType> poiType
    ) {
        return guardvillagers$filterReservedPotentialSites(originalPredicate, candidatePos, poiPosPredicate, queryResult, entityStatus, world, entity, poiType);
    }

    @Redirect(
            method = "method_46881",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/function/Predicate;test(Ljava/lang/Object;)Z"
            ),
            require = 0
    )
    private static boolean guardvillagers$filterReservedPotentialSites$method46881(
            Predicate<BlockPos> originalPredicate,
            Object candidatePos,
            PointOfInterestStorage poiStorage,
            Predicate<BlockPos> poiPosPredicate,
            BlockPos pos,
            MemoryQueryResult<?, ?> queryResult,
            ServerWorld world,
            Optional<Byte> entityStatus,
            PathAwareEntity entity,
            it.unimi.dsi.fastutil.longs.Long2ObjectMap<?> retryMarkers,
            RegistryEntry<PointOfInterestType> poiType
    ) {
        return guardvillagers$filterReservedPotentialSites(originalPredicate, candidatePos, poiPosPredicate, queryResult, entityStatus, world, entity, poiType);
    }

    private static boolean guardvillagers$filterReservedPotentialSites(
            Predicate<BlockPos> originalPredicate,
            Object candidatePos,
            Predicate<BlockPos> poiPosPredicate,
            MemoryQueryResult<?, ?> queryResult,
            Optional<Byte> entityStatus,
            ServerWorld world,
            PathAwareEntity entity,
            RegistryEntry<PointOfInterestType> poiType
    ) {
        if (!(candidatePos instanceof BlockPos blockPos)) {
            return ((Predicate<Object>) (Predicate<?>) originalPredicate).test(candidatePos);
        }

        if (!PotentialJobSiteSelectionContext.shouldApplyReservationFilter(
                originalPredicate,
                poiPosPredicate,
                queryResult,
                entityStatus,
                LOGGER)) {
            return originalPredicate.test(blockPos);
        }

        TakeJobSiteInjectDiagnostics.markPotentialJobSiteHookObserved();

        if (ConvertedWorkerJobSiteReservationManager.isReservedForAnyConvertedWorker(world, blockPos)) {
            LOGGER.debug("potential job site rejected (reserved): entity={} jobSite={} poiType={}",
                    entity.getUuidAsString(),
                    blockPos.toShortString(),
                    String.valueOf(poiType));
            return false;
        }

        return originalPredicate.test(blockPos);
    }
}
