package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.VillageAnchorState;
import dev.sterner.guardvillagers.common.util.VillageMappedBoundsState;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BarrelBlockEntity;
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
import java.util.TreeSet;

public class CartographerMapExplorationGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(CartographerMapExplorationGoal.class);
    private static final int CHECK_INTERVAL_TICKS = 600;
    private static final double MOVE_SPEED = 0.6D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int MAP_EXPLORE_TIMEOUT_TICKS = 20 * 120;
    private static final int MAPPING_BATCH_TIMEOUT_TICKS = 20 * 360;
    private static final int RETURN_TRAVEL_TIMEOUT_TICKS = 20 * 180;
    private static final int TABLE_TRAVEL_TIMEOUT_TICKS = 20 * 120;
    private static final int DEFAULT_MAP_SCALE = 0;
    private static final int REQUIRED_MAP_BATCH = 4;
    /** Maps needed in chest before triggering the cartography-table copy run.
     *  After exploration deposits REQUIRED_MAP_BATCH (4) maps, the copy run fires immediately. */
    private static final int COPY_TRIGGER_COUNT = 4;
    /** Number of map copies to produce at the cartography table (one per original tile). */
    private static final int MAPS_TO_COPY = 4;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;
    private boolean immediateCheckPending;
    private int mapScale = DEFAULT_MAP_SCALE;
    /** True once the cartography-table copy run has been completed for the current mapping cycle. */
    private boolean mapsCopiedThisCycle = false;
    private final Set<Long> mappedTargets = new HashSet<>();
    private final List<MapTarget> pendingTargets = new ArrayList<>();
    private final List<MapTarget> workflowTargets = new ArrayList<>();
    private final List<ItemStack> workflowMaps = new ArrayList<>();
    private final Set<Integer> completedWorkflowIndices = new HashSet<>();
    private int workflowIndex;
    private MapTarget currentTarget;
    private ItemStack activeMap = ItemStack.EMPTY;
    private final List<BlockPos> explorationWaypoints = new ArrayList<>();
    private int waypointIndex;
    private long mapExploreStartTick;
    private long workflowBatchStartTick;
    private long returnTravelStartTick;
    private long tableTravelStartTick;

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

        int emptyMaps = countEmptyMaps(world);
        int pending = pendingTargets.size();

        if (emptyMaps < REQUIRED_MAP_BATCH || pending < REQUIRED_MAP_BATCH) {
            LOGGER.info("Cartographer {} canStart=false: emptyMaps={} (need {}) pendingTiles={} (need {}) mappedTiles={}",
                    villager.getUuidAsString(), emptyMaps, REQUIRED_MAP_BATCH, pending, REQUIRED_MAP_BATCH, mappedTargets.size());
            nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
            immediateCheckPending = false;
            return false;
        }

        // Log first tile destination so the user can confirm cartographer is about to move
        if (!pendingTargets.isEmpty()) {
            MapTarget first = pendingTargets.get(0);
            int dist = (int) Math.sqrt(villager.squaredDistanceTo(first.centerX(), villager.getY(), first.centerZ()));
            LOGGER.info("Cartographer {} canStart=true: emptyMaps={} pendingTiles={} firstTarget=({},{}) approxDist={}",
                    villager.getUuidAsString(), emptyMaps, pending, first.centerX(), first.centerZ(), dist);
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
        returnTravelStartTick = 0L;
        tableTravelStartTick = 0L;
        workflowBatchStartTick = 0L;
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

        applyChestDrivenColdProtection(world);

        if (enforceBatchTimeout(world)) {
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
                completedWorkflowIndices.clear();
                for (int i = 0; i < REQUIRED_MAP_BATCH; i++) {
                    MapTarget target = pendingTargets.remove(0);
                    workflowTargets.add(target);
                    workflowMaps.add(FilledMapItem.createMap(world, target.centerX(), target.centerZ(), (byte) mapScale, true, false));
                }

                workflowIndex = 0;
                workflowBatchStartTick = world.getTime();
                setCurrentWorkflowTarget(world);
            }
            case GO_TO_TARGET -> {
                tickActiveMap(world);
                BlockPos waypoint = explorationWaypoints.get(waypointIndex);
                if (isNear(waypoint)) {
                    stage = Stage.EXPLORE_MAP;
                    mapExploreStartTick = world.getTime();
                } else if (hasTimedOut(world.getTime(), mapExploreStartTick, MAP_EXPLORE_TIMEOUT_TICKS)) {
                    LOGGER.warn("Cartographer {} timed out before reaching exploration waypoint {} for tile {}/{}; forcing completion",
                            villager.getUuidAsString(),
                            waypoint.toShortString(),
                            workflowIndex + 1,
                            workflowTargets.size());
                    completeCurrentTerritory(world, true);
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
                        List<ItemStack> mapsToDeposit = collectCompletedWorkflowMapsForDeposit(workflowMaps, completedWorkflowIndices);
                        for (ItemStack workflowMap : mapsToDeposit) {
                            ItemStack finalizedMap = workflowMap.copyWithCount(1);
                            forceCompleteMapStack(world, finalizedMap);
                            boolean inserted = insertStackChecked(inventory, finalizedMap);
                            if (!inserted) {
                                LOGGER.warn("Cartographer {} could not deposit map into chest at {} — chest may be full",
                                        villager.getUuidAsString(), chestPos.toShortString());
                                // Drop it as item entity so it's not silently lost
                                world.spawnEntity(new net.minecraft.entity.ItemEntity(world,
                                        chestPos.getX() + 0.5, chestPos.getY() + 1.0, chestPos.getZ() + 0.5, finalizedMap));
                            }
                        }
                        inventory.markDirty();
                        LOGGER.info("Cartographer {} deposited {} completed map(s) to chest {}",
                                villager.getUuidAsString(),
                                mapsToDeposit.size(),
                                chestPos.toShortString());
                    } else {
                        LOGGER.warn("Cartographer {} could not open chest at {} for map deposit",
                                villager.getUuidAsString(), chestPos.toShortString());
                    }

                    clearWorkflowState();
                    if (villager.getMainHandStack().isOf(Items.FILLED_MAP)) {
                        villager.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
                    }

                    // If chest now has ≥8 filled maps and we haven't done the copy run yet,
                    // head to the cartography table to produce 4 duplicates.
                    if (!mapsCopiedThisCycle && countFilledMaps(world) >= COPY_TRIGGER_COUNT) {
                        LOGGER.info("Cartographer {}: {} filled maps in chest — heading to cartography table to copy",
                                villager.getUuidAsString(), countFilledMaps(world));
                        stage = Stage.GO_TO_TABLE_FOR_COPY;
                        tableTravelStartTick = world.getTime();
                        moveTo(jobPos);
                    } else {
                        refreshMappedTargets(world);
                        buildTargets(world);
                        if (countEmptyMaps(world) >= REQUIRED_MAP_BATCH && pendingTargets.size() >= REQUIRED_MAP_BATCH) {
                            stage = Stage.ACQUIRE_MAPS;
                        } else {
                            nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
                            stage = Stage.DONE;
                        }
                    }
                } else {
                    if (hasTimedOut(world.getTime(), returnTravelStartTick, RETURN_TRAVEL_TIMEOUT_TICKS)) {
                        LOGGER.warn("Cartographer {} return-to-chest stalled for {} ticks; performing recovery teleport near {}",
                                villager.getUuidAsString(),
                                world.getTime() - returnTravelStartTick,
                                chestPos.toShortString());
                        recoverStalledTravel(world, chestPos);
                        returnTravelStartTick = world.getTime();
                    }
                    moveTo(chestPos);
                }
            }
            case GO_TO_TABLE_FOR_COPY -> {
                if (isNear(jobPos)) {
                    stage = Stage.COPY_MAPS;
                } else {
                    if (hasTimedOut(world.getTime(), tableTravelStartTick, TABLE_TRAVEL_TIMEOUT_TICKS)) {
                        LOGGER.warn("Cartographer {} table travel stalled for {} ticks; performing recovery teleport near {}",
                                villager.getUuidAsString(),
                                world.getTime() - tableTravelStartTick,
                                jobPos.toShortString());
                        recoverStalledTravel(world, jobPos);
                        tableTravelStartTick = world.getTime();
                    }
                    moveTo(jobPos);
                }
            }
            case COPY_MAPS -> {
                // Simulate cartography-table duplication: insert MAPS_TO_COPY additional filled
                // map copies into the chest (one duplicate per original tile map).
                Inventory inventory = getChestInventory(world).orElse(null);
                if (inventory != null) {
                    List<ItemStack> originals = collectFilledMaps(world, MAPS_TO_COPY);
                    int copied = 0;
                    for (ItemStack original : originals) {
                        ItemStack copy = original.copy();
                        if (insertStackChecked(inventory, copy)) {
                            copied++;
                        } else {
                            LOGGER.warn("Cartographer {}: chest full, could not insert map copy", villager.getUuidAsString());
                            break;
                        }
                    }
                    inventory.markDirty();
                    LOGGER.info("Cartographer {}: cartography table copy run complete — {} map(s) duplicated", villager.getUuidAsString(), copied);
                }
                mapsCopiedThisCycle = true;
                refreshMappedTargets(world);
                buildTargets(world);
                if (countEmptyMaps(world) >= REQUIRED_MAP_BATCH && pendingTargets.size() >= REQUIRED_MAP_BATCH) {
                    stage = Stage.ACQUIRE_MAPS;
                } else {
                    nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
                    stage = Stage.DONE;
                }
            }
            case IDLE, DONE -> {
            }
        }
    }

    private boolean isMappingStage() {
        return stage == Stage.GO_TO_TARGET || stage == Stage.EXPLORE_MAP;
    }

    private boolean enforceBatchTimeout(ServerWorld world) {
        if (!isMappingStage() || workflowBatchStartTick <= 0L) {
            return false;
        }
        if (!shouldAbortMappingBatch(world.getTime(), workflowBatchStartTick)) {
            return false;
        }

        LOGGER.warn("Cartographer {} mapping batch exceeded {} ticks (completed {}/{}); returning to chest with partial maps",
                villager.getUuidAsString(),
                MAPPING_BATCH_TIMEOUT_TICKS,
                workflowIndex,
                workflowTargets.size());
        stage = Stage.RETURN_TO_CHEST;
        returnTravelStartTick = world.getTime();
        moveTo(chestPos);
        return true;
    }

    private void setCurrentWorkflowTarget(ServerWorld world) {
        if (workflowIndex < 0 || workflowIndex >= workflowTargets.size() || workflowIndex >= workflowMaps.size()) {
            stage = Stage.RETURN_TO_CHEST;
            returnTravelStartTick = world.getTime();
            moveTo(chestPos);
            return;
        }

        currentTarget = workflowTargets.get(workflowIndex);
        activeMap = workflowMaps.get(workflowIndex);
        villager.setStackInHand(Hand.MAIN_HAND, activeMap);
        prepareExplorationPath(currentTarget);
        stage = Stage.GO_TO_TARGET;
        LOGGER.info("Cartographer {} starting territory {}/{} at {}",
                villager.getUuidAsString(),
                workflowIndex + 1,
                workflowTargets.size(),
                currentTarget.toPos(jobPos.getY()).toShortString());
        moveTo(explorationWaypoints.get(waypointIndex));
        mapExploreStartTick = world.getTime();
    }

    private void completeCurrentTerritory(ServerWorld world, boolean timedOut) {
        mappedTargets.add(currentTarget.key());
        forceCompleteMapStack(world, activeMap);
        completedWorkflowIndices.add(workflowIndex);
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
            returnTravelStartTick = world.getTime();
            moveTo(chestPos);
        }
    }

    static boolean hasTimedOut(long worldTime, long startTick, int timeoutTicks) {
        return worldTime - startTick >= timeoutTicks;
    }

    static boolean shouldAbortMappingBatch(long worldTime, long batchStartTick) {
        return batchStartTick > 0L && hasTimedOut(worldTime, batchStartTick, MAPPING_BATCH_TIMEOUT_TICKS);
    }

    private void recoverStalledTravel(ServerWorld world, BlockPos destination) {
        int surfaceY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, destination.getX(), destination.getZ());
        double tx = destination.getX() + 0.5D;
        double ty = surfaceY;
        double tz = destination.getZ() + 0.5D;
        villager.getNavigation().stop();
        villager.requestTeleport(tx, ty, tz);
        villager.getNavigation().startMovingTo(tx, ty, tz, MOVE_SPEED);
    }

    /**
     * Computes the bounding box that covers all 4 completed map tiles and writes it to
     * {@link VillageMappedBoundsState}, keyed by the nearest QM chest to the cartographer's job site.
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

        // Find the nearest QM chest to the job site — this is the village anchor key.
        VillageAnchorState anchorState = VillageAnchorState.get(world.getServer());
        Optional<BlockPos> nearestQm = anchorState.getNearestQmChest(world, jobPos, 300);
        if (nearestQm.isEmpty()) {
            LOGGER.warn("Cartographer {} could not find a nearby QM chest to key mapped bounds; skipping registry write",
                    villager.getUuidAsString());
            return;
        }
        BlockPos anchorPos = nearestQm.get();

        VillageMappedBoundsState boundsState = VillageMappedBoundsState.get(world.getServer());
        VillageMappedBoundsState.MappedBounds bounds = new VillageMappedBoundsState.MappedBounds(minX, maxX, minZ, maxZ);
        boundsState.putBounds(world.getRegistryKey(), anchorPos, bounds);

        LOGGER.info("Cartographer {} wrote mapped bounds for QM chest {} → [{},{} to {},{}]",
                villager.getUuidAsString(),
                anchorPos.toShortString(),
                minX, minZ, maxX, maxZ);
    }

    private void clearWorkflowState() {
        workflowTargets.clear();
        workflowMaps.clear();
        completedWorkflowIndices.clear();
        workflowIndex = 0;
        currentTarget = null;
        activeMap = ItemStack.EMPTY;
        workflowBatchStartTick = 0L;
    }

    static List<ItemStack> collectCompletedWorkflowMapsForDeposit(List<ItemStack> workflowMaps, Set<Integer> completedIndices) {
        if (workflowMaps.isEmpty() || completedIndices.isEmpty()) {
            return List.of();
        }

        List<ItemStack> result = new ArrayList<>();
        for (Integer index : new TreeSet<>(completedIndices)) {
            if (index == null || index < 0 || index >= workflowMaps.size()) {
                continue;
            }
            ItemStack stack = workflowMaps.get(index);
            if (!stack.isEmpty()) {
                result.add(stack);
            }
        }
        return result;
    }

    private void resetMappingCycle() {
        clearWorkflowState();
        mapsCopiedThisCycle = false;
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

        // If all 4 tiles are already mapped, clear mappedTargets so the cartographer
        // can do a fresh remapping cycle on the next check. Otherwise it would sit idle forever.
        boolean allMapped = true;
        for (int[] offset : offsets) {
            long key = packKey(baseIndexX + offset[0], baseIndexZ + offset[1]);
            if (!mappedTargets.contains(key)) {
                allMapped = false;
                break;
            }
        }
        if (allMapped) {
            LOGGER.info("Cartographer {} all 4 tiles already mapped — resetting for next mapping cycle", villager.getUuidAsString());
            mappedTargets.clear();
            mapsCopiedThisCycle = false;
        }

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
        int half = mapSize / 2;
        int inset = Math.max(8, mapSize / 16);

        int westX = target.centerX() - half + inset;
        int eastX = target.centerX() + half - inset;
        int northZ = target.centerZ() - half + inset;
        int southZ = target.centerZ() + half - inset;
        int midX = target.centerX();
        int midZ = target.centerZ();

        // Use surface heightmap Y per waypoint so the navigator is never sent
        // into a tree trunk, underground, or mid-air on hilly terrain.
        explorationWaypoints.add(surfacePos(midX, midZ));
        explorationWaypoints.add(surfacePos(westX, northZ));
        explorationWaypoints.add(surfacePos(midX, northZ));
        explorationWaypoints.add(surfacePos(eastX, northZ));
        explorationWaypoints.add(surfacePos(eastX, midZ));
        explorationWaypoints.add(surfacePos(eastX, southZ));
        explorationWaypoints.add(surfacePos(midX, southZ));
        explorationWaypoints.add(surfacePos(westX, southZ));
        explorationWaypoints.add(surfacePos(westX, midZ));
        explorationWaypoints.add(surfacePos(midX, midZ));
    }

    /**
     * Returns a surface-level BlockPos at (x, z) using the MOTION_BLOCKING_NO_LEAVES
     * heightmap so waypoints land on walkable ground and are never inside tree trunks.
     * Falls back to WORLD_SURFACE if the world isn't a ServerWorld (should never occur
     * in practice since this goal guards on instanceof ServerWorld in canStart/tick).
     */
    private BlockPos surfacePos(int x, int z) {
        if (villager.getWorld() instanceof ServerWorld world) {
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            return new BlockPos(x, y, z);
        }
        return new BlockPos(x, jobPos.getY(), z);
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
        BlockPos.Mutable mutablePos = new BlockPos.Mutable();
        for (int mapX = 0; mapX < 128; mapX++) {
            for (int mapZ = 0; mapZ < 128; mapZ++) {
                int worldX = state.centerX + (mapX - 64) * sampleStep;
                int worldZ = state.centerZ + (mapZ - 64) * sampleStep;
                int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, worldX, worldZ) - 1;
                mutablePos.set(worldX, Math.max(world.getBottomY(), topY), worldZ);
                MapColor mapColor = world.getBlockState(mutablePos).getMapColor(world, mutablePos);
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

    /** Counts all FILLED_MAP items in the paired chest. */
    private int countFilledMaps(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) return 0;
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(Items.FILLED_MAP)) count += stack.getCount();
        }
        return count;
    }

    /**
     * Returns up to {@code max} filled-map stacks (one copy per stack) from the
     * paired chest to use as originals for the cartography-table copy run.
     * Does NOT consume the originals — they stay in the chest.
     */
    private List<ItemStack> collectFilledMaps(ServerWorld world, int max) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) return List.of();
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < inventory.size() && result.size() < max; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(Items.FILLED_MAP)) {
                result.add(stack.copyWithCount(1));
            }
        }
        return result;
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
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, chestPos, false));
        }
        if (state.getBlock() instanceof BarrelBlock) {
            if (world.getBlockEntity(chestPos) instanceof BarrelBlockEntity barrel) {
                return Optional.of(barrel);
            }
        }
        return Optional.empty();
    }

    /**
     * If the paired chest contains leather boots, treat the cartographer as cold-protected for
     * this tick (same gameplay intent as wearing leather boots in snow workflows).
     */
    private void applyChestDrivenColdProtection(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return;
        }
        if (!hasItem(inventory, Items.LEATHER_BOOTS)) {
            return;
        }

        // Prevent freeze buildup/damage in cold biomes and powder snow while this chest policy is active.
        if (villager.getFrozenTicks() > 0) {
            villager.setFrozenTicks(0);
        }
    }

    private boolean hasItem(Inventory inventory, net.minecraft.item.Item item) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && stack.isOf(item)) {
                return true;
            }
        }
        return false;
    }

    /** Inserts stack into inventory; silently discards any remainder. */
    private void insertStack(Inventory inventory, ItemStack stack) {
        insertStackChecked(inventory, stack);
    }

    /**
     * Inserts stack into inventory. Returns {@code true} if all items were inserted,
     * {@code false} if the inventory was too full to accept the full stack.
     */
    private boolean insertStackChecked(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (remaining.isEmpty()) {
                return true;
            }
            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                if (!inventory.isValid(slot, remaining)) {
                    continue;
                }
                int moved = Math.min(remaining.getCount(), remaining.getMaxCount());
                ItemStack toInsert = remaining.copy();
                toInsert.setCount(moved);
                inventory.setStack(slot, toInsert);
                remaining.decrement(moved);
                continue;
            }
            if (!ItemStack.areItemsAndComponentsEqual(existing, remaining)) {
                continue;
            }
            if (!inventory.isValid(slot, remaining)) {
                continue;
            }
            int space = existing.getMaxCount() - existing.getCount();
            if (space <= 0) {
                continue;
            }
            int moved = Math.min(space, remaining.getCount());
            existing.increment(moved);
            remaining.decrement(moved);
        }
        return remaining.isEmpty();
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
        GO_TO_TABLE_FOR_COPY,
        COPY_MAPS,
        DONE
    }

    private record MapTarget(int centerX, int centerZ, long key) {
        BlockPos toPos(int y) {
            return new BlockPos(centerX, y, centerZ);
        }
    }
}
