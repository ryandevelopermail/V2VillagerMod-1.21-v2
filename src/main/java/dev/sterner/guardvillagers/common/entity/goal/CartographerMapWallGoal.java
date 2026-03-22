package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Cluster 6 — Cartographer Map Wall.
 *
 * <p>Trigger: paired chest contains ≥8 FILLED_MAP + ≥4 ITEM_FRAME.
 *
 * <p>Behaviour:
 * <ol>
 *   <li>Find a 2×2 wall surface within {@value SCAN_RADIUS} blocks of the job site with
 *       solid backing and clear face positions (air in front).</li>
 *   <li>Walk to each of the 4 frame positions in sequence.</li>
 *   <li>Consume 1 ITEM_FRAME + 1 FILLED_MAP from chest, spawn an ItemFrameEntity on the wall,
 *       put the map in it.</li>
 *   <li>After all 4 are placed, mark done (scan gated for {@value SCAN_INTERVAL_TICKS} ticks).</li>
 * </ol>
 */
public class CartographerMapWallGoal extends Goal {

    private static final Logger LOGGER = LoggerFactory.getLogger(CartographerMapWallGoal.class);

    private static final int MAPS_NEEDED = 8;
    private static final int FRAMES_NEEDED = 4;
    private static final int FRAMES_TO_PLACE = 4;
    private static final int SCAN_RADIUS = 20;
    private static final int SCAN_INTERVAL_TICKS = 1200;
    private static final double MOVE_SPEED = 0.6D;
    private static final double REACH_SQ = 4.0D;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;

    private long nextScanTick = 0L;
    private Stage stage = Stage.IDLE;

    /** Each entry: the block position of the wall block + which direction the frame faces. */
    private final List<FrameSlot> pendingSlots = new ArrayList<>();
    private int currentSlotIndex = 0;

    public CartographerMapWallGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        setTargets(jobPos, chestPos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.stage = Stage.IDLE;
        this.pendingSlots.clear();
    }

    // -------------------------------------------------------------------------
    // Goal lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) return false;
        if (!villager.isAlive()) return false;
        if (jobPos == null || chestPos == null) return false;
        if (world.getTime() < nextScanTick) return false;

        nextScanTick = world.getTime() + SCAN_INTERVAL_TICKS;

