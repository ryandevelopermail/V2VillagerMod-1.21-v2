package dev.sterner.guardvillagers.common.util;

import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LumberjackDemandPlanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(LumberjackDemandPlanner.class);
    public static final double RECIPIENT_SCAN_RANGE = 24.0D;
    private static final int TOOL_BOOTSTRAP_STICK_RESERVE = 8;
    private static final int TOOL_BOOTSTRAP_PLANK_RESERVE = 8;
    private static final double TOOL_BOOTSTRAP_RESERVE_PRIORITY_BONUS = 3.0D;

    private LumberjackDemandPlanner() {
    }

    public static DemandSnapshot buildSnapshot(ServerWorld world, net.minecraft.entity.Entity source, Inventory sourceInventory) {
        RecipeDemandIndex.RouteIndex routeIndex = RecipeDemandIndex.forWorld(world);
        EnumMap<MaterialType, MaterialDemand> demandByType = new EnumMap<>(MaterialType.class);
        for (MaterialType materialType : MaterialType.values()) {
            List<DistributionRouteEngine.ProfessionRoute> routes = routeIndex.routesFor(materialType.material());
            List<DistributionRecipientHelper.RecipientRecord> recipients = DistributionRouteEngine.findEligibleRecipients(world, source, RECIPIENT_SCAN_RANGE, routes);
            int sourceStock = countMaterialInInventory(sourceInventory, materialType);
            List<RecipientDemand> rankedRecipients = rankRecipients(world, recipients, materialType, routes, sourceStock);
            int demandDeficit = rankedRecipients.stream().mapToInt(RecipientDemand::deficit).sum();
            double weightedDemandDeficit = rankedRecipients.stream().mapToDouble(RecipientDemand::weightedDeficit).sum();
            demandByType.put(materialType, new MaterialDemand(materialType, sourceStock, recipients.size(), demandDeficit, weightedDemandDeficit, rankedRecipients));
        }
        DemandSnapshot snapshot = new DemandSnapshot(demandByType);
        snapshot.logDiagnostics(LOGGER);
        return snapshot;
    }


    public static java.util.Set<net.minecraft.village.VillagerProfession> resolveToolMaterialDemandProfessions(ServerWorld world) {
        RecipeDemandIndex.RouteIndex routeIndex = RecipeDemandIndex.forWorld(world);
        java.util.Set<net.minecraft.village.VillagerProfession> professions = new java.util.HashSet<>();

        for (DistributionRouteEngine.ProfessionRoute route : routeIndex.routesFor(RecipeDemandIndex.DemandMaterial.STICK)) {
            if (route.toolRecipeDemandRoute()) {
                professions.add(route.profession());
            }
        }
        for (DistributionRouteEngine.ProfessionRoute route : routeIndex.routesFor(RecipeDemandIndex.DemandMaterial.PLANKS)) {
            if (route.toolRecipeDemandRoute()) {
                professions.add(route.profession());
            }
        }

        return java.util.Set.copyOf(professions);
    }

    public static int countRecipientsUnderTargetStockForMaterials(ServerWorld world,
                                                                   net.minecraft.entity.Entity source,
                                                                   java.util.Set<MaterialType> materialTypes,
                                                                   java.util.Set<net.minecraft.village.VillagerProfession> professions,
                                                                   java.util.function.Predicate<DistributionRecipientHelper.RecipientRecord> recipientFilter) {
        RecipeDemandIndex.RouteIndex routeIndex = RecipeDemandIndex.forWorld(world);
        java.util.Set<java.util.UUID> underStockedRecipientIds = new java.util.HashSet<>();

        for (MaterialType materialType : materialTypes) {
            List<DistributionRouteEngine.ProfessionRoute> routes = routeIndex.routesFor(materialType.material());
            List<DistributionRecipientHelper.RecipientRecord> recipients = DistributionRouteEngine.findEligibleRecipients(world, source, RECIPIENT_SCAN_RANGE, routes);
            List<RecipientDemand> rankedRecipients = rankRecipients(world, recipients, materialType, routes, 0);

            for (RecipientDemand recipientDemand : rankedRecipients) {
                net.minecraft.village.VillagerProfession profession = recipientDemand.record().recipient().getVillagerData().getProfession();
                if (!professions.contains(profession)) {
                    continue;
                }
                if (!recipientFilter.test(recipientDemand.record())) {
                    continue;
                }
                underStockedRecipientIds.add(recipientDemand.record().recipient().getUuid());
            }
        }

        return underStockedRecipientIds.size();
    }

    private static List<RecipientDemand> rankRecipients(ServerWorld world,
                                                        List<DistributionRecipientHelper.RecipientRecord> recipients,
                                                        MaterialType materialType,
                                                        List<DistributionRouteEngine.ProfessionRoute> routes,
                                                        int sourceStock) {
        List<RecipientDemand> ranked = new ArrayList<>();
        for (DistributionRecipientHelper.RecipientRecord recipient : recipients) {
            Optional<Inventory> inventory = DistributionInventoryAccess.getChestInventory(world, recipient.chestPos());
            if (inventory.isEmpty()) {
                continue;
            }
            DistributionRouteEngine.ProfessionRoute route = routeFor(routes, recipient.recipient().getVillagerData().getProfession());
            if (route == null) {
                continue;
            }

            int stock = countMaterialInInventory(inventory.get(), materialType);
            int targetStock = route.targetStockCap() > 0 ? route.targetStockCap() : materialType.targetStock();
            int deficit = Math.max(0, targetStock - stock);
            if (deficit <= 0) {
                continue;
            }

            int reserveDeficit = reserveDeficit(materialType, stock, route);
            double chestFullness = DistributionInventoryAccess.getInventoryFullness(inventory.get());
            double weightedDeficit = (deficit * route.demandWeight())
                    + reservePriorityBonus(materialType, route, reserveDeficit, sourceStock);
            ranked.add(new RecipientDemand(recipient, stock, targetStock, deficit, weightedDeficit, chestFullness, route.toolRecipeDemandRoute(), reserveDeficit));
        }

        ranked.sort(Comparator
                .comparingDouble(RecipientDemand::weightedDeficit).reversed()
                .thenComparing(Comparator.comparingInt(RecipientDemand::deficit).reversed())
                .thenComparingDouble(RecipientDemand::chestFullness)
                .thenComparingDouble(recipient -> recipient.record().sourceSquaredDistance())
                .thenComparing(recipient -> recipient.record().recipient().getUuid(), java.util.UUID::compareTo));
        return List.copyOf(ranked);
    }


    private static int reserveDeficit(MaterialType materialType,
                                      int currentStock,
                                      DistributionRouteEngine.ProfessionRoute route) {
        if (!route.toolRecipeDemandRoute()) {
            return 0;
        }
        int reserveTarget = reserveTarget(materialType);
        if (reserveTarget <= 0) {
            return 0;
        }
        return Math.max(0, reserveTarget - currentStock);
    }

    private static int reserveTarget(MaterialType materialType) {
        if (materialType == MaterialType.STICK) {
            return TOOL_BOOTSTRAP_STICK_RESERVE;
        }
        if (materialType == MaterialType.PLANKS) {
            return TOOL_BOOTSTRAP_PLANK_RESERVE;
        }
        return 0;
    }

    private static double reservePriorityBonus(MaterialType materialType,
                                               DistributionRouteEngine.ProfessionRoute route,
                                               int reserveDeficit,
                                               int sourceStock) {
        if (!route.toolRecipeDemandRoute() || reserveDeficit <= 0) {
            return 0.0D;
        }
        int reserveTarget = reserveTarget(materialType);
        if (reserveTarget <= 0) {
            return 0.0D;
        }
        int reservePressure = Math.max(0, reserveTarget - sourceStock);
        return (reserveDeficit + reservePressure) * TOOL_BOOTSTRAP_RESERVE_PRIORITY_BONUS;
    }

    private static DistributionRouteEngine.ProfessionRoute routeFor(List<DistributionRouteEngine.ProfessionRoute> routes,
                                                                    net.minecraft.village.VillagerProfession profession) {
        for (DistributionRouteEngine.ProfessionRoute route : routes) {
            if (route.profession() == profession) {
                return route;
            }
        }
        return null;
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

    public enum MaterialType {
        CHARCOAL("charcoal", 16, RecipeDemandIndex.DemandMaterial.CHARCOAL),
        STICK("stick", 24, RecipeDemandIndex.DemandMaterial.STICK),
        PLANKS("planks", 32, RecipeDemandIndex.DemandMaterial.PLANKS),
        FENCES("fences", 48, RecipeDemandIndex.DemandMaterial.FENCES),
        FENCE_GATES("fence_gates", 12, RecipeDemandIndex.DemandMaterial.FENCE_GATES),
        LOGS("logs", 32, RecipeDemandIndex.DemandMaterial.LOGS);

        private final String label;
        private final int targetStock;
        private final RecipeDemandIndex.DemandMaterial material;

        MaterialType(String label, int targetStock, RecipeDemandIndex.DemandMaterial material) {
            this.label = label;
            this.targetStock = targetStock;
            this.material = material;
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

        public boolean matches(ItemStack stack) {
            return material.matches(stack);
        }

        public String label() {
            return label;
        }

        public int targetStock() {
            return targetStock;
        }

        public RecipeDemandIndex.DemandMaterial material() {
            return material;
        }
    }

    public record RecipientDemand(DistributionRecipientHelper.RecipientRecord record,
                                  int currentStock,
                                  int targetStock,
                                  int deficit,
                                  double weightedDeficit,
                                  double chestFullness,
                                  boolean toolRecipeDemandRoute,
                                  int reserveDeficit) {
    }

    public record MaterialDemand(MaterialType materialType, int sourceStock, int recipientCount, int demandDeficit,
                                 double weightedDemandDeficit,
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

        public double weightedDeficitFor(MaterialType materialType) {
            MaterialDemand demand = demandFor(materialType);
            return demand == null ? 0.0D : demand.weightedDemandDeficit();
        }

        public List<RecipientDemand> rankedRecipientsFor(MaterialType materialType) {
            MaterialDemand demand = demandFor(materialType);
            return demand == null ? List.of() : demand.rankedRecipients();
        }

        public void logDiagnostics(Logger logger) {
            for (MaterialType materialType : MaterialType.values()) {
                MaterialDemand demand = demandFor(materialType);
                if (demand == null) {
                    continue;
                }
                logger.debug("lumberjack demand snapshot material={} sourceStock={} recipients={} demandDeficit={} weightedDemandDeficit={}",
                        materialType.label(),
                        demand.sourceStock(),
                        demand.recipientCount(),
                        demand.demandDeficit(),
                        demand.weightedDemandDeficit());

                for (RecipientDemand recipient : demand.rankedRecipients()) {
                    logger.debug("  -> profession={} sourceStock={} targetCap={} deficit={} toolRecipeDemandPath={} reserveDeficit={} weightedDeficit={}",
                            recipient.record().recipient().getVillagerData().getProfession(),
                            demand.sourceStock(),
                            recipient.targetStock(),
                            recipient.deficit(),
                            recipient.toolRecipeDemandRoute(),
                            recipient.reserveDeficit(),
                            recipient.weightedDeficit());
                }
            }
        }
    }
}
