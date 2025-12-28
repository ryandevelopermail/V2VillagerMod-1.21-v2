package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import dev.sterner.guardvillagers.common.villager.FarmerBannerTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;

public class FarmerHarvestGoal extends Goal {
    private static final int HARVEST_RADIUS = 50;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final double MOVE_SPEED = 0.6D;
    private static final int CHECK_INTERVAL_TICKS = 20;
    private static final int TARGET_TIMEOUT_TICKS = 200;
    private static final Logger LOGGER = LoggerFactory.getLogger(FarmerHarvestGoal.class);

    private final VillagerEntity villager;
    private final Deque<BlockPos> harvestTargets = new ArrayDeque<>();

    private BlockPos jobPos;
    private BlockPos chestPos;
    private boolean enabled;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;
    private long lastHarvestDay = -1L;
    private boolean dailyHarvestRun;
    private FarmerCraftingGoal craftingGoal;
    private BlockPos currentTarget;
    private long currentTargetStartTick;
    private BlockPos bannerPos;
    private BlockPos gatePos;
    private BlockPos gateInteriorPos;
    private BlockPos penTargetPos;
    private int feedsRemaining;
    private AnimalEntity targetAnimal;
    private int feedCooldownTicks;
    private boolean feedingInside;

    public FarmerHarvestGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        setTargets(jobPos, chestPos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.enabled = true;
        this.stage = Stage.IDLE;
        this.harvestTargets.clear();
    }

    public void setCraftingGoal(FarmerCraftingGoal craftingGoal) {
        this.craftingGoal = craftingGoal;
    }

    @Override
    public boolean canStart() {
        if (!enabled || !villager.isAlive() || jobPos == null || chestPos == null) {
            return false;
        }
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (world.getTime() < nextCheckTime) {
            return false;
        }

        long day = world.getTimeOfDay() / 24000L;
        if (day != lastHarvestDay) {
            lastHarvestDay = day;
            dailyHarvestRun = true;
            nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
            return true;
        }

        int matureCount = countMatureCrops(world);
        nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
        return matureCount >= 1;
    }

    @Override
    public boolean shouldContinue() {
        return enabled && villager.isAlive() && stage != Stage.DONE;
    }

