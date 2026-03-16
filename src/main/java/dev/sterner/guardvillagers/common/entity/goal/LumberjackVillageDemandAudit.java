package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.util.LumberjackDemandPlanner;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class LumberjackVillageDemandAudit {
    private LumberjackVillageDemandAudit() {
    }

    public static AuditSnapshot run(ServerWorld world, LumberjackGuardEntity guard) {
        int v1MissingChest = LumberjackChestTriggerController.countEligibleV1VillagersMissingPairedChest(world, guard);
        int v2MissingCraftingTable = LumberjackChestTriggerController.countEligibleV2VillagersMissingCraftingTable(world, guard);
        Set<VillagerProfession> toolDemandProfessions = LumberjackDemandPlanner.resolveToolMaterialDemandProfessions(world);

        LumberjackDemandPlanner.DemandSnapshot demandSnapshot =
                LumberjackDemandPlanner.buildSnapshot(world, guard, new SimpleInventory(0));

        Map<UUID, RecipientToolMaterialDeficit> byRecipient = new HashMap<>();
        for (LumberjackDemandPlanner.MaterialType materialType : EnumSet.of(LumberjackDemandPlanner.MaterialType.STICK,
                LumberjackDemandPlanner.MaterialType.PLANKS)) {
            for (LumberjackDemandPlanner.RecipientDemand recipientDemand : demandSnapshot.rankedRecipientsFor(materialType)) {
                if (!LumberjackChestTriggerController.isEligibleV2Recipient(world, recipientDemand.record().recipient())) {
                    continue;
                }

                VillagerProfession profession = recipientDemand.record().recipient().getVillagerData().getProfession();
                if (!toolDemandProfessions.contains(profession)) {
                    continue;
                }

                UUID recipientId = recipientDemand.record().recipient().getUuid();
                RecipientToolMaterialDeficit existing = byRecipient.get(recipientId);
                int stickDeficit = existing == null ? 0 : existing.stickDeficit();
                int plankDeficit = existing == null ? 0 : existing.plankDeficit();
                double weightedDeficit = existing == null ? 0.0D : existing.weightedDeficit();

                if (materialType == LumberjackDemandPlanner.MaterialType.STICK) {
                    stickDeficit += recipientDemand.deficit();
                } else if (materialType == LumberjackDemandPlanner.MaterialType.PLANKS) {
                    plankDeficit += recipientDemand.deficit();
                }
                weightedDeficit += recipientDemand.weightedDeficit();

                byRecipient.put(recipientId, new RecipientToolMaterialDeficit(
                        recipientId,
                        profession,
                        stickDeficit,
                        plankDeficit,
                        weightedDeficit));
            }
        }

        List<RecipientToolMaterialDeficit> prioritizedRecipients = new ArrayList<>(byRecipient.values());
        prioritizedRecipients.sort(Comparator
                .comparingDouble(RecipientToolMaterialDeficit::weightedDeficit).reversed()
                .thenComparing(Comparator.comparingInt(RecipientToolMaterialDeficit::totalDeficit).reversed())
                .thenComparing(RecipientToolMaterialDeficit::recipientId, UUID::compareTo));

        return new AuditSnapshot(v1MissingChest, v2MissingCraftingTable, prioritizedRecipients.size(),
                List.copyOf(prioritizedRecipients));
    }

    public record AuditSnapshot(int eligibleV1VillagersMissingChest,
                                int eligibleV2VillagersMissingCraftingTable,
                                int eligibleV2ProfessionalsUnderToolMaterialThreshold,
                                List<RecipientToolMaterialDeficit> prioritizedRecipients) {
        public Map<UUID, Integer> prioritizedRecipientRanks() {
            Map<UUID, Integer> rankByRecipient = new HashMap<>();
            for (int i = 0; i < prioritizedRecipients.size(); i++) {
                rankByRecipient.put(prioritizedRecipients.get(i).recipientId(), i);
            }
            return rankByRecipient;
        }
    }

    public record RecipientToolMaterialDeficit(UUID recipientId,
                                               VillagerProfession profession,
                                               int stickDeficit,
                                               int plankDeficit,
                                               double weightedDeficit) {
        public int totalDeficit() {
            return stickDeficit + plankDeficit;
        }
    }
}
