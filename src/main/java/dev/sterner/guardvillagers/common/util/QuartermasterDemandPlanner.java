package dev.sterner.guardvillagers.common.util;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Plans deterministic quartermaster demand queue entries from a full inventory snapshot.
 */
public final class QuartermasterDemandPlanner {
    private static final int DEFAULT_TARGET_CAP = 16;
    private static final Set<RecipeDemandIndex.DemandMaterial> CONTESTED_MATERIALS = Set.of(
            RecipeDemandIndex.DemandMaterial.DIAMOND,
            RecipeDemandIndex.DemandMaterial.IRON,
            RecipeDemandIndex.DemandMaterial.STICK,
            RecipeDemandIndex.DemandMaterial.PLANKS
    );
    private static final Map<RecipeDemandIndex.DemandMaterial, List<VillagerProfession>> CONTESTED_PRIORITY_ORDER = buildContestedPriorityOrder();
    private static final Map<RecipeDemandIndex.DemandMaterial, Integer> CONTESTED_ROTATION_CURSOR = new EnumMap<>(RecipeDemandIndex.DemandMaterial.class);

    private static final Map<VillagerProfession, EnumMap<RecipeDemandIndex.DemandMaterial, Integer>> EXPLICIT_CAPS =
            buildExplicitCaps();

    private QuartermasterDemandPlanner() {
    }

    public static List<QueueEntry> plan(ServerWorld world,
                                        BlockPos quartermasterChest,
                                        List<ChestSnapshot> snapshots,
                                        int perTransferCap) {
        return plan(RecipeDemandIndex.forWorld(world), quartermasterChest, snapshots, perTransferCap);
    }

    static List<QueueEntry> plan(RecipeDemandIndex.RouteIndex routeIndex,
                                 BlockPos quartermasterChest,
                                 List<ChestSnapshot> snapshots,
                                 int perTransferCap) {
        ChestSnapshot source = snapshots.stream()
                .filter(snapshot -> snapshot.chestPos().equals(quartermasterChest))
                .findFirst()
                .orElse(null);
        if (source == null) {
            return List.of();
        }

        List<QueueEntry> entries = new ArrayList<>();
        for (RecipeDemandIndex.DemandMaterial material : RecipeDemandIndex.DemandMaterial.values()) {
            int sourceMaterialStock = countMaterial(source, material);
            if (sourceMaterialStock <= 0) {
                continue;
            }

            Optional<Item> preferredSourceItem = preferredSourceItem(source, material);
            if (preferredSourceItem.isEmpty()) {
                continue;
            }

            List<DistributionRouteEngine.ProfessionRoute> routes = routeIndex.routesFor(material);
            if (routes.isEmpty()) {
                continue;
            }

            List<MaterialCandidate> materialCandidates = new ArrayList<>();
            for (DistributionRouteEngine.ProfessionRoute route : routes) {
                for (ChestSnapshot snapshot : snapshots) {
                    if (snapshot.chestPos().equals(quartermasterChest)) {
                        continue;
                    }
                    if (snapshot.profession() != route.profession()) {
                        continue;
                    }

                    int current = countMaterial(snapshot, material);
                    int target = resolveTargetCap(route.profession(), material, route.targetStockCap());
                    int deficit = Math.max(0, target - current);
                    if (deficit <= 0) {
                        continue;
                    }

                    int amount = Math.min(perTransferCap, deficit);
                    amount = Math.min(amount, sourceMaterialStock);
                    if (amount <= 0) {
                        continue;
                    }

                    boolean completesCraftNow = completesRecipeBundleNow(
                            source,
                            snapshot,
                            route.profession(),
                            material,
                            amount,
                            perTransferCap
                    );
                    double urgency = (deficit * route.demandWeight())
                            + (route.toolRecipeDemandRoute() ? 1.5D : 0.0D)
                            + (completesCraftNow ? 50.0D : 0.0D)
                            + tieBreakNudge(snapshot.chestPos(), material);

                    materialCandidates.add(new MaterialCandidate(
                            route.profession(),
                            snapshot.chestPos(),
                            preferredSourceItem.get(),
                            deficit,
                            amount,
                            completesCraftNow,
                            urgency,
                            tieBreakKey(route.profession(), snapshot.chestPos(), material)));
                }
            }
            entries.addAll(allocateForMaterial(material, sourceMaterialStock, materialCandidates));
        }

        Comparator<QueueEntry> queueOrder = Comparator.comparing(QueueEntry::completesCraftNow).reversed()
                .thenComparing(Comparator.comparingDouble(QueueEntry::urgencyScore).reversed())
                .thenComparing(Comparator.comparingInt(QueueEntry::deficit).reversed())
                .thenComparing(QueueEntry::tieBreakKey);
        entries.sort(queueOrder);

        return List.copyOf(entries);
    }

