package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.MasonGuardEntity;
import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.util.QuartermasterDemandPlanner;
import dev.sterner.guardvillagers.common.util.QuartermasterPrerequisiteHelper;
import dev.sterner.guardvillagers.common.util.VillageAnchorState;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import dev.sterner.guardvillagers.common.villager.behavior.WeaponsmithBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.BedBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
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
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
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
    private static final int DEFAULT_LUMBERJACK_DRAIN_SWEEP_INTERVAL_TICKS = 12_000;
    private static final int LUMBERJACK_DRAIN_ELIGIBLE_DISTINCT_ITEM_THRESHOLD = 3;
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
    private static final int BELL_CHEST_LOW_THRESHOLD = 128;
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
    private static final int LUMBERJACK_PROMOTION_CHAIN_PLANK_FLOOR = 20;
    private static final long BOOTSTRAP_DISCOVERY_RETRY_INTERVAL_TICKS = 1200L;
    private static final int BOOTSTRAP_MAX_EMPTY_DISCOVERY_RETRIES = 5;
    private static final long BOOTSTRAP_DRAINED_RECHECK_INTERVAL_TICKS = 24_000L;
    /** Minimum saplings a Forester chest must hold before the QM stops topping it up. */
    private static final int FORESTER_SAPLING_TOP_UP_THRESHOLD = 8;
    // The villager displayed as "Forester" in MoreVillagers has internal profession ID "woodworker"
    private static final Identifier FORESTER_PROFESSION_ID = Identifier.of("morevillagers", "woodworker");
    /**
     * All blocks that count as village job sites for the bootstrap chest scan.
     * Mutable so soft-dep mods (e.g. MoreVillagers) can register their job blocks at startup
     * via {@link #registerNaturalVillageJobSiteBlock(net.minecraft.block.Block)}.
     */
    private static final Set<net.minecraft.block.Block> NATURAL_VILLAGE_JOB_SITE_BLOCKS = new HashSet<>(Set.of(
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
    ));

    /**
     * Registers an additional block as a village job-site anchor for the bootstrap chest scan.
     * Call this from mod compat bridges after the block registry is available.
     */
    public static void registerNaturalVillageJobSiteBlock(net.minecraft.block.Block block) {
        NATURAL_VILLAGE_JOB_SITE_BLOCKS.add(block);
    }

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
        // Accept saplings (routed to Forester chests)
        if (stack.isIn(ItemTags.SAPLINGS)) return true;
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
    private static final ProfessionReclaimPolicy LUMBERJACK_RECLAIM_POLICY = ProfessionReclaimPolicy.of(
            stack -> stack.isIn(ItemTags.LOGS)
                    || stack.isIn(ItemTags.PLANKS)
                    || stack.isOf(Items.CHARCOAL)
                    || stack.isOf(Items.STICK)
                    || stack.isIn(ItemTags.FENCES)
                    || stack.isIn(ItemTags.FENCE_GATES)
                    || stack.isOf(Items.CHEST)
                    || stack.isOf(Items.TRAPPED_CHEST)
                    || stack.isOf(Items.CRAFTING_TABLE),
            Map.of(
                    Items.STICK, 4,
                    Items.CHEST, 1,
                    Items.TRAPPED_CHEST, 1,
                    Items.CRAFTING_TABLE, 1
            ));
    private static final Map<VillagerProfession, ProfessionReclaimPolicy> PROFESSION_RECLAIM_POLICIES = buildProfessionReclaimPolicies();

    private static Map<VillagerProfession, ProfessionReclaimPolicy> buildProfessionReclaimPolicies() {
        Map<VillagerProfession, ProfessionReclaimPolicy> policies = new HashMap<>();
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
    private long nextDemandQueueRebuildTick = 0L;
    /**
     * Deferred until bootstrap completes — set to MAX_VALUE so the first reclaim never
     * fires before the QM has finished draining natural village chests.
     */
    private long nextDailyReclaimTick = Long.MAX_VALUE;
    /**
     * Deferred until bootstrap completes — same reasoning as nextDailyReclaimTick.
     */
    private long nextLumberjackDrainSweepTick = Long.MAX_VALUE;
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
    /**
     * All stacks the QM is carrying on the current haul trip.
     * Populated by planning — either a full chest-drain (bootstrap, surplus, reclaim)
     * or a precisely-sized single-item delivery (demand queue, targeted top-ups).
     * Drained into the destination chest by {@link #insertPayloadToInventory}.
     */
    private final List<ItemStack> transferPayload = new ArrayList<>();
    /** How {@link #takePayloadFromInventory} should drain the source chest. */
    private TransferMode transferMode = TransferMode.SINGLE_STACK;
    private final List<BlockPos> cachedSurplusCandidates = new ArrayList<>();
    private final List<BlockPos> rebuildingSurplusCandidates = new ArrayList<>();
    private int surplusCandidateCursor = 0;
    private int pairingRebuildCursor = 0;
    private int cachedPairingCount = -1;
    private boolean candidateCacheStale = true;
    private long nextCandidateCacheRefreshTick = 0L;
    private SurplusScanMetrics lastSurplusScanMetrics = SurplusScanMetrics.empty();
    private final Deque<BlockPos> bootstrapSourceQueue = new ArrayDeque<>();
    private final Deque<QuartermasterDemandPlanner.QueueEntry> demandQueue = new ArrayDeque<>();
    private int bootstrapDiscoveryRuns = 0;
    private int bootstrapEmptyDiscoveryRetries = 0;
    private long nextBootstrapDiscoveryTick = 0L;
    private boolean discoveredBootstrapSourceAtLeastOnce = false;
    private final Set<BlockPos> bootstrapVisitedThisCycle = new HashSet<>();
    private BootstrapCycleMetrics activeBootstrapCycleMetrics = null;
    private boolean demandQueueDirty = true;
    private BootstrapConsolidationState bootstrapConsolidationState = BootstrapConsolidationState.NOT_STARTED;
    /**
     * True while the bootstrap sweep is in progress. When set, {@link #tryPlanTransfer}
     * checks bootstrap FIRST — before lumberjack drain, reclaim, and demand queue —
     * so every discovered natural chest is visited before the QM starts distributing.
     */
    private boolean bootstrapSweepActive = false;
    private final Deque<TransferLeg> lumberjackDrainQueue = new ArrayDeque<>();
    private LumberjackDrainCycleMetrics activeLumberjackDrainCycle = null;

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
        requestImmediateDemandReplan();
    }

    /**
     * Signals that demand queue planning should run immediately on the next goal check.
     * Used by inventory mutation listeners.
     */
    public void requestImmediateDemandReplan() {
        demandQueueDirty = true;
        nextDemandQueueRebuildTick = 0L;
        nextCheckTick = 0L;
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
        transferPayload.clear();
        transferMode = TransferMode.SINGLE_STACK;
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
                if (!takePayloadFromInventory(world, sourcePos)) {
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
                insertPayloadToInventory(world, destPos);
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
    // Transfer planning helpers
    // -------------------------------------------------------------------------

    /**
     * Plans a full-chest haul: the QM will walk to {@code source}, drain <em>everything</em>
     * from it, then walk to {@code dest} and deposit the lot.
     * The payload is intentionally left empty here; {@link #takePayloadFromInventory}
     * fills it from the live chest contents at pickup time so we don't snapshot stale data.
     * Used during bootstrap consolidation sweeps.
     */
    private void planFullChestHaul(BlockPos source, BlockPos dest) {
        sourcePos = source;
        destPos = dest;
        transferPayload.clear();
        transferMode = TransferMode.FULL_CHEST;
    }

    /**
     * Plans a whitelisted full-chest haul: the QM drains all {@link #SURPLUS_HAUL_WHITELIST}
     * items from {@code source} in one trip.  Used for Priority-3 surplus redistribution.
     */
    private void planWhitelistedChestHaul(BlockPos source, BlockPos dest) {
        sourcePos = source;
        destPos = dest;
        transferPayload.clear();
        transferMode = TransferMode.WHITELISTED_CHEST;
    }

    /**
     * Plans a precise single-stack delivery.
     * Used by demand-queue entries and targeted top-up transfers where the amount
     * has been carefully computed to avoid overfilling a profession chest.
     */
    private void planSingleStackHaul(BlockPos source, BlockPos dest, ItemStack stack) {
        sourcePos = source;
        destPos = dest;
        transferPayload.clear();
        if (!stack.isEmpty()) transferPayload.add(stack.copy());
        transferMode = TransferMode.SINGLE_STACK;
    }

    // -------------------------------------------------------------------------
    // Transfer planning
    // -------------------------------------------------------------------------

    private boolean tryPlanTransfer(ServerWorld world) {
        BlockPos qmChestPos = resolveBellChestPos(world);

        // Bootstrap is sticky: once it starts visiting chests it takes exclusive priority
        // until every discovered natural village chest has been drained. This ensures the
        // QM visits ALL chests before switching to distribution mode.
        if (bootstrapSweepActive) {
            if (qmChestPos != null && tryPlanBootstrapConsolidationTransfer(world, qmChestPos)) {
                return true;
            }
            // Sweep ended (queue drained or completed) — fall through to normal priorities
        }

        if (tryPlanActiveLumberjackDrainTransfer()) {
            return true;
        }
        if (tryPlanLumberjackDrainSweepIfDue(world)) {
            return true;
        }
        if (tryPlanDailyReclaimTransferIfDue(world)) {
            return true;
        }

        // qmChestPos is the QM's own paired chest — it doubles as the village bank/hub.
        // Only enter bootstrap from this non-sticky path when the sweep is NOT already active
        // (that case was handled above) AND the state machine is in a phase that needs driving
        // (NOT_STARTED, WAITING_RECHECK, or CONSOLIDATING with remaining queue entries).
        // Specifically exclude DRAINED_COMPLETE so we never re-enter while sleeping in the
        // post-drain cooldown — doing so caused the retry counter to double-increment per cycle
        // and blow past BOOTSTRAP_MAX_EMPTY_DISCOVERY_RETRIES prematurely.
        if (qmChestPos != null
                && bootstrapConsolidationState != BootstrapConsolidationState.DRAINED_COMPLETE
                && !isBootstrapComplete(world)) {
            if (tryPlanBootstrapConsolidationTransfer(world, qmChestPos)) {
                return true;
            }
        }

        // Priority 1: mason is building (low on stone) → top up from QM chest
        if (tryPlanDemandQueueTransfer(world, qmChestPos)) {
            return true;
        }

        // Priority 1: mason is building (low on stone) → top up from QM chest
        Optional<MasonGuardEntity> masonNeedingStone = findMasonNeedingStone(world);
        if (masonNeedingStone.isPresent() && qmChestPos != null) {
            int stoneInQm = countItem(world, qmChestPos, Items.COBBLESTONE);
            if (stoneInQm > 0) {
                BlockPos masonChest = masonNeedingStone.get().getPairedChestPos();
                if (masonChest != null) {
                    planSingleStackHaul(qmChestPos, masonChest, new ItemStack(Items.COBBLESTONE, Math.min(HAUL_AMOUNT, stoneInQm)));
                    LOGGER.debug("QM {}: hauling stone from QM chest to mason {}", villager.getUuidAsString(), masonChest.toShortString());
                    return true;
                }
            }
        }

        // Priority 2a: lumberjack crafting (low on planks) → top up from QM chest.
        Optional<LumberjackGuardEntity> lumberjackNeedingPlanks = findLumberjackNeedingPlanks(world);
        if (lumberjackNeedingPlanks.isPresent() && qmChestPos != null) {
            ItemStack bestPlanks = findBestTagItem(world, qmChestPos, ItemTags.PLANKS);
            if (!bestPlanks.isEmpty()) {
                BlockPos ljChest = lumberjackNeedingPlanks.get().getPairedChestPos();
                if (ljChest != null) {
                    planSingleStackHaul(qmChestPos, ljChest, bestPlanks.copyWithCount(Math.min(HAUL_AMOUNT, bestPlanks.getCount())));
                    LOGGER.debug("QM {}: hauling {} planks from QM chest to lumberjack {}", villager.getUuidAsString(), bestPlanks.getItem(), ljChest.toShortString());
                    return true;
                }
            }
        }

        // Priority 2b: weaponsmith low on planks → top up from QM chest.
        Optional<BlockPos> weaponsmithChestNeedingPlanks = findWeaponsmithChestNeedingPlanks(world);
        if (weaponsmithChestNeedingPlanks.isPresent() && qmChestPos != null) {
            ItemStack bestPlanks = findBestTagItem(world, qmChestPos, ItemTags.PLANKS);
            if (!bestPlanks.isEmpty()) {
                planSingleStackHaul(qmChestPos, weaponsmithChestNeedingPlanks.get(), bestPlanks.copyWithCount(Math.min(HAUL_AMOUNT, bestPlanks.getCount())));
                LOGGER.debug("QM {}: hauling {} planks from QM chest to weaponsmith at {}", villager.getUuidAsString(), bestPlanks.getItem(), weaponsmithChestNeedingPlanks.get().toShortString());
                return true;
            }
        }

        // Priority 2c: lumberjack needs cobblestone for furnace crafting.
        Optional<LumberjackFurnaceStoneNeed> lumberjackNeedingFurnaceStone = findLumberjackNeedingFurnaceStone(world);
        if (lumberjackNeedingFurnaceStone.isPresent()) {
            LumberjackFurnaceStoneNeed need = lumberjackNeedingFurnaceStone.get();
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
                if (toDeliver > 0) {
                    planSingleStackHaul(stoneSource, need.chestPos(), new ItemStack(Items.COBBLESTONE, toDeliver));
                    LOGGER.debug("QM {}: hauling {} cobblestone from {} to lumberjack {} for furnace crafting",
                            villager.getUuidAsString(), toDeliver, stoneSource.toShortString(), need.chestPos().toShortString());
                    return true;
                }
            }
        }

        // Priority 2d: QM chest has saplings → route to any nearby Forester chest that is running low.
        Optional<BlockPos> foresterChestNeedingSaplings = findForesterChestNeedingSaplings(world);
        if (foresterChestNeedingSaplings.isPresent()) {
            ItemStack bestSaplings = findBestTagItem(world, chestPos, ItemTags.SAPLINGS);
            if (!bestSaplings.isEmpty()) {
                planSingleStackHaul(chestPos, foresterChestNeedingSaplings.get(), bestSaplings.copyWithCount(Math.min(HAUL_AMOUNT, bestSaplings.getCount())));
                LOGGER.debug("QM {}: routing {} saplings from QM chest to forester at {}", villager.getUuidAsString(), bestSaplings.getCount(), foresterChestNeedingSaplings.get().toShortString());
                return true;
            }
        }

        // Priority 3: QM chest is low → haul entire whitelisted contents from a surplus chest.
        if (qmChestPos != null) {
            int qmTotal = countAllItems(world, qmChestPos);
            if (qmTotal < BELL_CHEST_LOW_THRESHOLD) {
                Optional<BlockPos> surplusChest = findSurplusChest(world, qmChestPos);
                if (surplusChest.isPresent()) {
                    // Whitelisted haul — takePayloadFromInventory will drain SURPLUS_HAUL_WHITELIST items only.
                    planWhitelistedChestHaul(surplusChest.get(), qmChestPos);
                    LOGGER.debug("QM {}: hauling surplus chest {} to QM chest", villager.getUuidAsString(), surplusChest.get().toShortString());
                    return true;
                }
            }
        }

        return false;
    }

    private boolean tryPlanDemandQueueTransfer(ServerWorld world, BlockPos bellChestPos) {
        if (bellChestPos == null) {
            return false;
        }
        if (demandQueueDirty || world.getTime() >= nextDemandQueueRebuildTick || demandQueue.isEmpty()) {
            rebuildDemandQueue(world, bellChestPos);
        }

        while (!demandQueue.isEmpty()) {
            QuartermasterDemandPlanner.QueueEntry entry = demandQueue.pollFirst();
            int available = countItem(world, bellChestPos, entry.requestedStack().getItem());
            if (available <= 0) {
                continue;
            }
            int amount = Math.min(HAUL_AMOUNT, Math.min(available, entry.requestedStack().getCount()));
            if (amount <= 0) {
                continue;
            }
            planSingleStackHaul(bellChestPos, entry.recipientChestPos(), entry.requestedStack().copyWithCount(amount));
            return true;
        }
        return false;
    }

    private void rebuildDemandQueue(ServerWorld world, BlockPos bellChestPos) {
        List<QuartermasterDemandPlanner.ChestSnapshot> snapshots = buildDemandPlannerSnapshots(world, bellChestPos);
        List<QuartermasterDemandPlanner.QueueEntry> planned =
                QuartermasterDemandPlanner.plan(world, bellChestPos, snapshots, HAUL_AMOUNT);
        demandQueue.clear();
        demandQueue.addAll(planned);
        demandQueueDirty = false;
        nextDemandQueueRebuildTick = world.getTime() + CHECK_INTERVAL_TICKS;
    }

    private List<QuartermasterDemandPlanner.ChestSnapshot> buildDemandPlannerSnapshots(ServerWorld world, BlockPos bellChestPos) {
        List<QuartermasterDemandPlanner.ChestSnapshot> snapshots = new ArrayList<>();
        Map<net.minecraft.item.Item, Integer> qmTotals = inventoryTotals(world, bellChestPos);
        if (!qmTotals.isEmpty()) {
            snapshots.add(new QuartermasterDemandPlanner.ChestSnapshot(
                    VillagerProfession.LIBRARIAN,
                    bellChestPos.toImmutable(),
                    qmTotals
            ));
        }

        for (JobBlockPairingHelper.CachedVillagerChestPairing pairing : JobBlockPairingHelper.getCachedVillagerChestPairings(world)) {
            BlockPos pairedChestPos = pairing.chestPos();
            if (pairedChestPos == null || pairedChestPos.equals(bellChestPos) || pairing.villagerUuid().equals(villager.getUuid())) {
                continue;
            }
            Map<net.minecraft.item.Item, Integer> totals = inventoryTotals(world, pairedChestPos);
            if (totals.isEmpty()) {
                continue;
            }
            snapshots.add(new QuartermasterDemandPlanner.ChestSnapshot(
                    pairing.profession(),
                    pairedChestPos.toImmutable(),
                    totals
            ));
        }
        snapshots.sort(Comparator
                .comparing((QuartermasterDemandPlanner.ChestSnapshot snapshot) -> snapshot.profession().toString())
                .thenComparingLong(snapshot -> snapshot.chestPos().asLong()));
        return snapshots;
    }

    private Map<net.minecraft.item.Item, Integer> inventoryTotals(ServerWorld world, BlockPos pos) {
        Optional<Inventory> inventory = getInventory(world, pos);
        if (inventory.isEmpty()) {
            return Map.of();
        }
        Map<net.minecraft.item.Item, Integer> totalsByItem = new HashMap<>();
        for (int i = 0; i < inventory.get().size(); i++) {
            ItemStack stack = inventory.get().getStack(i);
            if (stack.isEmpty()) continue;
            totalsByItem.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return totalsByItem;
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
        planSingleStackHaul(leg.sourcePos(), leg.destPos(), leg.transferStack().copy());
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

    private boolean tryPlanLumberjackDrainSweepIfDue(ServerWorld world) {
        long worldTime = world.getTime();
        if (worldTime < nextLumberjackDrainSweepTick) {
            return false;
        }
        nextLumberjackDrainSweepTick = worldTime + getLumberjackDrainSweepIntervalTicks();
        double qmChestFullness = getChestFullness(world, chestPos);
        double fullnessThreshold = getLumberjackReclaimQmChestFullnessThreshold();
        if (qmChestFullness >= fullnessThreshold) {
            LOGGER.info("QM {}: skipping lumberjack reclaim cycle (reason=qm_chest_fullness_gate fullness={} threshold={} recheck_tick={})",
                    villager.getUuidAsString(),
                    String.format("%.3f", qmChestFullness),
                    String.format("%.3f", fullnessThreshold),
                    nextLumberjackDrainSweepTick);
            return false;
        }
        if (!rebuildLumberjackDrainQueue(world)) {
            return false;
        }
        return tryPlanActiveLumberjackDrainTransfer();
    }

    private boolean tryPlanActiveLumberjackDrainTransfer() {
        if (lumberjackDrainQueue.isEmpty()) {
            finishActiveLumberjackDrainCycleIfNeeded();
            return false;
        }
        TransferLeg leg = lumberjackDrainQueue.pollFirst();
        planSingleStackHaul(leg.sourcePos(), leg.destPos(), leg.transferStack().copy());
        return true;
    }

    private boolean rebuildLumberjackDrainQueue(ServerWorld world) {
        lumberjackDrainQueue.clear();
        List<BlockPos> eligibleChests = new ArrayList<>();
        int plannedStacks = 0;
        for (LumberjackGuardEntity lumberjack : getNearbyLumberjacks(world)) {
            if (!lumberjack.isAlive()) {
                continue;
            }
            BlockPos lumberjackChestPos = lumberjack.getPairedChestPos();
            if (lumberjackChestPos == null || lumberjackChestPos.equals(chestPos)) {
                continue;
            }
            Optional<Inventory> inventory = getInventory(world, lumberjackChestPos);
            if (inventory.isEmpty()) {
                continue;
            }
            if (countDistinctNonEmptyItems(inventory.get()) <= LUMBERJACK_DRAIN_ELIGIBLE_DISTINCT_ITEM_THRESHOLD) {
                continue;
            }

            Set<net.minecraft.item.Item> excludedItems = Set.of();
            if (GuardVillagersConfig.quartermasterLumberjackDrainProtectUpgradeRecipeInputs) {
                LumberjackChestTriggerController.UpgradeDemand activeDemand = resolveLumberjackUpgradeDemand(world, lumberjack);
                excludedItems = buildLumberjackActiveRecipeExclusions(activeDemand);
            }

            List<TransferLeg> plannedLegs = planLumberjackChestDrainLegs(lumberjackChestPos, inventory.get(), excludedItems);
            if (plannedLegs.isEmpty()) {
                continue;
            }
            eligibleChests.add(lumberjackChestPos.toImmutable());
            lumberjackDrainQueue.addAll(plannedLegs);
            plannedStacks += plannedLegs.size();
        }

        if (lumberjackDrainQueue.isEmpty()) {
            activeLumberjackDrainCycle = null;
            return false;
        }
        activeLumberjackDrainCycle = new LumberjackDrainCycleMetrics(eligibleChests);
        LOGGER.info("QM {}: lumberjack drain cycle started (eligible_chests={} planned_stacks={} path={} -> {} -> {})",
                villager.getUuidAsString(),
                eligibleChests.size(),
                plannedStacks,
                chestPos.toShortString(),
                eligibleChests.stream().map(BlockPos::toShortString).toList(),
                chestPos.toShortString());
        return true;
    }

    private List<TransferLeg> planLumberjackChestDrainLegs(BlockPos sourceChestPos, Inventory inventory, Set<net.minecraft.item.Item> excludedItems) {
        ProfessionReclaimPolicy policy = LUMBERJACK_RECLAIM_POLICY;
        Map<net.minecraft.item.Item, Integer> totals = totalsByItem(inventory);
        int remainingLogs = Math.max(0, countMatching(inventory, stack -> stack.isIn(ItemTags.LOGS)) - getLumberjackLogReserveFloor());
        int remainingPlanks = Math.max(0, countMatching(inventory, stack -> stack.isIn(ItemTags.PLANKS)) - getLumberjackPlankReserveFloor());
        int remainingCharcoal = Math.max(0, totals.getOrDefault(Items.CHARCOAL, 0) - getLumberjackCharcoalReserveFloor());
        Map<net.minecraft.item.Item, Integer> remainingByItem = new HashMap<>();
        for (Map.Entry<net.minecraft.item.Item, Integer> entry : totals.entrySet()) {
            int keep = policy.reserveCount(entry.getKey());
            remainingByItem.put(entry.getKey(), Math.max(0, entry.getValue() - keep));
        }

        List<TransferLeg> planned = new ArrayList<>();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty() || !policy.canReclaim(stack) || excludedItems.contains(stack.getItem())) {
                continue;
            }
            int reclaimable = switch (reserveGrouping(stack)) {
                case LOGS -> remainingLogs;
                case PLANKS -> remainingPlanks;
                case CHARCOAL -> remainingCharcoal;
                case ITEM -> remainingByItem.getOrDefault(stack.getItem(), 0);
            };
            if (reclaimable <= 0) {
                continue;
            }
            int toMove = Math.min(reclaimable, stack.getCount());
            if (toMove <= 0) {
                continue;
            }
            planned.add(new TransferLeg(sourceChestPos, chestPos, stack.copyWithCount(toMove)));
            switch (reserveGrouping(stack)) {
                case LOGS -> remainingLogs -= toMove;
                case PLANKS -> remainingPlanks -= toMove;
                case CHARCOAL -> remainingCharcoal -= toMove;
                case ITEM -> remainingByItem.merge(stack.getItem(), -toMove, Integer::sum);
            }
        }
        return planned;
    }

    private static Map<net.minecraft.item.Item, Integer> totalsByItem(Inventory inventory) {
        Map<net.minecraft.item.Item, Integer> totals = new HashMap<>();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty()) {
                totals.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return totals;
    }

    private static int countMatching(Inventory inventory, Predicate<ItemStack> predicate) {
        int total = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && predicate.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countDistinctNonEmptyItems(Inventory inventory) {
        Set<net.minecraft.item.Item> distinct = new HashSet<>();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty()) {
                distinct.add(stack.getItem());
            }
        }
        return distinct.size();
    }

    private int getLumberjackDrainSweepIntervalTicks() {
        return Math.max(20, GuardVillagersConfig.quartermasterLumberjackDrainSweepIntervalTicks > 0
                ? GuardVillagersConfig.quartermasterLumberjackDrainSweepIntervalTicks
                : DEFAULT_LUMBERJACK_DRAIN_SWEEP_INTERVAL_TICKS);
    }

    private double getLumberjackReclaimQmChestFullnessThreshold() {
        return Math.max(0.0D, Math.min(1.0D, GuardVillagersConfig.quartermasterLumberjackReclaimQmChestFullnessThreshold));
    }

    private double getChestFullness(ServerWorld world, BlockPos pos) {
        Optional<Inventory> inventory = getInventory(world, pos);
        if (inventory.isEmpty()) {
            return 0.0D;
        }
        Inventory inv = inventory.get();
        if (inv.size() <= 0) {
            return 0.0D;
        }
        double usedCapacity = 0.0D;
        double totalCapacity = 0.0D;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            int slotCapacity = stack.isEmpty() ? inv.getMaxCountPerStack() : Math.min(inv.getMaxCountPerStack(), stack.getMaxCount());
            if (slotCapacity <= 0) {
                continue;
            }
            totalCapacity += slotCapacity;
            usedCapacity += Math.min(stack.getCount(), slotCapacity);
        }
        if (totalCapacity <= 0.0D) {
            return 0.0D;
        }
        return usedCapacity / totalCapacity;
    }

    private Set<net.minecraft.item.Item> buildLumberjackActiveRecipeExclusions(LumberjackChestTriggerController.UpgradeDemand demand) {
        Set<net.minecraft.item.Item> exclusions = new HashSet<>();
        if (demand == null) {
            return exclusions;
        }
        if (demand.planksCost() > 0) {
            exclusions.add(Items.OAK_PLANKS);
            exclusions.add(Items.SPRUCE_PLANKS);
            exclusions.add(Items.BIRCH_PLANKS);
            exclusions.add(Items.JUNGLE_PLANKS);
            exclusions.add(Items.ACACIA_PLANKS);
            exclusions.add(Items.DARK_OAK_PLANKS);
            exclusions.add(Items.MANGROVE_PLANKS);
            exclusions.add(Items.CHERRY_PLANKS);
            exclusions.add(Items.BAMBOO_PLANKS);
            exclusions.add(Items.CRIMSON_PLANKS);
            exclusions.add(Items.WARPED_PLANKS);
        }
        if (demand.stickCost() > 0) {
            exclusions.add(Items.STICK);
        }
        exclusions.add(demand.outputItem());
        return exclusions;
    }

    private void finishActiveLumberjackDrainCycleIfNeeded() {
        if (activeLumberjackDrainCycle == null) {
            return;
        }
        LOGGER.info("QM {}: lumberjack drain cycle complete (eligible_chests={} visited_chests={} total_stacks_moved={})",
                villager.getUuidAsString(),
                activeLumberjackDrainCycle.eligibleChestCount(),
                activeLumberjackDrainCycle.visitedChests().stream().map(BlockPos::toShortString).toList(),
                activeLumberjackDrainCycle.totalStacksMoved());
        activeLumberjackDrainCycle = null;
    }

    protected LumberjackChestTriggerController.UpgradeDemand resolveLumberjackUpgradeDemand(ServerWorld world, LumberjackGuardEntity lumberjack) {
        return LumberjackChestTriggerController.resolveNextUpgradeDemand(world, lumberjack);
    }

    protected List<LumberjackGuardEntity> getNearbyLumberjacks(ServerWorld world) {
        Box box = new Box(jobPos).expand(getScanRange());
        return world.getEntitiesByClass(LumberjackGuardEntity.class, box, LumberjackGuardEntity::isAlive);
    }

    private int getLumberjackLogReserveFloor() {
        return Math.max(0, GuardVillagersConfig.quartermasterLumberjackReserveLogs);
    }

    private int getLumberjackPlankReserveFloor() {
        int configured = Math.max(0, GuardVillagersConfig.quartermasterLumberjackReservePlanks);
        return Math.max(configured, LUMBERJACK_PROMOTION_CHAIN_PLANK_FLOOR);
    }

    private int getLumberjackCharcoalReserveFloor() {
        return Math.max(0, GuardVillagersConfig.quartermasterLumberjackReserveCharcoal);
    }

    private int getLumberjackFurnaceFuelReserveFloor() {
        return Math.max(0, GuardVillagersConfig.quartermasterLumberjackFurnaceFuelReserve);
    }

    private ReserveGrouping reserveGrouping(ItemStack stack) {
        if (stack.isIn(ItemTags.LOGS)) {
            return ReserveGrouping.LOGS;
        }
        if (stack.isIn(ItemTags.PLANKS)) {
            return ReserveGrouping.PLANKS;
        }
        if (stack.isOf(Items.CHARCOAL)) {
            return ReserveGrouping.CHARCOAL;
        }
        return ReserveGrouping.ITEM;
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

    /**
     * Finds a paired Forester chest that is low on saplings, if the QM chest
     * currently holds any saplings to supply.
     *
     * <p>Safe to call when MoreVillagers is not loaded — the profession registry
     * lookup returns empty and the method returns empty immediately.
     */
    private Optional<BlockPos> findForesterChestNeedingSaplings(ServerWorld world) {
        if (countTagItems(world, chestPos, ItemTags.SAPLINGS) == 0) return Optional.empty();
        Optional<VillagerProfession> foresterOpt = Registries.VILLAGER_PROFESSION.getOrEmpty(FORESTER_PROFESSION_ID);
        if (foresterOpt.isEmpty()) return Optional.empty();
        VillagerProfession foresterProfession = foresterOpt.get();
        for (JobBlockPairingHelper.CachedVillagerChestPairing pairing : JobBlockPairingHelper.getCachedVillagerChestPairings(world)) {
            if (pairing.profession() != foresterProfession) continue;
            if (!pairing.jobPos().isWithinDistance(jobPos, getScanRange())) continue;
            if (countTagItems(world, pairing.chestPos(), ItemTags.SAPLINGS) < FORESTER_SAPLING_TOP_UP_THRESHOLD) {
                return Optional.of(pairing.chestPos());
            }
        }
        return Optional.empty();
    }

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

    private List<BlockPos> discoverBootstrapSourceChests(ServerWorld world, BlockPos bellChestPos) {
        return discoverBootstrapSourceChestsWithStats(world, bellChestPos).discovered();
    }

    private int getNaturalVillagePoiScanRadius() {
        return Math.max(GuardVillagersConfig.MIN_QUARTERMASTER_NATURAL_VILLAGE_POI_SCAN_RADIUS,
                GuardVillagersConfig.quartermasterNaturalVillagePoiScanRadius);
    }

    private int getNaturalVillageChestLocalPoiRadius() {
        return Math.max(GuardVillagersConfig.MIN_QUARTERMASTER_NATURAL_VILLAGE_CHEST_LOCAL_POI_RADIUS,
                GuardVillagersConfig.quartermasterNaturalVillageChestLocalPoiRadius);
    }

    private BootstrapDiscoveryResult discoverBootstrapSourceChestsWithStats(ServerWorld world, BlockPos bellChestPos) {
        Set<BlockPos> pairedChests = new HashSet<>();
        for (JobBlockPairingHelper.CachedVillagerChestPairing pairing : JobBlockPairingHelper.getCachedVillagerChestPairings(world)) {
            BlockPos pairingChest = pairing.chestPos();
            if (pairingChest == null) continue;
            // Canonicalize so the excluded set matches what candidateChestPos() returns for double-chest halves.
            BlockPos canonicalPairing = canonicalChestPos(world, pairingChest.toImmutable());
            pairedChests.add(canonicalPairing);
            // Also add the non-canonical half so either half is recognised as excluded.
            pairedChests.add(pairingChest.toImmutable());
            findDoubleChestOtherHalf(world, pairingChest).ifPresent(other -> pairedChests.add(other.toImmutable()));
        }

        Set<BlockPos> excluded = new HashSet<>(pairedChests);
        // Exclude both canonical and raw positions of the QM's own chest.
        excluded.add(chestPos.toImmutable());
        excluded.add(canonicalChestPos(world, chestPos.toImmutable()));
        findDoubleChestOtherHalf(world, chestPos).ifPresent(other -> excluded.add(other.toImmutable()));

        Set<BlockPos> deduplicated = new HashSet<>();
        List<BlockPos> tierA = new ArrayList<>();
        List<BlockPos> tierB = new ArrayList<>();
        int filteredPaired = 0;
        int filteredEmpty = 0;
        int strictTierBSkipped = 0;
        int range = (int) Math.ceil(getScanRange());
        boolean strictVillageOnly = GuardVillagersConfig.quartermasterBootstrapStrictVillageOnly;
        for (BlockPos scanPos : BlockPos.iterate(bellChestPos.add(-range, -range, -range), bellChestPos.add(range, range, range))) {
            if (!bellChestPos.isWithinDistance(scanPos, getScanRange())) continue;
            BlockState state = world.getBlockState(scanPos);
            if (!(state.getBlock() instanceof ChestBlock)) continue;
            BlockPos candidate = canonicalChestPos(world, scanPos.toImmutable());
            if (excluded.contains(candidate)) {
                filteredPaired++;
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("QM {} bootstrap candidate={} reject_reason=paired_chest_excluded",
                            villager.getUuidAsString(),
                            candidate);
                }
                continue;
            }
            if (!isNaturalVillageChest(world, bellChestPos, candidate)) {
                filteredNotNatural++;
                String naturalRejectReason = getNaturalVillageChestRejectReason(world, bellChestPos, candidate);
                LOGGER.info("QM {} bootstrap candidate={} reject_reason={}",
                        villager.getUuidAsString(),
                        candidate.toShortString(),
                        naturalRejectReason);
                continue;
            }
            int candidateItemCount = countAllItems(world, candidate);
            if (candidateItemCount <= 0) {
                filteredEmpty++;
                LOGGER.info("QM {} bootstrap candidate={} reject_reason=empty_chest",
                        villager.getUuidAsString(),
                        candidate.toShortString());
                continue;
            }
            if (deduplicated.add(candidate)) {
                discovered.add(candidate);
                LOGGER.info("QM {} bootstrap discovered_natural_chest={} items={}",
                        villager.getUuidAsString(),
                        candidate.toShortString(),
                        candidateItemCount);
            }
        }
        tierA.sort(Comparator.comparingDouble(candidate -> candidate.getSquaredDistance(bellChestPos)));
        tierB.sort(Comparator.comparingDouble(candidate -> candidate.getSquaredDistance(bellChestPos)));
        return new BootstrapDiscoveryResult(tierA, tierB, filteredPaired, filteredEmpty, strictTierBSkipped, strictVillageOnly);
    }

    private boolean tryPlanBootstrapConsolidationTransfer(ServerWorld world, BlockPos bellChestPos) {
        long now = world.getTime();
        if (isBootstrapComplete(world)) {
            if (now < nextBootstrapDiscoveryTick) {
                bootstrapConsolidationState = BootstrapConsolidationState.DRAINED_COMPLETE;
                bootstrapSourceQueue.clear();
                bootstrapSweepActive = false;
                return false;
            }
            clearBootstrapComplete(world);
            bootstrapConsolidationState = BootstrapConsolidationState.NOT_STARTED;
        }

        if (bootstrapConsolidationState == BootstrapConsolidationState.NOT_STARTED
                || bootstrapConsolidationState == BootstrapConsolidationState.WAITING_RECHECK
                || (!discoveredBootstrapSourceAtLeastOnce && bootstrapSourceQueue.isEmpty() && now >= nextBootstrapDiscoveryTick)) {
            bootstrapConsolidationState = BootstrapConsolidationState.DISCOVERING;
            bootstrapDiscoveryRuns++;
            bootstrapSourceQueue.clear();
            bootstrapVisitedThisCycle.clear();
            BootstrapDiscoveryResult discovery = discoverBootstrapSourceChestsWithStats(world, bellChestPos);
            bootstrapSourceQueue.addAll(discovery.discovered());
            LOGGER.info("QM {} bootstrap discovery run #{}: discovered={} filtered_paired={} filtered_not_natural={} filtered_empty={} (zone_radius={} local_poi_radius={})",
                    villager.getUuidAsString(),
                    bootstrapDiscoveryRuns,
                    discovery.discovered().size(),
                    discovery.filteredPaired(),
                    discovery.filteredNotNatural(),
                    discovery.filteredEmpty(),
                    getNaturalVillagePoiScanRadius(),
                    getNaturalVillageChestLocalPoiRadius());
            bootstrapConsolidationState = BootstrapConsolidationState.CONSOLIDATING;
            if (bootstrapSourceQueue.isEmpty()) {
                bootstrapEmptyDiscoveryRetries++;
                nextBootstrapDiscoveryTick = now + BOOTSTRAP_DISCOVERY_RETRY_INTERVAL_TICKS;
                bootstrapConsolidationState = BootstrapConsolidationState.WAITING_RECHECK;
                bootstrapSweepActive = false;
                LOGGER.info("QM {} bootstrap: no natural village chests found (retry={}/{}, next_check_in_ticks={})",
                        villager.getUuidAsString(),
                        bootstrapEmptyDiscoveryRetries,
                        BOOTSTRAP_MAX_EMPTY_DISCOVERY_RETRIES,
                        BOOTSTRAP_DISCOVERY_RETRY_INTERVAL_TICKS);
                return false;
            }
            // Found chests — mark sweep active so we hold priority over all other goals
            // until every discovered chest has been visited.
            bootstrapSweepActive = true;
            discoveredBootstrapSourceAtLeastOnce = true;
            bootstrapEmptyDiscoveryRetries = 0;
            LOGGER.info("QM {}: bootstrap sweep started — identified {} natural chest(s) to drain: {}",
                    villager.getUuidAsString(),
                    bootstrapSourceQueue.size(),
                    bootstrapSourceQueue.stream().map(BlockPos::toShortString).toList());
        }

        // Advance through the queue: skip nulls and already-empty chests,
        // haul the entire contents of the next non-empty one in a single trip.
        while (!bootstrapSourceQueue.isEmpty()) {
            BlockPos source = bootstrapSourceQueue.peekFirst();
            if (source == null || source.equals(bellChestPos)) {
                if (activeBootstrapCycleMetrics != null) {
                    activeBootstrapCycleMetrics.recordSkipped("invalid_source");
                }
                bootstrapSourceQueue.pollFirst();
                continue;
            }
            if (countAllItems(world, source) == 0) {
                // Already empty — skip to next chest without a trip
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("QM {} bootstrap source={} already_empty_skipping",
                            villager.getUuidAsString(), source);
                }
                bootstrapVisitedThisCycle.add(source.toImmutable());
                if (activeBootstrapCycleMetrics != null) {
                    activeBootstrapCycleMetrics.recordDrained(source);
                }
                bootstrapSourceQueue.pollFirst();
                continue;
            }
            // Full-chest haul: take everything from this source in one trip.
            // Do NOT pop the source from the queue yet — it gets popped next cycle
            // once takePayloadFromInventory has drained it (countAllItems will be 0 then).
            bootstrapSourceQueue.pollFirst();
            int itemsInSource = countAllItems(world, source);
            planFullChestHaul(source, bellChestPos);
            LOGGER.info("QM {}: bootstrap TARGETING chest={} items_to_load={} dest={} remaining_in_queue={}",
                    villager.getUuidAsString(),
                    source.toShortString(),
                    itemsInSource,
                    bellChestPos.toShortString(),
                    bootstrapSourceQueue.size());
            return true;
        }

        // Queue exhausted — all chests visited
        bootstrapSweepActive = false;

        if (discoveredBootstrapSourceAtLeastOnce) {
            logBootstrapCycleSummary();
            completeBootstrap(world, "drained_complete", BootstrapConsolidationState.DRAINED_COMPLETE, now + BOOTSTRAP_DRAINED_RECHECK_INTERVAL_TICKS);
        } else if (now >= nextBootstrapDiscoveryTick) {
            if (bootstrapEmptyDiscoveryRetries >= BOOTSTRAP_MAX_EMPTY_DISCOVERY_RETRIES) {
                // Village has no natural chests after exhausting retries — arm timers and move on.
                armPostBootstrapTimers(now);
                bootstrapConsolidationState = BootstrapConsolidationState.DRAINED_COMPLETE;
                setBootstrapComplete(world);
                LOGGER.info("QM {} bootstrap: giving up — no natural chests found after {} retries, arming post-bootstrap timers",
                        villager.getUuidAsString(), bootstrapEmptyDiscoveryRetries);
            } else {
                nextBootstrapDiscoveryTick = now + BOOTSTRAP_DISCOVERY_RETRY_INTERVAL_TICKS;
                bootstrapConsolidationState = BootstrapConsolidationState.WAITING_RECHECK;
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("QM {} bootstrap state_reason=empty_now_retrying retry={} max_retries={} next_discovery_tick={}",
                            villager.getUuidAsString(),
                            bootstrapEmptyDiscoveryRetries,
                            BOOTSTRAP_MAX_EMPTY_DISCOVERY_RETRIES,
                            nextBootstrapDiscoveryTick);
                }
            }
        }
        return false;
    }

    private void armPostBootstrapTimers(long now) {
        if (nextDailyReclaimTick == Long.MAX_VALUE) {
            nextDailyReclaimTick = now + DAILY_RECLAIM_INTERVAL_TICKS;
        }
        if (nextLumberjackDrainSweepTick == Long.MAX_VALUE) {
            nextLumberjackDrainSweepTick = now + getLumberjackDrainSweepIntervalTicks();
        }
    }

    private void completeBootstrap(ServerWorld world, String reason, BootstrapConsolidationState completionState, long nextRecheckTick) {
        bootstrapConsolidationState = completionState;
        bootstrapSourceQueue.clear();
        bootstrapVisitedThisCycle.clear();
        activeBootstrapCycleMetrics = null;
        nextBootstrapDiscoveryTick = Math.max(nextBootstrapDiscoveryTick, nextRecheckTick);
        setBootstrapComplete(world);
        // Arm the reclaim and drain-sweep timers now that bootstrap is done.
        // Both were deferred to Long.MAX_VALUE to ensure bootstrap always runs first.
        long now = world.getTime();
        armPostBootstrapTimers(now);
        LOGGER.info("QM {} bootstrap COMPLETE reason={} armed_reclaim_tick={} armed_drain_sweep_tick={} next_recheck_tick={}",
                villager.getUuidAsString(), reason, nextDailyReclaimTick, nextLumberjackDrainSweepTick, nextBootstrapDiscoveryTick);
    }

    private void logBootstrapCycleSummary() {
        if (activeBootstrapCycleMetrics == null || !LOGGER.isInfoEnabled()) {
            return;
        }
        LOGGER.info("QM {} bootstrap cycle summary: strict_mode={} discovered={} attempted={} drained={} skipped={} skip_reasons={}",
                villager.getUuidAsString(),
                activeBootstrapCycleMetrics.strictVillageOnly,
                activeBootstrapCycleMetrics.discoveredCount,
                activeBootstrapCycleMetrics.attemptedSources.size(),
                activeBootstrapCycleMetrics.drainedSources.size(),
                activeBootstrapCycleMetrics.totalSkipped(),
                activeBootstrapCycleMetrics.skippedReasons);
    }

    private BlockPos canonicalChestPos(ServerWorld world, BlockPos chestCandidate) {
        Optional<BlockPos> other = findDoubleChestOtherHalf(world, chestCandidate);
        if (other.isEmpty()) return chestCandidate;
        return chestCandidate.asLong() <= other.get().asLong() ? chestCandidate : other.get();
    }

    private boolean isNaturalVillageChest(ServerWorld world, BlockPos bellChestPos, BlockPos chestCandidate) {
        return getNaturalVillageChestRejectReason(world, bellChestPos, chestCandidate) == null;
    }

    private String getNaturalVillageChestRejectReason(ServerWorld world, BlockPos bellChestPos, BlockPos chestCandidate) {
        int villageZoneRadius = getNaturalVillagePoiScanRadius();
        int localPoiRadius = getNaturalVillageChestLocalPoiRadius();
        if (!bellChestPos.isWithinDistance(chestCandidate, villageZoneRadius)) {
            return "not_in_village_zone";
        }
        // Scan the village zone with a single flat iterate — O(n) instead of the former O(n³) triple loop.
        // Bell presence is treated as optional: modded / non-standard villages may have no bell, and we
        // must not reject every chest solely because of a missing bell.  A chest qualifies as a natural
        // village chest if the zone contains at least one bed or job-site block AND the candidate chest
        // is within localPoiRadius of that bed/job-site (or of a bell, if one exists).
        boolean villageHasBedOrJobSite = false;
        boolean chestNearVillagePoi = false;
        for (BlockPos cursor : BlockPos.iterate(
                bellChestPos.add(-villageZoneRadius, -villageZoneRadius, -villageZoneRadius),
                bellChestPos.add(villageZoneRadius, villageZoneRadius, villageZoneRadius))) {
            if (!bellChestPos.isWithinDistance(cursor, villageZoneRadius)) continue;
            BlockState nearbyState = world.getBlockState(cursor);
            // Bell counts as a village POI anchor (optional — villages without bells are still valid).
            if (nearbyState.isOf(Blocks.BELL)) {
                if (cursor.isWithinDistance(chestCandidate, localPoiRadius)) {
                    chestNearVillagePoi = true;
                }
            }
            if (nearbyState.getBlock() instanceof BedBlock || NATURAL_VILLAGE_JOB_SITE_BLOCKS.contains(nearbyState.getBlock())) {
                villageHasBedOrJobSite = true;
                if (cursor.isWithinDistance(chestCandidate, localPoiRadius)) {
                    chestNearVillagePoi = true;
                }
            }
            // Early exit: both conditions satisfied, no need to keep scanning.
            if (villageHasBedOrJobSite && chestNearVillagePoi) break;
        }
        if (!villageHasBedOrJobSite) {
            return "village_zone_missing_bed_or_job_site";
        }
        if (!chestNearVillagePoi) {
            return "no_local_village_poi";
        }
        return null;
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

    /**
     * Takes items from the chest at {@code pos} into {@link #transferPayload} according
     * to the current {@link #transferMode}.
     *
     * <ul>
     *   <li>{@link TransferMode#SINGLE_STACK} — payload was populated by the planner;
     *       drain exactly those items from the source slot-by-slot.</li>
     *   <li>{@link TransferMode#FULL_CHEST} — payload is empty; drain <em>all</em> items
     *       from the source into the payload (bootstrap consolidation).</li>
     *   <li>{@link TransferMode#WHITELISTED_CHEST} — payload is empty; drain only items
     *       that pass {@link #SURPLUS_HAUL_WHITELIST} (Priority-3 surplus haul).</li>
     * </ul>
     *
     * @return {@code true} if at least one item was taken; {@code false} if nothing was
     *         available (caller should abort the trip).
     */
    private boolean takePayloadFromInventory(ServerWorld world, BlockPos pos) {
        Optional<Inventory> inv = getInventory(world, pos);
        if (inv.isEmpty()) {
            requestImmediatePrerequisiteRevalidation();
            return false;
        }
        Inventory inventory = inv.get();

        if (transferMode == TransferMode.SINGLE_STACK) {
            // Drain exactly the stacks that were planned.
            if (transferPayload.isEmpty()) return false;
            boolean anyTaken = false;
            for (int p = 0; p < transferPayload.size(); p++) {
                ItemStack wanted = transferPayload.get(p);
                if (wanted.isEmpty()) continue;
                net.minecraft.item.Item item = wanted.getItem();
                int needed = wanted.getCount();
                int taken = 0;
                for (int i = 0; i < inventory.size() && taken < needed; i++) {
                    ItemStack slot = inventory.getStack(i);
                    if (!slot.isEmpty() && slot.isOf(item)) {
                        int grab = Math.min(slot.getCount(), needed - taken);
                        slot.decrement(grab);
                        if (slot.isEmpty()) inventory.setStack(i, ItemStack.EMPTY);
                        taken += grab;
                    }
                }
                if (taken > 0) {
                    transferPayload.set(p, new ItemStack(item, taken));
                    anyTaken = true;
                } else {
                    transferPayload.set(p, ItemStack.EMPTY);
                }
            }
            transferPayload.removeIf(ItemStack::isEmpty);
            if (anyTaken) inventory.markDirty();
            return anyTaken;
        }

        // FULL_CHEST or WHITELISTED_CHEST — payload must be empty at this point (set by planner).
        transferPayload.clear();
        Predicate<ItemStack> filter = (transferMode == TransferMode.WHITELISTED_CHEST)
                ? SURPLUS_HAUL_WHITELIST
                : stack -> !stack.isEmpty();

        for (int i = 0; i < inventory.size(); i++) {
            ItemStack slot = inventory.getStack(i);
            if (slot.isEmpty() || !filter.test(slot)) continue;
            // Merge into payload by item type so the payload doesn't balloon with duplicates.
            ItemStack copy = slot.copy();
            boolean merged = false;
            for (ItemStack existing : transferPayload) {
                if (ItemStack.areItemsAndComponentsEqual(existing, copy)
                        && existing.getCount() < existing.getMaxCount()) {
                    int space = existing.getMaxCount() - existing.getCount();
                    int move = Math.min(space, copy.getCount());
                    existing.increment(move);
                    copy.decrement(move);
                    if (copy.isEmpty()) { merged = true; break; }
                }
            }
            if (!merged && !copy.isEmpty()) {
                transferPayload.add(copy);
            }
            inventory.setStack(i, ItemStack.EMPTY);
        }
        if (!transferPayload.isEmpty()) {
            inventory.markDirty();
            int totalLoaded = transferPayload.stream().mapToInt(ItemStack::getCount).sum();
            LOGGER.info("QM {}: LOADED {} total items from {} ({} stacks, mode={}): {}",
                    villager.getUuidAsString(),
                    totalLoaded,
                    pos.toShortString(),
                    transferPayload.size(),
                    transferMode,
                    transferPayload.stream()
                            .map(s -> s.getCount() + "x" + s.getItem())
                            .toList());
            return true;
        }
        LOGGER.info("QM {}: LOAD attempted from {} but chest was empty (mode={})",
                villager.getUuidAsString(), pos.toShortString(), transferMode);
        return false;
    }

    /**
     * Inserts every stack in {@link #transferPayload} into the chest at {@code pos}.
     * Any overflow that does not fit is dropped at the villager's feet as item entities
     * so items are never silently destroyed.
     * Clears {@link #transferPayload} at the end regardless of success.
     */
    private void insertPayloadToInventory(ServerWorld world, BlockPos pos) {
        Optional<Inventory> inv = getInventory(world, pos);
        if (inv.isEmpty()) {
            requestImmediatePrerequisiteRevalidation();
            transferPayload.clear();
            return;
        }
        Inventory inventory = inv.get();

        for (ItemStack carrying : transferPayload) {
            if (carrying.isEmpty()) continue;
            ItemStack remaining = carrying.copy();

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

            // If dest chest was full, drop overflow at the villager's feet so items are never lost.
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
        }

        inventory.markDirty();

        int totalDelivered = transferPayload.stream().mapToInt(ItemStack::getCount).sum();
        LOGGER.info("QM {}: UNLOADED {} total items into {} ({} stacks, mode={}): {}",
                villager.getUuidAsString(),
                totalDelivered,
                pos.toShortString(),
                transferPayload.size(),
                transferMode,
                transferPayload.stream()
                        .map(s -> s.getCount() + "x" + s.getItem())
                        .toList());

        if (activeLumberjackDrainCycle != null && chestPos.equals(pos)) {
            activeLumberjackDrainCycle.recordVisitedSource(sourcePos);
            // Count each stack delivered as one moved-stack event.
            for (int k = 0; k < transferPayload.size(); k++) {
                activeLumberjackDrainCycle.recordMovedStack();
            }
            if (lumberjackDrainQueue.isEmpty()) {
                finishActiveLumberjackDrainCycleIfNeeded();
            }
        }

        transferPayload.clear();
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
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            // open=false: programmatic access must not trigger chest open-sound / lid animation.
            return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, pos, false));
        }
        if (world.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace) {
            return Optional.of(furnace);
        }
        return Optional.empty();
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
        // For test purposes expose the first payload stack (covers all single-stack haul assertions).
        ItemStack firstStack = goal.transferPayload.isEmpty() ? ItemStack.EMPTY : goal.transferPayload.get(0).copy();
        return new PlannedTransfer(goal.sourcePos, goal.destPos, firstStack);
    }

    static List<QuartermasterDemandPlanner.QueueEntry> getDemandQueueForTest(QuartermasterGoal goal) {
        return List.copyOf(goal.demandQueue);
    }

    private boolean isBootstrapComplete(ServerWorld world) {
        return BOOTSTRAP_COMPLETE_BY_QM.getOrDefault(new QmBootstrapKey(world.getRegistryKey(), villager.getUuid()), false);
    }

    private void setBootstrapComplete(ServerWorld world) {
        BOOTSTRAP_COMPLETE_BY_QM.put(new QmBootstrapKey(world.getRegistryKey(), villager.getUuid()), true);
    }

    private void clearBootstrapComplete(ServerWorld world) {
        BOOTSTRAP_COMPLETE_BY_QM.remove(new QmBootstrapKey(world.getRegistryKey(), villager.getUuid()));
    }

    record PlannedTransfer(BlockPos sourcePos, BlockPos destPos, ItemStack transferStack) {}
    private record BootstrapDiscoveryResult(
            List<BlockPos> tierA,
            List<BlockPos> tierB,
            int filteredPaired,
            int filteredEmpty,
            int skippedStrictTierB,
            boolean strictVillageOnly
    ) {
        int discoveredCount() {
            return tierA.size() + tierB.size();
        }
    }
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
    private enum ReserveGrouping { LOGS, PLANKS, CHARCOAL, ITEM }
    private enum BootstrapConsolidationState { NOT_STARTED, DISCOVERING, CONSOLIDATING, WAITING_RECHECK, DRAINED_COMPLETE }
    /**
     * Controls how {@link #takePayloadFromInventory} drains the source chest.
     * <ul>
     *   <li>{@code SINGLE_STACK} — payload already holds the precise stack(s) to move;
     *       drain exactly those items from source.</li>
     *   <li>{@code FULL_CHEST} — payload is empty at planning time; drain <em>everything</em>
     *       from the source (used during bootstrap consolidation).</li>
     *   <li>{@code WHITELISTED_CHEST} — payload is empty at planning time; drain only
     *       items that pass {@link #SURPLUS_HAUL_WHITELIST} (used for Priority-3 surplus haul).</li>
     * </ul>
     */
    private enum TransferMode { SINGLE_STACK, FULL_CHEST, WHITELISTED_CHEST }

    private static final class LumberjackDrainCycleMetrics {
        private final int eligibleChestCount;
        private final Set<BlockPos> visitedChests = new HashSet<>();
        private int totalStacksMoved = 0;

        private LumberjackDrainCycleMetrics(List<BlockPos> eligibleChests) {
            this.eligibleChestCount = eligibleChests.size();
        }

        void recordVisitedSource(BlockPos source) {
            if (source != null) {
                visitedChests.add(source.toImmutable());
            }
        }

        void recordMovedStack() {
            totalStacksMoved++;
        }

        int eligibleChestCount() { return eligibleChestCount; }
        int totalStacksMoved() { return totalStacksMoved; }
        Set<BlockPos> visitedChests() { return visitedChests; }
    }

    private static final class BootstrapCycleMetrics {
        private final int discoveredCount;
        private final boolean strictVillageOnly;
        private final Set<BlockPos> attemptedSources = new HashSet<>();
        private final Set<BlockPos> drainedSources = new HashSet<>();
        private final Map<String, Integer> skippedReasons = new LinkedHashMap<>();

        private BootstrapCycleMetrics(BootstrapDiscoveryResult discovery) {
            this.discoveredCount = discovery.discoveredCount();
            this.strictVillageOnly = discovery.strictVillageOnly();
            if (discovery.filteredPaired() > 0) {
                skippedReasons.put("paired_chest_excluded", discovery.filteredPaired());
            }
            if (discovery.filteredEmpty() > 0) {
                skippedReasons.put("empty_chest", discovery.filteredEmpty());
            }
            if (discovery.skippedStrictTierB() > 0) {
                skippedReasons.put("strict_village_mode", discovery.skippedStrictTierB());
            }
        }

        static BootstrapCycleMetrics start(BootstrapDiscoveryResult discovery) {
            return new BootstrapCycleMetrics(discovery);
        }

        void recordAttempt(BlockPos source) {
            if (source != null) {
                attemptedSources.add(source.toImmutable());
            }
        }

        void recordDrained(BlockPos source) {
            if (source != null) {
                drainedSources.add(source.toImmutable());
            }
        }

        void recordSkipped(String reason) {
            skippedReasons.merge(reason, 1, Integer::sum);
        }

        int totalSkipped() {
            int total = 0;
            for (Integer count : skippedReasons.values()) {
                total += count;
            }
            return total;
        }
    }

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
