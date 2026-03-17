package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.VillageMappedBoundsState;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.MapColor;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class CartographerMapExplorationGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(CartographerMapExplorationGoal.class);
    private static final int CHECK_INTERVAL_TICKS = 600;
    private static final double MOVE_SPEED = 0.6D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int MAP_EXPLORE_TIMEOUT_TICKS = 20 * 120;
    private static final int DEFAULT_MAP_SCALE = 0;
    private static final int REQUIRED_MAP_BATCH = 4;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;
    private boolean immediateCheckPending;
    private int mapScale = DEFAULT_MAP_SCALE;
    private final Set<Long> mappedTargets = new HashSet<>();
    private final List<MapTarget> pendingTargets = new ArrayList<>();
    private final List<MapTarget> workflowTargets = new ArrayList<>();
    private final List<ItemStack> workflowMaps = new ArrayList<>();
    private int workflowIndex;
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
        clearWorkflowState();
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

        refreshMappedTargets(world);
        buildTargets(world);

        if (countEmptyMaps(world) < REQUIRED_MAP_BATCH || pendingTargets.size() < REQUIRED_MAP_BATCH) {
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
        stage = Stage.ACQUIRE_MAPS;
        immediateCheckPending = false;
        clearWorkflowState();
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        stage = Stage.DONE;
        clearWorkflowState();
        if (villager.getMainHandStack().isOf(Items.FILLED_MAP)) {
            villager.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case ACQUIRE_MAPS -> {
                Inventory inventory = getChestInventory(world).orElse(null);
                if (inventory == null || pendingTargets.size() < REQUIRED_MAP_BATCH) {
                    stage = Stage.DONE;
                    nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
                    return;
                }

                List<ItemStack> emptyMaps = takeEmptyMapsFromChest(world, REQUIRED_MAP_BATCH);
                if (emptyMaps.size() < REQUIRED_MAP_BATCH) {
                    stage = Stage.DONE;
                    nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
                    return;
                }

                workflowTargets.clear();
                workflowMaps.clear();
                for (int i = 0; i < REQUIRED_MAP_BATCH; i++) {
                    MapTarget target = pendingTargets.remove(0);
                    workflowTargets.add(target);
                    workflowMaps.add(FilledMapItem.createMap(world, target.centerX(), target.centerZ(), (byte) mapScale, true, false));
                }

                workflowIndex = 0;
                setCurrentWorkflowTarget(world);
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
                        completeCurrentTerritory(world, false);
                    }
                } else if (world.getTime() - mapExploreStartTick >= MAP_EXPLORE_TIMEOUT_TICKS) {
                    completeCurrentTerritory(world, true);
                } else {
                    moveTo(waypoint);
                }
            }
            case RETURN_TO_CHEST -> {
                if (isNear(chestPos)) {
                    Inventory inventory = getChestInventory(world).orElse(null);
                    if (inventory != null) {
                        for (ItemStack workflowMap : workflowMaps) {
                            ItemStack finalizedMap = workflowMap.copy();
                            forceCompleteMapStack(world, finalizedMap);
                            insertStack(inventory, finalizedMap);
                        }
                        inventory.markDirty();
                    }

                    clearWorkflowState();
                    if (villager.getMainHandStack().isOf(Items.FILLED_MAP)) {
                        villager.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
                    }

                    refreshMappedTargets(world);
                    buildTargets(world);
                    if (countEmptyMaps(world) >= REQUIRED_MAP_BATCH && pendingTargets.size() >= REQUIRED_MAP_BATCH) {
                        stage = Stage.ACQUIRE_MAPS;
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

    private void setCurrentWorkflowTarget(ServerWorld world) {
        if (workflowIndex < 0 || workflowIndex >= workflowTargets.size() || workflowIndex >= workflowMaps.size()) {
            stage = Stage.RETURN_TO_CHEST;
            moveTo(chestPos);
            return;
        }

        currentTarget = workflowTargets.get(workflowIndex);
        activeMap = workflowMaps.get(workflowIndex);
        villager.setStackInHand(Hand.MAIN_HAND, activeMap);
        prepareExplorationPath(currentTarget);
        stage = Stage.GO_TO_TARGET;
        moveTo(explorationWaypoints.get(waypointIndex));
        mapExploreStartTick = world.getTime();
    }

    private void completeCurrentTerritory(ServerWorld world, boolean timedOut) {
        mappedTargets.add(currentTarget.key());
        forceCompleteMapStack(world, activeMap);
        LOGGER.info("Cartographer {} completed territory {}/{} at {} (timeout={})",
                villager.getUuidAsString(),
                workflowIndex + 1,
                workflowTargets.size(),
                currentTarget.toPos(jobPos.getY()).toShortString(),
                timedOut);

        workflowIndex++;
        if (workflowIndex < workflowTargets.size()) {
            setCurrentWorkflowTarget(world);
        } else {
            // All 4 maps complete — compute mapped bounds and store them.
            emitMappedBoundsToRegistry(world);
            activeMap = ItemStack.EMPTY;
            stage = Stage.RETURN_TO_CHEST;
            moveTo(chestPos);
        }
    }

    /**
     * Computes the bounding box that covers all 4 completed map tiles and writes it to
     * {@link VillageMappedBoundsState}, keyed by the nearest bell to the cartographer's job site.
     */
    private void emitMappedBoundsToRegistry(ServerWorld world) {
        if (workflowTargets.isEmpty()) {
            return;
        }

        int mapSize = getMapSize(mapScale);
        int half = mapSize / 2;

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (MapTarget target : workflowTargets) {
            minX = Math.min(minX, target.centerX() - half);
            maxX = Math.max(maxX, target.centerX() + half);
            minZ = Math.min(minZ, target.centerZ() - half);
            maxZ = Math.max(maxZ, target.centerZ() + half);
        }

        // Find nearest bell to the job site so we can key the bounds correctly.
        BlockPos nearestBell = findNearestBell(world, jobPos, 300);
        if (nearestBell == null) {
            LOGGER.warn("Cartographer {} could not find a nearby bell to key mapped bounds; skipping registry write",
                    villager.getUuidAsString());
            return;
        }

        VillageMappedBoundsState boundsState = VillageMappedBoundsState.get(world.getServer());
        VillageMappedBoundsState.MappedBounds bounds = new VillageMappedBoundsState.MappedBounds(minX, maxX, minZ, maxZ);
        boundsState.putBounds(world.getRegistryKey(), nearestBell, bounds);

        LOGGER.info("Cartographer {} wrote mapped bounds for bell {} → [{},{} to {},{}]",
                villager.getUuidAsString(),
                nearestBell.toShortString(),
                minX, minZ, maxX, maxZ);
    }

    private BlockPos findNearestBell(ServerWorld world, BlockPos center, int radius) {
        BlockPos min = center.add(-radius, -16, -radius);
        BlockPos max = center.add(radius, 16, radius);
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (BlockPos cursor : BlockPos.iterate(min, max)) {
            if (world.getBlockState(cursor).isOf(Blocks.BELL)) {
                double dist = center.getSquaredDistance(cursor);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = cursor.toImmutable();
                }
            }
        }
        return nearest;
    }

    private void clearWorkflowState() {
        workflowTargets.clear();
        workflowMaps.clear();
        workflowIndex = 0;
        currentTarget = null;
        activeMap = ItemStack.EMPTY;
    }

    public void onChestInventoryChanged(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return;
        }

        boolean changed = false;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isOf(Items.FILLED_MAP)) {
                continue;
            }
            forceCompleteMapStack(world, stack);
            changed = true;
        }

        if (changed) {
            inventory.markDirty();
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
            if (isMostlyUncolored(stack, world)) {
                forceCompleteMapStack(world, stack);
                inventory.markDirty();
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
        activeMap.inventoryTick(world, villager, 0, true);
        forceMapColorUpdate(world, activeMap);
    }

    private void forceCompleteMapStack(ServerWorld world, ItemStack mapStack) {
        if (mapStack.isEmpty() || !mapStack.isOf(Items.FILLED_MAP)) {
            return;
        }

        for (int pass = 0; pass < 4; pass++) {
            mapStack.inventoryTick(world, villager, 0, true);
            forceMapColorUpdate(world, mapStack);
            FilledMapItem.fillExplorationMap(world, mapStack);
            forceMapColorUpdate(world, mapStack);
        }
        populateMapFromWorld(world, mapStack);
        finalizeMapColors(world, mapStack);
    }

    private boolean isMostlyUncolored(ItemStack mapStack, ServerWorld world) {
        MapState state = FilledMapItem.getMapState(mapStack, world);
        if (state == null) {
            return true;
        }
        int colored = 0;
        for (byte color : state.colors) {
            if (color != 0) {
                colored++;
                if (colored >= 128) {
                    return false;
                }
            }
        }
        return true;
    }

    private void forceMapColorUpdate(ServerWorld world, ItemStack mapStack) {
        if (!(mapStack.getItem() instanceof FilledMapItem filledMapItem)) {
            return;
        }
        MapState state = FilledMapItem.getMapState(mapStack, world);
        if (state == null) {
            return;
        }
        filledMapItem.updateColors(world, villager, state);
        state.markDirty();
    }

    private void populateMapFromWorld(ServerWorld world, ItemStack mapStack) {
        MapState state = FilledMapItem.getMapState(mapStack, world);
        if (state == null) {
            return;
        }

        int scale = state.scale;
        int sampleStep = 1 << scale;
        for (int mapX = 0; mapX < 128; mapX++) {
            for (int mapZ = 0; mapZ < 128; mapZ++) {
                int worldX = state.centerX + (mapX - 64) * sampleStep;
                int worldZ = state.centerZ + (mapZ - 64) * sampleStep;
                int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, worldX, worldZ) - 1;
                BlockPos samplePos = new BlockPos(worldX, Math.max(world.getBottomY(), topY), worldZ);
                MapColor mapColor = world.getBlockState(samplePos).getMapColor(world, samplePos);
                if (mapColor == MapColor.CLEAR) {
                    continue;
                }
                byte packedColor = (byte) (mapColor.id * 4 + 1);
                state.setColor(mapX, mapZ, packedColor);
            }
        }
        state.markDirty();
    }

    private void finalizeMapColors(ServerWorld world, ItemStack mapStack) {
        MapState state = FilledMapItem.getMapState(mapStack, world);
        if (state == null) {
            FilledMapItem.fillExplorationMap(world, mapStack);
            state = FilledMapItem.getMapState(mapStack, world);
            if (state == null) {
                return;
            }
        }

        byte fallback = 1;
        for (byte color : state.colors) {
            if (color != 0) {
                fallback = color;
                break;
            }
        }

        for (int x = 0; x < 128; x++) {
            for (int z = 0; z < 128; z++) {
                int index = x + z * 128;
                if (state.colors[index] == 0) {
                    state.setColor(x, z, fallback);
                }
            }
        }
        state.markDirty();
    }

    private int countEmptyMaps(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (isEmptyMap(stack, world)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private List<ItemStack> takeEmptyMapsFromChest(ServerWorld world, int amount) {
        List<ItemStack> taken = new ArrayList<>();
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return taken;
        }

        for (int slot = 0; slot < inventory.size() && taken.size() < amount; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isEmptyMap(stack, world)) {
                continue;
            }
            while (!stack.isEmpty() && taken.size() < amount) {
                ItemStack split = stack.split(1);
                if (!split.isEmpty()) {
                    taken.add(split);
                }
            }
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
        }

        if (taken.size() < amount) {
            for (ItemStack stack : taken) {
                insertStack(inventory, stack);
            }
            inventory.markDirty();
            return List.of();
        }

        inventory.markDirty();
        return taken;
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
        ACQUIRE_MAPS,
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
