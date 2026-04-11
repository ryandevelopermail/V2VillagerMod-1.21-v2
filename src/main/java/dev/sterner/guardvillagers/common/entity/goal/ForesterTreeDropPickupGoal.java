package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Scans a large radius around the Forester's job site for tree drops left behind
 * by players or Lumberjacks — saplings, sticks, apples, and logs.
 *
 * <p><b>V2 mode (paired chest):</b> all collected items are deposited into the chest.
 * <p><b>V1 mode (no chest):</b> saplings are kept in the villager's own inventory
 * (where the planting goal reads from); other drops are discarded after collection
 * so they don't clog inventory slots.
 *
 * <p>Scanning is throttled by {@link #SCAN_COOLDOWN_TICKS} to avoid per-tick
 * world queries. Once a target item entity is acquired the Forester walks to it,
 * absorbs it, then either deposits into the chest (V2) or stays put (V1).
 */
public class ForesterTreeDropPickupGoal extends Goal {

    private static final Logger LOGGER = LoggerFactory.getLogger(ForesterTreeDropPickupGoal.class);

    /** Box half-width for the item entity scan around the job position. */
    private static final double PICKUP_RANGE = 100.0D;
    /** Ticks between successive item scans to limit world queries. */
    private static final int SCAN_COOLDOWN_TICKS = 80;
    /** Squared distance threshold for "close enough to pick up". */
    private static final double REACH_SQ = 2.5D * 2.5D;
    /** Squared distance for "close enough to deposit into chest". */
    private static final double CHEST_REACH_SQ = 3.5D * 3.5D;

    private static final double MOVE_SPEED = 0.65D;
    private static final int PATH_RETRY_TICKS = 20;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    /** Null in V1 mode — saplings stay in villager inventory instead. */
    private BlockPos chestPos;

    private Stage stage = Stage.IDLE;
    private ItemEntity targetItem = null;
    private int scanCooldown = 0;
    private BlockPos currentNavTarget = null;
    private long lastPathRequestTick = Long.MIN_VALUE;

    private enum Stage { IDLE, MOVE_TO_ITEM, DEPOSIT_TO_CHEST, DONE }

    public ForesterTreeDropPickupGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos != null ? chestPos.toImmutable() : null;
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos != null ? chestPos.toImmutable() : null;
        this.stage = Stage.IDLE;
        this.targetItem = null;
    }

    // -------------------------------------------------------------------------
    // Goal lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) return false;
        if (!villager.isAlive() || !world.isDay()) return false;
        if (jobPos == null) return false;
        // V1: also gate on having inventory space (don't chase drops we can't carry)
        if (chestPos == null && !hasInventorySpace()) return false;
        if (scanCooldown > 0) {
            scanCooldown--;
            return false;
        }
        scanCooldown = SCAN_COOLDOWN_TICKS;

        targetItem = findNearestTreeDrop(world);
        return targetItem != null;
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.IDLE && stage != Stage.DONE && villager.isAlive();
    }

    @Override
    public void start() {
        stage = Stage.MOVE_TO_ITEM;
        if (targetItem != null) moveTo(targetItem.getBlockPos());
        LOGGER.debug("[forester] {} starting tree-drop pickup run", villager.getUuidAsString());
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        currentNavTarget = null;
        lastPathRequestTick = Long.MIN_VALUE;
        targetItem = null;
        stage = Stage.IDLE;
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case MOVE_TO_ITEM -> {
                if (targetItem == null || !targetItem.isAlive() || targetItem.isRemoved()) {
                    // Try to find another nearby drop before giving up
                    targetItem = findNearestTreeDrop(world);
                    if (targetItem == null) {
                        stage = Stage.DONE;
                        return;
                    }
                }
                if (villager.squaredDistanceTo(targetItem) <= REACH_SQ) {
                    absorb(targetItem);
                    targetItem = null;
                    // Check if there are more drops to collect in one trip
                    ItemEntity next = findNearestTreeDrop(world);
                    if (next != null && hasInventorySpace()) {
                        targetItem = next;
                        moveTo(targetItem.getBlockPos());
                    } else if (chestPos != null) {
                        stage = Stage.DEPOSIT_TO_CHEST;
                        moveTo(chestPos);
                    } else {
                        // V1: saplings stay in villager inventory for the planting goal;
                        // discard any non-sapling items so they don't clog slots
                        purgNonSaplingsFromInventory();
                        stage = Stage.DONE;
                    }
                } else {
                    moveTo(targetItem.getBlockPos());
                }
            }
            case DEPOSIT_TO_CHEST -> {
                if (chestPos == null) {
                    stage = Stage.DONE;
                    return;
                }
                if (isNear(chestPos, CHEST_REACH_SQ)) {
                    Optional<Inventory> invOpt = getChestInventory(world);
                    if (invOpt.isPresent()) {
                        dumpVillagerInventoryToChest(invOpt.get());
                    }
                    stage = Stage.DONE;
                } else {
                    moveTo(chestPos);
                }
            }
            case DONE -> stage = Stage.IDLE;
            default -> {}
        }
    }

    // -------------------------------------------------------------------------
    // Item scanning
    // -------------------------------------------------------------------------

    private ItemEntity findNearestTreeDrop(ServerWorld world) {
        Box searchBox = new Box(jobPos).expand(PICKUP_RANGE);
        List<ItemEntity> drops = world.getEntitiesByClass(ItemEntity.class, searchBox,
                e -> e.isAlive() && !e.isRemoved() && isTreeDrop(e.getStack()));
        if (drops.isEmpty()) return null;

        // Return the closest qualifying drop
        double bestDistSq = Double.MAX_VALUE;
        ItemEntity best = null;
        for (ItemEntity e : drops) {
            double dSq = villager.squaredDistanceTo(e);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = e;
            }
        }
        return best;
    }

    /**
     * Returns true for items that Foresters should collect: saplings, logs,
     * sticks, and apples — the typical drops from a lumberjack tree harvest.
     */
    private static boolean isTreeDrop(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.isIn(ItemTags.SAPLINGS)
                || stack.isIn(ItemTags.LOGS)
                || stack.isOf(Items.STICK)
                || stack.isOf(Items.APPLE);
    }

    // -------------------------------------------------------------------------
    // Item absorption
    // -------------------------------------------------------------------------

    private void absorb(ItemEntity entity) {
        if (entity == null || !entity.isAlive()) return;
        ItemStack stack = entity.getStack().copy();
        if (stack.isEmpty()) return;
        ItemStack originalStack = stack.copy(); // snapshot before we mutate
        Inventory villagerInv = villager.getInventory();
        // Try to merge with existing stack
        for (int i = 0; i < villagerInv.size() && !stack.isEmpty(); i++) {
            ItemStack slot = villagerInv.getStack(i);
            if (!slot.isEmpty() && ItemStack.areItemsEqual(slot, stack)) {
                int space = slot.getMaxCount() - slot.getCount();
                int transfer = Math.min(space, stack.getCount());
                slot.increment(transfer);
                stack.decrement(transfer);
            }
        }
        // Place remainder in empty slots
        for (int i = 0; i < villagerInv.size() && !stack.isEmpty(); i++) {
            if (villagerInv.getStack(i).isEmpty()) {
                villagerInv.setStack(i, stack.copy());
                stack = ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            entity.discard();
            boolean isSapling = originalStack.isIn(net.minecraft.registry.tag.ItemTags.SAPLINGS);
            LOGGER.info("[forester-pickup] {} ABSORBED {} x {} ({})",
                    villager.getUuidAsString(),
                    originalStack.getCount(),
                    originalStack.getItem(),
                    isSapling ? "SAPLING" : "tree-drop");
        } else {
            entity.setStack(stack);
            LOGGER.info("[forester-pickup] {} PARTIAL absorb of {} — {} taken, {} left on ground",
                    villager.getUuidAsString(),
                    originalStack.getItem(),
                    originalStack.getCount() - stack.getCount(),
                    stack.getCount());
        }
    }

    private boolean hasInventorySpace() {
        Inventory inv = villager.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isEmpty()) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // V1 inventory management
    // -------------------------------------------------------------------------

    /**
     * In V1 mode the forester keeps its inventory for sapling planting.
     * Non-sapling tree drops (logs, sticks, apples) are cleared out so they
     * don't fill inventory slots that the provision goal needs for saplings.
     */
    private void purgNonSaplingsFromInventory() {
        Inventory inv = villager.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && !s.isIn(net.minecraft.registry.tag.ItemTags.SAPLINGS)) {
                inv.setStack(i, ItemStack.EMPTY);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Chest deposit
    // -------------------------------------------------------------------------

    private void dumpVillagerInventoryToChest(Inventory chestInv) {
        Inventory villagerInv = villager.getInventory();
        // Tally what we're about to deposit before touching anything
        int saplingsBefore = 0;
        int otherBefore = 0;
        for (int i = 0; i < villagerInv.size(); i++) {
            ItemStack s = villagerInv.getStack(i);
            if (s.isEmpty()) continue;
            if (s.isIn(net.minecraft.registry.tag.ItemTags.SAPLINGS)) saplingsBefore += s.getCount();
            else otherBefore += s.getCount();
        }
        for (int i = 0; i < villagerInv.size(); i++) {
            ItemStack stack = villagerInv.getStack(i);
            if (stack.isEmpty()) continue;
            // Try to merge with existing chest stacks
            for (int j = 0; j < chestInv.size() && !stack.isEmpty(); j++) {
                ItemStack slot = chestInv.getStack(j);
                if (!slot.isEmpty() && ItemStack.areItemsEqual(slot, stack)) {
                    int space = slot.getMaxCount() - slot.getCount();
                    int transfer = Math.min(space, stack.getCount());
                    slot.increment(transfer);
                    stack.decrement(transfer);
                    chestInv.markDirty();
                }
            }
            // Place remainder in empty chest slots
            for (int j = 0; j < chestInv.size() && !stack.isEmpty(); j++) {
                if (chestInv.getStack(j).isEmpty()) {
                    chestInv.setStack(j, stack.copy());
                    stack = ItemStack.EMPTY;
                    chestInv.markDirty();
                }
            }
            if (stack.isEmpty()) {
                villagerInv.setStack(i, ItemStack.EMPTY);
            } else {
                villagerInv.setStack(i, stack);
            }
        }
        LOGGER.info("[forester-pickup] {} DEPOSITED into chest at {} — saplings={} other={}",
                villager.getUuidAsString(), chestPos.toShortString(), saplingsBefore, otherBefore);
    }

    // -------------------------------------------------------------------------
    // Chest access
    // -------------------------------------------------------------------------

    private Optional<Inventory> getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) return Optional.empty();
        Inventory inv = ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
        return Optional.ofNullable(inv);
    }

    // -------------------------------------------------------------------------
    // Nav helpers
    // -------------------------------------------------------------------------

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
