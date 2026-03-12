package dev.sterner.guardvillagers.common.util;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

public final class LumberjackDemandPlanner {
    public static final double RECIPIENT_SCAN_RANGE = 24.0D;

    private LumberjackDemandPlanner() {
    }

    public static DemandSnapshot buildSnapshot(ServerWorld world, Entity source, Inventory sourceInventory) {
        EnumMap<MaterialType, MaterialDemand> demandByType = new EnumMap<>(MaterialType.class);
        for (MaterialType materialType : MaterialType.values()) {
            List<DistributionRecipientHelper.RecipientRecord> recipients = findRecipients(world, source, materialType);
            List<RecipientDemand> rankedRecipients = rankRecipients(world, recipients, materialType);
            int sourceStock = countMaterialInInventory(sourceInventory, materialType);
            int demandDeficit = rankedRecipients.stream().mapToInt(RecipientDemand::deficit).sum();
            demandByType.put(materialType, new MaterialDemand(materialType, sourceStock, recipients.size(), demandDeficit, rankedRecipients));
        }
        return new DemandSnapshot(demandByType);
    }

    private static List<DistributionRecipientHelper.RecipientRecord> findRecipients(ServerWorld world, Entity source, MaterialType materialType) {
        List<DistributionRecipientHelper.RecipientRecord> candidates = new ArrayList<>();
        for (RecipientTarget target : materialType.recipientTargets()) {
            candidates.addAll(DistributionRecipientHelper.findEligibleRecipients(
                    world,
                    source,
                    RECIPIENT_SCAN_RANGE,
                    target.profession(),
                    target.expectedJobBlock(),
                    recipient -> isStrictlyPaired(world, recipient, target.requiresCraftingTable())));
        }

        return candidates.stream().distinct().sorted(Comparator
                .comparingDouble(DistributionRecipientHelper.RecipientRecord::sourceSquaredDistance)
                .thenComparing(record -> record.recipient().getUuid(), java.util.UUID::compareTo)).toList();
    }

