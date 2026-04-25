package dev.sterner.guardvillagers.common.villager.behavior;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.util.ShepherdPenStateHolder;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

public final class ShepherdPenLifecycle {
    private ShepherdPenLifecycle() {
    }

    public static boolean shouldBlockPenConstruction(VillagerEntity villager, ServerWorld world, Logger logger, String goalName) {
        if (!hasConstructedPen(villager)) {
            logger.debug("{} {}: building first pen", goalName, villager.getUuidAsString());
            return false;
        }

        if (GuardVillagersConfig.shepherdAllowPenRebuildIfMissing && isOwnedPenMissing(villager, world)) {
            clearConstructedPen(villager);
            logger.info("{} {}: owned pen anchor missing and rebuild is enabled — building first pen", goalName, villager.getUuidAsString());
            return false;
        }

        logger.debug("{} {}: blocked because own pen already built", goalName, villager.getUuidAsString());
        return true;
    }

    public static void markPenConstructed(VillagerEntity villager, @Nullable BlockPos ownedPenAnchor) {
        if (!(villager instanceof ShepherdPenStateHolder holder)) {
            return;
        }
        holder.guardvillagers$setHasConstructedPen(true);
        holder.guardvillagers$setOwnedPenAnchor(ownedPenAnchor == null ? null : ownedPenAnchor.toImmutable());
    }

    public static void clearConstructedPen(VillagerEntity villager) {
        if (!(villager instanceof ShepherdPenStateHolder holder)) {
            return;
        }
        holder.guardvillagers$setHasConstructedPen(false);
        holder.guardvillagers$setOwnedPenAnchor(null);
    }

    public static boolean hasConstructedPen(VillagerEntity villager) {
        return villager instanceof ShepherdPenStateHolder holder && holder.guardvillagers$hasConstructedPen();
    }

    @Nullable
    public static BlockPos getOwnedPenAnchor(VillagerEntity villager) {
        return villager instanceof ShepherdPenStateHolder holder ? holder.guardvillagers$getOwnedPenAnchor() : null;
    }

    static boolean isOwnedPenMissing(VillagerEntity villager, ServerWorld world) {
        BlockPos anchor = getOwnedPenAnchor(villager);
        if (anchor == null) {
            return true;
        }
        return !(world.getBlockState(anchor).getBlock() instanceof FenceGateBlock);
    }
}
