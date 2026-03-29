package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.ButcherGuardEntity;
import dev.sterner.guardvillagers.common.entity.FishermanGuardEntity;
import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.entity.MasonGuardEntity;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.util.QuartermasterPrerequisiteHelper;
import dev.sterner.guardvillagers.common.util.VillageAnchorState;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import dev.sterner.guardvillagers.common.villager.behavior.WeaponsmithBehavior;
import net.minecraft.block.BlockState;
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
import net.minecraft.item.Item;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    private static final int CHECK_INTERVAL_TICKS = 100;
    private static final double SCAN_RANGE = VillageGuardStandManager.BELL_EFFECT_RANGE;
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
    /** Rods are unique tools; cap delivery so we don't strip the whole bank. */
    private static final int HAUL_AMOUNT_RODS = 2;
    /** Minimum coal+charcoal in butcher chest before we top it up. */
    private static final int BUTCHER_FUEL_THRESHOLD = 8;
    /** Minimum fishing rods in fisherman chest before we top it up. */
    private static final int FISHERMAN_ROD_THRESHOLD = 1;
    /** Minimum wheat seeds in farmer chest before we top it up. */
    private static final int FARMER_SEED_THRESHOLD = 32;
    /** Minimum planks in shepherd chest before we top it up (for bed crafting). */
    private static final int SHEPHERD_PLANK_THRESHOLD = 16;
    /** Minimum sticks in fletcher chest before we top it up. */
    private static final int FLETCHER_STICK_THRESHOLD = 16;
    /** Minimum iron ingots in toolsmith chest before we top it up. */
    private static final int TOOLSMITH_IRON_THRESHOLD = 16;
    /** Minimum iron ingots in armorer chest before we top it up. */
    private static final int ARMORER_IRON_THRESHOLD = 16;
    /** Minimum nether wart in cleric chest before we top it up. */
    private static final int CLERIC_WART_THRESHOLD = 8;
    /** Minimum paper in cartographer chest before we top it up. */
    private static final int CARTOGRAPHER_PAPER_THRESHOLD = 16;
    /** Minimum leather in leatherworker chest before we top it up. */
    private static final int LEATHERWORKER_LEATHER_THRESHOLD = 8;

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

    private final VillagerEntity villager;
    private final BlockPos jobPos;
    private final BlockPos chestPos;  // librarian's paired chest (double-chest second half)

    private long nextCheckTick = 0L;
    private boolean immediateCheckPending = false;
    private Stage stage = Stage.IDLE;
    private boolean anchorRegistered = false;
    private boolean demoted = false;

    // Current active transfer
    private BlockPos sourcePos = null;
    private BlockPos destPos = null;
    private ItemStack transferStack = ItemStack.EMPTY;

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
     * Schedules an immediate transfer-planning check on the next goal tick, bypassing
     * the normal {@link #CHECK_INTERVAL_TICKS} cooldown. Call when the QM's chest
     * inventory changes so shortages are addressed without waiting up to 5 seconds.
     */
    public void requestImmediateCheck() {
        immediateCheckPending = true;
    }

    /**
     * Unregisters this QM's chest anchor. Call when the QM dies or loses pairing.
     */
    public void unregisterAnchor(ServerWorld world) {
        VillageAnchorState.get(world.getServer()).unregister(world, chestPos);
    }

    private void ensureAnchorUnregistered(ServerWorld world) {
        if (anchorRegistered) {
            unregisterAnchor(world);
            anchorRegistered = false;
        }
    }

    // -------------------------------------------------------------------------
    // Goal lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) return false;
        if (!villager.isAlive() || villager.isRemoved()) {
            ensureAnchorUnregistered(world);
            return false;
        }
        if (!validateAndSyncPrerequisites(world)) return false;
        if (!anchorRegistered) {
            registerAnchor(world);
            anchorRegistered = true;
        }
        if (!immediateCheckPending && world.getTime() < nextCheckTick) return false;
        immediateCheckPending = false;
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
        if (!villager.isAlive() || villager.isRemoved()) {
            ensureAnchorUnregistered(world);
            stage = Stage.DONE;
            villager.getNavigation().stop();
            return;
        }
        if (!validateAndSyncPrerequisites(world)) {
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

    private boolean validateAndSyncPrerequisites(ServerWorld world) {
        QuartermasterPrerequisiteHelper.Result result =
                QuartermasterPrerequisiteHelper.validate(world, villager, jobPos, chestPos);
        if (result.valid()) {
            demoted = false;
            return true;
        }

        ensureAnchorUnregistered(world);
        if (!demoted) {
            Optional<BlockPos> secondChest = findDoubleChestOtherHalf(world, chestPos);
            String secondChestText = secondChest.map(BlockPos::toShortString).orElse("none");
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
        BlockPos bellChestPos = resolveBellChestPos(world);

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

        // Priority 1b: butcher guard needs coal/charcoal to fuel the smoker.
        Optional<BlockPos> butcherNeedingFuel = findButcherNeedingFuel(world);
        if (butcherNeedingFuel.isPresent()) {
            ItemStack fuel = findBestFuelItem(world, chestPos);
            if (!fuel.isEmpty()) {
                sourcePos = chestPos;
                destPos = butcherNeedingFuel.get();
                transferStack = fuel.copyWithCount(Math.min(HAUL_AMOUNT, fuel.getCount()));
                LOGGER.debug("QM {}: hauling {} to butcher at {}", villager.getUuidAsString(), fuel.getItem(), destPos.toShortString());
                return true;
            }
        }

        // Priority 1c: fisherman guard has no fishing rod.
        Optional<BlockPos> fishermanNeedingRod = findFishermanNeedingRod(world);
        if (fishermanNeedingRod.isPresent()) {
            int rodsAvailable = countItem(world, chestPos, Items.FISHING_ROD);
            if (rodsAvailable > 0) {
                sourcePos = chestPos;
                destPos = fishermanNeedingRod.get();
                transferStack = new ItemStack(Items.FISHING_ROD, Math.min(HAUL_AMOUNT_RODS, rodsAvailable));
                LOGGER.debug("QM {}: hauling fishing rod to fisherman at {}", villager.getUuidAsString(), destPos.toShortString());
                return true;
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
                LOGGER.info("QM {}: hauling {} planks from bell chest to weaponsmith at {}", villager.getUuidAsString(), bestPlanks.getItem(), destPos.toShortString());
                return true;
            } else {
                LOGGER.info("QM {}: weaponsmith at {} needs planks but bell chest at {} has none",
                        villager.getUuidAsString(), weaponsmithChestNeedingPlanks.get().toShortString(),
                        bellChestPos.toShortString());
            }
        }

        // Priority 2c: lumberjack needs cobblestone for furnace crafting.
        // Deliver exactly 8 cobblestone so the lumberjack can craft a furnace for charcoal production.
        // Skip if a furnace already exists near the lumberjack's job site.
        Optional<LumberjackFurnaceStoneNeed> lumberjackNeedingFurnaceStone = findLumberjackNeedingFurnaceStone(world);
        if (lumberjackNeedingFurnaceStone.isPresent()) {
            LumberjackFurnaceStoneNeed need = lumberjackNeedingFurnaceStone.get();
            // Prefer mason chest as source; fall back to QM's own chest
            BlockPos stoneSource = findMasonChestWithCobblestone(world, LUMBERJACK_FURNACE_STONE_AMOUNT);
            if (stoneSource == null && countItem(world, chestPos, Items.COBBLESTONE) >= LUMBERJACK_FURNACE_STONE_AMOUNT) {
                stoneSource = chestPos;
            }
            if (stoneSource != null) {
                int available = countItem(world, stoneSource, Items.COBBLESTONE);
                int toDeliver = Math.min(LUMBERJACK_FURNACE_STONE_AMOUNT, available);
                sourcePos = stoneSource;
                destPos = need.chestPos();
                transferStack = new ItemStack(Items.COBBLESTONE, toDeliver);
                LOGGER.info("QM {}: hauling {} cobblestone from {} to lumberjack {} for furnace crafting",
                        villager.getUuidAsString(), toDeliver, stoneSource.toShortString(), destPos.toShortString());
                return true;
            }
        }

        // Priority 2d: farmer needs wheat seeds for the planting cycle.
        Optional<BlockPos> farmerNeedingSeeds = findProfessionChestNeedingItem(
                world, VillagerProfession.FARMER, Items.WHEAT_SEEDS, FARMER_SEED_THRESHOLD);
        if (farmerNeedingSeeds.isPresent()) {
            int available = countItem(world, chestPos, Items.WHEAT_SEEDS);
            if (available > 0) {
                sourcePos = chestPos;
                destPos = farmerNeedingSeeds.get();
                transferStack = new ItemStack(Items.WHEAT_SEEDS, Math.min(HAUL_AMOUNT, available));
                LOGGER.debug("QM {}: hauling wheat seeds to farmer at {}", villager.getUuidAsString(), destPos.toShortString());
                return true;
            }
        }

        // Priority 2e: shepherd needs planks for bed crafting.
        // Use tag-based total for the threshold check, then tag-based delivery from bell chest.
        Optional<BlockPos> shepherdNeedingPlanks = findProfessionChestNeedingTagItem(
                world, VillagerProfession.SHEPHERD, ItemTags.PLANKS, SHEPHERD_PLANK_THRESHOLD);
        if (shepherdNeedingPlanks.isPresent() && bellChestPos != null) {
            ItemStack bestPlanks = findBestTagItem(world, bellChestPos, ItemTags.PLANKS);
            if (!bestPlanks.isEmpty()) {
                sourcePos = bellChestPos;
                destPos = shepherdNeedingPlanks.get();
                transferStack = bestPlanks.copyWithCount(Math.min(HAUL_AMOUNT, bestPlanks.getCount()));
                LOGGER.debug("QM {}: hauling {} planks to shepherd at {}", villager.getUuidAsString(), bestPlanks.getItem(), destPos.toShortString());
                return true;
            }
        }

        // Priority 2f: fletcher needs sticks for arrow crafting.
        Optional<BlockPos> fletcherNeedingSticks = findProfessionChestNeedingItem(
                world, VillagerProfession.FLETCHER, Items.STICK, FLETCHER_STICK_THRESHOLD);
        if (fletcherNeedingSticks.isPresent()) {
            int available = countItem(world, chestPos, Items.STICK);
            if (available > 0) {
                sourcePos = chestPos;
                destPos = fletcherNeedingSticks.get();
                transferStack = new ItemStack(Items.STICK, Math.min(HAUL_AMOUNT, available));
                LOGGER.debug("QM {}: hauling sticks to fletcher at {}", villager.getUuidAsString(), destPos.toShortString());
                return true;
            }
        }

        // Priority 2g: toolsmith needs iron ingots for tool crafting.
        Optional<BlockPos> toolsmithNeedingIron = findProfessionChestNeedingItem(
                world, VillagerProfession.TOOLSMITH, Items.IRON_INGOT, TOOLSMITH_IRON_THRESHOLD);
        if (toolsmithNeedingIron.isPresent()) {
            int available = countItem(world, chestPos, Items.IRON_INGOT);
            if (available > 0) {
                sourcePos = chestPos;
                destPos = toolsmithNeedingIron.get();
                transferStack = new ItemStack(Items.IRON_INGOT, Math.min(HAUL_AMOUNT, available));
                LOGGER.debug("QM {}: hauling iron ingots to toolsmith at {}", villager.getUuidAsString(), destPos.toShortString());
                return true;
            }
        }

        // Priority 2h: armorer needs iron ingots for armor crafting.
        Optional<BlockPos> armorerNeedingIron = findProfessionChestNeedingItem(
                world, VillagerProfession.ARMORER, Items.IRON_INGOT, ARMORER_IRON_THRESHOLD);
        if (armorerNeedingIron.isPresent()) {
            int available = countItem(world, chestPos, Items.IRON_INGOT);
            if (available > 0) {
                sourcePos = chestPos;
                destPos = armorerNeedingIron.get();
                transferStack = new ItemStack(Items.IRON_INGOT, Math.min(HAUL_AMOUNT, available));
                LOGGER.debug("QM {}: hauling iron ingots to armorer at {}", villager.getUuidAsString(), destPos.toShortString());
                return true;
            }
        }

        // Priority 2i: cleric needs nether wart for brewing.
        Optional<BlockPos> clericNeedingWart = findProfessionChestNeedingItem(
                world, VillagerProfession.CLERIC, Items.NETHER_WART, CLERIC_WART_THRESHOLD);
        if (clericNeedingWart.isPresent()) {
            int available = countItem(world, chestPos, Items.NETHER_WART);
            if (available > 0) {
                sourcePos = chestPos;
                destPos = clericNeedingWart.get();
                transferStack = new ItemStack(Items.NETHER_WART, Math.min(HAUL_AMOUNT, available));
                LOGGER.debug("QM {}: hauling nether wart to cleric at {}", villager.getUuidAsString(), destPos.toShortString());
                return true;
            }
        }

        // Priority 2j: cartographer needs paper for map crafting.
        Optional<BlockPos> cartographerNeedingPaper = findProfessionChestNeedingItem(
                world, VillagerProfession.CARTOGRAPHER, Items.PAPER, CARTOGRAPHER_PAPER_THRESHOLD);
        if (cartographerNeedingPaper.isPresent()) {
            int available = countItem(world, chestPos, Items.PAPER);
            if (available > 0) {
                sourcePos = chestPos;
                destPos = cartographerNeedingPaper.get();
                transferStack = new ItemStack(Items.PAPER, Math.min(HAUL_AMOUNT, available));
                LOGGER.debug("QM {}: hauling paper to cartographer at {}", villager.getUuidAsString(), destPos.toShortString());
                return true;
            }
        }

        // Priority 2k: leatherworker needs leather for armor crafting.
        Optional<BlockPos> leatherworkerNeedingLeather = findProfessionChestNeedingItem(
                world, VillagerProfession.LEATHERWORKER, Items.LEATHER, LEATHERWORKER_LEATHER_THRESHOLD);
        if (leatherworkerNeedingLeather.isPresent()) {
            int available = countItem(world, chestPos, Items.LEATHER);
            if (available > 0) {
                sourcePos = chestPos;
                destPos = leatherworkerNeedingLeather.get();
                transferStack = new ItemStack(Items.LEATHER, Math.min(HAUL_AMOUNT, available));
                LOGGER.debug("QM {}: hauling leather to leatherworker at {}", villager.getUuidAsString(), destPos.toShortString());
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

    // -------------------------------------------------------------------------
    // Scouts
    // -------------------------------------------------------------------------

    private Optional<MasonGuardEntity> findMasonNeedingStone(ServerWorld world) {
        Box box = new Box(jobPos).expand(SCAN_RANGE);
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
        Box box = new Box(jobPos).expand(SCAN_RANGE);
        return world.getEntitiesByClass(LumberjackGuardEntity.class, box,
                lj -> lj.isAlive()
                        && lj.getPairedChestPos() != null
                        && countTagItems(world, lj.getPairedChestPos(), ItemTags.PLANKS) < LUMBERJACK_PLANK_THRESHOLD
        ).stream().findFirst();
    }

    /**
     * Finds the paired chest of the first Butcher guard within scan range that has fewer than
     * {@link #BUTCHER_FUEL_THRESHOLD} total coal+charcoal. The butcher's smoker needs fuel to
     * cook meat; without it, the production pipeline stalls.
     */
    private Optional<BlockPos> findButcherNeedingFuel(ServerWorld world) {
        Box box = new Box(jobPos).expand(SCAN_RANGE);
        return world.getEntitiesByClass(ButcherGuardEntity.class, box,
                bg -> bg.isAlive()
                        && bg.getPairedChestPos() != null
                        && countItem(world, bg.getPairedChestPos(), Items.COAL)
                           + countItem(world, bg.getPairedChestPos(), Items.CHARCOAL) < BUTCHER_FUEL_THRESHOLD
        ).stream().findFirst().map(ButcherGuardEntity::getPairedChestPos);
    }

    /**
     * Finds the paired chest of the first Fisherman guard within scan range that has fewer than
     * {@link #FISHERMAN_ROD_THRESHOLD} fishing rods. Without a rod the fisherman cannot fish.
     */
    private Optional<BlockPos> findFishermanNeedingRod(ServerWorld world) {
        Box box = new Box(jobPos).expand(SCAN_RANGE);
        return world.getEntitiesByClass(FishermanGuardEntity.class, box,
                fg -> fg.isAlive()
                        && fg.getPairedChestPos() != null
                        && countItem(world, fg.getPairedChestPos(), Items.FISHING_ROD) < FISHERMAN_ROD_THRESHOLD
        ).stream().findFirst().map(FishermanGuardEntity::getPairedChestPos);
    }

    /**
     * Generic scout for v2-behavior villagers (those that remain {@link VillagerEntity} instances
     * with a {@link net.minecraft.entity.ai.brain.MemoryModuleType#JOB_SITE} brain memory).
     * Finds the paired chest of the first villager of {@code profession} within scan range that
     * has fewer than {@code threshold} of {@code item}.
     *
     * <p>Uses the same JOB_SITE + {@link JobBlockPairingHelper#findNearbyChest} pattern as
     * {@link #findSurplusChest} for reliable chest discovery regardless of villager position.
     */
    private Optional<BlockPos> findProfessionChestNeedingItem(
            ServerWorld world, VillagerProfession profession,
            Item item, int threshold) {
        Box box = new Box(jobPos).expand(SCAN_RANGE);
        for (VillagerEntity v : world.getEntitiesByClass(
                VillagerEntity.class, box,
                v -> v.isAlive() && v.getVillagerData().getProfession() == profession)) {
            BlockPos vJobSite = v.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE)
                    .filter(gp -> gp.dimension() == world.getRegistryKey())
                    .map(GlobalPos::pos)
                    .orElse(null);
            if (vJobSite == null) continue;
            Optional<BlockPos> pairedChest = JobBlockPairingHelper.findNearbyChest(world, vJobSite);
            if (pairedChest.isEmpty()) continue;
            BlockPos immutable = pairedChest.get().toImmutable();
            if (countItem(world, immutable, item) < threshold) {
                return Optional.of(immutable);
            }
        }
        return Optional.empty();
    }

    /**
     * Tag-based variant of {@link #findProfessionChestNeedingItem}: finds the paired chest of the
     * first villager of {@code profession} within scan range that has fewer than {@code threshold}
     * total items matching {@code tag}.
     */
    private Optional<BlockPos> findProfessionChestNeedingTagItem(
            ServerWorld world, VillagerProfession profession,
            net.minecraft.registry.tag.TagKey<Item> tag, int threshold) {
        Box box = new Box(jobPos).expand(SCAN_RANGE);
        for (VillagerEntity v : world.getEntitiesByClass(
                VillagerEntity.class, box,
                v -> v.isAlive() && v.getVillagerData().getProfession() == profession)) {
            BlockPos vJobSite = v.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE)
                    .filter(gp -> gp.dimension() == world.getRegistryKey())
                    .map(GlobalPos::pos)
                    .orElse(null);
            if (vJobSite == null) continue;
            Optional<BlockPos> pairedChest = JobBlockPairingHelper.findNearbyChest(world, vJobSite);
            if (pairedChest.isEmpty()) continue;
            BlockPos immutable = pairedChest.get().toImmutable();
            if (countTagItems(world, immutable, tag) < threshold) {
                return Optional.of(immutable);
            }
        }
        return Optional.empty();
    }

    /**
     * Finds the most abundant fuel item (coal preferred over charcoal) in the chest at {@code pos}.
     * Returns {@link ItemStack#EMPTY} if neither is present.
     */
    private ItemStack findBestFuelItem(ServerWorld world, BlockPos pos) {
        int coalCount = countItem(world, pos, Items.COAL);
        if (coalCount > 0) return new ItemStack(Items.COAL, coalCount);
        int charcoalCount = countItem(world, pos, Items.CHARCOAL);
        if (charcoalCount > 0) return new ItemStack(Items.CHARCOAL, charcoalCount);
        return ItemStack.EMPTY;
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
        Box box = new Box(jobPos).expand(SCAN_RANGE);
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
        Box box = new Box(jobPos).expand(SCAN_RANGE);
        for (MasonGuardEntity mason : world.getEntitiesByClass(MasonGuardEntity.class, box, MasonGuardEntity::isAlive)) {
            BlockPos mc = mason.getPairedChestPos();
            if (mc != null && countItem(world, mc, Items.COBBLESTONE) >= minAmount) {
                return mc;
            }
        }
        return null;
    }

    private record LumberjackFurnaceStoneNeed(LumberjackGuardEntity lumberjack, BlockPos chestPos) {}

    private Optional<BlockPos> findWeaponsmithChestNeedingPlanks(ServerWorld world) {
        for (BlockPos chestPos : WeaponsmithBehavior.getPairedChestPositions()) {
            if (chestPos.isWithinDistance(jobPos, SCAN_RANGE)
                    && countTagItems(world, chestPos, ItemTags.PLANKS) < WEAPONSMITH_PLANK_THRESHOLD) {
                return Optional.of(chestPos);
            }
        }
        return Optional.empty();
    }

    private Optional<BlockPos> findSurplusChest(ServerWorld world, BlockPos bellChestPos) {
        // Find any chest near job-site villagers with more items than threshold.
        // Must exclude:
        //   - bellChestPos  (we're trying to fill it, not drain it further)
        //   - chestPos      (the QM's own transit buffer — draining it causes haul loops)
        //   - mason paired chests  (draining them undoes Priority 1 stone hauls)
        //   - lumberjack paired chests (draining them undoes Priority 2 plank hauls)
        Box box = new Box(jobPos).expand(SCAN_RANGE);

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
        // Protect fisherman and butcher guard chests — their contents (fish, meat, rods) are
        // specialist goods that should never be drained into the QM bank by the surplus haul.
        for (FishermanGuardEntity fg : world.getEntitiesByClass(FishermanGuardEntity.class, box, FishermanGuardEntity::isAlive)) {
            if (fg.getPairedChestPos() != null) {
                protectedChests.add(fg.getPairedChestPos());
                findDoubleChestOtherHalf(world, fg.getPairedChestPos()).ifPresent(protectedChests::add);
            }
        }
        for (ButcherGuardEntity bg : world.getEntitiesByClass(ButcherGuardEntity.class, box, ButcherGuardEntity::isAlive)) {
            if (bg.getPairedChestPos() != null) {
                protectedChests.add(bg.getPairedChestPos());
                findDoubleChestOtherHalf(world, bg.getPairedChestPos()).ifPresent(protectedChests::add);
            }
        }
        // Protect shepherd chests — they contain beds + wool + planks needed for bed economy.
        // Without this, QM Priority-3 hauls beds out of the shepherd chest into the bell chest.
        for (VillagerEntity shepherd : world.getEntitiesByClass(VillagerEntity.class, box,
                v -> v.isAlive() && v.getVillagerData().getProfession() == VillagerProfession.SHEPHERD)) {
            BlockPos jobSite = shepherd.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE)
                    .filter(gp -> gp.dimension() == world.getRegistryKey())
                    .map(GlobalPos::pos)
                    .orElse(null);
            if (jobSite != null) {
                JobBlockPairingHelper.findNearbyChest(world, jobSite).ifPresent(shepherdChest -> {
                    protectedChests.add(shepherdChest);
                    // Also protect the other half if it is a double-chest.
                    findDoubleChestOtherHalf(world, shepherdChest).ifPresent(protectedChests::add);
                });
            }
        }

        // Discover surplus chests via JOB_SITE brain memory, NOT v.getBlockPos().
        // Villagers wander constantly; their chest is almost never within ±3 blocks of wherever
        // they happen to be standing when this scan fires. Scanning by job-site makes chest
        // discovery reliable regardless of villager movement. This was the root cause of
        // Priority-3 surplus haul almost never firing despite chests having surplus material.
        List<VillagerEntity> villagers = world.getEntitiesByClass(VillagerEntity.class, box, Entity::isAlive);
        for (VillagerEntity v : villagers) {
            if (v == villager) continue;
            BlockPos vJobSite = v.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE)
                    .filter(gp -> gp.dimension() == world.getRegistryKey())
                    .map(GlobalPos::pos)
                    .orElse(null);
            if (vJobSite == null) continue;
            // Use the same pairing helper the rest of the mod uses to find a chest near a job site.
            Optional<BlockPos> pairedChest = JobBlockPairingHelper.findNearbyChest(world, vJobSite);
            if (pairedChest.isEmpty()) continue;
            BlockPos immutable = pairedChest.get().toImmutable();
            // Skip all protected chests (bell, QM transit, mason, lumberjack, shepherd)
            if (protectedChests.contains(immutable)) continue;
            // Only count whitelisted bulk materials so specialist trade goods (arrows, potions,
            // enchanted books, iron gear, fish, maps, meat) never trigger the threshold.
            if (countWhitelistedItems(world, immutable) > BELL_CHEST_LOW_THRESHOLD * 2) {
                return Optional.of(immutable);
            }
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Inventory helpers
    // -------------------------------------------------------------------------

    private boolean takeFromInventory(ServerWorld world, BlockPos pos) {
        Optional<Inventory> inv = getInventory(world, pos);
        if (inv.isEmpty() || transferStack.isEmpty()) return false;
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
        if (inv.isEmpty() || transferStack.isEmpty()) return;
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
            LOGGER.info("QM {}: dest chest at {} full, dropping {} x {} at villager feet",
                    villager.getUuidAsString(), pos.toShortString(),
                    remaining.getCount(), remaining.getItem());
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

    private Optional<Inventory> getInventory(ServerWorld world, BlockPos pos) {
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
        Box box = new Box(anchor).expand(range);
        List<net.minecraft.entity.passive.VillagerEntity> librarians = world.getEntitiesByClass(
                net.minecraft.entity.passive.VillagerEntity.class,
                box,
                v -> v.isAlive()
                        && v.getVillagerData().getProfession() == net.minecraft.village.VillagerProfession.LIBRARIAN);

        for (net.minecraft.entity.passive.VillagerEntity librarian : librarians) {
            for (PrioritizedGoal prioritizedGoal : librarian.goalSelector.getGoals()) {
                if (prioritizedGoal.getGoal() instanceof QuartermasterGoal) {
                    LOGGER.debug(
                            "Quartermaster presence check: true (anchor={} range={} librarian={} running={})",
                            anchor.toShortString(),
                            range,
                            librarian.getUuidAsString(),
                            prioritizedGoal.isRunning());
                    return true;
                }
            }
        }

        LOGGER.debug(
                "Quartermaster presence check: false (anchor={} range={} librarians_scanned={})",
                anchor.toShortString(),
                range,
                librarians.size());
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
}
