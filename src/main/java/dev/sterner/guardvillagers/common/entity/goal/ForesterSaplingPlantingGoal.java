package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.VillageAnchorState;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Once per day the Forester fetches all saplings from their paired chest and
 * plants them at the village outskirts – between {@link #MIN_PLANT_DISTANCE} and
 * {@link #MAX_PLANT_DISTANCE} blocks from the village anchor (QM chest), with at
 * least {@link #MIN_SAPLING_SPACING} blocks between each planted sapling.
 *
 * <p>Valid planting ground: dirt, grass, podzol, coarse dirt, rooted dirt, or moss.
 * The block immediately above must be air, and no existing sapling block may exist
 * within {@link #MIN_SAPLING_SPACING} of the candidate.
 */
public class ForesterSaplingPlantingGoal extends Goal {

    private static final Logger LOGGER = LoggerFactory.getLogger(ForesterSaplingPlantingGoal.class);

    /** Minimum distance (blocks) from village anchor to plant saplings. */
    private static final int MIN_PLANT_DISTANCE = 16;
    /** Maximum distance (blocks) from village anchor to plant saplings. */
    private static final int MAX_PLANT_DISTANCE = 64;
    /** Minimum gap between any two planted saplings. */
    private static final int MIN_SAPLING_SPACING = 5;
    /** Maximum saplings planted in a single day. Matches the provision amount. */
    private static final int SAPLINGS_PER_DAY = 4;
    /** Radius of the volume scanned for planting candidates. */
    private static final int PLANT_SCAN_RADIUS = 80;
    /**
     * Maximum block positions evaluated per canStart() to avoid tick stalls.
     *
     * <p>The scan iterates a 160×16×160 box (~409K positions) but filters by
     * horizontal distance band (16–64 blocks). A budget of 256 was far too low —
     * the iterator starts in the bounding-box corner and the first ~hundreds of
     * positions all fail the MIN_PLANT_DISTANCE check before we ever reach the
     * valid ring. Raised to 8192 so the scan reliably reaches valid candidates.
     * This goal fires at most once per day so the cost is negligible.
     */
    private static final int SCAN_BUDGET = 8192;
    /** Y range above/below the anchor to scan. */
    private static final int SCAN_Y_RANGE = 8;

    private static final double MOVE_SPEED = 0.6D;
    private static final double CHEST_REACH_SQ = 3.5D * 3.5D;
    private static final double PLANT_REACH_SQ = 2.5D * 2.5D;
    private static final int PATH_RETRY_TICKS = 20;

    /** Blocks that saplings can be planted on. */
    private static final Set<Block> VALID_GROUND = Set.of(
            Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.PODZOL,
            Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.MOSS_BLOCK
    );

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;

    private Stage stage = Stage.IDLE;
    private long lastPlantingDay = -1L;
    private final Deque<BlockPos> plantTargets = new ArrayDeque<>();
    /** Item currently held (taken from chest) waiting to be planted. */
    private Item heldSaplingItem = null;
    private BlockPos currentNavTarget = null;
    private long lastPathRequestTick = Long.MIN_VALUE;
    private boolean needsWorkCheck = false;

    /** Linked provision goal – receives feedback when planting spots are exhausted or succeed. */
    private ForesterSaplingProvisionGoal linkedProvisionGoal = null;

    private enum Stage { IDLE, FETCH_FROM_CHEST, MOVE_TO_SITE, PLANT, RETURN_TO_CHEST, DONE }

    public ForesterSaplingPlantingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos != null ? chestPos.toImmutable() : null;
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos != null ? chestPos.toImmutable() : null;
        this.stage = Stage.IDLE;
    }

    public void requestImmediateWorkCheck() {
        needsWorkCheck = true;
    }

    /** Wire the provision goal so this goal can report back planting outcomes. */
    public void linkProvisionGoal(ForesterSaplingProvisionGoal goal) {
        this.linkedProvisionGoal = goal;
    }

    // -----------------------------------------------------------------------------------------
    // Goal lifecycle
    // -----------------------------------------------------------------------------------------

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) return false;
        if (!villager.isAlive() || !world.isDay()) return false;
        if (jobPos == null) return false;

        long currentDay = world.getTimeOfDay() / 24000L;
        if (currentDay == lastPlantingDay && !needsWorkCheck) return false;
        needsWorkCheck = false;

        if (chestPos == null) {
            // V1 mode: saplings must already be in the villager's inventory (placed by provision goal)
            if (!hasSaplingInVillagerInventory()) return false;
        } else {
            // V2 mode: fetch from chest
            Optional<Inventory> invOpt = getChestInventory(world);
            if (invOpt.isEmpty()) return false;
            if (countSaplingsInInventory(invOpt.get()) == 0) return false;
        }

        // Need at least one valid planting spot
        List<BlockPos> targets = findPlantingTargets(world);
        if (targets.isEmpty()) {
            LOGGER.debug("[forester] {} found no valid planting sites", villager.getUuidAsString());
            if (linkedProvisionGoal != null) {
                linkedProvisionGoal.reportNoPlantingSpots();
            }
            return false;
        }

        plantTargets.clear();
        plantTargets.addAll(targets);
        return true;
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.IDLE && stage != Stage.DONE && villager.isAlive();
    }

    @Override
    public void start() {
        if (chestPos == null) {
            // V1 mode: saplings already in inventory – skip fetch step
            heldSaplingItem = findFirstSaplingInVillagerInventory();
            stage = Stage.MOVE_TO_SITE;
            if (!plantTargets.isEmpty()) moveTo(plantTargets.peek());
        } else {
            stage = Stage.FETCH_FROM_CHEST;
            moveTo(chestPos);
        }
        LOGGER.debug("[forester] {} starting planting run ({} targets, chestless={})",
                villager.getUuidAsString(), plantTargets.size(), chestPos == null);
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        currentNavTarget = null;
        lastPathRequestTick = Long.MIN_VALUE;
        // Return any held sapling back to the chest if we stopped mid-run (v2 mode only)
        if (heldSaplingItem != null && chestPos != null && villager.getWorld() instanceof ServerWorld world) {
            Optional<Inventory> invOpt = getChestInventory(world);
            invOpt.ifPresent(inv -> depositAllSaplingsFromVillager(world, inv));
        }
        heldSaplingItem = null;
        plantTargets.clear();
        stage = Stage.IDLE;
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case FETCH_FROM_CHEST -> {
                if (isNear(chestPos, CHEST_REACH_SQ)) {
                    Optional<Inventory> invOpt = getChestInventory(world);
                    if (invOpt.isEmpty() || countSaplingsInInventory(invOpt.get()) == 0) {
                        stage = Stage.DONE;
                        return;
                    }
                    takeSaplingsFromChest(world, invOpt.get());
                    if (plantTargets.isEmpty() || heldSaplingItem == null) {
                        stage = Stage.DONE;
                        return;
                    }
                    stage = Stage.MOVE_TO_SITE;
                    moveTo(plantTargets.peek());
                } else {
                    moveTo(chestPos);
                }
            }
            case MOVE_TO_SITE -> {
                BlockPos target = plantTargets.peek();
                if (target == null) {
                    stage = Stage.RETURN_TO_CHEST;
                    return;
                }
                if (isNear(target, PLANT_REACH_SQ)) {
                    stage = Stage.PLANT;
                } else {
                    moveTo(target);
                }
            }
            case PLANT -> {
                BlockPos target = plantTargets.poll();
                if (target == null || heldSaplingItem == null) {
                    stage = Stage.RETURN_TO_CHEST;
                    return;
                }
                if (tryPlantSapling(world, target)) {
                    consumeOneFromVillagerInventory(heldSaplingItem);
                    LOGGER.debug("[forester] {} planted {} at {}", villager.getUuidAsString(), heldSaplingItem, target.toShortString());
                    if (linkedProvisionGoal != null) {
                        linkedProvisionGoal.reportPlantingSucceeded();
                    }
                    // Refresh held item in case this type is now exhausted (e.g. mixed types in inventory)
                    if (!hasSaplingOfTypeInVillagerInventory(heldSaplingItem)) {
                        heldSaplingItem = findFirstSaplingInVillagerInventory();
                    }
                }
                // Check if we can continue planting
                boolean hasSapling = hasSaplingInVillagerInventory();
                boolean hasTarget = !plantTargets.isEmpty();
                if (hasSapling && hasTarget) {
                    stage = Stage.MOVE_TO_SITE;
                    moveTo(plantTargets.peek());
                } else {
                    stage = Stage.RETURN_TO_CHEST;
                }
            }
            case RETURN_TO_CHEST -> {
                if (chestPos == null) {
                    // V1 mode: no chest to return to; leftover saplings stay in inventory for next cycle
                    stage = Stage.DONE;
                } else if (hasSaplingInVillagerInventory()) {
                    // V2 mode: deposit leftovers back into the chest
                    if (isNear(chestPos, CHEST_REACH_SQ)) {
                        Optional<Inventory> invOpt = getChestInventory(world);
                        invOpt.ifPresent(inv -> depositAllSaplingsFromVillager(world, inv));
                        stage = Stage.DONE;
                    } else {
                        moveTo(chestPos);
                    }
                } else {
                    stage = Stage.DONE;
                }
            }
            case DONE -> {
                lastPlantingDay = world.getTimeOfDay() / 24000L;
                heldSaplingItem = null;
                plantTargets.clear();
                stage = Stage.IDLE;
            }
            default -> {}
        }
    }

    // -----------------------------------------------------------------------------------------
    // Planting site discovery
    // -----------------------------------------------------------------------------------------

    private List<BlockPos> findPlantingTargets(ServerWorld world) {
        // Determine village center: prefer QM anchor, fall back to job block
        BlockPos center = VillageAnchorState.get(world.getServer())
                .getNearestQmChest(world, villager.getBlockPos(), 128)
                .orElse(jobPos);

        List<BlockPos> found = new ArrayList<>();
        int checked = 0;

        // Candidate positions already committed in this run (to enforce spacing)
        List<BlockPos> committed = new ArrayList<>();

        BlockPos.Mutable mut = new BlockPos.Mutable();
        // Scan a bounding box bounded by MAX_PLANT_DISTANCE (not the wider PLANT_SCAN_RADIUS)
        // so we don't waste budget iterating positions that are geometrically too far away.
        for (BlockPos candidate : BlockPos.iterate(
                center.add(-MAX_PLANT_DISTANCE, -SCAN_Y_RANGE, -MAX_PLANT_DISTANCE),
                center.add(MAX_PLANT_DISTANCE, SCAN_Y_RANGE, MAX_PLANT_DISTANCE))) {
            if (checked >= SCAN_BUDGET) break;
            checked++;

            double dx = candidate.getX() - center.getX();
            double dz = candidate.getZ() - center.getZ();
            double horizDist = Math.sqrt(dx * dx + dz * dz);

            if (horizDist < MIN_PLANT_DISTANCE || horizDist > MAX_PLANT_DISTANCE) continue;
            if (!VALID_GROUND.contains(world.getBlockState(candidate).getBlock())) continue;

            BlockPos above = candidate.up();
            if (!world.getBlockState(above).isAir()) continue;

            // No existing sapling block within spacing radius
            if (hasNearbySapling(world, candidate, MIN_SAPLING_SPACING)) continue;

            // No committed target within spacing radius
            boolean tooClose = false;
            for (BlockPos c : committed) {
                if (c.getManhattanDistance(candidate) < MIN_SAPLING_SPACING) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) continue;

            found.add(candidate.toImmutable());
            committed.add(candidate.toImmutable());
            if (found.size() >= SAPLINGS_PER_DAY) break;
        }

        return found;
    }

    private boolean hasNearbySapling(ServerWorld world, BlockPos center, int radius) {
        Box box = new Box(center).expand(radius);
        for (BlockPos pos : BlockPos.iterate(
                (int) box.minX, (int) box.minY, (int) box.minZ,
                (int) box.maxX, (int) box.maxY, (int) box.maxZ)) {
            if (world.getBlockState(pos).isIn(BlockTags.SAPLINGS)) return true;
        }
        return false;
    }

    private boolean tryPlantSapling(ServerWorld world, BlockPos groundPos) {
        if (heldSaplingItem == null) return false;
        if (!(heldSaplingItem instanceof BlockItem bi)) return false;
        Block saplingBlock = bi.getBlock();
        BlockPos above = groundPos.up();
        BlockState saplingState = saplingBlock.getDefaultState();
        if (!saplingState.canPlaceAt(world, above)) return false;
        world.setBlockState(above, saplingState, Block.NOTIFY_ALL);
        return true;
    }

    // -----------------------------------------------------------------------------------------
    // Villager inventory helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Moves all saplings from the chest into the villager's personal inventory.
     * Sets {@link #heldSaplingItem} to the first sapling type encountered.
     */
    private void takeSaplingsFromChest(ServerWorld world, Inventory chestInv) {
        Inventory villagerInv = villager.getInventory();
        for (int i = 0; i < chestInv.size() && !isVillagerInventoryFull(villagerInv); i++) {
            ItemStack stack = chestInv.getStack(i);
            if (stack.isEmpty() || !stack.isIn(ItemTags.SAPLINGS)) continue;
            ItemStack taken = stack.split(stack.getCount());
            chestInv.markDirty();
            if (heldSaplingItem == null) heldSaplingItem = taken.getItem();
            // Try to add to villager inventory
            for (int j = 0; j < villagerInv.size(); j++) {
                ItemStack existing = villagerInv.getStack(j);
                if (existing.isEmpty()) {
                    villagerInv.setStack(j, taken);
                    taken = ItemStack.EMPTY;
                    break;
                } else if (ItemStack.areItemsEqual(existing, taken)) {
                    int space = existing.getMaxCount() - existing.getCount();
                    int transfer = Math.min(space, taken.getCount());
                    existing.increment(transfer);
                    taken.decrement(transfer);
                    if (taken.isEmpty()) break;
                }
            }
            // If we couldn't fit it all, put remainder back
            if (!taken.isEmpty()) {
                chestInv.setStack(i, taken);
                chestInv.markDirty();
                break;
            }
        }
    }

    private void depositAllSaplingsFromVillager(ServerWorld world, Inventory chestInv) {
        Inventory villagerInv = villager.getInventory();
        for (int i = 0; i < villagerInv.size(); i++) {
            ItemStack stack = villagerInv.getStack(i);
            if (stack.isEmpty() || !stack.isIn(ItemTags.SAPLINGS)) continue;
            // Try to insert into chest
            for (int j = 0; j < chestInv.size(); j++) {
                ItemStack slot = chestInv.getStack(j);
                if (slot.isEmpty()) {
                    chestInv.setStack(j, stack.copy());
                    villagerInv.setStack(i, ItemStack.EMPTY);
                    chestInv.markDirty();
                    break;
                } else if (ItemStack.areItemsEqual(slot, stack)) {
                    int space = slot.getMaxCount() - slot.getCount();
                    int transfer = Math.min(space, stack.getCount());
                    slot.increment(transfer);
                    stack.decrement(transfer);
                    chestInv.markDirty();
                    if (stack.isEmpty()) {
                        villagerInv.setStack(i, ItemStack.EMPTY);
                        break;
                    }
                }
            }
        }
        heldSaplingItem = null;
    }

    private void consumeOneFromVillagerInventory(Item item) {
        Inventory villagerInv = villager.getInventory();
        for (int i = 0; i < villagerInv.size(); i++) {
            ItemStack stack = villagerInv.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                stack.decrement(1);
                if (stack.isEmpty()) villagerInv.setStack(i, ItemStack.EMPTY);
                return;
            }
        }
    }

    private boolean hasSaplingInVillagerInventory() {
        Inventory villagerInv = villager.getInventory();
        for (int i = 0; i < villagerInv.size(); i++) {
            if (villagerInv.getStack(i).isIn(ItemTags.SAPLINGS)) return true;
        }
        return false;
    }

    private boolean hasSaplingOfTypeInVillagerInventory(Item item) {
        if (item == null) return false;
        Inventory villagerInv = villager.getInventory();
        for (int i = 0; i < villagerInv.size(); i++) {
            ItemStack s = villagerInv.getStack(i);
            if (!s.isEmpty() && s.isOf(item)) return true;
        }
        return false;
    }

    private Item findFirstSaplingInVillagerInventory() {
        Inventory villagerInv = villager.getInventory();
        for (int i = 0; i < villagerInv.size(); i++) {
            ItemStack s = villagerInv.getStack(i);
            if (!s.isEmpty() && s.isIn(ItemTags.SAPLINGS)) return s.getItem();
        }
        return null;
    }

    private int countSaplingsInInventory(Inventory inv) {
        int count = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isIn(ItemTags.SAPLINGS)) count += s.getCount();
        }
        return count;
    }

    private boolean isVillagerInventoryFull(Inventory inv) {
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isEmpty()) return false;
        }
        return true;
    }

    // -----------------------------------------------------------------------------------------
    // Chest access
    // -----------------------------------------------------------------------------------------

    private Optional<Inventory> getChestInventory() {
        return getChestInventory((ServerWorld) villager.getWorld());
    }

    private Optional<Inventory> getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) return Optional.empty();
        Inventory inv = ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
        return Optional.ofNullable(inv);
    }

    // -----------------------------------------------------------------------------------------
    // Nav helpers
    // -----------------------------------------------------------------------------------------

    private void moveTo(BlockPos target) {
        if (target == null) return;
        long now = villager.getWorld().getTime();
        boolean shouldPath = !target.equals(currentNavTarget)
               || villager.getNavigation().isIdle()
                || now - lastPathRequestTick >= PATH_RETRY_TICKS;
        if (!shouldPath) return;
        villager.getNavigation().startMovingTo(
                target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
        currentNavTarget = target.toImmutable();
        lastPathRequestTick = now;
    }

    private boolean isNear(BlockPos target, double reachSq) {
        return target != null
                && villager.squaredDistanceTo(
                        target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= reachSq;
    }
}
