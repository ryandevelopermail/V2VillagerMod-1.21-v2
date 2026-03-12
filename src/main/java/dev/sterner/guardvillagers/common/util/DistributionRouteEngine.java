package dev.sterner.guardvillagers.common.util;

import net.minecraft.block.Block;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class DistributionRouteEngine {
    private DistributionRouteEngine() {
    }

    public static List<DistributionRecipientHelper.RecipientRecord> findEligibleRecipients(ServerWorld world,
                                                                                           net.minecraft.entity.Entity source,
                                                                                           double scanRange,
                                                                                           List<ProfessionRoute> routes) {
        List<DistributionRecipientHelper.RecipientRecord> candidates = new ArrayList<>();
        for (ProfessionRoute route : routes) {
            candidates.addAll(DistributionRecipientHelper.findEligibleRecipients(
                    world,
                    source,
                    scanRange,
                    route.profession(),
                    route.expectedJobBlock(),
                    recipient -> isStrictlyPaired(world, recipient, route.requiresCraftingTable())));
        }

        return candidates.stream().distinct().sorted(Comparator
                .comparingDouble(DistributionRecipientHelper.RecipientRecord::sourceSquaredDistance)
                .thenComparing(record -> record.recipient().getUuid(), java.util.UUID::compareTo)).toList();
    }

    private static boolean isStrictlyPaired(ServerWorld world,
                                            DistributionRecipientHelper.RecipientRecord recipient,
                                            boolean requiresCraftingTable) {
        if (JobBlockPairingHelper.findNearbyChest(world, recipient.jobPos())
                .map(pos -> pos.equals(recipient.chestPos()))
                .orElse(false)) {
            if (!requiresCraftingTable) {
                return true;
            }
            return JobBlockPairingHelper.isCraftingTable(world.getBlockState(recipient.jobPos()));
        }
        return false;
    }

    public record ProfessionRoute(net.minecraft.village.VillagerProfession profession,
                                  Block expectedJobBlock,
                                  boolean requiresCraftingTable,
                                  int targetStockCap,
                                  double demandWeight) {
        public ProfessionRoute(net.minecraft.village.VillagerProfession profession,
                               Block expectedJobBlock,
                               boolean requiresCraftingTable) {
            this(profession, expectedJobBlock, requiresCraftingTable, -1, 1.0D);
        }
    }
}
