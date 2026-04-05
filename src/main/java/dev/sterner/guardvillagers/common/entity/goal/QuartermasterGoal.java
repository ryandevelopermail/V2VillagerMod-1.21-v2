package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.MasonGuardEntity;
import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.util.QuartermasterPrerequisiteHelper;
import dev.sterner.guardvillagers.common.util.VillageAnchorState;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import dev.sterner.guardvillagers.common.villager.behavior.WeaponsmithBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.BedBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.PrioritizedGoal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Cluster 3 — Quartermaster Goal (added to a Librarian villager after double-chest promotion).
 *
 * <p>The Quartermaster is a <em>proactive material accelerator</em> on top of the existing
 * librarian distribution goal.  It polls the village state every
 * {@link #CHECK_INTERVAL_TICKS} ticks and performs one of the following priority-ordered
 * actions:
 *
 * <ol>
 *   <li><b>Mason building wall</b> → haul stone from bell chest to mason's paired chest.</li>
 *   <li><b>Lumberjack crafting (planks)</b> → haul planks/wood from bell chest to lumberjack's chest.</li>
 *   <li><b>Weaponsmith planks</b> → haul planks to weaponsmith chest for wood weapon crafting.</li>
 *   <li><b>Lumberjack furnace stone</b> → haul 8 cobblestone to lumberjack for furnace crafting (skipped if furnace already exists near job site).</li>
 *   <li><b>Village chest low</b> → haul from any over-stocked profession chest to bell chest.</li>
 * </ol>
 *
 * <p>The Quartermaster's paired chest is used as the transit buffer.  The bell chest is
 * resolved via {@link BellChestMappingState}.
 */
public class QuartermasterGoal extends Goal {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuartermasterGoal.class);

    private static final int CHECK_INTERVAL_TICKS = 300;
    private static final long DAILY_RECLAIM_INTERVAL_TICKS = 24_000L;
    private static final int STRUCTURAL_CHECK_INTERVAL_TICKS = 20;
    private static final double DEFAULT_SCAN_RANGE = VillageGuardStandManager.BELL_EFFECT_RANGE;
    private static final double MOVE_SPEED = 0.55D;
    private static final double REACH_SQ = 3.0D * 3.0D;
    /** Minimum stone in mason chest before we top it up. */
    private static final int MASON_STONE_THRESHOLD = 32;
    /** Minimum planks in lumberjack chest before we top it up. */
    private static final int LUMBERJACK_PLANK_THRESHOLD = 16;
    /** Minimum planks in weaponsmith chest before we top it up (for wood weapon crafting). */
    private static final int WEAPONSMITH_PLANK_THRESHOLD = 16;
    /** Cobblestone needed to craft one furnace (8 cobblestone = 1 furnace). */
    private static final int LUMBERJACK_FURNACE_STONE_AMOUNT = 8;
    /** Radius around lumberjack job site to check for an existing furnace. */
    private static final int FURNACE_CHECK_RADIUS = 5;
    /** Bell chest is considered "low" if total items < this. */
    private static final int BELL_CHEST_LOW_THRESHOLD = 32;
    /** Amount to transfer per haul trip. */
    private static final int HAUL_AMOUNT = 16;
    /** Hard cap of cached candidate chests checked in one planning cycle. */
    private static final int MAX_SURPLUS_CANDIDATE_CHESTS_PER_CYCLE = 12;
    /** Hard cap of inventories inspected in one planning cycle. */
    private static final int MAX_SURPLUS_INVENTORIES_PER_CYCLE = 12;
    /** Hard cap of villager pairing entries scanned while rebuilding candidate cache. */
    private static final int MAX_PAIRINGS_SCANNED_PER_CYCLE = 24;
    /** Candidate chest cache refresh cadence. */
    private static final int CANDIDATE_CACHE_REFRESH_INTERVAL_TICKS = 20 * 30;
    private static final int MASON_COBBLESTONE_RESERVE = 8;
    private static final int MASON_MINING_DIRT_BUFFER = 8;
    private static final int NATURAL_VILLAGE_POI_SCAN_RADIUS = 8;
    private static final Set<net.minecraft.block.Block> NATURAL_VILLAGE_JOB_SITE_BLOCKS = Set.of(
            Blocks.BLAST_FURNACE,
            Blocks.SMOKER,
            Blocks.CARTOGRAPHY_TABLE,
            Blocks.BREWING_STAND,
            Blocks.COMPOSTER,
            Blocks.BARREL,
            Blocks.FLETCHING_TABLE,
            Blocks.LECTERN,
            Blocks.CAULDRON,
            Blocks.STONECUTTER,
            Blocks.LOOM,
            Blocks.SMITHING_TABLE,
            Blocks.GRINDSTONE
    );
    /**
     * Runtime-only active quartermaster registry, keyed by world + anchor chest position.
     *
     * <p>This avoids repeatedly scanning nearby librarians and their goal selectors for
     * "is any QM active?" checks.
     */
    private static final Map<net.minecraft.registry.RegistryKey<net.minecraft.world.World>, Map<BlockPos, Set<UUID>>>
            ACTIVE_QM_BY_WORLD_ANCHOR = new HashMap<>();
    private static final Map<QmBootstrapKey, Boolean> BOOTSTRAP_COMPLETE_BY_QM = new HashMap<>();

    /**
     * Item whitelist for Priority-3 surplus haul.
     *
     * <p>Priority-3 hauls from ANY over-stocked chest near a villager. Without this
     * safelist the QM would drain Cleric potions, Fletcher arrows, Armorer iron gear,
     * Fisherman fish, Cartographer maps, Butcher meat — all specialist trade goods —
     * into the generic bell chest. This makes those professions silently stop trading.
     *
     * <p>Only "generic village bulk" materials that are safe to redistribute are
     * included here. Logs and planks are particularly important because the bell chest
     * is the primary routing hub for the Lumberjack→Shepherd plank pipeline.
     */
    private static final Predicate<ItemStack> SURPLUS_HAUL_WHITELIST = stack -> {
        if (stack.isEmpty()) return false;
        // Accept any log type
        if (stack.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) return true;
        // Accept any plank type
        if (stack.isIn(ItemTags.PLANKS)) return true;
        // Accept any wool type
        if (stack.isIn(net.minecraft.registry.tag.ItemTags.WOOL)) return true;
        // Accept specific bulk construction/farming materials
        net.minecraft.item.Item item = stack.getItem();
        return item == Items.COBBLESTONE
                || item == Items.STONE
                || item == Items.GRAVEL
                || item == Items.SAND
                || item == Items.WHEAT
                || item == Items.WHEAT_SEEDS
                || item == Items.HAY_BLOCK
                || item == Items.COAL
                || item == Items.CHARCOAL
                || item == Items.STICK;
    };
    private static final Map<VillagerProfession, ProfessionReclaimPolicy> PROFESSION_RECLAIM_POLICIES = buildProfessionReclaimPolicies();

    private static Map<VillagerProfession, ProfessionReclaimPolicy> buildProfessionReclaimPolicies() {
        Map<VillagerProfession, ProfessionReclaimPolicy> policies = new EnumMap<>(VillagerProfession.class);
        policies.put(VillagerProfession.FARMER, ProfessionReclaimPolicy.of(
                stack -> stack.isOf(Items.WHEAT) || stack.isOf(Items.WHEAT_SEEDS) || stack.isOf(Items.HAY_BLOCK),
                Map.of(
                        Items.WHEAT, 32,
                        Items.WHEAT_SEEDS, 32,
                        Items.HAY_BLOCK, 8
                )));
        policies.put(VillagerProfession.SHEPHERD, ProfessionReclaimPolicy.of(
                stack -> stack.isIn(net.minecraft.registry.tag.ItemTags.WOOL),
                Map.of()));
        policies.put(VillagerProfession.FLETCHER, ProfessionReclaimPolicy.of(
                stack -> stack.isOf(Items.STICK),
                Map.of(Items.STICK, 32)));
        policies.put(VillagerProfession.LIBRARIAN, ProfessionReclaimPolicy.none());
        policies.put(VillagerProfession.CLERIC, ProfessionReclaimPolicy.none());
        policies.put(VillagerProfession.ARMORER, ProfessionReclaimPolicy.none());
        policies.put(VillagerProfession.BUTCHER, ProfessionReclaimPolicy.none());
        policies.put(VillagerProfession.CARTOGRAPHER, ProfessionReclaimPolicy.none());
        policies.put(VillagerProfession.LEATHERWORKER, ProfessionReclaimPolicy.none());
        policies.put(VillagerProfession.TOOLSMITH, ProfessionReclaimPolicy.none());
        policies.put(VillagerProfession.WEAPONSMITH, ProfessionReclaimPolicy.of(
                stack -> stack.isIn(ItemTags.PLANKS),
                Map.of()));
        policies.put(VillagerProfession.MASON, ProfessionReclaimPolicy.of(
                stack -> stack.isIn(ItemTags.STAIRS) || stack.isIn(ItemTags.SLABS) || stack.isOf(Items.STONE),
                Map.of(
                        Items.COBBLESTONE, MASON_COBBLESTONE_RESERVE,
                        Items.DIRT, MASON_MINING_DIRT_BUFFER
                )));
        policies.put(VillagerProfession.FISHERMAN, ProfessionReclaimPolicy.none());
        return policies;
    }

    private final VillagerEntity villager;
    private final BlockPos jobPos;
    private final BlockPos chestPos;  // librarian's paired chest (double-chest second half)

    private long nextCheckTick = 0L;
    private long nextDailyReclaimTick = 0L;
    private long nextStructuralCheckTick = 0L;
    private Stage stage = Stage.IDLE;
    private boolean anchorRegistered = false;
    private boolean demoted = false;
    private boolean forceStructuralRevalidation = true;
    private boolean prerequisitesValid = false;
    private BlockPos cachedSecondChestPos = null;

    // Current active transfer
    private BlockPos sourcePos = null;
    private BlockPos destPos = null;
    private ItemStack transferStack = ItemStack.EMPTY;
    private final List<BlockPos> cachedSurplusCandidates = new ArrayList<>();
    private final List<BlockPos> rebuildingSurplusCandidates = new ArrayList<>();
    private int surplusCandidateCursor = 0;
    private int pairingRebuildCursor = 0;
    private int cachedPairingCount = -1;
    private boolean candidateCacheStale = true;
    private long nextCandidateCacheRefreshTick = 0L;
    private SurplusScanMetrics lastSurplusScanMetrics = SurplusScanMetrics.empty();
    private final Deque<TransferLeg> bootstrapTransferQueue = new ArrayDeque<>();
    private int bootstrapDiscoveryRuns = 0;

    public QuartermasterGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        this.jobPos = jobPos;
        this.chestPos = chestPos;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    /**
     * Registers this QM's chest as the village anchor. Call after construction when the
     * world is available (e.g. on first canStart() or from the behavior's onChestPaired callback).
     */
    public void registerAnchor(ServerWorld world) {
        VillageAnchorState.get(world.getServer()).register(world, chestPos);
    }

    /**
     * Unregisters this QM's chest anchor. Call when the QM dies or loses pairing.
     */
    public void unregisterAnchor(ServerWorld world) {
        VillageAnchorState.get(world.getServer()).unregister(world, chestPos);
    }

    /**
     * Registers a Quartermaster as active in the runtime registry.
     * Called when the goal is installed.
     */
    public static void registerActiveQuartermaster(ServerWorld world, BlockPos anchorPos, UUID villagerId) {
        Map<BlockPos, Set<UUID>> byAnchor = ACTIVE_QM_BY_WORLD_ANCHOR.computeIfAbsent(world.getRegistryKey(), k -> new HashMap<>());
        byAnchor.computeIfAbsent(anchorPos.toImmutable(), k -> new HashSet<>()).add(villagerId);
    }

    /**
     * Unregisters a Quartermaster from the runtime registry.
     * Called when the goal is removed.
     */
    public static void unregisterActiveQuartermaster(ServerWorld world, BlockPos anchorPos, UUID villagerId) {
        Map<BlockPos, Set<UUID>> byAnchor = ACTIVE_QM_BY_WORLD_ANCHOR.get(world.getRegistryKey());
        if (byAnchor == null) return;
        Set<UUID> ids = byAnchor.get(anchorPos);
        if (ids == null) return;
        ids.remove(villagerId);
        if (ids.isEmpty()) {
            byAnchor.remove(anchorPos);
        }
        if (byAnchor.isEmpty()) {
            ACTIVE_QM_BY_WORLD_ANCHOR.remove(world.getRegistryKey());
        }
    }

    public static void clearBootstrapState(ServerWorld world, UUID villagerId) {
        BOOTSTRAP_COMPLETE_BY_QM.remove(new QmBootstrapKey(world.getRegistryKey(), villagerId));
    }

    /**
     * Signals that prerequisites should be revalidated immediately on the next goal evaluation.
     * Used by known invalidation paths such as chest listener updates.
     */
    public void requestImmediatePrerequisiteRevalidation() {
        forceStructuralRevalidation = true;
        nextStructuralCheckTick = 0L;
    }

    private void ensureAnchorUnregistered(ServerWorld world) {
        if (anchorRegistered) {
            unregisterAnchor(world);
            anchorRegistered = false;
        }
    }

    private double getScanRange() {
        int configured = GuardVillagersConfig.quartermasterScanRange;
        return configured > 0 ? configured : DEFAULT_SCAN_RANGE;
    }

    // -------------------------------------------------------------------------
    // Goal lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) return false;
        if (!passesFastEntityChecks(world)) {
            ensureAnchorUnregistered(world);
            return false;
        }
        if (!validateAndSyncPrerequisites(world, false)) return false;
        if (!anchorRegistered) {
            registerAnchor(world);
            anchorRegistered = true;
        }
        if (world.getTime() < nextCheckTick) return false;

        nextCheckTick = world.getTime() + CHECK_INTERVAL_TICKS;
        return tryPlanTransfer(world);
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.IDLE && stage != Stage.DONE && villager.isAlive() && !villager.isRemoved();
    }

    @Override
    public void start() {
        stage = Stage.MOVE_TO_SOURCE;
    }

    @Override
    public void stop() {
        if (villager.getWorld() instanceof ServerWorld world && (!villager.isAlive() || villager.isRemoved())) {
            ensureAnchorUnregistered(world);
        }
        villager.getNavigation().stop();
        stage = Stage.IDLE;
        sourcePos = null;
        destPos = null;
        transferStack = ItemStack.EMPTY;
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }
        if (!passesFastEntityChecks(world)) {
            ensureAnchorUnregistered(world);
            stage = Stage.DONE;
            villager.getNavigation().stop();
            return;
        }
        if (!validateAndSyncPrerequisites(world, stage == Stage.TAKE_FROM_SOURCE || stage == Stage.INSERT_TO_DEST)) {
            stage = Stage.DONE;
            villager.getNavigation().stop();
            return;
        }

        switch (stage) {
            case MOVE_TO_SOURCE -> {
                if (sourcePos == null) { stage = Stage.DONE; return; }
                if (isNear(sourcePos)) {
                    stage = Stage.TAKE_FROM_SOURCE;
                } else {
                    moveTo(sourcePos);
                }
            }
            case TAKE_FROM_SOURCE -> {
                if (!takeFromInventory(world, sourcePos)) {
                    stage = Stage.DONE;
                    return;
                }
                stage = Stage.MOVE_TO_DEST;
            }
            case MOVE_TO_DEST -> {
                if (destPos == null) { stage = Stage.DONE; return; }
                if (isNear(destPos)) {
                    stage = Stage.INSERT_TO_DEST;
                } else {
                    moveTo(destPos);
                }
            }
            case INSERT_TO_DEST -> {
                insertToInventory(world, destPos);
                stage = Stage.DONE;
            }
            case DONE -> stage = Stage.IDLE;
            default -> {}
        }
    }

    private boolean passesFastEntityChecks(ServerWorld world) {
        return villager.isAlive()
                && !villager.isRemoved()
                && villager.getVillagerData().getProfession() == VillagerProfession.LIBRARIAN;
    }

    private boolean validateAndSyncPrerequisites(ServerWorld world, boolean forceNow) {
        long worldTime = world.getTime();
        if (!forceNow
                && !forceStructuralRevalidation
                && worldTime < nextStructuralCheckTick
                && prerequisitesValid) {
            return true;
        }
        QuartermasterPrerequisiteHelper.Result result =
                QuartermasterPrerequisiteHelper.validate(world, villager, jobPos, chestPos);
        forceStructuralRevalidation = false;
        nextStructuralCheckTick = worldTime + STRUCTURAL_CHECK_INTERVAL_TICKS;
        if (result.valid()) {
            demoted = false;
            prerequisitesValid = true;
            cachedSecondChestPos = result.secondChestPos();
            return true;
        }

        ensureAnchorUnregistered(world);
        BlockPos lastKnownSecondChestPos = cachedSecondChestPos;
        prerequisitesValid = false;
        cachedSecondChestPos = null;
        forceStructuralRevalidation = true;
        if (!demoted) {
            String secondChestText = Optional.ofNullable(lastKnownSecondChestPos)
                    .map(BlockPos::toShortString)
                    .orElse("none");
            LOGGER.info("Librarian {} demoted from Quartermaster (chest={} second_chest={} job_site={})",
                    villager.getUuidAsString(),
                    chestPos.toShortString(),
                    secondChestText,
                    jobPos.toShortString());
            demoted = true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Transfer planning
    // -------------------------------------------------------------------------

    private boolean tryPlanTransfer(ServerWorld world) {
        if (tryPlanDailyReclaimTransferIfDue(world)) {
            return true;
        }

        BlockPos bellChestPos = resolveBellChestPos(world);
        if (bellChestPos != null) {
            if (!isBootstrapComplete(world)) {
                bootstrapTransferQueue.clear();
                queueBootstrapTransferLegs(world, bellChestPos);
                setBootstrapComplete(world);
            }
            if (!bootstrapTransferQueue.isEmpty()) {
                TransferLeg leg = bootstrapTransferQueue.pollFirst();
                sourcePos = leg.sourcePos();
                destPos = leg.destPos();
                transferStack = leg.transferStack().copy();
                return true;
            }
        }

        // Priority 1: mason is building (low on stone) → top up from bell chest
        Optional<MasonGuardEntity> masonNeedingStone = findMasonNeedingStone(world);
        if (masonNeedingStone.isPresent() && bellChestPos != null) {
            int stoneInBell = countItem(world, bellChestPos, Items.COBBLESTONE);
            if (stoneInBell > 0) {
                sourcePos = bellChestPos;
                destPos = masonNeedingStone.get().getPairedChestPos();
                transferStack = new ItemStack(Items.COBBLESTONE, Math.min(HAUL_AMOUNT, stoneInBell));
                LOGGER.debug("QM {}: hauling stone from bell chest to mason {}", villager.getUuidAsString(), destPos.toShortString());
                return destPos != null;
            }
        }

        // Priority 2a: lumberjack crafting (low on planks) → top up from bell chest.
        // Use any plank type (tag-based), pick the most abundant stack in the bell chest
        // so we don't artificially lock onto oak when e.g. birch is available.
        Optional<LumberjackGuardEntity> lumberjackNeedingPlanks = findLumberjackNeedingPlanks(world);
        if (lumberjackNeedingPlanks.isPresent() && bellChestPos != null) {
            ItemStack bestPlanks = findBestTagItem(world, bellChestPos, ItemTags.PLANKS);
            if (!bestPlanks.isEmpty()) {
                sourcePos = bellChestPos;
                destPos = lumberjackNeedingPlanks.get().getPairedChestPos();
                transferStack = bestPlanks.copyWithCount(Math.min(HAUL_AMOUNT, bestPlanks.getCount()));
                LOGGER.debug("QM {}: hauling {} planks from bell chest to lumberjack {}", villager.getUuidAsString(), bestPlanks.getItem(), destPos.toShortString());
                return destPos != null;
            }
        }

        // Priority 2b: weaponsmith low on planks → top up from bell chest (for wood weapon crafting).
        Optional<BlockPos> weaponsmithChestNeedingPlanks = findWeaponsmithChestNeedingPlanks(world);
        if (weaponsmithChestNeedingPlanks.isPresent() && bellChestPos != null) {
            ItemStack bestPlanks = findBestTagItem(world, bellChestPos, ItemTags.PLANKS);
            if (!bestPlanks.isEmpty()) {
                sourcePos = bellChestPos;
                destPos = weaponsmithChestNeedingPlanks.get();
                transferStack = bestPlanks.copyWithCount(Math.min(HAUL_AMOUNT, bestPlanks.getCount()));
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("QM {}: hauling {} planks from bell chest to weaponsmith at {}",
                            villager.getUuidAsString(), bestPlanks.getItem(), destPos.toShortString());
                }
                return true;
            } else {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("QM {}: weaponsmith at {} needs planks but bell chest at {} has none",
                            villager.getUuidAsString(), weaponsmithChestNeedingPlanks.get().toShortString(),
                            bellChestPos.toShortString());
                }
            }
        }

        // Priority 2c: lumberjack needs cobblestone for furnace crafting.
        // Deliver exactly 8 cobblestone so the lumberjack can craft a furnace for charcoal production.
        // Skip if a furnace already exists near the lumberjack's job site.
        Optional<LumberjackFurnaceStoneNeed> lumberjackNeedingFurnaceStone = findLumberjackNeedingFurnaceStone(world);
        if (lumberjackNeedingFurnaceStone.isPresent()) {
            LumberjackFurnaceStoneNeed need = lumberjackNeedingFurnaceStone.get();
            // Prefer QM stock first; fall back to mason chest using reserve-safe extraction limits.
            BlockPos stoneSource = countItem(world, chestPos, Items.COBBLESTONE) >= LUMBERJACK_FURNACE_STONE_AMOUNT ? chestPos : null;
            if (stoneSource == null) {
                stoneSource = findMasonChestWithCobblestone(world, LUMBERJACK_FURNACE_STONE_AMOUNT);
            }
            if (stoneSource != null) {
                int available = countItem(world, stoneSource, Items.COBBLESTONE);
                int toDeliver = stoneSource.equals(chestPos)
                        ? Math.min(LUMBERJACK_FURNACE_STONE_AMOUNT, available)
                        : Math.min(LUMBERJACK_FURNACE_STONE_AMOUNT,
                        computeMasonCobblestoneExtractionCount(available, MASON_COBBLESTONE_RESERVE));
                if (toDeliver <= 0) {
                    return false;
                }
                sourcePos = stoneSource;
                destPos = need.chestPos();
                transferStack = new ItemStack(Items.COBBLESTONE, toDeliver);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("QM {}: hauling {} cobblestone from {} to lumberjack {} for furnace crafting",
                            villager.getUuidAsString(), toDeliver, stoneSource.toShortString(), destPos.toShortString());
                }
                return true;
            }
        }

        // Priority 3: bell chest is low → find any profession chest with surplus and haul to bell chest.
        // IMPORTANT: only haul whitelisted bulk materials (logs, planks, wool, cobble, wheat, coal, etc.)
        // to avoid draining specialist profession chests of their unique trade goods (arrows, potions,
        // enchanted books, iron gear, fish, maps, meat, etc.). EC-NEW-EC-8 resolution.
        if (bellChestPos != null) {
            int bellTotal = countAllItems(world, bellChestPos);
            if (bellTotal < BELL_CHEST_LOW_THRESHOLD) {
                Optional<BlockPos> surplusChest = findSurplusChest(world, bellChestPos);
                if (surplusChest.isPresent()) {
                    ItemStack whitelistedItem = findTopWhitelistedItem(world, surplusChest.get());
                    if (!whitelistedItem.isEmpty()) {
                        sourcePos = surplusChest.get();
                        destPos = bellChestPos;
                        transferStack = whitelistedItem.copyWithCount(Math.min(HAUL_AMOUNT, whitelistedItem.getCount()));
                        LOGGER.debug("QM {}: hauling {} surplus from {} to bell chest", villager.getUuidAsString(), whitelistedItem.getItem(), sourcePos.toShortString());
                        return true;
                    }
                }
            }
        }

        return false;
    }

    boolean tryPlanDailyReclaimTransferIfDue(ServerWorld world) {
        long worldTime = world.getTime();
        if (worldTime < nextDailyReclaimTick) {
            return false;
        }
        nextDailyReclaimTick = worldTime + DAILY_RECLAIM_INTERVAL_TICKS;
        Optional<TransferLeg> plannedLeg = planDailyReclaimTransfer(world);
        if (plannedLeg.isEmpty()) {
            return false;
        }
        TransferLeg leg = plannedLeg.get();
        sourcePos = leg.sourcePos();
        destPos = leg.destPos();
        transferStack = leg.transferStack().copy();
        return true;
    }

    private Optional<TransferLeg> planDailyReclaimTransfer(ServerWorld world) {
        List<ProfessionChestSnapshot> snapshots = buildProfessionChestSnapshots(world);
        Optional<TransferLeg> masonCompletedLeg = planMasonCompletedMaterialReclaim(snapshots);
        if (masonCompletedLeg.isPresent()) {
            return masonCompletedLeg;
        }
        TransferLeg bestLeg = null;
        int bestCount = 0;
        for (ProfessionChestSnapshot snapshot : snapshots) {
            ProfessionReclaimPolicy policy = PROFESSION_RECLAIM_POLICIES.getOrDefault(snapshot.profession(), ProfessionReclaimPolicy.none());
            for (int i = 0; i < snapshot.inventory().size(); i++) {
                ItemStack stack = snapshot.inventory().getStack(i);
                if (stack.isEmpty() || !policy.canReclaim(stack)) {
                    continue;
                }
                int totalItemCount = snapshot.totalByItem().getOrDefault(stack.getItem(), 0);
                int reclaimable = Math.max(0, totalItemCount - policy.reserveCount(stack.getItem()));
                if (reclaimable <= 0) {
                    continue;
                }
                int toMove;
                if (snapshot.profession() == VillagerProfession.MASON && stack.isOf(Items.COBBLESTONE)) {
                    toMove = Math.min(stack.getCount(),
                            computeMasonCobblestoneExtractionCount(totalItemCount, MASON_COBBLESTONE_RESERVE));
                } else {
                    toMove = Math.min(Math.min(reclaimable, stack.getCount()), HAUL_AMOUNT);
                }
                if (toMove > bestCount) {
                    bestCount = toMove;
                    bestLeg = new TransferLeg(snapshot.chestPos(), chestPos, stack.copyWithCount(toMove));
                }
            }
        }
        return Optional.ofNullable(bestLeg);
    }

    private Optional<TransferLeg> planMasonCompletedMaterialReclaim(List<ProfessionChestSnapshot> snapshots) {
        TransferLeg bestLeg = null;
        int bestCount = 0;
        for (ProfessionChestSnapshot snapshot : snapshots) {
            if (snapshot.profession() != VillagerProfession.MASON) {
                continue;
            }
            for (int i = 0; i < snapshot.inventory().size(); i++) {
                ItemStack stack = snapshot.inventory().getStack(i);
                if (stack.isEmpty()) {
                    continue;
                }
                if (!stack.isIn(ItemTags.STAIRS) && !stack.isIn(ItemTags.SLABS)) {
                    continue;
                }
                int toMove = Math.min(64, stack.getCount());
                if (toMove > bestCount) {
                    bestCount = toMove;
                    bestLeg = new TransferLeg(snapshot.chestPos(), chestPos, stack.copyWithCount(toMove));
                }
            }
        }
        return Optional.ofNullable(bestLeg);
    }

    private List<ProfessionChestSnapshot> buildProfessionChestSnapshots(ServerWorld world) {
        List<ProfessionChestSnapshot> snapshots = new ArrayList<>();
        for (JobBlockPairingHelper.CachedVillagerChestPairing pairing : JobBlockPairingHelper.getCachedVillagerChestPairings(world)) {
            BlockPos pairedChestPos = pairing.chestPos();
            if (pairedChestPos == null || pairing.villagerUuid().equals(villager.getUuid()) || pairedChestPos.equals(chestPos)) {
                continue;
            }
            Optional<Inventory> inventory = getInventory(world, pairedChestPos);
            if (inventory.isEmpty()) {
                continue;
            }
            Map<net.minecraft.item.Item, Integer> totalsByItem = new HashMap<>();
            for (int i = 0; i < inventory.get().size(); i++) {
                ItemStack stack = inventory.get().getStack(i);
                if (stack.isEmpty()) continue;
                totalsByItem.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
            snapshots.add(new ProfessionChestSnapshot(pairing.profession(), pairedChestPos.toImmutable(), inventory.get(), totalsByItem));
        }
        return snapshots;
    }

    // -------------------------------------------------------------------------
    // Scouts
    // -------------------------------------------------------------------------

    private Optional<MasonGuardEntity> findMasonNeedingStone(ServerWorld world) {
        Box box = new Box(jobPos).expand(getScanRange());
        return world.getEntitiesByClass(MasonGuardEntity.class, box,
                mason -> mason.isAlive()
                        && mason.getPairedChestPos() != null
                        // Deliver stone to ANY mason with low cobblestone:
                        // - wall builders (getWallSegments non-empty) need it for block placement
                        // - all other masons need it for stonecutting crafting recipes
                        && countItem(world, mason.getPairedChestPos(), Items.COBBLESTONE) < MASON_STONE_THRESHOLD
        ).stream().findFirst();
    }

    private Optional<LumberjackGuardEntity> findLumberjackNeedingPlanks(ServerWorld world) {
        Box box = new Box(jobPos).expand(getScanRange());
        return world.getEntitiesByClass(LumberjackGuardEntity.class, box,
                lj -> lj.isAlive()
                        && lj.getPairedChestPos() != null
                        && countTagItems(world, lj.getPairedChestPos(), ItemTags.PLANKS) < LUMBERJACK_PLANK_THRESHOLD
        ).stream().findFirst();
    }

    /**
     * Returns the chest position of any weaponsmith that is low on planks and close enough
     * to our job site to warrant a delivery trip. Uses WeaponsmithBehavior.getPairedChestPositions()
     * so we don't need to scan entities — the chest positions are tracked by the behavior.
     */
    /**
     * Finds a lumberjack that needs cobblestone for furnace crafting:
     * - Paired chest has fewer than 8 cobblestone
     * - No furnace or blast furnace exists within FURNACE_CHECK_RADIUS of the lumberjack's job pos
     */
    private Optional<LumberjackFurnaceStoneNeed> findLumberjackNeedingFurnaceStone(ServerWorld world) {
        Box box = new Box(jobPos).expand(getScanRange());
        for (LumberjackGuardEntity lj : world.getEntitiesByClass(LumberjackGuardEntity.class, box, LumberjackGuardEntity::isAlive)) {
            BlockPos ljChest = lj.getPairedChestPos();
            BlockPos ljJob = lj.getPairedJobPos();
            if (ljChest == null || ljJob == null) continue;
            int stoneInChest = countItem(world, ljChest, Items.COBBLESTONE);
            if (stoneInChest >= LUMBERJACK_FURNACE_STONE_AMOUNT) continue;
            // Check if a furnace already exists near the lumberjack's job site
            if (hasFurnaceNear(world, ljJob)) continue;
            return Optional.of(new LumberjackFurnaceStoneNeed(lj, ljChest));
        }
        return Optional.empty();
    }

    private void queueBootstrapTransferLegs(ServerWorld world, BlockPos bellChestPos) {
        bootstrapDiscoveryRuns++;
        List<BlockPos> discovered = discoverBootstrapSourceChests(world, bellChestPos);
        for (BlockPos source : discovered) {
            if (source.equals(bellChestPos)) continue;
            ItemStack stack = findTopTransferableItem(world, source);
            if (stack.isEmpty()) continue;
            bootstrapTransferQueue.addLast(new TransferLeg(
                    source,
                    bellChestPos,
                    stack.copyWithCount(Math.min(HAUL_AMOUNT, stack.getCount()))
            ));
        }
    }

    private List<BlockPos> discoverBootstrapSourceChests(ServerWorld world, BlockPos bellChestPos) {
        Set<BlockPos> pairedChests = new HashSet<>();
        for (JobBlockPairingHelper.CachedVillagerChestPairing pairing : JobBlockPairingHelper.getCachedVillagerChestPairings(world)) {
            BlockPos pairingChest = pairing.chestPos();
            if (pairingChest == null) continue;
            pairedChests.add(pairingChest.toImmutable());
            findDoubleChestOtherHalf(world, pairingChest).ifPresent(pairedChests::add);
        }

        Set<BlockPos> excluded = new HashSet<>(pairedChests);
        excluded.add(chestPos.toImmutable());
        findDoubleChestOtherHalf(world, chestPos).ifPresent(excluded::add);

        Set<BlockPos> deduplicated = new HashSet<>();
        List<BlockPos> discovered = new ArrayList<>();
        int range = (int) Math.ceil(getScanRange());
        for (BlockPos scanPos : BlockPos.iterate(bellChestPos.add(-range, -range, -range), bellChestPos.add(range, range, range))) {
            if (!bellChestPos.isWithinDistance(scanPos, getScanRange())) continue;
            BlockState state = world.getBlockState(scanPos);
            if (!(state.getBlock() instanceof ChestBlock)) continue;
            BlockPos candidate = canonicalChestPos(world, scanPos.toImmutable());
            if (excluded.contains(candidate)) continue;
            if (!isNaturalVillageChest(world, candidate)) continue;
            if (deduplicated.add(candidate)) {
                discovered.add(candidate);
            }
        }
        return discovered;
    }

    private BlockPos canonicalChestPos(ServerWorld world, BlockPos chestCandidate) {
        Optional<BlockPos> other = findDoubleChestOtherHalf(world, chestCandidate);
        if (other.isEmpty()) return chestCandidate;
        return chestCandidate.asLong() <= other.get().asLong() ? chestCandidate : other.get();
    }

    private boolean isNaturalVillageChest(ServerWorld world, BlockPos chestCandidate) {
        int r = NATURAL_VILLAGE_POI_SCAN_RADIUS;
        boolean nearBell = false;
        boolean nearBedOrJobSite = false;
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    cursor.set(chestCandidate.getX() + dx, chestCandidate.getY() + dy, chestCandidate.getZ() + dz);
                    if (!chestCandidate.isWithinDistance(cursor, r)) continue;
                    BlockState nearbyState = world.getBlockState(cursor);
                    if (nearbyState.isOf(Blocks.BELL)) {
                        nearBell = true;
                    }
                    if (nearbyState.getBlock() instanceof BedBlock || NATURAL_VILLAGE_JOB_SITE_BLOCKS.contains(nearbyState.getBlock())) {
                        nearBedOrJobSite = true;
                    }
                    if (nearBell && nearBedOrJobSite) return true;
                }
            }
        }
        return false;
    }

    /** Returns true if a furnace or blast furnace exists within FURNACE_CHECK_RADIUS of center. */
    private boolean hasFurnaceNear(ServerWorld world, BlockPos center) {
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        int r = FURNACE_CHECK_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    mutable.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState bs = world.getBlockState(mutable);
                    if (bs.isOf(Blocks.FURNACE) || bs.isOf(Blocks.BLAST_FURNACE)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Finds the nearest mason paired chest that has at least minAmount cobblestone. */
    private BlockPos findMasonChestWithCobblestone(ServerWorld world, int minAmount) {
        Box box = new Box(jobPos).expand(getScanRange());
        for (MasonGuardEntity mason : world.getEntitiesByClass(MasonGuardEntity.class, box, MasonGuardEntity::isAlive)) {
            BlockPos mc = mason.getPairedChestPos();
            if (mc != null) {
                int available = countItem(world, mc, Items.COBBLESTONE);
                int extractable = computeMasonCobblestoneExtractionCount(available, MASON_COBBLESTONE_RESERVE);
                if (extractable >= minAmount) {
                    return mc;
                }
            }
        }
        return null;
    }

    static int computeMasonCobblestoneExtractionCount(int availableCobblestone, int reserve) {
        if (availableCobblestone <= 0) {
            return 0;
        }
        int reclaimableByReserve = Math.max(0, availableCobblestone - Math.max(0, reserve));
        if (reclaimableByReserve <= 0) {
            return 0;
        }
        int reclaimableWithoutDrain = Math.max(0, availableCobblestone - 1);
        int capped = Math.min(64, Math.min(reclaimableByReserve, reclaimableWithoutDrain));
        if (capped >= 64) {
            return 64;
        }
        if (capped >= 32) {
            return 32;
        }
        if (capped >= 16) {
            return 16;
        }
        return 0;
    }

    private record LumberjackFurnaceStoneNeed(LumberjackGuardEntity lumberjack, BlockPos chestPos) {}

    private Optional<BlockPos> findWeaponsmithChestNeedingPlanks(ServerWorld world) {
        for (BlockPos chestPos : WeaponsmithBehavior.getPairedChestPositions()) {
            if (chestPos.isWithinDistance(jobPos, getScanRange())
                    && countTagItems(world, chestPos, ItemTags.PLANKS) < WEAPONSMITH_PLANK_THRESHOLD) {
                return Optional.of(chestPos);
            }
        }
        return Optional.empty();
    }

    private Optional<BlockPos> findSurplusChest(ServerWorld world, BlockPos bellChestPos) {
        SurplusScanBudget budget = new SurplusScanBudget(
                MAX_SURPLUS_CANDIDATE_CHESTS_PER_CYCLE,
                MAX_SURPLUS_INVENTORIES_PER_CYCLE);

        rebuildSurplusCandidateCacheIncrementally(world);

        // Find any chest near job-site villagers with more items than threshold.
        // Must exclude:
        //   - bellChestPos  (we're trying to fill it, not drain it further)
        //   - chestPos      (the QM's own transit buffer — draining it causes haul loops)
        //   - mason paired chests  (draining them undoes Priority 1 stone hauls)
        //   - lumberjack paired chests (draining them undoes Priority 2 plank hauls)
        Box box = new Box(jobPos).expand(getScanRange());

        // Build the protected set of specialist chests we must never drain.
        Set<BlockPos> protectedChests = new HashSet<>();
        if (bellChestPos != null) {
            protectedChests.add(bellChestPos);
            // If the bell chest is a double-chest, protect both halves. Otherwise the QM could
            // treat the other half as a surplus source and haul items from it into bellChestPos,
            // which both resolve to the same DoubleInventory — a no-op loop.
            findDoubleChestOtherHalf(world, bellChestPos).ifPresent(protectedChests::add);
        }
        protectedChests.add(chestPos);
        // Also protect the QM's own double-chest other half if present.
        findDoubleChestOtherHalf(world, chestPos).ifPresent(protectedChests::add);
        for (MasonGuardEntity mason : world.getEntitiesByClass(MasonGuardEntity.class, box, MasonGuardEntity::isAlive)) {
            if (mason.getPairedChestPos() != null) {
                protectedChests.add(mason.getPairedChestPos());
                findDoubleChestOtherHalf(world, mason.getPairedChestPos()).ifPresent(protectedChests::add);
            }
        }
        for (LumberjackGuardEntity lj : world.getEntitiesByClass(LumberjackGuardEntity.class, box, LumberjackGuardEntity::isAlive)) {
            if (lj.getPairedChestPos() != null) {
                protectedChests.add(lj.getPairedChestPos());
                findDoubleChestOtherHalf(world, lj.getPairedChestPos()).ifPresent(protectedChests::add);
            }
        }
        Optional<BlockPos> fromCache = scanSurplusCandidatesWithBudget(world, protectedChests, budget);
        if (fromCache.isPresent()) {
            lastSurplusScanMetrics = budget.toMetrics(cachedSurplusCandidates.size(), candidateCacheStale);
            return fromCache;
        }

        // Fallback discovery: only used while cache is empty or stale, and still budget-limited.
        if (cachedSurplusCandidates.isEmpty() || candidateCacheStale) {
            Optional<BlockPos> fallback = scanFallbackPairingsWithBudget(world, protectedChests, budget);
            if (fallback.isPresent()) {
                lastSurplusScanMetrics = budget.toMetrics(cachedSurplusCandidates.size(), candidateCacheStale);
                return fallback;
            }
        }
        lastSurplusScanMetrics = budget.toMetrics(cachedSurplusCandidates.size(), candidateCacheStale);
        return Optional.empty();
    }

    private void rebuildSurplusCandidateCacheIncrementally(ServerWorld world) {
        List<JobBlockPairingHelper.CachedVillagerChestPairing> pairings = JobBlockPairingHelper.getCachedVillagerChestPairings(world);
        long now = world.getTime();
        boolean refreshDue = now >= nextCandidateCacheRefreshTick;
        boolean pairingCountChanged = cachedPairingCount != pairings.size();
        if (refreshDue || pairingCountChanged) {
            candidateCacheStale = true;
        }
        if (!candidateCacheStale) return;

        UUID quartermasterUuid = villager.getUuid();
        int scanned = 0;
        while (scanned < MAX_PAIRINGS_SCANNED_PER_CYCLE && pairingRebuildCursor < pairings.size()) {
            JobBlockPairingHelper.CachedVillagerChestPairing pairing = pairings.get(pairingRebuildCursor++);
            scanned++;
            if (pairing.villagerUuid().equals(quartermasterUuid)) continue;
            if (!pairing.jobPos().isWithinDistance(jobPos, getScanRange())) continue;
            if (pairing.profession() == VillagerProfession.SHEPHERD) continue;
            BlockPos candidate = pairing.chestPos();
            if (candidate != null) {
                rebuildingSurplusCandidates.add(candidate.toImmutable());
            }
        }
        if (pairingRebuildCursor >= pairings.size()) {
            cachedSurplusCandidates.clear();
            cachedSurplusCandidates.addAll(rebuildingSurplusCandidates);
            rebuildingSurplusCandidates.clear();
            pairingRebuildCursor = 0;
            cachedPairingCount = pairings.size();
            candidateCacheStale = false;
            nextCandidateCacheRefreshTick = now + CANDIDATE_CACHE_REFRESH_INTERVAL_TICKS;
            if (surplusCandidateCursor >= cachedSurplusCandidates.size()) {
                surplusCandidateCursor = 0;
            }
        }
    }

    private Optional<BlockPos> scanSurplusCandidatesWithBudget(ServerWorld world, Set<BlockPos> protectedChests, SurplusScanBudget budget) {
        if (cachedSurplusCandidates.isEmpty()) return Optional.empty();

        int scanStart = Math.floorMod(surplusCandidateCursor, cachedSurplusCandidates.size());
        while (budget.canCheckAnotherCandidate()) {
            BlockPos candidate = cachedSurplusCandidates.get(surplusCandidateCursor);
            surplusCandidateCursor = (surplusCandidateCursor + 1) % cachedSurplusCandidates.size();

            budget.recordCandidateCheck();
            if (protectedChests.contains(candidate)) continue;
            if (!budget.canInspectAnotherInventory()) break;

            budget.recordInventoryInspection();
            if (countWhitelistedItems(world, candidate) > BELL_CHEST_LOW_THRESHOLD * 2) {
                return Optional.of(candidate);
            }
            if (surplusCandidateCursor == scanStart) break;
        }
        return Optional.empty();
    }

    private Optional<BlockPos> scanFallbackPairingsWithBudget(ServerWorld world, Set<BlockPos> protectedChests, SurplusScanBudget budget) {
        List<JobBlockPairingHelper.CachedVillagerChestPairing> pairings = JobBlockPairingHelper.getCachedVillagerChestPairings(world);
        if (pairings.isEmpty()) return Optional.empty();

        int scanStart = Math.floorMod(pairingRebuildCursor, pairings.size());
        while (budget.canCheckAnotherCandidate()) {
            JobBlockPairingHelper.CachedVillagerChestPairing pairing = pairings.get(pairingRebuildCursor);
            pairingRebuildCursor = (pairingRebuildCursor + 1) % pairings.size();

            if (pairing.villagerUuid().equals(villager.getUuid())) {
                if (pairingRebuildCursor == scanStart) break;
                continue;
            }
            if (!pairing.jobPos().isWithinDistance(jobPos, getScanRange())) {
                if (pairingRebuildCursor == scanStart) break;
                continue;
            }
            if (pairing.profession() == VillagerProfession.SHEPHERD) {
                if (pairingRebuildCursor == scanStart) break;
                continue;
            }

            BlockPos candidate = pairing.chestPos();
            if (candidate == null) {
                if (pairingRebuildCursor == scanStart) break;
                continue;
            }

            budget.recordCandidateCheck();
            if (protectedChests.contains(candidate)) {
                if (pairingRebuildCursor == scanStart) break;
                continue;
            }
            if (!budget.canInspectAnotherInventory()) break;

            budget.recordInventoryInspection();
            if (countWhitelistedItems(world, candidate) > BELL_CHEST_LOW_THRESHOLD * 2) {
                return Optional.of(candidate);
            }
            if (pairingRebuildCursor == scanStart) break;
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Inventory helpers
    // -------------------------------------------------------------------------

    private boolean takeFromInventory(ServerWorld world, BlockPos pos) {
        Optional<Inventory> inv = getInventory(world, pos);
        if (inv.isEmpty()) {
            requestImmediatePrerequisiteRevalidation();
            return false;
        }
        if (transferStack.isEmpty()) return false;
        Inventory inventory = inv.get();
        net.minecraft.item.Item item = transferStack.getItem();
        int needed = transferStack.getCount();
        int taken = 0;

        for (int i = 0; i < inventory.size() && taken < needed; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                int grab = Math.min(stack.getCount(), needed - taken);
                stack.decrement(grab);
                if (stack.isEmpty()) inventory.setStack(i, ItemStack.EMPTY);
                taken += grab;
            }
        }

        if (taken > 0) {
            transferStack = new ItemStack(item, taken);
            inventory.markDirty();
            return true;
        }
        return false;
    }

    private void insertToInventory(ServerWorld world, BlockPos pos) {
        Optional<Inventory> inv = getInventory(world, pos);
        if (inv.isEmpty()) {
            requestImmediatePrerequisiteRevalidation();
            return;
        }
        if (transferStack.isEmpty()) return;
        Inventory inventory = inv.get();
        ItemStack remaining = transferStack.copy();

        for (int i = 0; i < inventory.size() && !remaining.isEmpty(); i++) {
            ItemStack existing = inventory.getStack(i);
            if (existing.isEmpty()) {
                if (!inventory.isValid(i, remaining)) continue;
                int moved = Math.min(remaining.getCount(), remaining.getMaxCount());
                inventory.setStack(i, remaining.copyWithCount(moved));
                remaining.decrement(moved);
            } else if (ItemStack.areItemsAndComponentsEqual(existing, remaining)) {
                int space = existing.getMaxCount() - existing.getCount();
                if (space > 0) {
                    int moved = Math.min(space, remaining.getCount());
                    existing.increment(moved);
                    remaining.decrement(moved);
                }
            }
        }

        inventory.markDirty();
        // If dest chest was full, remaining items would be silently lost.
        // Drop them at the villager's feet so items are never destroyed.
        if (!remaining.isEmpty()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("QM {}: dest chest at {} full, dropping {} x {} at villager feet",
                        villager.getUuidAsString(), pos.toShortString(),
                        remaining.getCount(), remaining.getItem());
            }
            ItemEntity drop = new ItemEntity(
                    world, villager.getX(), villager.getY(), villager.getZ(), remaining.copy());
            drop.setPickupDelay(10);
            world.spawnEntity(drop);
        }
        transferStack = ItemStack.EMPTY;
        LOGGER.debug("QM {}: delivered to {}", villager.getUuidAsString(), pos.toShortString());
    }

    /** Counts all items matching a tag across all slots in the chest at {@code pos}. */
    private int countTagItems(ServerWorld world, BlockPos pos, net.minecraft.registry.tag.TagKey<net.minecraft.item.Item> tag) {
        Optional<Inventory> inv = getInventory(world, pos);
        if (inv.isEmpty()) return 0;
        int count = 0;
        for (int i = 0; i < inv.get().size(); i++) {
            ItemStack stack = inv.get().getStack(i);
            if (!stack.isEmpty() && stack.isIn(tag)) count += stack.getCount();
        }
        return count;
    }

    /**
     * Finds the largest single stack matching {@code tag} in the chest at {@code pos}.
     * Returns a copy, or {@link ItemStack#EMPTY} if none found.
     */
    private ItemStack findBestTagItem(ServerWorld world, BlockPos pos, net.minecraft.registry.tag.TagKey<net.minecraft.item.Item> tag) {
        Optional<Inventory> inv = getInventory(world, pos);
        if (inv.isEmpty()) return ItemStack.EMPTY;
        ItemStack best = ItemStack.EMPTY;
        for (int i = 0; i < inv.get().size(); i++) {
            ItemStack stack = inv.get().getStack(i);
            if (!stack.isEmpty() && stack.isIn(tag) && stack.getCount() > best.getCount()) {
                best = stack;
            }
        }
        return best.isEmpty() ? ItemStack.EMPTY : best.copy();
    }

    private int countItem(ServerWorld world, BlockPos pos, net.minecraft.item.Item item) {
        Optional<Inventory> inv = getInventory(world, pos);
        if (inv.isEmpty()) return 0;
        int count = 0;
        for (int i = 0; i < inv.get().size(); i++) {
            ItemStack stack = inv.get().getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }

    private int countAllItems(ServerWorld world, BlockPos pos) {
        Optional<Inventory> inv = getInventory(world, pos);
        if (inv.isEmpty()) return 0;
        int count = 0;
        for (int i = 0; i < inv.get().size(); i++) {
            count += inv.get().getStack(i).getCount();
        }
        return count;
    }

    /**
     * Counts only items that pass {@link #SURPLUS_HAUL_WHITELIST} in the chest at {@code pos}.
     * Used by {@link #findSurplusChest} to avoid treating specialist chests as "surplus" just
     * because they contain many arrows, potions, or other high-count trade goods.
     */
    private int countWhitelistedItems(ServerWorld world, BlockPos pos) {
        Optional<Inventory> inv = getInventory(world, pos);
        if (inv.isEmpty()) return 0;
        int count = 0;
        for (int i = 0; i < inv.get().size(); i++) {
            ItemStack stack = inv.get().getStack(i);
            if (!stack.isEmpty() && SURPLUS_HAUL_WHITELIST.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * Finds the largest single stack that passes {@link #SURPLUS_HAUL_WHITELIST} in the chest at
     * {@code pos}. Returns a copy, or {@link ItemStack#EMPTY} if no whitelisted material found.
     *
     * <p>Used by Priority-3 surplus haul so that only generic bulk materials (logs, planks, wool,
     * cobblestone, wheat, coal, etc.) are ever moved into the bell chest. Specialist trade goods
     * (arrows, potions, enchanted books, iron gear, fish, maps, meat) will never be touched.
     */
    private ItemStack findTopWhitelistedItem(ServerWorld world, BlockPos pos) {
        Optional<Inventory> inv = getInventory(world, pos);
        if (inv.isEmpty()) return ItemStack.EMPTY;
        ItemStack best = ItemStack.EMPTY;
        for (int i = 0; i < inv.get().size(); i++) {
            ItemStack stack = inv.get().getStack(i);
            if (!stack.isEmpty() && SURPLUS_HAUL_WHITELIST.test(stack) && stack.getCount() > best.getCount()) {
                best = stack;
            }
        }
        return best.isEmpty() ? ItemStack.EMPTY : best.copy();
    }

    private ItemStack findTopTransferableItem(ServerWorld world, BlockPos pos) {
        Optional<Inventory> inv = getInventory(world, pos);
        if (inv.isEmpty()) return ItemStack.EMPTY;
        ItemStack best = ItemStack.EMPTY;
        for (int i = 0; i < inv.get().size(); i++) {
            ItemStack stack = inv.get().getStack(i);
            if (!stack.isEmpty() && stack.getCount() > best.getCount()) {
                best = stack;
            }
        }
        return best.isEmpty() ? ItemStack.EMPTY : best.copy();
    }

    /**
     * If the chest at {@code pos} is one half of a double-chest, returns the position of the
     * other half. Returns empty for single chests or non-chest blocks.
     * Used to add both halves to protected sets so the QM never hauls within the same double-chest.
     */
    private Optional<BlockPos> findDoubleChestOtherHalf(ServerWorld world, BlockPos pos) {
        if (pos == null) return Optional.empty();
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) return Optional.empty();
        ChestType chestType = state.get(ChestBlock.CHEST_TYPE);
        if (chestType == ChestType.SINGLE) return Optional.empty();
        Direction facing = state.get(ChestBlock.FACING);
        Direction offsetDir = chestType == ChestType.LEFT
                ? facing.rotateYClockwise()
                : facing.rotateYCounterclockwise();
        BlockPos otherHalf = pos.offset(offsetDir).toImmutable();
        BlockState otherState = world.getBlockState(otherHalf);
        if (otherState.getBlock() instanceof ChestBlock) {
            return Optional.of(otherHalf);
        }
        return Optional.empty();
    }

    protected Optional<Inventory> getInventory(ServerWorld world, BlockPos pos) {
        if (pos == null) return Optional.empty();
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) return Optional.empty();
        // open=false: programmatic access must not trigger chest open-sound / lid animation.
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, pos, false));
    }

    private BlockPos resolveBellChestPos(ServerWorld world) {
        // The QM's own paired chest IS the village bank. No bell lookup needed.
        return chestPos;
    }

    // -------------------------------------------------------------------------
    // Static utility — used by ArmorerIronRoutingGoal to defer when QM is present
    // -------------------------------------------------------------------------

    /**
     * Returns true if any living Librarian-profession villager with an installed
     * QuartermasterGoal exists within {@code range} blocks of {@code anchor}.
     */
    public static boolean isAnyActive(ServerWorld world, BlockPos anchor, double range) {
        Set<BlockPos> anchors = VillageAnchorState.get(world.getServer()).getAllQmChests(world);
        if (anchors.isEmpty()) {
            return false;
        }

        double rangeSq = range * range;
        Map<BlockPos, Set<UUID>> byAnchor = ACTIVE_QM_BY_WORLD_ANCHOR.get(world.getRegistryKey());
        if (byAnchor == null || byAnchor.isEmpty()) {
            return false;
        }

        for (BlockPos anchorPos : anchors) {
            if (anchorPos.getSquaredDistance(anchor) > rangeSq) continue;
            Set<UUID> ids = byAnchor.get(anchorPos);
            if (ids == null || ids.isEmpty()) continue;

            Set<UUID> stale = new HashSet<>();
            for (UUID id : ids) {
                Entity entity = world.getEntity(id);
                if (entity instanceof VillagerEntity villager
                        && villager.isAlive()
                        && !villager.isRemoved()
                        && villager.getVillagerData().getProfession() == VillagerProfession.LIBRARIAN
                        && hasInstalledQuartermasterGoal(villager)) {
                    LOGGER.debug(
                            "Quartermaster presence check: true (anchor={} range={} librarian={})",
                            anchor.toShortString(),
                            range,
                            villager.getUuidAsString());
                    return true;
                }
                stale.add(id);
            }
            if (!stale.isEmpty()) {
                ids.removeAll(stale);
                if (ids.isEmpty()) {
                    byAnchor.remove(anchorPos);
                }
            }
        }

        if (byAnchor.isEmpty()) {
            ACTIVE_QM_BY_WORLD_ANCHOR.remove(world.getRegistryKey());
        }
        LOGGER.debug("Quartermaster presence check: false (anchor={} range={})", anchor.toShortString(), range);
        return false;
    }

    private static boolean hasInstalledQuartermasterGoal(VillagerEntity villager) {
        for (PrioritizedGoal prioritizedGoal : villager.goalSelector.getGoals()) {
            if (prioritizedGoal.getGoal() instanceof QuartermasterGoal) {
                return true;
            }
        }
        return false;
    }

    private void moveTo(BlockPos target) {
        villager.getNavigation().startMovingTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, MOVE_SPEED);
    }

    private boolean isNear(BlockPos target) {
        return villager.squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) <= REACH_SQ;
    }

    // -------------------------------------------------------------------------
    // Enums
    // -------------------------------------------------------------------------

    private enum Stage {
        IDLE,
        MOVE_TO_SOURCE,
        TAKE_FROM_SOURCE,
        MOVE_TO_DEST,
        INSERT_TO_DEST,
        DONE
    }

    static SurplusScanMetrics getLastSurplusScanMetricsForTest(QuartermasterGoal goal) {
        return goal.lastSurplusScanMetrics;
    }

    static final class SurplusScanMetrics {
        private final int candidatesChecked;
        private final int inventoriesInspected;
        private final int cachedCandidates;
        private final boolean cacheStale;

        private SurplusScanMetrics(int candidatesChecked, int inventoriesInspected, int cachedCandidates, boolean cacheStale) {
            this.candidatesChecked = candidatesChecked;
            this.inventoriesInspected = inventoriesInspected;
            this.cachedCandidates = cachedCandidates;
            this.cacheStale = cacheStale;
        }

        static SurplusScanMetrics empty() {
            return new SurplusScanMetrics(0, 0, 0, true);
        }

        int candidatesChecked() { return candidatesChecked; }
        int inventoriesInspected() { return inventoriesInspected; }
        int cachedCandidates() { return cachedCandidates; }
        boolean cacheStale() { return cacheStale; }
    }

    static int getBootstrapDiscoveryRunsForTest(QuartermasterGoal goal) {
        return goal.bootstrapDiscoveryRuns;
    }

    static PlannedTransfer getPlannedTransferForTest(QuartermasterGoal goal) {
        return new PlannedTransfer(goal.sourcePos, goal.destPos, goal.transferStack.copy());
    }

    private boolean isBootstrapComplete(ServerWorld world) {
        return BOOTSTRAP_COMPLETE_BY_QM.getOrDefault(new QmBootstrapKey(world.getRegistryKey(), villager.getUuid()), false);
    }

    private void setBootstrapComplete(ServerWorld world) {
        BOOTSTRAP_COMPLETE_BY_QM.put(new QmBootstrapKey(world.getRegistryKey(), villager.getUuid()), true);
    }

    record PlannedTransfer(BlockPos sourcePos, BlockPos destPos, ItemStack transferStack) {}
    private record ProfessionChestSnapshot(
            VillagerProfession profession,
            BlockPos chestPos,
            Inventory inventory,
            Map<net.minecraft.item.Item, Integer> totalByItem
    ) {}

    private record ProfessionReclaimPolicy(Predicate<ItemStack> reclaimable, Map<net.minecraft.item.Item, Integer> reserveByItem) {
        static ProfessionReclaimPolicy of(Predicate<ItemStack> reclaimable, Map<net.minecraft.item.Item, Integer> reserveByItem) {
            return new ProfessionReclaimPolicy(reclaimable, reserveByItem);
        }

        static ProfessionReclaimPolicy none() {
            return new ProfessionReclaimPolicy(stack -> false, Map.of());
        }

        boolean canReclaim(ItemStack stack) {
            return reclaimable.test(stack);
        }

        int reserveCount(net.minecraft.item.Item item) {
            return reserveByItem.getOrDefault(item, 0);
        }
    }

    private record TransferLeg(BlockPos sourcePos, BlockPos destPos, ItemStack transferStack) {}
    private record QmBootstrapKey(net.minecraft.registry.RegistryKey<net.minecraft.world.World> worldKey, UUID villagerUuid) {}

    private static final class SurplusScanBudget {
        private final int maxCandidates;
        private final int maxInventories;
        private int candidatesChecked;
        private int inventoriesInspected;

        private SurplusScanBudget(int maxCandidates, int maxInventories) {
            this.maxCandidates = maxCandidates;
            this.maxInventories = maxInventories;
        }

        boolean canCheckAnotherCandidate() { return candidatesChecked < maxCandidates; }
        boolean canInspectAnotherInventory() { return inventoriesInspected < maxInventories; }
        void recordCandidateCheck() { candidatesChecked++; }
        void recordInventoryInspection() { inventoriesInspected++; }

        SurplusScanMetrics toMetrics(int cachedCandidates, boolean cacheStale) {
            return new SurplusScanMetrics(candidatesChecked, inventoriesInspected, cachedCandidates, cacheStale);
        }
    }
}