    @Override
    public void start() {
        villager.setCanPickUpLoot(true);
        setStage(Stage.GO_TO_JOB);
        populateHarvestTargets();
        moveTo(jobPos);
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        harvestTargets.clear();
        currentTarget = null;
        setStage(Stage.DONE);
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld serverWorld)) {
            setStage(Stage.DONE);
            return;
        }

        switch (stage) {
            case GO_TO_JOB -> {
                if (isNear(jobPos)) {
                    setStage(Stage.HARVEST);
                } else {
                    moveTo(jobPos);
                }
            }
            case HARVEST -> {
                if (harvestTargets.isEmpty()) {
                    if (prepareFeeding(serverWorld)) {
                        setStage(Stage.GO_TO_PEN);
                        moveTo(bannerPos);
                    } else {
                        setStage(Stage.RETURN_TO_CHEST);
                        moveTo(chestPos);
                    }
                    return;
                }

                BlockPos target = harvestTargets.peekFirst();
                if (currentTarget == null || !currentTarget.equals(target)) {
                    currentTarget = target;
                    currentTargetStartTick = serverWorld.getTime();
                }

                if (serverWorld.getTime() - currentTargetStartTick >= TARGET_TIMEOUT_TICKS) {
                    harvestTargets.removeFirst();
                    currentTarget = null;
                    return;
                }
                if (!isMatureCrop(serverWorld.getBlockState(target))) {
                    harvestTargets.removeFirst();
                    currentTarget = null;
                    return;
                }

                if (!isNear(target)) {
                    moveTo(target);
                    return;
                }

                BlockState harvestedState = serverWorld.getBlockState(target);
                serverWorld.breakBlock(target, true, villager);
                attemptReplant(serverWorld, target, harvestedState);
                collectNearbyDrops(serverWorld, target);
                harvestTargets.removeFirst();
                currentTarget = null;
            }
            case RETURN_TO_CHEST -> {
                if (isNear(chestPos)) {
                    setStage(Stage.DEPOSIT);
                } else {
                    moveTo(chestPos);
                }
            }
            case GO_TO_PEN -> {
                if (bannerPos == null) {
                    setStage(Stage.RETURN_TO_CHEST);
                    moveTo(chestPos);
                    return;
                }
                if (gatePos == null) {
                    setStage(Stage.RETURN_TO_CHEST);
                    moveTo(chestPos);
                    return;
                }
                if (isNear(gatePos)) {
                    openGate(serverWorld, gatePos, true);
                    setStage(Stage.ENTER_PEN);
                } else {
                    moveTo(gatePos);
                }
            }
            case ENTER_PEN -> {
                if (penTargetPos == null) {
                    setStage(Stage.CLOSE_GATE_INSIDE);
                    return;
                }
                if (isNear(penTargetPos)) {
                    setStage(Stage.CLOSE_GATE_INSIDE);
                } else {
                    moveTo(penTargetPos);
                }
            }
            case CLOSE_GATE_INSIDE -> {
                if (gatePos != null) {
                    openGate(serverWorld, gatePos, false);
                }
                feedingInside = true;
                setStage(Stage.FEED_ANIMALS);
            }
            case FEED_ANIMALS -> {
                if (feedCooldownTicks > 0) {
                    feedCooldownTicks--;
                    return;
                }
                if (!feedAnimals(serverWorld)) {
                    villager.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
                    feedingInside = false;
                    setStage(Stage.OPEN_GATE_EXIT);
                }
            }
            case OPEN_GATE_EXIT -> {
                if (gatePos == null) {
                    setStage(Stage.RETURN_TO_CHEST);
                    moveTo(chestPos);
                    return;
                }
                openGate(serverWorld, gatePos, true);
                setStage(Stage.EXIT_PEN);
                moveTo(gatePos);
            }
            case EXIT_PEN -> {
                if (gatePos == null) {
                    setStage(Stage.RETURN_TO_CHEST);
                    moveTo(chestPos);
                    return;
                }
                if (isNear(gatePos)) {
                    openGate(serverWorld, gatePos, false);
                    setStage(Stage.RETURN_TO_CHEST);
                    moveTo(chestPos);
                } else {
                    moveTo(gatePos);
                }
            }
            case DEPOSIT -> {
                if (!isNear(chestPos)) {
                    setStage(Stage.RETURN_TO_CHEST);
                    return;
                }

                depositInventory(serverWorld);
                if (dailyHarvestRun) {
                    notifyDailyHarvestComplete(serverWorld);
                    dailyHarvestRun = false;
                }
                setStage(Stage.DONE);
            }
            case IDLE, DONE -> {
            }
        }
    }

    private void populateHarvestTargets() {
        if (!(villager.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        harvestTargets.clear();
        int radius = HARVEST_RADIUS;
        int radiusSquared = radius * radius;
        BlockPos start = jobPos.add(-radius, -radius, -radius);
        BlockPos end = jobPos.add(radius, radius, radius);
        ArrayList<BlockPos> targets = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(start, end)) {
            if (pos.getSquaredDistance(jobPos) > radiusSquared) {
                continue;
            }
            BlockState state = serverWorld.getBlockState(pos);
            if (isMatureCrop(state)) {
                targets.add(pos.toImmutable());
            }
        }

        targets.sort(Comparator.comparingDouble(pos -> villager.squaredDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)));
        harvestTargets.addAll(targets);
    }

    private int countMatureCrops(ServerWorld world) {
        int count = 0;
        int radius = HARVEST_RADIUS;
        int radiusSquared = radius * radius;
        BlockPos start = jobPos.add(-radius, -radius, -radius);
        BlockPos end = jobPos.add(radius, radius, radius);
        for (BlockPos pos : BlockPos.iterate(start, end)) {
            if (pos.getSquaredDistance(jobPos) > radiusSquared) {
                continue;
            }
            if (isMatureCrop(world.getBlockState(pos))) {
                count++;
                if (count > 1) {
                    return count;
                }
            }
        }
        return count;
    }

    private boolean isMatureCrop(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) {
            return crop.isMature(state);
        }
        return false;
    }

    private boolean isNear(BlockPos target) {
        return villager.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private void moveTo(BlockPos target) {
        villager.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
    }

    private void notifyDailyHarvestComplete(ServerWorld world) {
        if (craftingGoal != null) {
            craftingGoal.notifyDailyHarvestComplete(world.getTimeOfDay() / 24000L);
        }
    }

    private void attemptReplant(ServerWorld world, BlockPos pos, BlockState harvestedState) {
        if (!(harvestedState.getBlock() instanceof CropBlock crop)) {
            return;
        }

        if (!world.getBlockState(pos).isAir()) {
            return;
        }

        Item seedItem = getSeedItem(crop);
        if (seedItem == null) {
            return;
        }

        Inventory inventory = villager.getInventory();
        if (!consumeSeed(inventory, seedItem) && !consumeSeedFromChest(world, seedItem)) {
            return;
        }

        BlockState replantedState = crop.getDefaultState();
        if (!replantedState.canPlaceAt(world, pos)) {
            return;
        }

        world.setBlockState(pos, replantedState);
    }

    private void collectNearbyDrops(ServerWorld world, BlockPos pos) {
        Box box = new Box(pos).expand(2.0D);
        for (ItemEntity itemEntity : world.getEntitiesByClass(ItemEntity.class, box, entity -> entity.isAlive() && !entity.getStack().isEmpty())) {
            pickupItemEntity(itemEntity);
        }
    }

    private void pickupItemEntity(ItemEntity itemEntity) {
        ItemStack remaining = insertStack(villager.getInventory(), itemEntity.getStack());
        if (remaining.isEmpty()) {
            itemEntity.discard();
        } else {
            itemEntity.setStack(remaining);
        }
    }

    private Item getSeedItem(CropBlock crop) {
        Block block = crop;
        if (block == Blocks.WHEAT) {
            return Items.WHEAT_SEEDS;
        }
        if (block == Blocks.CARROTS) {
            return Items.CARROT;
        }
        if (block == Blocks.POTATOES) {
            return Items.POTATO;
        }
        if (block == Blocks.BEETROOTS) {
            return Items.BEETROOT_SEEDS;
        }
        return null;
    }

    private boolean consumeSeed(Inventory inventory, Item seedItem) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || stack.getItem() != seedItem) {
                continue;
            }

            stack.decrement(1);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            inventory.markDirty();
            return true;
        }
        return false;
    }

    private boolean consumeSeedFromChest(ServerWorld world, Item seedItem) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return false;
        }
        Inventory chestInventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
        if (chestInventory == null) {
            return false;
        }
        return consumeSeed(chestInventory, seedItem);
    }

    private void depositInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return;
        }

        Inventory chestInventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
        if (chestInventory == null) {
            return;
        }

        Inventory villagerInventory = villager.getInventory();
        for (int i = 0; i < villagerInventory.size(); i++) {
            ItemStack stack = villagerInventory.getStack(i);
            if (stack.isEmpty()) {
                continue;
            }

            ItemStack remaining = insertStack(chestInventory, stack);
            villagerInventory.setStack(i, remaining);
        }

        villagerInventory.markDirty();
        chestInventory.markDirty();
    }

    private ItemStack insertStack(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (remaining.isEmpty()) {
                return ItemStack.EMPTY;
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

        return remaining;
    }

    private void setStage(Stage newStage) {
        if (stage == newStage) {
            return;
        }
        stage = newStage;
        LOGGER.info("Farmer {} entering harvest stage {}", villager.getUuidAsString(), newStage);
    }

    private enum Stage {
        IDLE,
        GO_TO_JOB,
        HARVEST,
        GO_TO_PEN,
        ENTER_PEN,
        CLOSE_GATE_INSIDE,
        FEED_ANIMALS,
        OPEN_GATE_EXIT,
        EXIT_PEN,
        RETURN_TO_CHEST,
        DEPOSIT,
        DONE
    }

    private boolean prepareFeeding(ServerWorld world) {
        bannerPos = FarmerBannerTracker.getBanner(villager).orElse(null);
        if (bannerPos == null) {
            return false;
        }
        if (villager.getInventory().isEmpty()) {
            return false;
        }
        gatePos = findNearestGate(world, bannerPos);
        if (gatePos == null) {
            return false;
        }
        gateInteriorPos = findGateInterior(world, gatePos, bannerPos);
        penTargetPos = gateInteriorPos != null ? gateInteriorPos : bannerPos;
        feedsRemaining = 1 + villager.getRandom().nextInt(5);
        targetAnimal = null;
        feedCooldownTicks = 0;
        feedingInside = false;
        return !getAnimalsNearBanner(world).isEmpty();
    }

    private boolean feedAnimals(ServerWorld world) {
        if (feedsRemaining <= 0) {
            return false;
        }
        if (bannerPos == null) {
            return false;
        }

        if (!feedingInside) {
            return false;
        }

        if (targetAnimal == null || !targetAnimal.isAlive()) {
            targetAnimal = findNearestAnimal(world);
            if (targetAnimal == null) {
                return false;
            }
        }

        ItemStack feedStack = findBreedingStack(villager.getInventory(), targetAnimal);
        if (feedStack == null) {
            return false;
        }

        villager.setStackInHand(Hand.MAIN_HAND, new ItemStack(feedStack.getItem()));

        if (!isNear(targetAnimal.getBlockPos())) {
            moveTo(targetAnimal.getBlockPos());
            return true;
        }

        if (!consumeFeedItems(villager.getInventory(), feedStack.getItem())) {
            return false;
        }

        applyBreedingState(targetAnimal);
        LOGGER.info("Farmer {} fed {} x2 at {}", villager.getUuidAsString(), feedStack.getName().getString(), targetAnimal.getBlockPos().toShortString());
        if (targetAnimal.isInLove()) {
            LOGGER.info("Animal {} entered breeding state at {}", targetAnimal.getType().getName().getString(), targetAnimal.getBlockPos().toShortString());
        }
        feedsRemaining--;
        targetAnimal = null;
        feedCooldownTicks = 20;
        return feedsRemaining > 0;
    }

    private List<AnimalEntity> getAnimalsNearBanner(ServerWorld world) {
        Box box = new Box(bannerPos).expand(6.0D);
        return world.getEntitiesByClass(AnimalEntity.class, box, animal -> animal.isAlive() && !animal.isBaby());
    }

    private AnimalEntity findNearestAnimal(ServerWorld world) {
        List<AnimalEntity> animals = getAnimalsNearBanner(world);
        return animals.stream()
                .min(Comparator.comparingDouble(animal -> animal.squaredDistanceTo(bannerPos.getX() + 0.5D, bannerPos.getY() + 0.5D, bannerPos.getZ() + 0.5D)))
                .orElse(null);
    }

    private ItemStack findBreedingStack(Inventory inventory, AnimalEntity animal) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.getCount() < 2) {
                continue;
            }
            if (animal.isBreedingItem(stack)) {
                return stack;
            }
        }
        return null;
    }

    private boolean consumeFeedItems(Inventory inventory, Item item) {
        int remaining = 2;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || stack.getItem() != item) {
                continue;
            }
            int removed = Math.min(remaining, stack.getCount());
            stack.decrement(removed);
            remaining -= removed;
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            if (remaining <= 0) {
                inventory.markDirty();
                return true;
            }
        }
        return false;
    }

    private void applyBreedingState(AnimalEntity animal) {
        animal.setLoveTicks(600);
    }

    private BlockPos findGateInterior(ServerWorld world, BlockPos gatePos, BlockPos bannerPos) {
        BlockState state = world.getBlockState(gatePos);
        if (state.contains(FenceGateBlock.FACING)) {
            Direction facing = state.get(FenceGateBlock.FACING);
            BlockPos frontPos = gatePos.offset(facing);
            BlockPos backPos = gatePos.offset(facing.getOpposite());
            double frontDistance = squaredDistance(frontPos, bannerPos);
            double backDistance = squaredDistance(backPos, bannerPos);
            return frontDistance <= backDistance ? frontPos : backPos;
        }
        return bannerPos;
    }

    private double squaredDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() + 0.5D - (b.getX() + 0.5D);
        double dy = a.getY() + 0.5D - (b.getY() + 0.5D);
        double dz = a.getZ() + 0.5D - (b.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    private BlockPos findNearestGate(ServerWorld world, BlockPos center) {
        int gateRange = 6;
        BlockPos start = center.add(-gateRange, -gateRange, -gateRange);
        BlockPos end = center.add(gateRange, gateRange, gateRange);
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(start, end)) {
            BlockState state = world.getBlockState(pos);
            if (!(state.getBlock() instanceof FenceGateBlock)) {
                continue;
            }
            double distance = villager.squaredDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = pos.toImmutable();
            }
        }
        return nearest;
    }

    private void openGate(ServerWorld world, BlockPos pos, boolean open) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof FenceGateBlock gateBlock)) {
            return;
        }
        if (state.get(FenceGateBlock.OPEN) == open) {
            return;
        }
        world.setBlockState(pos, state.with(FenceGateBlock.OPEN, open), 2);
    }
}
