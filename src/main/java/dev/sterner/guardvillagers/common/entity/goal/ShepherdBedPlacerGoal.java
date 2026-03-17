package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Shepherd picks a bed from their chest and places it in the world,
 * adjacent to an existing village bed (so new beds end up indoors by proxy).
 *
 * <p>Only activates when:
 * <ol>
 *   <li>At least one nearby villager has no sleeping position claim.</li>
 *   <li>At least one bed item is in the shepherd's chest.</li>
 *   <li>At least one existing placed bed exists nearby (so we can anchor placement).</li>
 * </ol>
 * </p>
 */
public class ShepherdBedPlacerGoal extends Goal {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShepherdBedPlacerGoal.class);

    private static final double BEDLESS_SCAN_RANGE = 64.0D;
    private static final double BED_ANCHOR_SCAN_RANGE = 32.0D;
    private static final double MOVE_SPEED = 0.6D;
    private static final double REACH_SQ = 4.0D;
    private static final int PATH_RETRY_TICKS = 20;
    private static final int CHECK_INTERVAL_TICKS = 1200;   // 1 min cooldown between checks
    private static final int PLACEMENT_SCAN_RADIUS = 3;     // blocks around an existing bed to look for a new spot
    private static final int BED_ANCHOR_Y_RANGE = 8;        // ±Y range when scanning for existing bed anchors (covers 2 floors)
    private static final int MAX_BED_ANCHOR_CANDIDATES = 16;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;

    private Stage stage = Stage.IDLE;
    private long nextCheckTime = 0L;
    private BlockPos targetBedSite = null;       // foot-of-bed position we want to place at
    private Direction targetBedFacing = null;    // facing of the bed
    private ItemStack heldBed = ItemStack.EMPTY;
    private BlockPos currentNavTarget = null;
    private long lastPathRequestTick = Long.MIN_VALUE;

    public ShepherdBedPlacerGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.stage = Stage.IDLE;
        this.targetBedSite = null;
        this.targetBedFacing = null;
        this.heldBed = ItemStack.EMPTY;
    }

    public void requestImmediateCheck() {
        nextCheckTime = 0L;
    }

    // -------------------------------------------------------------------------
    // Goal lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!villager.isAlive() || !world.isDay()) {
            return false;
        }
        if (villager.getVillagerData().getProfession() != VillagerProfession.SHEPHERD) {
            return false;
        }
        if (jobPos == null || chestPos == null) {
            return false;
        }
        if (world.getTime() < nextCheckTime) {
            return false;
        }

        nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;

        // Need a bedless villager
        if (!hasBedlessVillagerNearby(world)) {
            return false;
        }

        // Need a bed in chest
        Optional<Inventory> invOpt = getChestInventory(world);
        if (invOpt.isEmpty()) {
            return false;
        }
        Inventory inv = invOpt.get();
        ItemStack bedStack = findBedInChest(inv);
        if (bedStack.isEmpty()) {
            return false;
        }

        // Find a valid placement site anchored to existing beds
        BedSite site = findBedPlacementSite(world);
        if (site == null) {
            LOGGER.info("Shepherd {} has bed to place but found no valid site (no existing beds nearby or no free adjacent spot)",
                    villager.getUuidAsString());
            return false;
        }

        targetBedSite = site.footPos();
        targetBedFacing = site.facing();
        return true;
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.DONE && stage != Stage.IDLE && villager.isAlive();
    }

    @Override
    public void start() {
        stage = Stage.GO_TO_CHEST;
        moveTo(chestPos);
        LOGGER.info("Shepherd {} starting bed placement run to site {}", villager.getUuidAsString(), targetBedSite.toShortString());
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        currentNavTarget = null;
        lastPathRequestTick = Long.MIN_VALUE;
        // If we still hold a bed, put it back
        if (!heldBed.isEmpty() && villager.getWorld() instanceof ServerWorld world) {
            putBedBackInChest(world);
        }
        heldBed = ItemStack.EMPTY;
        stage = Stage.IDLE;
        targetBedSite = null;
        targetBedFacing = null;
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case GO_TO_CHEST -> {
                if (isNear(chestPos)) {
                    heldBed = takeBedFromChest(world);
                    if (heldBed.isEmpty()) {
                        LOGGER.info("Shepherd {} reached chest but no bed available", villager.getUuidAsString());
                        stage = Stage.DONE;
                        return;
                    }
                    stage = Stage.GO_TO_SITE;
                    moveTo(targetBedSite);
                } else {
                    moveTo(chestPos);
                }
            }
            case GO_TO_SITE -> {
                if (targetBedSite == null) {
                    stage = Stage.DONE;
                    return;
                }
                if (isNear(targetBedSite)) {
                    placeBed(world);
                    stage = Stage.DONE;
                } else {
                    moveTo(targetBedSite);
                }
            }
            case IDLE, DONE -> {}
        }
    }

    // -------------------------------------------------------------------------
    // Bed placement logic
    // -------------------------------------------------------------------------

    /**
     * Scans for existing bed blocks near the shepherd, then looks for a free
     * adjacent floor position where a new bed can be placed.
     */
    private BedSite findBedPlacementSite(ServerWorld world) {
        BlockPos villagerPos = villager.getBlockPos();
        // Note: scan is a manual 3-nested loop (X/Z ±BED_ANCHOR_SCAN_RANGE, Y ±BED_ANCHOR_Y_RANGE)
        // rather than a Box query because BedBlock is not a BlockEntity and requires a block scan.

        // Collect all bed foot-block positions (avoid scanning heads)
        List<BlockPos> existingBedPositions = new ArrayList<>();
        for (int x = (int)(villagerPos.getX() - BED_ANCHOR_SCAN_RANGE); x <= villagerPos.getX() + BED_ANCHOR_SCAN_RANGE; x++) {
            for (int z = (int)(villagerPos.getZ() - BED_ANCHOR_SCAN_RANGE); z <= villagerPos.getZ() + BED_ANCHOR_SCAN_RANGE; z++) {
                for (int y = villagerPos.getY() - BED_ANCHOR_Y_RANGE; y <= villagerPos.getY() + BED_ANCHOR_Y_RANGE; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (state.getBlock() instanceof BedBlock
                            && state.get(BedBlock.PART) == BedPart.FOOT) {
                        existingBedPositions.add(pos.toImmutable());
                        if (existingBedPositions.size() >= MAX_BED_ANCHOR_CANDIDATES) {
                            break;
                        }
                    }
                }
                if (existingBedPositions.size() >= MAX_BED_ANCHOR_CANDIDATES) break;
            }
            if (existingBedPositions.size() >= MAX_BED_ANCHOR_CANDIDATES) break;
        }

        if (existingBedPositions.isEmpty()) {
            LOGGER.info("Shepherd {} found no existing placed beds to anchor placement near", villager.getUuidAsString());
            return null;
        }

        // Sort by distance to shepherd
        existingBedPositions.sort((a, b) -> Double.compare(
                a.getSquaredDistance(villagerPos), b.getSquaredDistance(villagerPos)));

        // For each existing bed, try to find a free adjacent slot
        for (BlockPos anchorFoot : existingBedPositions) {
            BlockState anchorState = world.getBlockState(anchorFoot);
            if (!(anchorState.getBlock() instanceof BedBlock)) continue;
            Direction anchorFacing = anchorState.get(BedBlock.FACING);

            // Try all horizontal directions for a new bed adjacent to this one
            for (Direction tryFacing : Direction.Type.HORIZONTAL) {
                // Try offsets around the anchor foot: same row parallel, next row
                List<BlockPos> candidates = buildAdjacentCandidates(anchorFoot, anchorFacing, tryFacing);
                for (BlockPos candidate : candidates) {
                    if (canPlaceBedAt(world, candidate, tryFacing)) {
                        LOGGER.info("Shepherd {} found bed site {} (facing {}) adjacent to existing bed at {}",
                                villager.getUuidAsString(),
                                candidate.toShortString(),
                                tryFacing.name(),
                                anchorFoot.toShortString());
                        return new BedSite(candidate.toImmutable(), tryFacing);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Builds candidate foot-positions for a new bed adjacent to an existing one.
     * We try positions in the same row (beside existing bed) and the next row over.
     */
    private List<BlockPos> buildAdjacentCandidates(BlockPos anchorFoot, Direction anchorFacing, Direction tryFacing) {
        List<BlockPos> candidates = new ArrayList<>();
        // Side-by-side (perpendicular to anchor facing)
        Direction perp1 = anchorFacing.rotateYClockwise();
        Direction perp2 = anchorFacing.rotateYCounterclockwise();
        candidates.add(anchorFoot.offset(perp1, 2));  // 2 apart so beds don't overlap (bed is 2 long)
        candidates.add(anchorFoot.offset(perp2, 2));
        // Behind the anchor (row behind)
        candidates.add(anchorFoot.offset(anchorFacing.getOpposite(), 2));
        // In front of the anchor
        candidates.add(anchorFoot.offset(anchorFacing, 2));
        return candidates;
    }

    /**
     * Checks whether a bed with given foot-pos and facing can be placed:
     * - foot block is air
     * - head block (foot offset by facing) is air
     * - solid floor exists below both
     * - no bed already at that spot
     */
    private boolean canPlaceBedAt(ServerWorld world, BlockPos footPos, Direction facing) {
        BlockPos headPos = footPos.offset(facing);
        BlockPos floorFoot = footPos.down();
        BlockPos floorHead = headPos.down();

        BlockState foot = world.getBlockState(footPos);
        BlockState head = world.getBlockState(headPos);
        BlockState floorF = world.getBlockState(floorFoot);
        BlockState floorH = world.getBlockState(floorHead);

        // Use isReplaceable() instead of isAir() — matches how other placement goals work.
        // isAir() rejects cave_air, flowers, carpet, grass, snow_layer etc.
        // isReplaceable() accepts all blocks that can be overwritten by a new block placement.
        if (!foot.isReplaceable() || !head.isReplaceable()) return false;
        if (!floorF.isSolidBlock(world, floorFoot)) return false;
        if (!floorH.isSolidBlock(world, floorHead)) return false;
        return true;
    }

    private void placeBed(ServerWorld world) {
        if (heldBed.isEmpty() || targetBedSite == null || targetBedFacing == null) {
            return;
        }
        if (!(heldBed.getItem() instanceof BlockItem blockItem)) {
            return;
        }
        Block block = blockItem.getBlock();
        if (!(block instanceof BedBlock)) {
            return;
        }

        // Re-verify site is still clear
        if (!canPlaceBedAt(world, targetBedSite, targetBedFacing)) {
            LOGGER.info("Shepherd {} site {} no longer clear when arrived; aborting bed placement",
                    villager.getUuidAsString(), targetBedSite.toShortString());
            putBedBackInChest(world);
            heldBed = ItemStack.EMPTY;
            return;
        }

        BlockState bedState = block.getDefaultState()
                .with(BedBlock.FACING, targetBedFacing)
                .with(BedBlock.PART, BedPart.FOOT);
        BlockState bedHeadState = block.getDefaultState()
                .with(BedBlock.FACING, targetBedFacing)
                .with(BedBlock.PART, BedPart.HEAD);

        BlockPos headPos = targetBedSite.offset(targetBedFacing);

        boolean placedFoot = world.setBlockState(targetBedSite, bedState, Block.NOTIFY_ALL);
        boolean placedHead = world.setBlockState(headPos, bedHeadState, Block.NOTIFY_ALL);

        if (placedFoot && placedHead) {
            world.playSound(null, targetBedSite, SoundEvents.BLOCK_WOOL_PLACE, SoundCategory.BLOCKS, 1.0F, 1.0F);
            heldBed.decrement(1);
            if (heldBed.isEmpty()) {
                heldBed = ItemStack.EMPTY;
            }
            LOGGER.info("Shepherd {} placed {} at {} (facing {})",
                    villager.getUuidAsString(),
                    bedState.getBlock().getTranslationKey(),
                    targetBedSite.toShortString(),
                    targetBedFacing.name());
        } else {
            // Undo partial placement
            if (placedFoot) world.removeBlock(targetBedSite, false);
            if (placedHead) world.removeBlock(headPos, false);
            LOGGER.info("Shepherd {} failed to place bed at {} — setBlockState rejected", villager.getUuidAsString(), targetBedSite.toShortString());
            putBedBackInChest(world);
            heldBed = ItemStack.EMPTY;
        }
    }

    // -------------------------------------------------------------------------
    // Inventory helpers
    // -------------------------------------------------------------------------

    private Optional<Inventory> getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        Inventory inventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
        return Optional.ofNullable(inventory);
    }

    private ItemStack findBedInChest(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && stack.isIn(ItemTags.BEDS)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private ItemStack takeBedFromChest(ServerWorld world) {
        Optional<Inventory> invOpt = getChestInventory(world);
        if (invOpt.isEmpty()) {
            return ItemStack.EMPTY;
        }
        Inventory inv = invOpt.get();
        for (int slot = 0; slot < inv.size(); slot++) {
            ItemStack stack = inv.getStack(slot);
            if (!stack.isEmpty() && stack.isIn(ItemTags.BEDS)) {
                ItemStack taken = stack.split(1);
                if (stack.isEmpty()) {
                    inv.setStack(slot, ItemStack.EMPTY);
                }
                inv.markDirty();
                return taken;
            }
        }
        return ItemStack.EMPTY;
    }

    private void putBedBackInChest(ServerWorld world) {
        if (heldBed.isEmpty()) {
            return;
        }
        Optional<Inventory> invOpt = getChestInventory(world);
        if (invOpt.isPresent()) {
            Inventory inv = invOpt.get();
            // Try to merge with existing stack first, then find empty slot
            for (int slot = 0; slot < inv.size() && !heldBed.isEmpty(); slot++) {
                ItemStack existing = inv.getStack(slot);
                if (existing.isEmpty()) {
                    inv.setStack(slot, heldBed.copy());
                    heldBed = ItemStack.EMPTY;
                } else if (ItemStack.areItemsAndComponentsEqual(existing, heldBed)) {
                    int space = existing.getMaxCount() - existing.getCount();
                    int moved = Math.min(space, heldBed.getCount());
                    if (moved > 0) {
                        existing.increment(moved);
                        heldBed.decrement(moved);
                    }
                }
            }
            inv.markDirty();
        }
        // If chest was full or unavailable, drop the bed so it is never silently lost.
        if (!heldBed.isEmpty()) {
            LOGGER.info("Shepherd {} chest full or missing; dropping held bed at {}",
                    villager.getUuidAsString(), villager.getBlockPos().toShortString());
            ItemEntity drop = new ItemEntity(
                    world, villager.getX(), villager.getY(), villager.getZ(), heldBed.copy());
            drop.setPickupDelay(10);
            world.spawnEntity(drop);
            heldBed = ItemStack.EMPTY;
        }
    }

    // -------------------------------------------------------------------------
    // Demand check
    // -------------------------------------------------------------------------

    private boolean hasBedlessVillagerNearby(ServerWorld world) {
        Box box = new Box(villager.getBlockPos()).expand(BEDLESS_SCAN_RANGE);
        List<VillagerEntity> villagers = world.getEntitiesByClass(VillagerEntity.class, box, VillagerEntity::isAlive);
        for (VillagerEntity v : villagers) {
            // Exclude self: the shepherd is the craftsman/placer, not a sleeping recipient.
            // Shepherds rarely hold a HOME memory so they would always count as bedless,
            // causing the placer to run even when every *other* villager has a bed.
            if (v == villager) continue;
            if (!hasSleepingPos(v)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSleepingPos(VillagerEntity v) {
        return v.getSleepingPosition().isPresent()
                || v.getBrain().getOptionalRegisteredMemory(net.minecraft.entity.ai.brain.MemoryModuleType.HOME).isPresent();
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
        villager.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
        currentNavTarget = target.toImmutable();
        lastPathRequestTick = now;
    }

    private boolean isNear(BlockPos target) {
        return target != null
                && villager.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= REACH_SQ;
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    private enum Stage {
        IDLE,
        GO_TO_CHEST,
        GO_TO_SITE,
        DONE
    }

    private record BedSite(BlockPos footPos, Direction facing) {}
}
