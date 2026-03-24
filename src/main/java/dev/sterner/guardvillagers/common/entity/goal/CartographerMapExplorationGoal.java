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

public class CartographerMapExplorationGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(CartographerMapExplorationGoal.class);
    private static final int CHECK_INTERVAL_TICKS = 600;
    private static final double MOVE_SPEED = 0.6D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int MAP_EXPLORE_TIMEOUT_TICKS = 20 * 120;
    private static final int DEFAULT_MAP_SCALE = 0;
    private static final int REQUIRED_MAP_BATCH = 4;
    /** Number of filled-map originals required before running a cartography-table copy batch. */
    static final int ORIGINALS_REQUIRED_FOR_COPY = 4;
    /** Number of blank maps consumed by one cartography-table copy batch. */
    static final int BLANKS_REQUIRED_FOR_COPY = 4;
    /** Number of duplicated filled maps produced by one cartography-table copy batch. */
    static final int COPIES_TO_CREATE = 4;

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
                    } else {
                        LOGGER.warn("Cartographer {} could not open chest at {} for map deposit",
                                villager.getUuidAsString(), chestPos.toShortString());
                    }

                    clearWorkflowState();
                    if (villager.getMainHandStack().isOf(Items.FILLED_MAP)) {
                        villager.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
                    }

                    if (shouldRunCopyBatch(world)) {
                        LOGGER.info("Cartographer {}: copy batch ready (filledMaps={} blankMaps={}) — heading to cartography table",
                                villager.getUuidAsString(), countFilledMaps(world), countBlankMapsForCopy(world));
                        stage = Stage.GO_TO_TABLE_FOR_COPY;
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
                    moveTo(chestPos);
                }
            }
            case GO_TO_TABLE_FOR_COPY -> {
                if (isNear(jobPos)) {
                    stage = Stage.COPY_MAPS;
                } else {
                    moveTo(jobPos);
                }
            }
            case COPY_MAPS -> {
                Inventory inventory = getChestInventory(world).orElse(null);
                if (inventory != null) {
                    CopyBatchResult result = runCopyBatch(inventory, ORIGINALS_REQUIRED_FOR_COPY, BLANKS_REQUIRED_FOR_COPY, COPIES_TO_CREATE);
                    if (result.success()) {
                        mapsCopiedThisCycle = true;
                        LOGGER.info("Cartographer {}: cartography table copy run complete — {} map(s) duplicated; {} blank map(s) consumed",
                                villager.getUuidAsString(), result.copiesCreated(), result.blanksConsumed());
                    } else {
                        LOGGER.warn("Cartographer {}: cartography table copy run aborted ({})",
                                villager.getUuidAsString(), result.reason());
                    }
                    inventory.markDirty();
                }
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
        workflowIndex = 0;
        currentTarget = null;
        activeMap = ItemStack.EMPTY;
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

    /** Counts blank MAP items reserved for cartography-table copy batches. */
    private int countBlankMapsForCopy(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) return 0;
        return countMapsForCopy(inventory);
    }

    private boolean shouldRunCopyBatch(ServerWorld world) {
        return shouldRunCopyBatch(countFilledMaps(world), countBlankMapsForCopy(world), mapsCopiedThisCycle);
    }

    static boolean shouldRunCopyBatch(int filledMaps, int blankMaps, boolean mapsCopiedThisCycle) {
        return !mapsCopiedThisCycle
                && filledMaps >= ORIGINALS_REQUIRED_FOR_COPY
                && blankMaps >= BLANKS_REQUIRED_FOR_COPY;
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

    private static int countMapsForCopy(Inventory inventory) {
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(Items.MAP)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    static CopyBatchResult runCopyBatch(Inventory inventory, int originalsRequired, int blanksRequired, int copiesToCreate) {
        List<ItemStack> originals = collectFilledMaps(inventory, originalsRequired);
        if (originals.size() < originalsRequired) {
            return CopyBatchResult.failure("insufficient originals");
        }

        List<ItemStack> copies = new ArrayList<>();
        for (int i = 0; i < copiesToCreate; i++) {
            copies.add(originals.get(i % originals.size()).copy());
        }

        if (!canInsertAll(inventory, copies)) {
            return CopyBatchResult.failure("insufficient chest space");
        }

        List<ItemStack> consumedBlanks = removeBlankMaps(inventory, blanksRequired);
        if (consumedBlanks.size() < blanksRequired) {
            restoreItems(inventory, consumedBlanks);
            return CopyBatchResult.failure("insufficient blank maps");
        }

        int inserted = 0;
        for (ItemStack copy : copies) {
            if (insertStackCheckedStatic(inventory, copy)) {
                inserted++;
                continue;
            }
            restoreItems(inventory, consumedBlanks);
            return CopyBatchResult.failure("copy insertion failed");
        }

        return CopyBatchResult.success(inserted, blanksRequired);
    }

    private static List<ItemStack> collectFilledMaps(Inventory inventory, int max) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < inventory.size() && result.size() < max; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(Items.FILLED_MAP) && !stack.isEmpty()) {
                int take = Math.min(max - result.size(), stack.getCount());
                for (int i = 0; i < take; i++) {
                    result.add(stack.copyWithCount(1));
                }
            }
        }
        return result;
    }

    private static List<ItemStack> removeBlankMaps(Inventory inventory, int amount) {
        List<ItemStack> removed = new ArrayList<>();
        for (int slot = 0; slot < inventory.size() && removed.size() < amount; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isOf(Items.MAP)) {
                continue;
            }
            while (!stack.isEmpty() && removed.size() < amount) {
                ItemStack split = stack.split(1);
                if (!split.isEmpty()) {
                    removed.add(split);
                }
            }
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
        }
        return removed;
    }

    private static void restoreItems(Inventory inventory, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            insertStackCheckedStatic(inventory, stack);
        }
    }

    private static boolean canInsertAll(Inventory inventory, List<ItemStack> stacks) {
        List<ItemStack> simulated = new ArrayList<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            simulated.add(inventory.getStack(slot).copy());
        }
        for (ItemStack stack : stacks) {
            if (!insertStackCheckedStatic(simulated, stack, inventory)) {
                return false;
            }
        }
        return true;
    }

    private static boolean insertStackCheckedStatic(Inventory inventory, ItemStack stack) {
        List<ItemStack> target = new ArrayList<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            target.add(inventory.getStack(slot).copy());
        }
        boolean inserted = insertStackCheckedStatic(target, stack, inventory);
        if (!inserted) {
            return false;
        }
        for (int slot = 0; slot < target.size(); slot++) {
            inventory.setStack(slot, target.get(slot));
        }
        return true;
    }

    private static boolean insertStackCheckedStatic(List<ItemStack> target, ItemStack stack, Inventory validityInventory) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < target.size(); slot++) {
            if (remaining.isEmpty()) {
                return true;
            }
            ItemStack existing = target.get(slot);
            if (existing.isEmpty()) {
                if (!validityInventory.isValid(slot, remaining)) {
                    continue;
                }
                int moved = Math.min(remaining.getCount(), remaining.getMaxCount());
                ItemStack toInsert = remaining.copy();
                toInsert.setCount(moved);
                target.set(slot, toInsert);
                remaining.decrement(moved);
                continue;
            }
            if (!ItemStack.areItemsAndComponentsEqual(existing, remaining)) {
                continue;
            }
            if (!validityInventory.isValid(slot, remaining)) {
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

    record CopyBatchResult(boolean success, int copiesCreated, int blanksConsumed, String reason) {
        static CopyBatchResult success(int copiesCreated, int blanksConsumed) {
            return new CopyBatchResult(true, copiesCreated, blanksConsumed, "ok");
        }

        static CopyBatchResult failure(String reason) {
            return new CopyBatchResult(false, 0, 0, reason);
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
