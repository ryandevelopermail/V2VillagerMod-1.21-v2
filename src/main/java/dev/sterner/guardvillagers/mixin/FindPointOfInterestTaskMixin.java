package dev.sterner.guardvillagers.mixin;

import com.mojang.datafixers.util.Pair;
import dev.sterner.guardvillagers.common.util.ConvertedWorkerJobSiteReservationManager;
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
import java.util.stream.Stream;

@Mixin(FindPointOfInterestTask.class)
public class FindPointOfInterestTaskMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger(FindPointOfInterestTaskMixin.class);

    @Redirect(
            method = "method_46880",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/poi/PointOfInterestStorage;getSortedTypesAndPositions(Ljava/util/function/Predicate;Ljava/util/function/Predicate;Lnet/minecraft/util/math/BlockPos;ILnet/minecraft/world/poi/PointOfInterestStorage$OccupationStatus;)Ljava/util/stream/Stream;"
            ),
            require = 0
    )
    private static Stream<Pair<RegistryEntry<PointOfInterestType>, BlockPos>> guardvillagers$filterReservedPotentialSites$method46880(
            PointOfInterestStorage poiStorage,
            Predicate<RegistryEntry<PointOfInterestType>> typePredicate,
            Predicate<BlockPos> poiPosPredicate,
            BlockPos pos,
            int radius,
            PointOfInterestStorage.OccupationStatus occupationStatus,
            PointOfInterestStorage ignoredPoiStorage,
            Predicate<BlockPos> ignoredPoiPosPredicate,
            BlockPos ignoredPos,
            MemoryQueryResult<?, ?> ignoredQueryResult,
            ServerWorld world,
            Optional<Byte> ignoredEntityStatus,
            PathAwareEntity entity,
            it.unimi.dsi.fastutil.longs.Long2ObjectMap<?> ignoredRetryMarkers,
            RegistryEntry<PointOfInterestType> poiType
    ) {
        return poiStorage.getSortedTypesAndPositions(
                typePredicate,
                guardvillagers$withReservationFilter(poiPosPredicate, world, entity, poiType),
                pos,
                radius,
                occupationStatus
        );
    }

    @Redirect(
            method = "method_46881",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/poi/PointOfInterestStorage;getSortedTypesAndPositions(Ljava/util/function/Predicate;Ljava/util/function/Predicate;Lnet/minecraft/util/math/BlockPos;ILnet/minecraft/world/poi/PointOfInterestStorage$OccupationStatus;)Ljava/util/stream/Stream;"
            ),
            require = 0
    )
    private static Stream<Pair<RegistryEntry<PointOfInterestType>, BlockPos>> guardvillagers$filterReservedPotentialSites$method46881(
            PointOfInterestStorage poiStorage,
            Predicate<RegistryEntry<PointOfInterestType>> typePredicate,
            Predicate<BlockPos> poiPosPredicate,
            BlockPos pos,
            int radius,
            PointOfInterestStorage.OccupationStatus occupationStatus,
            PointOfInterestStorage ignoredPoiStorage,
            Predicate<BlockPos> ignoredPoiPosPredicate,
            BlockPos ignoredPos,
            MemoryQueryResult<?, ?> ignoredQueryResult,
            ServerWorld world,
            Optional<Byte> ignoredEntityStatus,
            PathAwareEntity entity,
            it.unimi.dsi.fastutil.longs.Long2ObjectMap<?> ignoredRetryMarkers,
            RegistryEntry<PointOfInterestType> poiType
    ) {
        return poiStorage.getSortedTypesAndPositions(
                typePredicate,
                guardvillagers$withReservationFilter(poiPosPredicate, world, entity, poiType),
                pos,
                radius,
                occupationStatus
        );
    }

    private static Predicate<BlockPos> guardvillagers$withReservationFilter(
            Predicate<BlockPos> originalPredicate,
            ServerWorld world,
            PathAwareEntity entity,
            RegistryEntry<PointOfInterestType> poiType
    ) {
        return blockPos -> {
            TakeJobSiteInjectDiagnostics.markPotentialJobSiteHookObserved();

            if (ConvertedWorkerJobSiteReservationManager.isReservedForAnyConvertedWorker(world, blockPos)) {
                LOGGER.debug("potential job site rejected (reserved): entity={} jobSite={} poiType={}",
                        entity.getUuidAsString(),
                        blockPos.toShortString(),
                        String.valueOf(poiType));
                return false;
            }

            return originalPredicate.test(blockPos);
        };
    }
}