    private static List<RecipientDemand> rankRecipients(ServerWorld world,
                                                        List<DistributionRecipientHelper.RecipientRecord> recipients,
                                                        MaterialType materialType) {
        List<RecipientDemand> ranked = new ArrayList<>();
        for (DistributionRecipientHelper.RecipientRecord recipient : recipients) {
            Optional<Inventory> inventory = getChestInventory(world, recipient.chestPos());
            if (inventory.isEmpty()) {
                continue;
            }

            int stock = countMaterialInInventory(inventory.get(), materialType);
            int targetStock = recipientTargetStock(materialType, recipient.record().recipient().getVillagerData().getProfession());
            int deficit = Math.max(0, targetStock - stock);
            if (deficit <= 0) {
                continue;
            }

            double chestFullness = getInventoryFullness(inventory.get());
            ranked.add(new RecipientDemand(recipient, stock, deficit, chestFullness));
        }

        ranked.sort(Comparator
                .comparingInt(RecipientDemand::deficit).reversed()
                .thenComparingDouble(RecipientDemand::chestFullness)
                .thenComparingDouble(recipient -> recipient.record().sourceSquaredDistance())
                .thenComparing(recipient -> recipient.record().recipient().getUuid(), java.util.UUID::compareTo));
        return List.copyOf(ranked);
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

    private static int recipientTargetStock(MaterialType materialType, VillagerProfession profession) {
        for (RecipientTarget target : materialType.recipientTargets()) {
            if (target.profession() == profession) {
                return target.targetStockCap() > 0 ? target.targetStockCap() : materialType.targetStock();
            }
        }
        return materialType.targetStock();
    }

    private static Optional<Inventory> getChestInventory(ServerWorld world, BlockPos chestPos) {
        if (!(world.getBlockState(chestPos).getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, world.getBlockState(chestPos), world, chestPos, true));
    }

    private static int countMaterialInInventory(Inventory inventory, MaterialType materialType) {
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (materialType.matches(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static double getInventoryFullness(Inventory inventory) {
        long maxCapacity = 0L;
        long usedCapacity = 0L;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            int slotLimit = Math.min(inventory.getMaxCountPerStack(), stack.isEmpty() ? 64 : stack.getMaxCount());
            maxCapacity += slotLimit;
            if (!stack.isEmpty()) {
                usedCapacity += Math.min(stack.getCount(), slotLimit);
            }
        }
        return maxCapacity > 0L ? (double) usedCapacity / (double) maxCapacity : 0.0D;
    }

    public enum MaterialType {
        CHARCOAL("charcoal", 16, new RecipientTarget[]{
                new RecipientTarget(VillagerProfession.BUTCHER, Blocks.SMOKER, false),
                new RecipientTarget(VillagerProfession.ARMORER, Blocks.BLAST_FURNACE, false),
                new RecipientTarget(VillagerProfession.TOOLSMITH, Blocks.SMITHING_TABLE, false),
                new RecipientTarget(VillagerProfession.WEAPONSMITH, Blocks.GRINDSTONE, false)
        }) {
            @Override
            public boolean matches(ItemStack stack) {
                return stack.isOf(Items.CHARCOAL);
            }
        },
        STICK("stick", 24, new RecipientTarget[]{
                new RecipientTarget(VillagerProfession.FARMER, Blocks.COMPOSTER, false, 24),
                new RecipientTarget(VillagerProfession.SHEPHERD, Blocks.LOOM, false, 24),
                new RecipientTarget(VillagerProfession.FLETCHER, Blocks.FLETCHING_TABLE, false, 24),
                new RecipientTarget(VillagerProfession.LEATHERWORKER, Blocks.CAULDRON, false, 16),
                new RecipientTarget(VillagerProfession.TOOLSMITH, Blocks.SMITHING_TABLE, false, 32),
                new RecipientTarget(VillagerProfession.WEAPONSMITH, Blocks.GRINDSTONE, false, 32),
                new RecipientTarget(VillagerProfession.FISHERMAN, Blocks.BARREL, false, 20)
        }) {
            @Override
            public boolean matches(ItemStack stack) {
                return stack.isOf(Items.STICK);
            }
        },
        PLANKS("planks", 32, new RecipientTarget[]{
                new RecipientTarget(VillagerProfession.TOOLSMITH, Blocks.SMITHING_TABLE, false)
        }) {
            @Override
            public boolean matches(ItemStack stack) {
                return stack.isIn(ItemTags.PLANKS);
            }
        },
        LOGS("logs", 32, new RecipientTarget[]{
                new RecipientTarget(VillagerProfession.BUTCHER, Blocks.SMOKER, false)
        }) {
            @Override
            public boolean matches(ItemStack stack) {
                return stack.isIn(ItemTags.LOGS);
            }
        };

        private final String label;
        private final int targetStock;
        private final RecipientTarget[] recipientTargets;

        MaterialType(String label, int targetStock, RecipientTarget[] recipientTargets) {
            this.label = label;
            this.targetStock = targetStock;
            this.recipientTargets = recipientTargets;
        }

        public static MaterialType fromStack(ItemStack stack) {
            if (stack.isEmpty()) {
                return null;
            }
            for (MaterialType type : values()) {
                if (type.matches(stack)) {
                    return type;
                }
            }
            return null;
        }

        public abstract boolean matches(ItemStack stack);

        public String label() {
            return label;
        }

        public int targetStock() {
            return targetStock;
        }

        public RecipientTarget[] recipientTargets() {
            return recipientTargets;
        }
    }

    public record RecipientTarget(VillagerProfession profession, Block expectedJobBlock, boolean requiresCraftingTable,
                                  int targetStockCap) {
        public RecipientTarget(VillagerProfession profession, Block expectedJobBlock, boolean requiresCraftingTable) {
            this(profession, expectedJobBlock, requiresCraftingTable, -1);
        }
    }

    public record RecipientDemand(DistributionRecipientHelper.RecipientRecord record, int currentStock, int deficit,
                                  double chestFullness) {
    }

    public record MaterialDemand(MaterialType materialType, int sourceStock, int recipientCount, int demandDeficit,
                                 List<RecipientDemand> rankedRecipients) {
    }

    public record DemandSnapshot(EnumMap<MaterialType, MaterialDemand> demandByType) {
        public MaterialDemand demandFor(MaterialType materialType) {
            return demandByType.get(materialType);
        }

        public int deficitFor(MaterialType materialType) {
            MaterialDemand demand = demandFor(materialType);
            return demand == null ? 0 : demand.demandDeficit();
        }

        public List<RecipientDemand> rankedRecipientsFor(MaterialType materialType) {
            MaterialDemand demand = demandFor(materialType);
            return demand == null ? List.of() : demand.rankedRecipients();
        }
    }
}
