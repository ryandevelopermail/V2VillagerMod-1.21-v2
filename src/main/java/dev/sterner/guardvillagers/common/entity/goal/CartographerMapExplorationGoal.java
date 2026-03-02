package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.item.FilledMapItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class CartographerMapExplorationGoal extends Goal {
    private static final int CHECK_INTERVAL_TICKS = 600;
    private static final double MOVE_SPEED = 0.6D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int MAP_EXPLORE_TIMEOUT_TICKS = 20 * 120;
    private static final int DEFAULT_MAP_SCALE = 0;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;
    private boolean immediateCheckPending;
    private int mapScale = DEFAULT_MAP_SCALE;
    private final Set<Long> mappedTargets = new HashSet<>();
    private final List<MapTarget> pendingTargets = new ArrayList<>();
    private MapTarget currentTarget;
    private ItemStack activeMap = ItemStack.EMPTY;
    private final List<BlockPos> explorationWaypoints = new ArrayList<>();
    private int waypointIndex;
    private long mapExploreStartTick;

    public CartographerMapExplorationGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        setTargets(jobPos, chestPos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.stage = Stage.IDLE;
    }

    public void requestImmediateCheck(ServerWorld world) {
        immediateCheckPending = true;
        nextCheckTime = 0L;
        refreshMappedTargets(world);
    }

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (jobPos == null || chestPos == null) {
            return false;
        }
        if (!immediateCheckPending && world.getTime() < nextCheckTime) {
            return false;
        }
        if (!hasEmptyMap(world)) {
            nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
            return false;
        }
        refreshMappedTargets(world);
        buildTargets(world);
        if (pendingTargets.isEmpty()) {
            nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
            immediateCheckPending = false;
            return false;
        }
        return true;
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.DONE && villager.isAlive();
    }

    @Override
    public void start() {
        stage = Stage.ACQUIRE_MAP;
        immediateCheckPending = false;
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        stage = Stage.DONE;
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case ACQUIRE_MAP -> {
                if (pendingTargets.isEmpty()) {
                    stage = Stage.DONE;
                    nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
                    return;
                }
                currentTarget = pendingTargets.remove(0);
                ItemStack emptyMap = takeEmptyMapFromChest(world);
                if (emptyMap.isEmpty()) {
                    stage = Stage.DONE;
                    nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
                    return;
                }
                activeMap = FilledMapItem.createMap(world, currentTarget.centerX(), currentTarget.centerZ(), (byte) mapScale, true, false);
                villager.setStackInHand(Hand.MAIN_HAND, activeMap);
                prepareExplorationPath(currentTarget);
                stage = Stage.GO_TO_TARGET;
                moveTo(explorationWaypoints.get(waypointIndex));
            }
            case GO_TO_TARGET -> {
                tickActiveMap(world);
                BlockPos waypoint = explorationWaypoints.get(waypointIndex);
                if (isNear(waypoint)) {
                    stage = Stage.EXPLORE_MAP;
                    mapExploreStartTick = world.getTime();
                } else {
                    moveTo(waypoint);
                }
            }
            case EXPLORE_MAP -> {
                tickActiveMap(world);
                BlockPos waypoint = explorationWaypoints.get(waypointIndex);
                if (isNear(waypoint)) {
                    if (waypointIndex + 1 < explorationWaypoints.size()) {
                        waypointIndex++;
                        moveTo(explorationWaypoints.get(waypointIndex));
                    } else {
                        mappedTargets.add(currentTarget.key());
                        forceCompleteActiveMap(world);
                        stage = Stage.RETURN_TO_CHEST;
                        moveTo(chestPos);
                    }
                } else if (world.getTime() - mapExploreStartTick >= MAP_EXPLORE_TIMEOUT_TICKS) {
                    mappedTargets.add(currentTarget.key());
                    forceCompleteActiveMap(world);
                    stage = Stage.RETURN_TO_CHEST;
                    moveTo(chestPos);
                } else {
                    moveTo(waypoint);
                }
            }
            case RETURN_TO_CHEST -> {
                if (isNear(chestPos)) {
                    Inventory inventory = getChestInventory(world).orElse(null);
                    if (inventory != null && !activeMap.isEmpty()) {
                        insertStack(inventory, activeMap.copy());
                        inventory.markDirty();
                    }
                    activeMap = ItemStack.EMPTY;
                    if (villager.getMainHandStack().isOf(Items.FILLED_MAP)) {
                        villager.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
                    }
                    refreshMappedTargets(world);
                    buildTargets(world);
                    if (hasEmptyMap(world) && !pendingTargets.isEmpty()) {
                        stage = Stage.ACQUIRE_MAP;
                    } else {
                        nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
                        stage = Stage.DONE;
                    }
                } else {
                    moveTo(chestPos);
                }
            }
            case IDLE, DONE -> {
            }
        }
    }

    private void refreshMappedTargets(ServerWorld world) {
        mappedTargets.clear();
        mapScale = DEFAULT_MAP_SCALE;
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return;
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isOf(Items.FILLED_MAP)) {
                continue;
            }
            MapState state = FilledMapItem.getMapState(stack, world);
            if (state == null) {
                continue;
            }
            mapScale = state.scale;
            int mapSize = getMapSize(mapScale);
            int mapIndexX = worldToMapIndex(state.centerX, mapSize);
            int mapIndexZ = worldToMapIndex(state.centerZ, mapSize);
            mappedTargets.add(packKey(mapIndexX, mapIndexZ));
        }
    }

    private void buildTargets(ServerWorld world) {
        pendingTargets.clear();
        int mapSize = getMapSize(mapScale);
        int baseIndexX = worldToMapIndex(jobPos.getX(), mapSize);
        int baseIndexZ = worldToMapIndex(jobPos.getZ(), mapSize);

        // Always target a fixed 2x2 set, anchored at the job-site map index.
        // Order: base, right, below-base, below-right.
        int[][] offsets = {
                {0, 0},
                {1, 0},
                {0, 1},
                {1, 1}
        };

        for (int[] offset : offsets) {
            int mapIndexX = baseIndexX + offset[0];
            int mapIndexZ = baseIndexZ + offset[1];
            long key = packKey(mapIndexX, mapIndexZ);
            if (mappedTargets.contains(key)) {
                continue;
            }
            int centerX = mapIndexToCenter(mapIndexX, mapSize);
            int centerZ = mapIndexToCenter(mapIndexZ, mapSize);
            pendingTargets.add(new MapTarget(centerX, centerZ, key));
        }
    }

    private int worldToMapIndex(int coordinate, int mapSize) {
        return Math.floorDiv(coordinate + 64, mapSize);
    }

    private int mapIndexToCenter(int mapIndex, int mapSize) {
        return mapIndex * mapSize + mapSize / 2 - 64;
    }


    private void prepareExplorationPath(MapTarget target) {
        explorationWaypoints.clear();
        waypointIndex = 0;

        int mapSize = getMapSize(mapScale);
        int y = jobPos.getY();
        int half = mapSize / 2;
        int inset = Math.max(8, mapSize / 16);

        int westX = target.centerX() - half + inset;
        int eastX = target.centerX() + half - inset;
        int northZ = target.centerZ() - half + inset;
        int southZ = target.centerZ() + half - inset;
        int midX = target.centerX();
        int midZ = target.centerZ();

        explorationWaypoints.add(new BlockPos(midX, y, midZ));
        explorationWaypoints.add(new BlockPos(westX, y, northZ));
        explorationWaypoints.add(new BlockPos(midX, y, northZ));
        explorationWaypoints.add(new BlockPos(eastX, y, northZ));
        explorationWaypoints.add(new BlockPos(eastX, y, midZ));
        explorationWaypoints.add(new BlockPos(eastX, y, southZ));
        explorationWaypoints.add(new BlockPos(midX, y, southZ));
        explorationWaypoints.add(new BlockPos(westX, y, southZ));
        explorationWaypoints.add(new BlockPos(westX, y, midZ));
        explorationWaypoints.add(new BlockPos(midX, y, midZ));
    }

    private void tickActiveMap(ServerWorld world) {
        if (activeMap.isEmpty() || !activeMap.isOf(Items.FILLED_MAP)) {
            return;
        }
        // Ensure map data is populated from villager exploration instead of waiting on player updates.
        activeMap.inventoryTick(world, villager, 0, true);
    }

    private void forceCompleteActiveMap(ServerWorld world) {
        if (activeMap.isEmpty() || !activeMap.isOf(Items.FILLED_MAP)) {
            return;
        }
        try {
            Method fillExplorationMap = FilledMapItem.class.getMethod("fillExplorationMap", ServerWorld.class, ItemStack.class);
            fillExplorationMap.invoke(null, world, activeMap);
        } catch (ReflectiveOperationException ignored) {
            // Keep normal behavior if runtime method name/signature differs.
        }
    }

    private boolean hasEmptyMap(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return false;
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (isEmptyMap(stack, world)) {
                return true;
            }
        }
        return false;
    }

    private ItemStack takeEmptyMapFromChest(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return ItemStack.EMPTY;
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isEmptyMap(stack, world)) {
                continue;
            }
            ItemStack result = stack.split(1);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            inventory.markDirty();
            return result;
        }
        return ItemStack.EMPTY;
    }

    private boolean isEmptyMap(ItemStack stack, ServerWorld world) {
        if (stack.isOf(Items.MAP)) {
            return true;
        }
        if (!stack.isOf(Items.FILLED_MAP)) {
            return false;
        }
        return FilledMapItem.getMapState(stack, world) == null;
    }

    private Optional<Inventory> getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, chestPos, true));
    }

    private void insertStack(Inventory inventory, ItemStack stack) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                inventory.setStack(slot, stack);
                return;
            }
            if (ItemStack.areItemsAndComponentsEqual(existing, stack) && existing.getCount() < existing.getMaxCount()) {
                int move = Math.min(stack.getCount(), existing.getMaxCount() - existing.getCount());
                existing.increment(move);
                stack.decrement(move);
                if (stack.isEmpty()) {
                    return;
                }
            }
        }
    }

    private void moveTo(BlockPos pos) {
        villager.getNavigation().startMovingTo(pos.getX(), pos.getY(), pos.getZ(), MOVE_SPEED);
    }

    private boolean isNear(BlockPos pos) {
        return villager.squaredDistanceTo(Vec3d.ofCenter(pos)) <= TARGET_REACH_SQUARED;
    }

    private int getMapSize(int scale) {
        return 128 * (1 << scale);
    }

    private long packKey(int mapIndexX, int mapIndexZ) {
        return (((long) mapIndexX) << 32) ^ (mapIndexZ & 0xffffffffL);
    }

    private enum Stage {
        IDLE,
        ACQUIRE_MAP,
        GO_TO_TARGET,
        EXPLORE_MAP,
        RETURN_TO_CHEST,
        DONE
    }

    private record MapTarget(int centerX, int centerZ, long key) {
        BlockPos toPos(int y) {
            return new BlockPos(centerX, y, centerZ);
        }
    }
}
