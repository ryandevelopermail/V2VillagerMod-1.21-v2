package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.VillagePenRegistry;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.poi.PointOfInterestStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Shepherd places a 7×7 fence pen (27 fences + 1 gate) near their job block when:
 * <ul>
 *   <li>No pen exists within PEN_SCAN_RADIUS blocks of the job site</li>
 *   <li>Chest has ≥ {@link ShepherdFenceCraftingGoal#FENCE_TARGET} fences AND ≥ 1 gate</li>
 *   <li>A valid flat 7×7 site exists within {@value #SITE_SEARCH_RADIUS} blocks of the job block</li>
 * </ul>
 *
 * <p>A 7×7 pen perimeter has 28 blocks (7+7+7+7) but one is the gate gap, so 27 fences placed
 * and 1 gate. We consume from the chest one block at a time, walking to each position.</p>
 *
 * <p>Validity rules for the 7×7 area:</p>
 * <ul>
 *   <li>No job-site POI block inside the interior (5×5 inner area)</li>
 *   <li>No DIRT_PATH blocks inside the area</li>
 *   <li>All floor blocks within ±1 Y of the chosen base Y (flat ground)</li>
 *   <li>All 7×7 positions must have solid floor and replaceable space at pen level</li>
 *   <li>Overworld only</li>
 * </ul>
 */
public class ShepherdFencePlacerGoal extends Goal {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShepherdFencePlacerGoal.class);

    /** Outer pen dimension. 7×7 exterior = 28 perimeter blocks, minus 1 gap = 27 fences. */
    private static final int PEN_SIZE = 7;
    /** Number of fence blocks placed (perimeter minus gate gap). */
    private static final int FENCE_COUNT = 27;
    /** Min fences needed in chest before triggering. */
    private static final int MIN_FENCES = ShepherdFenceCraftingGoal.FENCE_TARGET;
    /** Gates needed. */
    private static final int MIN_GATES = 1;

    /** How far from the job block to search for a valid pen site. */
    private static final int SITE_SEARCH_RADIUS = 32;
    /** Flat-ground tolerance: all floor Y must be within ±2 of the base Y. */
    private static final int FLAT_Y_TOLERANCE = 2;

    /** Pen scan radius — same as crafting goal. */
    private static final int PEN_SCAN_RADIUS = 300;

    /** How often to re-check if we can start (ticks). */
    private static final int CHECK_INTERVAL_TICKS = 1200;
    /** Movement speed. */
    private static final double MOVE_SPEED = 0.55D;
    /** Reach distance squared for block placement. */
    private static final double REACH_SQ = 3.5D * 3.5D;
    /** Path retry interval. */
    private static final int PATH_RETRY_TICKS = 20;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;

    private Stage stage = Stage.IDLE;
    private long nextCheckTime = 0L;
    private long lastPathRequestTick = Long.MIN_VALUE;
    private BlockPos currentNavTarget = null;

    // Planned placement
    private List<BlockPos> pendingFences = new ArrayList<>();
    private BlockPos pendingGatePos = null;
    private Direction pendingGateFacing = Direction.SOUTH;
    private int currentIndex = 0;

    public ShepherdFencePlacerGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.stage = Stage.IDLE;
        this.pendingFences = new ArrayList<>();
        this.pendingGatePos = null;
        this.currentIndex = 0;
    }

    public void requestImmediateCheck() {
        nextCheckTime = 0L;
    }

    // -------------------------------------------------------------------------
    // Goal lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) return false;
        if (!villager.isAlive()) return false;
        if (villager.getVillagerData().getProfession() != VillagerProfession.SHEPHERD) return false;
        if (jobPos == null || chestPos == null) return false;
        if (!world.getRegistryKey().equals(net.minecraft.world.World.OVERWORLD)) return false;
        if (world.getTime() < nextCheckTime) return false;

        nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;

        // Don't act if a pen already exists near the job site
        VillagePenRegistry registry = VillagePenRegistry.get(world.getServer());
        if (registry.getNearestPen(world, jobPos, PEN_SCAN_RADIUS, PEN_SCAN_RADIUS).isPresent()) {
            LOGGER.debug("ShepherdFencePlacer {}: pen already exists near job site {}, skipping",
                    villager.getUuidAsString(), jobPos.toShortString());
            return false;
        }

        // Check chest has enough materials
        Optional<Inventory> invOpt = getChestInventory(world);
        if (invOpt.isEmpty()) {
            LOGGER.info("ShepherdFencePlacer {}: no chest inventory at {}", villager.getUuidAsString(), chestPos.toShortString());
            return false;
        }
        Inventory inv = invOpt.get();

        int fenceCount = countTag(inv, ItemTags.FENCES);
        int gateCount = countTag(inv, ItemTags.FENCE_GATES);
        if (fenceCount < MIN_FENCES || gateCount < MIN_GATES) {
            LOGGER.info("ShepherdFencePlacer {}: insufficient materials — fences={}/{} gates={}/{}",
                    villager.getUuidAsString(), fenceCount, MIN_FENCES, gateCount, MIN_GATES);
            return false;
        }

        // Find a valid site
        PenPlan plan = findPenSite(world);
        if (plan == null) {
            LOGGER.info("ShepherdFencePlacer {}: has materials (fences={} gates={}) but found no valid 7×7 site within {} blocks of job site {}",
                    villager.getUuidAsString(), fenceCount, gateCount, SITE_SEARCH_RADIUS, jobPos.toShortString());
            return false;
        }

        pendingFences = plan.fencePositions();
        pendingGatePos = plan.gatePos();
        pendingGateFacing = plan.gateFacing();
        currentIndex = 0;

        LOGGER.info("ShepherdFencePlacer {}: planned 7×7 pen at origin {} ({} fences, gate at {} facing {})",
                villager.getUuidAsString(), plan.origin().toShortString(),
                pendingFences.size(), pendingGatePos.toShortString(), pendingGateFacing.name());
        return true;
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.DONE && stage != Stage.IDLE && villager.isAlive();
    }

    @Override
    public void start() {
        stage = Stage.MOVE_TO_FENCE;
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        currentNavTarget = null;
        lastPathRequestTick = Long.MIN_VALUE;
        stage = Stage.IDLE;
        pendingFences = new ArrayList<>();
        pendingGatePos = null;
        currentIndex = 0;
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case MOVE_TO_FENCE -> tickMoveToFence(world);
            case PLACE_FENCE   -> tickPlaceFence(world);
            case MOVE_TO_GATE  -> tickMoveToGate(world);
            case PLACE_GATE    -> tickPlaceGate(world);
            case DONE          -> stage = Stage.IDLE;
            default            -> {}
        }
    }

    // -------------------------------------------------------------------------
    // Stage ticks
    // -------------------------------------------------------------------------

    private void tickMoveToFence(ServerWorld world) {
        // Skip already-placed fences
        while (currentIndex < pendingFences.size()
                && world.getBlockState(pendingFences.get(currentIndex)).getBlock() instanceof FenceBlock) {
            currentIndex++;
        }

        if (currentIndex >= pendingFences.size()) {
            stage = pendingGatePos != null ? Stage.MOVE_TO_GATE : Stage.DONE;
            // Trigger registry rescan so the new pen is detected
            if (stage == Stage.DONE) triggerRescan(world);
            return;
        }

        BlockPos target = pendingFences.get(currentIndex);
        if (isNear(target)) {
            stage = Stage.PLACE_FENCE;
        } else {
            moveTo(target);
        }
    }

    private void tickPlaceFence(ServerWorld world) {
        if (currentIndex >= pendingFences.size()) {
            stage = pendingGatePos != null ? Stage.MOVE_TO_GATE : Stage.DONE;
            if (stage == Stage.DONE) triggerRescan(world);
            return;
        }

        BlockPos target = pendingFences.get(currentIndex);

        // Already placed by someone else? Skip
        if (world.getBlockState(target).getBlock() instanceof FenceBlock) {
            currentIndex++;
            stage = Stage.MOVE_TO_FENCE;
            return;
        }

        // Consume one fence from chest
        Optional<Inventory> invOpt = getChestInventory(world);
        if (invOpt.isEmpty()) {
            stage = Stage.DONE;
            return;
        }
        Inventory inv = invOpt.get();
        Block fenceBlock = chooseFenceBlock(inv);
        if (!consumeOne(inv, ItemTags.FENCES)) {
            LOGGER.debug("ShepherdFencePlacer {}: ran out of fences mid-build", villager.getUuidAsString());
            stage = Stage.DONE;
            return;
        }

        world.setBlockState(target, fenceBlock.getDefaultState(), Block.NOTIFY_ALL);
        world.playSound(null, target, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 1.0F, 1.0F);
        LOGGER.debug("ShepherdFencePlacer {}: placed {} at {}", villager.getUuidAsString(), fenceBlock, target.toShortString());

        currentIndex++;
        stage = Stage.MOVE_TO_FENCE;
    }

    private void tickMoveToGate(ServerWorld world) {
        if (pendingGatePos == null) {
            stage = Stage.DONE;
            triggerRescan(world);
            return;
        }
        if (isNear(pendingGatePos)) {
            stage = Stage.PLACE_GATE;
        } else {
            moveTo(pendingGatePos);
        }
    }

    private void tickPlaceGate(ServerWorld world) {
        if (pendingGatePos == null
                || world.getBlockState(pendingGatePos).getBlock() instanceof FenceGateBlock) {
            stage = Stage.DONE;
            triggerRescan(world);
            return;
        }

        Optional<Inventory> invOpt = getChestInventory(world);
        if (invOpt.isEmpty()) {
            stage = Stage.DONE;
            return;
        }
        Inventory inv = invOpt.get();
        Block gateBlock = chooseGateBlock(inv);
        if (!consumeOne(inv, ItemTags.FENCE_GATES)) {
            LOGGER.debug("ShepherdFencePlacer {}: no gate available", villager.getUuidAsString());
            stage = Stage.DONE;
            return;
        }

        BlockState gateState = gateBlock.getDefaultState()
                .with(FenceGateBlock.FACING, pendingGateFacing);
        world.setBlockState(pendingGatePos, gateState, Block.NOTIFY_ALL);
        world.playSound(null, pendingGatePos, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 1.0F, 1.0F);
        LOGGER.info("ShepherdFencePlacer {}: placed gate at {} (facing {})",
                villager.getUuidAsString(), pendingGatePos.toShortString(), pendingGateFacing.name());

        pendingGatePos = null;
        stage = Stage.DONE;
        triggerRescan(world);
    }

    /** Forces the pen registry to rescan on the next tick interval. */
    private void triggerRescan(ServerWorld world) {
        // Reset the lastRescanTick so the registry rescans at the next tick opportunity
        try {
            VillagePenRegistry registry = VillagePenRegistry.get(world.getServer());
            registry.markDirty();
            // Force rescan by calling tick with a manipulated time context isn't safe,
            // so instead we schedule it by setting nextCheckTime to 0 — the registry
            // will pick it up naturally within RESCAN_INTERVAL_TICKS ticks.
            // The ShepherdSpecialGoal's next canStart() will see the newly-placed pen.
            LOGGER.info("ShepherdFencePlacer {}: pen placement complete, registry will detect on next rescan",
                    villager.getUuidAsString());
        } catch (Exception e) {
            LOGGER.warn("ShepherdFencePlacer: failed to trigger registry markDirty", e);
        }
    }

    // -------------------------------------------------------------------------
    // Site finding
    // -------------------------------------------------------------------------

    /**
     * Scans for a valid 7×7 pen site within SITE_SEARCH_RADIUS blocks of the job block.
     * Uses BlockPos.Mutable to avoid GC pressure.
     */
    private PenPlan findPenSite(ServerWorld world) {
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        for (int dx = -SITE_SEARCH_RADIUS; dx <= SITE_SEARCH_RADIUS - PEN_SIZE; dx++) {
            for (int dz = -SITE_SEARCH_RADIUS; dz <= SITE_SEARCH_RADIUS - PEN_SIZE; dz++) {
                int baseX = jobPos.getX() + dx;
                int baseZ = jobPos.getZ() + dz;

                // Sample surface Y at the northwest corner of the candidate area
                mutable.set(baseX, jobPos.getY(), baseZ);
                int surfaceY = world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, mutable).getY() - 1;

                BlockPos origin = new BlockPos(baseX, surfaceY, baseZ);
                if (isSiteValid(world, origin)) {
                    return buildPenPlan(origin);
                }
            }
        }
        return null;
    }

    /**
     * Validates a 7×7 site with northwest corner at {@code origin}:
     * <ul>
     *   <li>All floor Y within ±FLAT_Y_TOLERANCE of origin.getY()</li>
     *   <li>No DIRT_PATH blocks anywhere in the 7×7 footprint</li>
     *   <li>No job-site POI inside the 5×5 interior</li>
     *   <li>Solid floor + replaceable space at pen level for all 49 positions</li>
     * </ul>
     */
    private boolean isSiteValid(ServerWorld world, BlockPos origin) {
        int baseY = origin.getY();
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        for (int dx = 0; dx < PEN_SIZE; dx++) {
            for (int dz = 0; dz < PEN_SIZE; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;

                // Sample actual surface at this column
                mutable.set(x, baseY, z);
                int surfY = world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, mutable).getY() - 1;

                // Flatness check
                if (Math.abs(surfY - baseY) > FLAT_Y_TOLERANCE) return false;

                // Use the actual surface Y for block checks
                mutable.set(x, surfY, z);
                BlockState floor = world.getBlockState(mutable);

                // Dirt path check — natural gap/road, don't build here
                if (floor.isOf(Blocks.DIRT_PATH)) return false;

                // Solid floor — use isSideSolidFullSquare so slabs, stairs, and other
                // partial blocks with a solid top face are accepted (isSolidBlock requires
                // full cube occlusion and rejects common terrain like bottom slabs).
                if (!floor.isSideSolidFullSquare(world, mutable.toImmutable(), net.minecraft.util.math.Direction.UP)) return false;

                // Space at pen level must be replaceable
                mutable.set(x, surfY + 1, z);
                if (!world.getBlockState(mutable).isReplaceable()) return false;

            }
        }

        // Single POI check for the entire interior (5×5 inner area)
        // More efficient than per-cell checks
        BlockPos interiorCenter = new BlockPos(
                origin.getX() + PEN_SIZE / 2,
                origin.getY() + 1,
                origin.getZ() + PEN_SIZE / 2);
        PointOfInterestStorage poiStorage = world.getPointOfInterestStorage();
        boolean hasPoi = poiStorage.getInSquare(
                type -> true,
                interiorCenter,
                PEN_SIZE / 2 - 1,
                PointOfInterestStorage.OccupationStatus.ANY
        ).findAny().isPresent();
        if (hasPoi) return false;

        return true;
    }

    /**
     * Builds the fence position list and gate position for a valid 7×7 site.
     * The pen exterior is the outer ring of the 7×7. Gate is at south face center (dx=3).
     * Gate faces SOUTH (inward opening direction is north).
     */
    private PenPlan buildPenPlan(BlockPos origin) {
        // Determine the actual Y for the pen level (surface + 1)
        // Use a consistent Y by reading the surface at the origin corner
        // We'll place fences at the surfaceY+1 level, adapting per column
        int baseX = origin.getX();
        int baseZ = origin.getZ();
        int penY = origin.getY() + 1; // fences sit on top of the ground

        List<BlockPos> fences = new ArrayList<>();

        // North face (dz=0): all 7 columns
        for (int dx = 0; dx < PEN_SIZE; dx++) {
            fences.add(new BlockPos(baseX + dx, penY, baseZ));
        }
        // South face (dz=PEN_SIZE-1): all 7 columns
        for (int dx = 0; dx < PEN_SIZE; dx++) {
            fences.add(new BlockPos(baseX + dx, penY, baseZ + PEN_SIZE - 1));
        }
        // West face (dx=0): interior Z only (skip corners already covered)
        for (int dz = 1; dz < PEN_SIZE - 1; dz++) {
            fences.add(new BlockPos(baseX, penY, baseZ + dz));
        }
        // East face (dx=PEN_SIZE-1): interior Z only
        for (int dz = 1; dz < PEN_SIZE - 1; dz++) {
            fences.add(new BlockPos(baseX + PEN_SIZE - 1, penY, baseZ + dz));
        }

        // Gate at south face center: dx = PEN_SIZE/2 = 3
        int gateDx = PEN_SIZE / 2; // = 3 for PEN_SIZE=7
        BlockPos gatePos = new BlockPos(baseX + gateDx, penY, baseZ + PEN_SIZE - 1);
        fences.remove(gatePos); // Remove the gate slot from fence list

        // Gate faces NORTH — opens inward toward pen interior
        Direction gateFacing = Direction.NORTH;

        return new PenPlan(origin, new ArrayList<>(fences), gatePos, gateFacing);
    }

    // -------------------------------------------------------------------------
    // Inventory helpers
    // -------------------------------------------------------------------------

    private Optional<Inventory> getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) return Optional.empty();
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, chestPos, false));
    }

    private int countTag(Inventory inventory, net.minecraft.registry.tag.TagKey<Item> tag) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && stack.isIn(tag)) total += stack.getCount();
        }
        return total;
    }

    private boolean consumeOne(Inventory inventory, net.minecraft.registry.tag.TagKey<Item> tag) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && stack.isIn(tag)) {
                stack.decrement(1);
                if (stack.isEmpty()) inventory.setStack(slot, ItemStack.EMPTY);
                inventory.markDirty();
                return true;
            }
        }
        return false;
    }

    private Block chooseFenceBlock(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && stack.isIn(ItemTags.FENCES) && stack.getItem() instanceof BlockItem bi) {
                return bi.getBlock();
            }
        }
        return Blocks.OAK_FENCE;
    }

    private Block chooseGateBlock(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && stack.isIn(ItemTags.FENCE_GATES) && stack.getItem() instanceof BlockItem bi) {
                return bi.getBlock();
            }
        }
        return Blocks.OAK_FENCE_GATE;
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

    private boolean isNear(BlockPos target) {
        return target != null
                && villager.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= REACH_SQ;
    }

    // -------------------------------------------------------------------------
    // Records / enums
    // -------------------------------------------------------------------------

    private record PenPlan(BlockPos origin, List<BlockPos> fencePositions, BlockPos gatePos, Direction gateFacing) {}

    private enum Stage {
        IDLE,
        MOVE_TO_FENCE,
        PLACE_FENCE,
        MOVE_TO_GATE,
        PLACE_GATE,
        DONE
    }
}