        if (!hasMaterials(world)) return false;
        return planWall(world);
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.IDLE && stage != Stage.DONE && villager.isAlive();
    }

    @Override
    public void start() {
        currentSlotIndex = 0;
        stage = Stage.MOVE_TO_FRAME;
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        stage = Stage.IDLE;
        pendingSlots.clear();
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case MOVE_TO_FRAME -> tickMoveToFrame(world);
            case PLACE_FRAME -> tickPlaceFrame(world);
            case DONE -> stage = Stage.IDLE;
            default -> {}
        }
    }

    // -------------------------------------------------------------------------
    // Stage ticks
    // -------------------------------------------------------------------------

    private void tickMoveToFrame(ServerWorld world) {
        // Skip slots where a frame already exists
        while (currentSlotIndex < pendingSlots.size()) {
            FrameSlot slot = pendingSlots.get(currentSlotIndex);
            if (!hasExistingFrame(world, slot)) break;
            currentSlotIndex++;
        }

        if (currentSlotIndex >= pendingSlots.size()) {
            LOGGER.info("CartographerMapWall {}: all frame slots filled", villager.getUuidAsString());
            stage = Stage.DONE;
            return;
        }

        BlockPos framePos = pendingSlots.get(currentSlotIndex).facePos();
        if (isNear(framePos)) {
            stage = Stage.PLACE_FRAME;
        } else {
            moveTo(framePos);
        }
    }

    private void tickPlaceFrame(ServerWorld world) {
        if (currentSlotIndex >= pendingSlots.size()) {
            stage = Stage.DONE;
            return;
        }

        FrameSlot slot = pendingSlots.get(currentSlotIndex);

        if (hasExistingFrame(world, slot)) {
            currentSlotIndex++;
            stage = Stage.MOVE_TO_FRAME;
            return;
        }

        // Consume materials from chest
        Inventory inv = getChestInventory(world).orElse(null);
        if (inv == null) {
            LOGGER.warn("CartographerMapWall {}: chest unavailable, aborting", villager.getUuidAsString());
            stage = Stage.DONE;
            return;
        }

        ItemStack frame = takeOne(inv, Items.ITEM_FRAME);
        if (frame.isEmpty()) {
            LOGGER.debug("CartographerMapWall {}: no item frames left", villager.getUuidAsString());
            stage = Stage.DONE;
            return;
        }

        ItemStack map = takeOne(inv, Items.FILLED_MAP);
        if (map.isEmpty()) {
            // Return the frame we just took
            insertStack(inv, frame);
            LOGGER.debug("CartographerMapWall {}: no filled maps left", villager.getUuidAsString());
            stage = Stage.DONE;
            return;
        }
        inv.markDirty();

        // Spawn item frame entity on the wall face
        ItemFrameEntity entity = new ItemFrameEntity(world, slot.wallBlock(), slot.facing());
        entity.setHeldItemStack(map, false);
        world.spawnEntity(entity);

        LOGGER.info("CartographerMapWall {}: placed item frame with map at {} facing {}",
                villager.getUuidAsString(), slot.wallBlock().toShortString(), slot.facing());

        currentSlotIndex++;
        stage = Stage.MOVE_TO_FRAME;
    }

    // -------------------------------------------------------------------------
    // Planning
    // -------------------------------------------------------------------------

    private boolean planWall(ServerWorld world) {
        pendingSlots.clear();

        // Search for a 2×2 arrangement on any cardinal wall face within radius.
        // For each candidate wall block, try all 4 horizontal directions.
        int range = SCAN_RADIUS;
        BlockPos center = jobPos;

        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos wallBlock = center.add(dx, dy, dz);
                    if (!world.getBlockState(wallBlock).isSolidBlock(world, wallBlock)) continue;

                    for (Direction dir : Direction.Type.HORIZONTAL) {
                        // The face position is one block in `dir` from the wall block
                        List<FrameSlot> slots = tryFind2x2Wall(world, wallBlock, dir);
                        if (slots != null) {
                            pendingSlots.addAll(slots);
                            LOGGER.info("CartographerMapWall {}: planned 2×2 map wall on {} face at {}",
                                    villager.getUuidAsString(), dir, wallBlock.toShortString());
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Checks if a 2×2 item frame grid can be placed on the {@code dir} face of blocks
     * anchored at {@code bottomLeftWall}.  Requires:
     * <ul>
     *   <li>All 4 wall blocks (bottomLeft, bottomRight, topLeft, topRight) are solid.</li>
     *   <li>All 4 face positions (one step in {@code dir} from each wall block) are air.</li>
     *   <li>No existing item frame entity is already at those positions.</li>
     * </ul>
     * The "right" direction is the clockwise rotation of {@code dir} (east of north, south of east, etc.).
     */
    private List<FrameSlot> tryFind2x2Wall(ServerWorld world, BlockPos bottomLeftWall, Direction dir) {
        Direction right = dir.rotateYClockwise();

        BlockPos[] wallBlocks = {
                bottomLeftWall,
                bottomLeftWall.offset(right),
                bottomLeftWall.up(),
                bottomLeftWall.offset(right).up()
        };

        List<FrameSlot> slots = new ArrayList<>(FRAMES_TO_PLACE);
        for (BlockPos wallBlock : wallBlocks) {
            // Wall block must be solid
            BlockState wallState = world.getBlockState(wallBlock);
            if (!wallState.isSolidBlock(world, wallBlock)) return null;

            // Face position must be air / replaceable
            BlockPos facePos = wallBlock.offset(dir);
            if (!world.getBlockState(facePos).isAir()) return null;

            // No existing item frame there
            Box searchBox = new Box(facePos).expand(0.1);
            if (!world.getEntitiesByType(EntityType.ITEM_FRAME, searchBox, e -> true).isEmpty()) return null;

            slots.add(new FrameSlot(wallBlock.toImmutable(), facePos.toImmutable(), dir));
        }
        return slots;
    }

    private boolean hasExistingFrame(ServerWorld world, FrameSlot slot) {
        Box box = new Box(slot.facePos()).expand(0.1);
        return !world.getEntitiesByType(EntityType.ITEM_FRAME, box, e -> true).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Material checks + inventory helpers
    // -------------------------------------------------------------------------

    private boolean hasMaterials(ServerWorld world) {
        Optional<Inventory> invOpt = getChestInventory(world);
        if (invOpt.isEmpty()) return false;
        Inventory inv = invOpt.get();
        return countItem(inv, Items.FILLED_MAP) >= MAPS_NEEDED
                && countItem(inv, Items.ITEM_FRAME) >= FRAMES_NEEDED;
    }

    private int countItem(Inventory inv, net.minecraft.item.Item item) {
        int total = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && s.isOf(item)) total += s.getCount();
        }
        return total;
    }

    private ItemStack takeOne(Inventory inv, net.minecraft.item.Item item) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && s.isOf(item)) {
                ItemStack taken = s.split(1);
                if (s.isEmpty()) inv.setStack(i, ItemStack.EMPTY);
                return taken;
            }
        }
        return ItemStack.EMPTY;
    }

    private void insertStack(Inventory inv, ItemStack stack) {
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isEmpty()) {
                inv.setStack(i, stack.copy());
                inv.markDirty();
                return;
            }
        }
    }

    private Optional<Inventory> getChestInventory(ServerWorld world) {
        if (chestPos == null) return Optional.empty();
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) return Optional.empty();
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, chestPos, false));
    }

    // -------------------------------------------------------------------------
    // Navigation helpers
    // -------------------------------------------------------------------------

    private void moveTo(BlockPos target) {
        villager.getNavigation().startMovingTo(
                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, MOVE_SPEED);
    }

    private boolean isNear(BlockPos target) {
        return villager.squaredDistanceTo(
                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) <= REACH_SQ;
    }

    // -------------------------------------------------------------------------
    // Types
    // -------------------------------------------------------------------------

    private enum Stage {
        IDLE,
        MOVE_TO_FRAME,
        PLACE_FRAME,
        DONE
    }

    /**
     * One item-frame slot: the solid wall block it attaches to, the air position in front
     * (where the entity will appear), and the direction the frame faces.
     */
    private record FrameSlot(BlockPos wallBlock, BlockPos facePos, Direction facing) {
    }
}
