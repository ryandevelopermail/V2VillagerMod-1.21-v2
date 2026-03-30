package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public final class RecipeDemandIndex {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecipeDemandIndex.class);
    private static final Map<String, RouteIndex> CACHE = new HashMap<>();
    private static final int TOOLSMITH_PLANK_COMPONENT_CAP = 32;
    private static final int TOOLSMITH_STICK_COMPONENT_CAP = 32;
    private static final int MASON_PLANK_COMPONENT_CAP = 24;
    private static final int MASON_STICK_COMPONENT_CAP = 24;
    private static final int FARMER_PLANK_COMPONENT_CAP = 32;
    private static final int FARMER_STICK_COMPONENT_CAP = 32;
    private static final int BUTCHER_SMOKER_LOG_CAP = 1;

    private RecipeDemandIndex() {
    }

    public static RouteIndex forWorld(ServerWorld world) {
        String key = world.getRegistryKey().getValue().toString();
        RouteIndex existing = CACHE.get(key);
        if (existing != null) {
            return existing;
        }
        RouteIndex built = build(world);
        CACHE.put(key, built);
        built.dump(LOGGER);
        return built;
    }

    public static void clearWorld(ServerWorld world) {
        CACHE.remove(world.getRegistryKey().getValue().toString());
    }

    public static void clearAll() {
        CACHE.clear();
    }

    private static RouteIndex build(ServerWorld world) {
        EnumMap<DemandMaterial, Map<VillagerProfession, MutableDemand>> aggregate = new EnumMap<>(DemandMaterial.class);
        for (DemandMaterial material : DemandMaterial.values()) {
            aggregate.put(material, new HashMap<>());
        }

        addFixedDemandRoutes(aggregate);

        // dynamic-goal recipe scans (RecipeManager-backed goals)
        Set<VillagerProfession> detectedToolMaterialDemandProfessions = new HashSet<>();
        scanDynamic(world, aggregate, detectedToolMaterialDemandProfessions, VillagerProfession.LIBRARIAN, RecipeDemandIndex::isLibrarianOutput, 48, 1.8D);
        scanDynamic(world, aggregate, detectedToolMaterialDemandProfessions, VillagerProfession.FISHERMAN, RecipeDemandIndex::isFishermanOutput, 40, 1.5D);
        scanDynamic(world, aggregate, detectedToolMaterialDemandProfessions, VillagerProfession.FLETCHER, RecipeDemandIndex::isFletcherOutput, 32, 1.2D);
        scanDynamic(world, aggregate, detectedToolMaterialDemandProfessions, VillagerProfession.TOOLSMITH, RecipeDemandIndex::isToolsmithOutput, 32, 1.6D);
        scanDynamic(world, aggregate, detectedToolMaterialDemandProfessions, VillagerProfession.FARMER, RecipeDemandIndex::isFarmerOutput, FARMER_PLANK_COMPONENT_CAP, 1.15D);
        scanDynamic(world, aggregate, detectedToolMaterialDemandProfessions, VillagerProfession.MASON, RecipeDemandIndex::isMasonOutput, MASON_PLANK_COMPONENT_CAP, 1.4D);

        // non-crafting but currently used by lumberjack fuel routing
        add(aggregate, DemandMaterial.CHARCOAL, VillagerProfession.BUTCHER, 1, 16, 1.0D, false, false);
        add(aggregate, DemandMaterial.CHARCOAL, VillagerProfession.ARMORER, 1, 16, 1.0D, false, false);
        add(aggregate, DemandMaterial.CHARCOAL, VillagerProfession.TOOLSMITH, 1, 16, 1.0D, false, false);
        add(aggregate, DemandMaterial.CHARCOAL, VillagerProfession.WEAPONSMITH, 1, 16, 1.0D, false, false);

        RouteIndex routeIndex = new RouteIndex(toImmutable(aggregate));
        validateToolMaterialDemandCoverage(routeIndex, detectedToolMaterialDemandProfessions);
        return routeIndex;
    }

    static RouteIndex buildFixedRouteIndexForTests() {
        EnumMap<DemandMaterial, Map<VillagerProfession, MutableDemand>> aggregate = new EnumMap<>(DemandMaterial.class);
        for (DemandMaterial material : DemandMaterial.values()) {
            aggregate.put(material, new HashMap<>());
        }
        addFixedDemandRoutes(aggregate);
        return new RouteIndex(toImmutable(aggregate));
    }

    private static void addFixedDemandRoutes(EnumMap<DemandMaterial, Map<VillagerProfession, MutableDemand>> aggregate) {
        // fixed-goal enum requirements
        add(aggregate, DemandMaterial.PLANKS, VillagerProfession.FARMER, 1, FARMER_PLANK_COMPONENT_CAP, 1.15D, false, true);
        add(aggregate, DemandMaterial.STICK, VillagerProfession.FARMER, 2, FARMER_STICK_COMPONENT_CAP, 1.15D, false, true);
        add(aggregate, DemandMaterial.STICK, VillagerProfession.SHEPHERD, 1, 24, 1.0D, false, false);
        // Shepherd needs planks for bed crafting (3 per bed)
        add(aggregate, DemandMaterial.PLANKS, VillagerProfession.SHEPHERD, 1, 32, 1.0D, false, false);
        // Shepherd fence pen construction stock routes
        add(aggregate, DemandMaterial.FENCES, VillagerProfession.SHEPHERD, 1, 48, 1.0D, false, false);
        add(aggregate, DemandMaterial.FENCE_GATES, VillagerProfession.SHEPHERD, 1, 12, 1.0D, false, false);
        add(aggregate,
                DemandMaterial.LOGS,
                VillagerProfession.BUTCHER,
                1,
                resolveFixedCap(DemandMaterial.LOGS, VillagerProfession.BUTCHER, 32),
                1.0D,
                false,
                false);
        add(aggregate, DemandMaterial.PLANKS, VillagerProfession.MASON, 1, MASON_PLANK_COMPONENT_CAP, 1.4D, false, true);
        add(aggregate, DemandMaterial.STICK, VillagerProfession.MASON, 1, MASON_STICK_COMPONENT_CAP, 1.4D, false, true);
    }

    private static void scanDynamic(ServerWorld world,
                                    EnumMap<DemandMaterial, Map<VillagerProfession, MutableDemand>> aggregate,
                                    Set<VillagerProfession> detectedToolMaterialDemandProfessions,
                                    VillagerProfession profession,
                                    Predicate<ItemStack> outputFilter,
                                    int defaultCap,
                                    double defaultWeight) {
        for (RecipeEntry<CraftingRecipe> entry : world.getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = entry.value();
            ItemStack output = recipe.getResult(world.getRegistryManager());
            if (!outputFilter.test(output)) {
                continue;
            }
            EnumSet<DemandMaterial> recipeMaterials = collectMaterialsForRecipe(recipe.getIngredients());
            int recipeStrength = strengthForOutput(output);
            boolean toolRecipeDemandRoute = isToolCraftingOutput(output)
                    && (recipeMaterials.contains(DemandMaterial.PLANKS) || recipeMaterials.contains(DemandMaterial.STICK));
            if (toolRecipeDemandRoute) {
                detectedToolMaterialDemandProfessions.add(profession);
            }
            for (DemandMaterial material : recipeMaterials) {
                boolean useToolRecipeDemandRoute = toolRecipeDemandRoute
                        && (material == DemandMaterial.PLANKS || material == DemandMaterial.STICK);
                add(aggregate,
                        material,
                        profession,
                        recipeStrength,
                        resolveDynamicCap(profession, material, defaultCap),
                        defaultWeight,
                        false,
                        useToolRecipeDemandRoute);
            }
        }
    }

    static int resolveDynamicCap(VillagerProfession profession, DemandMaterial material, int defaultCap) {
        if (profession == VillagerProfession.TOOLSMITH && material == DemandMaterial.PLANKS) {
            return Math.min(defaultCap, TOOLSMITH_PLANK_COMPONENT_CAP);
        }
        if (profession == VillagerProfession.TOOLSMITH && material == DemandMaterial.STICK) {
            return Math.min(defaultCap, TOOLSMITH_STICK_COMPONENT_CAP);
        }
        if (profession == VillagerProfession.MASON && material == DemandMaterial.STICK) {
            return Math.min(defaultCap, MASON_STICK_COMPONENT_CAP);
        }
        if (profession == VillagerProfession.FARMER && material == DemandMaterial.STICK) {
            return Math.min(defaultCap, FARMER_STICK_COMPONENT_CAP);
        }
        return defaultCap;
    }

    static int resolveFixedCap(DemandMaterial material, VillagerProfession profession, int defaultCap) {
        if (material == DemandMaterial.LOGS && profession == VillagerProfession.BUTCHER) {
            return BUTCHER_SMOKER_LOG_CAP;
        }
        return defaultCap;
    }

    static EnumSet<DemandMaterial> collectMaterialsForRecipe(List<Ingredient> ingredients) {
        EnumSet<DemandMaterial> recipeMaterials = EnumSet.noneOf(DemandMaterial.class);
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) {
                continue;
            }
            for (DemandMaterial material : DemandMaterial.values()) {
                if (ingredient.test(material.probe())) {
                    recipeMaterials.add(material);
                }
            }
        }
        return recipeMaterials;
    }

    static int strengthForOutput(ItemStack output) {
        return Math.max(1, Math.min(output.getCount(), 4));
    }

    static boolean isToolCraftingOutput(ItemStack stack) {
        return !stack.isEmpty() && (stack.getItem() instanceof net.minecraft.item.ToolItem
                || stack.getItem() instanceof net.minecraft.item.ShearsItem
                || stack.isOf(Items.FISHING_ROD));
    }

    static boolean validateToolMaterialDemandCoverage(RouteIndex routeIndex,
                                                      Set<VillagerProfession> detectedToolMaterialDemandProfessions) {
        Set<VillagerProfession> planksRoutes = routeIndex.routesFor(DemandMaterial.PLANKS).stream()
                .map(DistributionRouteEngine.ProfessionRoute::profession)
                .collect(java.util.stream.Collectors.toSet());
        Set<VillagerProfession> stickRoutes = routeIndex.routesFor(DemandMaterial.STICK).stream()
                .map(DistributionRouteEngine.ProfessionRoute::profession)
                .collect(java.util.stream.Collectors.toSet());
        Set<VillagerProfession> missing = new HashSet<>();
        for (VillagerProfession profession : detectedToolMaterialDemandProfessions) {
            if (!planksRoutes.contains(profession) && !stickRoutes.contains(profession)) {
                missing.add(profession);
            }
        }

        if (!missing.isEmpty()) {
            LOGGER.warn("[recipe-demand-index] tool recipe demand coverage missing professions={}", missing);
            return false;
        }

        LOGGER.debug("[recipe-demand-index] tool recipe demand coverage validated professions={}", detectedToolMaterialDemandProfessions.size());
        return true;
    }

    private static boolean isLibrarianOutput(ItemStack stack) {
        return !stack.isEmpty() && (stack.isOf(Items.BOOKSHELF) || stack.isOf(Items.BOOK) || stack.isOf(Items.PAPER) || stack.isOf(Items.WRITABLE_BOOK));
    }

    private static boolean isFishermanOutput(ItemStack stack) {
        return !stack.isEmpty() && (stack.isOf(Items.FISHING_ROD) || stack.isOf(Items.BUCKET) || stack.isIn(ItemTags.BOATS) || stack.isIn(ItemTags.CHEST_BOATS));
    }

    private static boolean isFletcherOutput(ItemStack stack) {
        return !stack.isEmpty() && (stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW) || stack.isOf(Items.ARROW) || stack.isOf(Items.STICK) || stack.isOf(Items.TARGET));
    }

    private static boolean isToolsmithOutput(ItemStack stack) {
        return !stack.isEmpty() && (stack.getItem() instanceof net.minecraft.item.PickaxeItem
                || stack.getItem() instanceof net.minecraft.item.ShovelItem
                || stack.getItem() instanceof net.minecraft.item.HoeItem
                || stack.getItem() instanceof net.minecraft.item.ShearsItem
                || stack.isOf(Items.FISHING_ROD));
    }

    private static boolean isFarmerOutput(ItemStack stack) {
        return !stack.isEmpty() && (stack.getItem() instanceof net.minecraft.item.HoeItem);
    }

    private static boolean isMasonOutput(ItemStack stack) {
        return !stack.isEmpty() && (stack.getItem() instanceof net.minecraft.item.PickaxeItem || stack.getItem() instanceof net.minecraft.item.ShovelItem);
    }

    private static void add(EnumMap<DemandMaterial, Map<VillagerProfession, MutableDemand>> aggregate,
                            DemandMaterial material,
                            VillagerProfession profession,
                            int strength,
                            int cap,
                            double weight,
                            boolean requiresCraftingTable,
                            boolean toolRecipeDemandRoute) {
        Map<VillagerProfession, MutableDemand> byProfession = aggregate.get(material);
        MutableDemand demand = byProfession.computeIfAbsent(profession, ignored -> new MutableDemand(profession, cap, weight, requiresCraftingTable));
        demand.strength += strength;
        demand.cap = Math.max(demand.cap, cap);
        demand.weight = Math.max(demand.weight, weight);
        demand.toolRecipeDemandRoute = demand.toolRecipeDemandRoute || toolRecipeDemandRoute;
    }

    private static EnumMap<DemandMaterial, List<DistributionRouteEngine.ProfessionRoute>> toImmutable(EnumMap<DemandMaterial, Map<VillagerProfession, MutableDemand>> aggregate) {
        EnumMap<DemandMaterial, List<DistributionRouteEngine.ProfessionRoute>> routes = new EnumMap<>(DemandMaterial.class);
        for (Map.Entry<DemandMaterial, Map<VillagerProfession, MutableDemand>> entry : aggregate.entrySet()) {
            List<DistributionRouteEngine.ProfessionRoute> materialRoutes = entry.getValue().values().stream()
                    .map(demand -> {
                        Block expectedBlock = ProfessionDefinitions.get(demand.profession)
                                .flatMap(def -> def.expectedJobBlocks().stream().findFirst())
                                .orElse(null);
                        return expectedBlock == null
                                ? null
                                : new DistributionRouteEngine.ProfessionRoute(demand.profession,
                                expectedBlock,
                                demand.requiresCraftingTable,
                                demand.cap,
                                demand.weight + (demand.strength * 0.05D),
                                demand.toolRecipeDemandRoute);
                    })
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(route -> route.profession().toString()))
                    .toList();
            routes.put(entry.getKey(), materialRoutes);
        }
        return routes;
    }

    public enum DemandMaterial {
        CHARCOAL("charcoal", new ItemStack(Items.CHARCOAL), stack -> stack.isOf(Items.CHARCOAL)),
        STICK("stick", new ItemStack(Items.STICK), stack -> stack.isOf(Items.STICK)),
        PLANKS("planks", new ItemStack(Items.OAK_PLANKS), stack -> stack.isIn(ItemTags.PLANKS)),
        FENCES("fences", new ItemStack(Items.OAK_FENCE), stack -> stack.isIn(ItemTags.FENCES)),
        FENCE_GATES("fence_gates", new ItemStack(Items.OAK_FENCE_GATE), stack -> stack.isIn(ItemTags.FENCE_GATES)),
        LOGS("logs", new ItemStack(Items.OAK_LOG), stack -> stack.isIn(ItemTags.LOGS));

        private final String id;
        private final ItemStack probe;
        private final Predicate<ItemStack> matcher;

        DemandMaterial(String id, ItemStack probe, Predicate<ItemStack> matcher) {
            this.id = id;
            this.probe = probe;
            this.matcher = matcher;
        }

        public String id() {
            return id;
        }

        public ItemStack probe() {
            return probe;
        }

        public boolean matches(ItemStack stack) {
            return matcher.test(stack);
        }
    }

    public record RouteIndex(EnumMap<DemandMaterial, List<DistributionRouteEngine.ProfessionRoute>> routes) {
        public List<DistributionRouteEngine.ProfessionRoute> routesFor(DemandMaterial material) {
            return routes.getOrDefault(material, List.of());
        }

        public void dump(Logger logger) {
            logger.debug("[recipe-demand-index] computed recipient routes:");
            for (DemandMaterial material : DemandMaterial.values()) {
                List<DistributionRouteEngine.ProfessionRoute> materialRoutes = routesFor(material);
                logger.debug("[recipe-demand-index] material={} routes={}", material.id(), materialRoutes.size());
                for (DistributionRouteEngine.ProfessionRoute route : materialRoutes) {
                    logger.debug("  -> {} cap={} weight={} requiresTable={} toolRecipePath={}",
                            route.profession(),
                            route.targetStockCap(),
                            route.demandWeight(),
                            route.requiresCraftingTable(),
                            route.toolRecipeDemandRoute());
                }
            }
        }
    }

    private static final class MutableDemand {
        private final VillagerProfession profession;
        private int strength;
        private int cap;
        private double weight;
        private final boolean requiresCraftingTable;
        private boolean toolRecipeDemandRoute;

        private MutableDemand(VillagerProfession profession, int cap, double weight, boolean requiresCraftingTable) {
            this.profession = profession;
            this.strength = 0;
            this.cap = cap;
            this.weight = weight;
            this.requiresCraftingTable = requiresCraftingTable;
            this.toolRecipeDemandRoute = false;
        }
    }
}