    private static int resolveTargetCap(VillagerProfession profession,
                                        RecipeDemandIndex.DemandMaterial material,
                                        int routeCap) {
        EnumMap<RecipeDemandIndex.DemandMaterial, Integer> caps = EXPLICIT_CAPS.get(profession);
        if (caps != null && caps.containsKey(material)) {
            return caps.get(material);
        }
        if (routeCap > 0) {
            return routeCap;
        }
        return DEFAULT_TARGET_CAP;
    }

    private static int countMaterial(ChestSnapshot snapshot, RecipeDemandIndex.DemandMaterial material) {
        int count = 0;
        for (Map.Entry<Item, Integer> entry : snapshot.itemCounts().entrySet()) {
            if (material.matches(new ItemStack(entry.getKey()))) {
                count += entry.getValue();
            }
        }
        return count;
    }

    private static Optional<Item> preferredSourceItem(ChestSnapshot source, RecipeDemandIndex.DemandMaterial material) {
        Item bestItem = null;
        int bestCount = 0;
        for (Map.Entry<Item, Integer> entry : source.itemCounts().entrySet()) {
            if (!material.matches(new ItemStack(entry.getKey()))) {
                continue;
            }
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                bestItem = entry.getKey();
            }
        }
        return Optional.ofNullable(bestItem);
    }

    private static List<QueueEntry> allocateForMaterial(RecipeDemandIndex.DemandMaterial material,
                                                        int sourceMaterialStock,
                                                        List<MaterialCandidate> candidates) {
        if (candidates.isEmpty() || sourceMaterialStock <= 0) {
            return List.of();
        }

        if (!CONTESTED_MATERIALS.contains(material)) {
            return candidates.stream()
                    .map(candidate -> candidate.toQueueEntry(Math.min(candidate.preferredAmount(), sourceMaterialStock)))
                    .toList();
        }

        List<MaterialCandidate> ordered = new ArrayList<>(candidates);
        List<VillagerProfession> priorityOrder = CONTESTED_PRIORITY_ORDER.getOrDefault(material, List.of());
        int rotationCursor = rotationCursor(material, priorityOrder.size());
        ordered.sort(contestedComparator(priorityOrder, rotationCursor));

        int remaining = sourceMaterialStock;
        List<QueueEntry> planned = new ArrayList<>();
        for (MaterialCandidate candidate : ordered) {
            if (remaining <= 0) {
                break;
            }
            int allocation = Math.min(candidate.preferredAmount(), remaining);
            if (allocation <= 0) {
                continue;
            }
            planned.add(candidate.toQueueEntry(allocation));
            remaining -= allocation;
        }

        if (!ordered.isEmpty()) {
            rotate(material, priorityOrder.size(), rotationCursor);
        }
        return planned;
    }

    private static Comparator<MaterialCandidate> contestedComparator(List<VillagerProfession> priorityOrder, int rotationCursor) {
        return Comparator
                .comparing(MaterialCandidate::completesCraftNow).reversed()
                .thenComparing(Comparator.comparingInt(MaterialCandidate::deficit).reversed())
                .thenComparingInt(candidate -> rotatedPriority(candidate.profession(), priorityOrder, rotationCursor))
                .thenComparingInt(candidate -> priorityIndex(candidate.profession(), priorityOrder))
                .thenComparing(MaterialCandidate::tieBreakKey);
    }

    private static synchronized int rotationCursor(RecipeDemandIndex.DemandMaterial material, int modulo) {
        if (modulo <= 0) {
            return 0;
        }
        return Math.floorMod(CONTESTED_ROTATION_CURSOR.getOrDefault(material, 0), modulo);
    }

    private static synchronized void rotate(RecipeDemandIndex.DemandMaterial material, int modulo, int currentCursor) {
        if (modulo <= 0) {
            return;
        }
        CONTESTED_ROTATION_CURSOR.put(material, Math.floorMod(currentCursor + 1, modulo));
    }

    private static int rotatedPriority(VillagerProfession profession,
                                       List<VillagerProfession> priorityOrder,
                                       int rotationCursor) {
        if (priorityOrder.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        int index = priorityIndex(profession, priorityOrder);
        if (index == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.floorMod(index - rotationCursor, priorityOrder.size());
    }

    private static int priorityIndex(VillagerProfession profession, List<VillagerProfession> priorityOrder) {
        int index = priorityOrder.indexOf(profession);
        return index >= 0 ? index : Integer.MAX_VALUE;
    }

    static synchronized void resetContestedAllocatorsForTests() {
        CONTESTED_ROTATION_CURSOR.clear();
    }

    private static boolean completesRecipeBundleNow(ChestSnapshot source,
                                                    ChestSnapshot recipient,
                                                    VillagerProfession profession,
                                                    RecipeDemandIndex.DemandMaterial sentMaterial,
                                                    int sentAmount,
                                                    int perTransferCap) {
        for (RecipeBundle bundle : explicitRecipeBundles(profession)) {
            if (!bundle.contains(sentMaterial)) {
                continue;
            }
            if (bundle.isCompletable(source, recipient, sentMaterial, sentAmount, perTransferCap)) {
                return true;
            }
        }
        return false;
    }

    private static List<RecipeBundle> explicitRecipeBundles(VillagerProfession profession) {
        if (profession == VillagerProfession.CARTOGRAPHER) {
            return List.of(
                    RecipeBundle.of(
                            requirement(RecipeDemandIndex.DemandMaterial.PAPER, 1),
                            requirement(RecipeDemandIndex.DemandMaterial.COMPASS, 1)
                    ),
                    RecipeBundle.of(
                            requirement(RecipeDemandIndex.DemandMaterial.PAPER, 1),
                            requirement(RecipeDemandIndex.DemandMaterial.EMPTY_MAP, 1)
                    )
            );
        }
        if (profession == VillagerProfession.TOOLSMITH
                || profession == VillagerProfession.WEAPONSMITH
                || profession == VillagerProfession.ARMORER) {
            return List.of(RecipeBundle.of(requirement(RecipeDemandIndex.DemandMaterial.DIAMOND, 1)));
        }
        return List.of();
    }

    private static RecipeRequirement requirement(RecipeDemandIndex.DemandMaterial material, int amount) {
        return new RecipeRequirement(material, amount);
    }

    private static double tieBreakNudge(BlockPos chestPos, RecipeDemandIndex.DemandMaterial material) {
        return (Math.floorMod(chestPos.asLong(), 13L) * 0.0001D) + (material.ordinal() * 0.00001D);
    }

    private static String tieBreakKey(VillagerProfession profession,
                                      BlockPos chestPos,
                                      RecipeDemandIndex.DemandMaterial material) {
        return profession + ":" + chestPos.asLong() + ":" + material.id();
    }

    private static Map<VillagerProfession, EnumMap<RecipeDemandIndex.DemandMaterial, Integer>> buildExplicitCaps() {
        Map<VillagerProfession, EnumMap<RecipeDemandIndex.DemandMaterial, Integer>> caps = new HashMap<>();

        EnumMap<RecipeDemandIndex.DemandMaterial, Integer> masonCaps = new EnumMap<>(RecipeDemandIndex.DemandMaterial.class);
        masonCaps.put(RecipeDemandIndex.DemandMaterial.PLANKS, 24);
        masonCaps.put(RecipeDemandIndex.DemandMaterial.STICK, 24);
        caps.put(VillagerProfession.MASON, masonCaps);

        EnumMap<RecipeDemandIndex.DemandMaterial, Integer> weaponsmithCaps = new EnumMap<>(RecipeDemandIndex.DemandMaterial.class);
        weaponsmithCaps.put(RecipeDemandIndex.DemandMaterial.PLANKS, 16);
        caps.put(VillagerProfession.WEAPONSMITH, weaponsmithCaps);

        EnumMap<RecipeDemandIndex.DemandMaterial, Integer> farmerCaps = new EnumMap<>(RecipeDemandIndex.DemandMaterial.class);
        farmerCaps.put(RecipeDemandIndex.DemandMaterial.PLANKS, 32);
        farmerCaps.put(RecipeDemandIndex.DemandMaterial.STICK, 32);
        caps.put(VillagerProfession.FARMER, farmerCaps);

        EnumMap<RecipeDemandIndex.DemandMaterial, Integer> butcherCaps = new EnumMap<>(RecipeDemandIndex.DemandMaterial.class);
        butcherCaps.put(RecipeDemandIndex.DemandMaterial.LOGS, 4);
        caps.put(VillagerProfession.BUTCHER, butcherCaps);

        EnumMap<RecipeDemandIndex.DemandMaterial, Integer> cartographerCaps = new EnumMap<>(RecipeDemandIndex.DemandMaterial.class);
        cartographerCaps.put(RecipeDemandIndex.DemandMaterial.PAPER, 24);
        cartographerCaps.put(RecipeDemandIndex.DemandMaterial.COMPASS, 4);
        cartographerCaps.put(RecipeDemandIndex.DemandMaterial.EMPTY_MAP, 4);
        caps.put(VillagerProfession.CARTOGRAPHER, cartographerCaps);

        EnumMap<RecipeDemandIndex.DemandMaterial, Integer> smithDiamondCaps = new EnumMap<>(RecipeDemandIndex.DemandMaterial.class);
        smithDiamondCaps.put(RecipeDemandIndex.DemandMaterial.DIAMOND, 4);
        caps.put(VillagerProfession.TOOLSMITH, smithDiamondCaps);
        caps.put(VillagerProfession.WEAPONSMITH, smithDiamondCaps);
        caps.put(VillagerProfession.ARMORER, smithDiamondCaps);

        return Map.copyOf(caps);
    }

    private static Map<RecipeDemandIndex.DemandMaterial, List<VillagerProfession>> buildContestedPriorityOrder() {
        Map<RecipeDemandIndex.DemandMaterial, List<VillagerProfession>> priorities = new EnumMap<>(RecipeDemandIndex.DemandMaterial.class);
        priorities.put(
                RecipeDemandIndex.DemandMaterial.DIAMOND,
                List.of(VillagerProfession.TOOLSMITH, VillagerProfession.WEAPONSMITH, VillagerProfession.ARMORER)
        );
        priorities.put(
                RecipeDemandIndex.DemandMaterial.IRON,
                List.of(VillagerProfession.TOOLSMITH, VillagerProfession.WEAPONSMITH, VillagerProfession.ARMORER)
        );
        priorities.put(
                RecipeDemandIndex.DemandMaterial.STICK,
                List.of(VillagerProfession.FARMER, VillagerProfession.MASON, VillagerProfession.SHEPHERD, VillagerProfession.FLETCHER)
        );
        priorities.put(
                RecipeDemandIndex.DemandMaterial.PLANKS,
                List.of(VillagerProfession.FARMER, VillagerProfession.MASON, VillagerProfession.SHEPHERD, VillagerProfession.FLETCHER)
        );
        return Collections.unmodifiableMap(priorities);
    }

    public record ChestSnapshot(VillagerProfession profession,
                                BlockPos chestPos,
                                Map<Item, Integer> itemCounts) {
    }

    public record QueueEntry(VillagerProfession recipientProfession,
                             BlockPos recipientChestPos,
                             ItemStack requestedStack,
                             int deficit,
                             boolean completesCraftNow,
                             double urgencyScore,
                             String tieBreakKey) {
    }

    private record MaterialCandidate(VillagerProfession profession,
                                     BlockPos chestPos,
                                     Item requestItem,
                                     int deficit,
                                     int preferredAmount,
                                     boolean completesCraftNow,
                                     double urgencyScore,
                                     String tieBreakKey) {
        private QueueEntry toQueueEntry(int amount) {
            return new QueueEntry(
                    profession,
                    chestPos,
                    new ItemStack(requestItem, amount),
                    deficit,
                    completesCraftNow,
                    urgencyScore,
                    tieBreakKey
            );
        }
    }

    private record RecipeRequirement(RecipeDemandIndex.DemandMaterial material, int amount) {
    }

    private record RecipeBundle(List<RecipeRequirement> requirements) {
        private static RecipeBundle of(RecipeRequirement... requirements) {
            return new RecipeBundle(Arrays.asList(requirements));
        }

        private boolean contains(RecipeDemandIndex.DemandMaterial material) {
            return requirements.stream().anyMatch(requirement -> requirement.material() == material);
        }

        private boolean isCompletable(ChestSnapshot source,
                                      ChestSnapshot recipient,
                                      RecipeDemandIndex.DemandMaterial sentMaterial,
                                      int sentAmount,
                                      int perTransferCap) {
            for (RecipeRequirement requirement : requirements) {
                int recipientCurrent = countMaterial(recipient, requirement.material());
                int sourceCurrent = countMaterial(source, requirement.material());
                int incoming = requirement.material() == sentMaterial ? sentAmount : 0;
                int totalAfterCurrentSend = recipientCurrent + incoming;
                if (totalAfterCurrentSend >= requirement.amount()) {
                    continue;
                }
                int remainingNeed = requirement.amount() - totalAfterCurrentSend;
                int maxAdditionalSend = Math.min(perTransferCap, Math.max(0, sourceCurrent - incoming));
                if (maxAdditionalSend < remainingNeed) {
                    return false;
                }
            }
            return true;
        }
    }
}
