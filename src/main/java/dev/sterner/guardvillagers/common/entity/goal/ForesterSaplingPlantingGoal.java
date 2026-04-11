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
import net.minecraft.server.network.ServerPlayerEntity;
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
 * The Forester fetches all available saplings from their paired chest (V2) or uses
 * saplings already in their own inventory (V1), then plants them at the village
 * outskirts at least {@link #MIN_PLANT_DISTANCE} blocks from the village anchor.
 *
 * <p><b>Batch / cooldown model (replaces the old one-per-day gate):</b>
 * After completing a planting run (whether saplings were planted or no spots were found),
 * a {@link #PLANTING_COOLDOWN_TICKS} cooldown starts before the goal tries again.
 * This means:
 * <ul>
 *   <li>If the chest is full of saplings, the forester plants them in batches at each
 *       cooldown boundary — not just once per day.</li>
 *   <li>If no planting sites are currently available, the forester waits the cooldown
 *       and retries rather than spinning in the log every tick.</li>
 * </ul>
 *
 * <p>Valid planting ground: dirt, grass, podzol, coarse dirt, rooted dirt, or moss.
 * The block immediately above must be air, and no existing sapling/log may exist
 * within {@link #MIN_SAPLING_SPACING} of the candidate.
 */
public class ForesterSaplingPlantingGoal extends Goal {

    private static final Logger LOGGER = LoggerFactory.getLogger(ForesterSaplingPlantingGoal.class);

    /**
     * Minimum horizontal distance (blocks) from the village anchor to plant saplings.
     * Raised from 16 to 24 to keep trees well clear of village building footprints.
     */
    private static final int MIN_PLANT_DISTANCE = 24;
    /**
     * Maximum horizontal distance (blocks) from the village anchor.
     * Raised from 64 to 80 to give more candidate area given the larger minimum.
     */
    private static final int MAX_PLANT_DISTANCE = 80;
    /** Minimum gap (blocks) between any two planted saplings or existing saplings/logs. */
    private static final int MIN_SAPLING_SPACING = 6;
    /** Max saplings planted in a single run. */
    private static final int MAX_SAPLINGS_PER_RUN = 8;
    /**
     * Ticks to wait between planting runs (~10 minutes at 20 TPS).
     * Gives a natural rhythm: one batch every ~half a Minecraft day when saplings are plentiful.
     * Short enough that a chest stocked with many saplings still gets cleared in a few sessions.
     */
    private static final long PLANTING_COOLDOWN_TICKS = 12000L;
    /**
     * Shorter cooldown used when no planting sites were found.
     * Retries after ~3 minutes so new terrain/saplings get checked reasonably quickly.
     */
    private static final long NO_SPOTS_COOLDOWN_TICKS = 3600L;
    /** Y range above/below the anchor to scan. */
    private static final int SCAN_Y_RANGE = 10;

    private static final double MOVE_SPEED = 0.6D;
    private static final double CHEST_REACH_SQ = 3.5D * 3.5D;
    private static final double PLANT_REACH_SQ = 2.5D * 2.5D;
    private static final int PATH_RETRY_TICKS = 20;

    /**
     * Planting only starts when a player is within this many blocks of the forester.
     * Matches a typical server render/simulation distance (10 chunks × 16 = 160 blocks).
     * Using a fixed constant rather than querying the server's configured view distance
     * keeps the check cheap and predictable regardless of server settings.
     */
    private static final double PLAYER_PROXIMITY_RANGE = 160.0D;

    /** Blocks that saplings can be planted on. */
    private static final Set<Block> VALID_GROUND = Set.of(
            Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.PODZOL,
            Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.MOSS_BLOCK
    );

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;

    private Stage stage = Stage.IDLE;
    /** World-time tick at which the planting cooldown expires. */
    private long cooldownExpiresTick = 0L;
    /** The Minecraft day on which the cooldown was last set, used to reset it each new day. */
    private long cooldownSetDay = -1L;
    private final Deque<BlockPos> plantTargets = new ArrayDeque<>();
    /** Item currently held (taken from chest) waiting to be planted. */
    private Item heldSaplingItem = null;
    private BlockPos currentNavTarget = null;
    private long lastPathRequestTick = Long.MIN_VALUE;

    /** Linked provision goal – receives feedback when planting succeeds. */
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

        // Only run when a player is within render distance — avoids background churn
        // and ensures the player can actually observe the forester planting trees.
        if (!isPlayerNearby(world)) return false;

        // Each new Minecraft day, reset the planting cooldown so the forester always
        // attempts a fresh run at day-start when a player enters render distance.
        // Without this, if the forester drained its saplings mid-day and the 10-minute
        // cooldown hadn't expired yet, it would stay idle until the cooldown ticked out
        // even though a new provision batch is now available.
        long now = world.getTime();
        long currentDay = now / 24000L;
        if (currentDay > cooldownSetDay && cooldownSetDay >= 0) {
            // New day — clear the cooldown so planting can fire immediately this morning.
            cooldownExpiresTick = 0L;
        }
        // Always stamp the current day so we track when the cooldown was last touched.
        cooldownSetDay = currentDay;

        // Cooldown gate — single check, no per-tick log spam
        if (now < cooldownExpiresTick) {
            return false;
        }

        if (chestPos == null) {
            // V1 mode: saplings must already be in the villager's inventory (placed by provision goal)
            if (!hasSaplingInVillagerInventory()) {
                return false;
            }
        } else {
            // V2 mode: check chest has saplings
            Optional<Inventory> invOpt = getChestInventory(world);
            if (invOpt.isEmpty()) {
                LOGGER.debug("[forester-planting] {} chest at {} not found, skipping",
                        villager.getUuidAsString(), chestPos.toShortString());
                return false;
            }
            int saplingCount = countSaplingsInInventory(invOpt.get());
            if (saplingCount == 0) {
                LOGGER.debug("[forester-planting] {} chest at {} has 0 saplings — nothing to plant",
                        villager.getUuidAsString(), chestPos.toShortString());
                return false;
            }
            LOGGER.info("[forester-planting] {} DETECTED {} sapling(s) in paired chest at {}",
                    villager.getUuidAsString(), saplingCount, chestPos.toShortString());
        }

        // Need at least one valid planting spot
        List<BlockPos> targets = findPlantingTargets(world);
        if (targets.isEmpty()) {
            // No spots right now — set a shorter retry cooldown and stay quiet
            cooldownExpiresTick = now + NO_SPOTS_COOLDOWN_TICKS;
            LOGGER.info("[forester-planting] {} no valid planting sites (ring {}–{} blocks); will retry in {} ticks",
                    villager.getUuidAsString(), MIN_PLANT_DISTANCE, MAX_PLANT_DISTANCE, NO_SPOTS_COOLDOWN_TICKS);
            if (linkedProvisionGoal != null) {
                linkedProvisionGoal.reportNoPlantingSpots();
            }
            return false;
        }

        LOGGER.info("[forester-planting] {} found {} valid planting site(s): {}",
                villager.getUuidAsString(),
                targets.size(),
                targets.stream().map(BlockPos::toShortString).toList());
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
                    LOGGER.info("[forester-planting] {} PLANTED {} at {} (remaining targets={})",
                            villager.getUuidAsString(), heldSaplingItem, target.toShortString(), plantTargets.size());
                    if (linkedProvisionGoal != null) {
                        linkedProvisionGoal.reportPlantingSucceeded();
                    }
                    // Refresh held item in case this type is now exhausted
                    if (!hasSaplingOfTypeInVillagerInventory(heldSaplingItem)) {
                        heldSaplingItem = findFirstSaplingInVillagerInventory();
                    }
                }
                // Continue planting if we have more sapling + targets
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
                    // V1 mode: no chest to return to; leftover saplings stay for next run
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
                // Apply standard cooldown and go idle
                long now = world.getTime();
                cooldownExpiresTick = now + PLANTING_COOLDOWN_TICKS;
                LOGGER.info("[forester-planting] {} planting run COMPLETE — next run after {} more ticks",
                        villager.getUuidAsString(), PLANTING_COOLDOWN_TICKS);
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
        // Positions already committed in this scan (to enforce spacing between new plantings)
        List<BlockPos> committed = new ArrayList<>();

        // Scan outward in concentric rings from MIN to MAX so we fill from the inner boundary
        // outward. This avoids always landing at the same far corner every run and distributes
        // plantings more evenly around the village perimeter.
        outer:
        for (int ring = MIN_PLANT_DISTANCE; ring <= MAX_PLANT_DISTANCE; ring += 4) {
            int ringMax = Math.min(ring + 3, MAX_PLANT_DISTANCE);
            for (BlockPos candidate : BlockPos.iterate(
                    center.add(-ringMax, -SCAN_Y_RANGE, -ringMax),
                    center.add(ringMax, SCAN_Y_RANGE, ringMax))) {

                double dx = candidate.getX() - center.getX();
                double dz = candidate.getZ() - center.getZ();
                double horizDist = Math.sqrt(dx * dx + dz * dz);

                if (horizDist < ring || horizDist > ringMax) continue;
                if (!VALID_GROUND.contains(world.getBlockState(candidate).getBlock())) continue;

                BlockPos above = candidate.up();
                if (!world.getBlockState(above).isAir()) continue;

                // No existing sapling or log within spacing radius (keeps trees spread out)
                if (hasNearbySaplingOrLog(world, candidate, MIN_SAPLING_SPACING)) continue;

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
                if (found.size() >= MAX_SAPLINGS_PER_RUN) break outer;
            }
        }

        return found;
    }

    private boolean hasNearbySaplingOrLog(ServerWorld world, BlockPos center, int radius) {
        Box box = new Box(center).expand(radius);
        for (BlockPos pos : BlockPos.iterate(
                (int) box.minX, (int) box.minY, (int) box.minZ,
                (int) box.maxX, (int) box.maxY, (int) box.maxZ)) {
            BlockState state = world.getBlockState(pos);
            if (state.isIn(BlockTags.SAPLINGS) || state.isIn(BlockTags.LOGS)) return true;
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
     * Moves saplings from the chest into the villager's personal inventory (up to MAX_SAPLINGS_PER_RUN).
     * Sets {@link #heldSaplingItem} to the first sapling type encountered.
     */
    private void takeSaplingsFromChest(ServerWorld world, Inventory chestInv) {
        int beforeCount = countSaplingsInInventory(chestInv);
        int takenTotal = 0;
        Inventory villagerInv = villager.getInventory();
        for (int i = 0; i < chestInv.size() && !isVillagerInventoryFull(villagerInv)
                && takenTotal < MAX_SAPLINGS_PER_RUN; i++) {
            ItemStack stack = chestInv.getStack(i);
            if (stack.isEmpty() || !stack.isIn(ItemTags.SAPLINGS)) continue;

            int toTake = Math.min(stack.getCount(), MAX_SAPLINGS_PER_RUN - takenTotal);
            ItemStack taken = stack.split(toTake);
            chestInv.markDirty();
            if (heldSaplingItem == null) heldSaplingItem = taken.getItem();

            // Try to add to villager inventory
            for (int j = 0; j < villagerInv.size() && !taken.isEmpty(); j++) {
                ItemStack existing = villagerInv.getStack(j);
                if (existing.isEmpty()) {
                    villagerInv.setStack(j, taken.copy());
                    takenTotal += taken.getCount();
                    taken = ItemStack.EMPTY;
                } else if (ItemStack.areItemsEqual(existing, taken)) {
                    int space = existing.getMaxCount() - existing.getCount();
                    int transfer = Math.min(space, taken.getCount());
                    existing.increment(transfer);
                    taken.decrement(transfer);
                    takenTotal += transfer;
                }
            }
            // If we couldn't fit it all, put remainder back
            if (!taken.isEmpty()) {
                chestInv.setStack(i, taken);
                chestInv.markDirty();
            }
        }
        int afterCount = countSaplingsInInventory(chestInv);
        LOGGER.info("[forester-planting] {} FETCHED {} sapling(s) from chest (chest had {}, now has {}, holding={})",
                villager.getUuidAsString(), takenTotal, beforeCount, afterCount, heldSaplingItem);
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

    /**
     * Returns true if at least one online player is within {@link #PLAYER_PROXIMITY_RANGE}
     * of this forester villager. Planting is suppressed when no player is nearby so that
     * the forester only works while observable — consistent with vanilla entity simulation
     * behaviour and avoids silent background world modification.
     */
    private boolean isPlayerNearby(ServerWorld world) {
        double rangeSq = PLAYER_PROXIMITY_RANGE * PLAYER_PROXIMITY_RANGE;
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.squaredDistanceTo(villager) <= rangeSq) {
                return true;
            }
        }
        return false;
    }
}
