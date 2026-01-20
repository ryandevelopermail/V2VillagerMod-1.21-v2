package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapItem;
import net.minecraft.item.map.MapState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

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
    private static final int MAP_UPDATE_TICKS = 200;
    private static final int DEFAULT_MAP_SCALE = 0;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;
    private boolean immediateCheckPending;
    private int mapScale = DEFAULT_MAP_SCALE;
    private int gridSize = 2;
    private final Set<Long> mappedTargets = new HashSet<>();
    private final List<MapTarget> pendingTargets = new ArrayList<>();
    private MapTarget currentTarget;
    private ItemStack activeMap = ItemStack.EMPTY;
    private long mapUpdateStartTick;

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
                activeMap = MapItem.createMap(world, currentTarget.centerX(), currentTarget.centerZ(), (byte) mapScale, true, false);
                villager.setStackInHand(Hand.MAIN_HAND, activeMap);
                stage = Stage.GO_TO_TARGET;
                moveTo(currentTarget.toPos(jobPos.getY()));
            }
            case GO_TO_TARGET -> {
                if (isNear(currentTarget.toPos(jobPos.getY()))) {
                    stage = Stage.WAIT_FOR_UPDATE;
                    mapUpdateStartTick = world.getTime();
                } else {
                    moveTo(currentTarget.toPos(jobPos.getY()));
                }
            }
            case WAIT_FOR_UPDATE -> {
                if (world.getTime() - mapUpdateStartTick >= MAP_UPDATE_TICKS) {
                    mappedTargets.add(currentTarget.key());
                    stage = Stage.RETURN_TO_CHEST;
                    moveTo(chestPos);
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
            MapState state = MapItem.getMapState(stack, world);
            if (state == null) {
                continue;
            }
            mapScale = state.scale;
            int mapSize = getMapSize(mapScale);
            int mapIndexX = Math.floorDiv(state.centerX, mapSize);
            int mapIndexZ = Math.floorDiv(state.centerZ, mapSize);
            mappedTargets.add(packKey(mapIndexX, mapIndexZ));
        }
    }

    private void buildTargets(ServerWorld world) {
        pendingTargets.clear();
        int mapSize = getMapSize(mapScale);
        int baseIndexX = Math.floorDiv(jobPos.getX(), mapSize);
        int baseIndexZ = Math.floorDiv(jobPos.getZ(), mapSize);
        gridSize = determineGridSize(mappedTargets.size());
        int half = gridSize / 2;
        int startX = baseIndexX - half;
        int startZ = baseIndexZ - half;
        for (int offsetX = 0; offsetX < gridSize; offsetX++) {
            for (int offsetZ = 0; offsetZ < gridSize; offsetZ++) {
                int mapIndexX = startX + offsetX;
                int mapIndexZ = startZ + offsetZ;
                long key = packKey(mapIndexX, mapIndexZ);
                if (mappedTargets.contains(key)) {
                    continue;
                }
                int centerX = mapIndexX * mapSize + mapSize / 2;
                int centerZ = mapIndexZ * mapSize + mapSize / 2;
                pendingTargets.add(new MapTarget(centerX, centerZ, key));
            }
        }
    }

    private int determineGridSize(int mappedCount) {
        int size = 2;
        while (mappedCount >= size * size) {
            size++;
        }
        return size;
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
        return MapItem.getMapState(stack, world) == null;
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
            if (ItemStack.canCombine(existing, stack) && existing.getCount() < existing.getMaxCount()) {
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
        WAIT_FOR_UPDATE,
        RETURN_TO_CHEST,
        DONE
    }

    private record MapTarget(int centerX, int centerZ, long key) {
        BlockPos toPos(int y) {
            return new BlockPos(centerX, y, centerZ);
        }
    }
}
