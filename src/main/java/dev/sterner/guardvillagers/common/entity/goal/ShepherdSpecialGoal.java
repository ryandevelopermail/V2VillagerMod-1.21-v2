package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.villager.FarmerBannerTracker;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Hand;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShepherdSpecialGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShepherdSpecialGoal.class);
    private static final int CHECK_INTERVAL_MIN_TICKS = 1200;
    private static final int CHECK_INTERVAL_VARIANCE_TICKS = 1200;
    private static final double MOVE_SPEED = 0.6D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int SHEEP_SCAN_RANGE = 100;
    private static final int PEN_SCAN_RANGE = 100;
    private static final int PEN_FENCE_RANGE = 16;
    private static final double FARMER_BANNER_PAIR_RANGE = 500.0D;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;
    private long lastShearDay = -1L;
    private TaskType taskType;
    private List<SheepEntity> sheepTargets = new ArrayList<>();
    private int sheepTargetIndex;
    private BlockPos penTarget;
    private ItemStack carriedItem = ItemStack.EMPTY;

    public ShepherdSpecialGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        setTargets(jobPos, chestPos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.stage = Stage.IDLE;
    }

    public void requestImmediateCheck() {
        nextCheckTime = 0L;
    }

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (jobPos == null || chestPos == null) {
            return false;
        }
        if (!world.isDay()) {
            return false;
        }
        long day = world.getTimeOfDay() / 24000L;
        if (day != lastShearDay) {
            lastShearDay = day;
            nextCheckTime = 0L;
        }

        if (world.getTime() < nextCheckTime) {
            return false;
        }

        TaskType nextTask = findTaskType(world);
        if (nextTask == null) {
            nextCheckTime = world.getTime() + nextRandomCheckInterval();
            return false;
        }

        taskType = nextTask;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.DONE && villager.isAlive();
    }

    @Override
    public void start() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        carriedItem = takeItemFromChest(world, taskType);
        if (carriedItem.isEmpty()) {
            nextCheckTime = world.getTime() + nextRandomCheckInterval();
            stage = Stage.DONE;
            return;
        }

        if (taskType == TaskType.SHEARS) {
            sheepTargets = findSheepTargets(world);
            sheepTargetIndex = 0;
            equipShearsFromInventory();
            if (sheepTargets.isEmpty()) {
                stage = Stage.RETURN_TO_CHEST;
                moveTo(chestPos);
                return;
            }
            stage = Stage.GO_TO_SHEEP;
            moveTo(sheepTargets.get(sheepTargetIndex).getBlockPos());
            return;
        }

        penTarget = findNearestPenTarget(world);
        if (penTarget == null) {
            stage = Stage.RETURN_TO_CHEST;
            moveTo(chestPos);
            return;
        }
        stage = Stage.GO_TO_PEN;
        moveTo(penTarget);
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        stage = Stage.DONE;
        taskType = null;
        sheepTargets = new ArrayList<>();
        sheepTargetIndex = 0;
        penTarget = null;
        carriedItem = ItemStack.EMPTY;
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case GO_TO_SHEEP -> {
                SheepEntity targetEntity = getSheepTarget();
                if (targetEntity == null) {
                    stage = Stage.RETURN_TO_CHEST;
                    moveTo(chestPos);
                    return;
                }

                if (isNear(targetEntity.getBlockPos())) {
                    shearSheep(world, targetEntity);
                    sheepTargetIndex++;
                    SheepEntity nextTarget = getSheepTarget();
                    if (nextTarget != null) {
                        moveTo(nextTarget.getBlockPos());
                    } else {
                        stage = Stage.RETURN_TO_CHEST;
                        moveTo(chestPos);
                    }
                } else {
                    moveTo(targetEntity.getBlockPos());
                }
            }
            case GO_TO_PEN -> {
                if (penTarget == null) {
                    stage = Stage.RETURN_TO_CHEST;
                    moveTo(chestPos);
                    return;
                }

                if (isNear(penTarget)) {
                    triggerBannerPairing(world, penTarget);
                    stage = Stage.RETURN_TO_CHEST;
                    moveTo(chestPos);
                } else {
                    moveTo(penTarget);
                }
            }
            case RETURN_TO_CHEST -> {
                if (isNear(chestPos)) {
                    depositSpecialItems(world);
                    nextCheckTime = world.getTime() + nextRandomCheckInterval();
                    stage = Stage.DONE;
                } else {
                    moveTo(chestPos);
                }
            }
            case IDLE, DONE -> {
            }
        }
    }

    private TaskType findTaskType(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return null;
        }

        if (hasMatchingItem(inventory, stack -> stack.isOf(Items.SHEARS))) {
            return TaskType.SHEARS;
        }

        if (hasMatchingItem(inventory, stack -> stack.isIn(ItemTags.BANNERS))) {
            return TaskType.BANNER;
        }

        return null;
    }

    private boolean hasMatchingItem(Inventory inventory, Predicate<ItemStack> matcher) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && matcher.test(stack)) {
                return true;
            }
        }
        return false;
    }

    private ItemStack takeItemFromChest(ServerWorld world, TaskType taskType) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return ItemStack.EMPTY;
        }

        boolean wantsShears = taskType == TaskType.SHEARS;
        Predicate<ItemStack> matcher = wantsShears
                ? stack -> stack.isOf(Items.SHEARS)
                : stack -> stack.isIn(ItemTags.BANNERS);

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !matcher.test(stack)) {
                continue;
            }

            ItemStack extracted = stack.split(1);
            if (wantsShears && villager.getMainHandStack().isEmpty()) {
                villager.setStackInHand(Hand.MAIN_HAND, extracted);
                inventory.setStack(slot, stack);
                inventory.markDirty();
                return extracted;
            }

            ItemStack remaining = insertStack(villager.getInventory(), extracted);
            if (!remaining.isEmpty()) {
                stack.increment(remaining.getCount());
                inventory.setStack(slot, stack);
                return ItemStack.EMPTY;
            }

            inventory.setStack(slot, stack);
            inventory.markDirty();
            villager.getInventory().markDirty();
            return extracted;
        }

        return ItemStack.EMPTY;
    }

    private void depositSpecialItems(ServerWorld world) {
        Inventory chestInventory = getChestInventory(world).orElse(null);
        if (chestInventory == null) {
            return;
        }

        Inventory villagerInventory = villager.getInventory();
        for (int slot = 0; slot < villagerInventory.size(); slot++) {
            ItemStack stack = villagerInventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (!(stack.isOf(Items.SHEARS) || stack.isIn(ItemTags.BANNERS))) {
                continue;
            }

            ItemStack remaining = insertStack(chestInventory, stack);
            villagerInventory.setStack(slot, remaining);
        }

        ItemStack mainHand = villager.getMainHandStack();
        if (mainHand.isOf(Items.SHEARS) || mainHand.isIn(ItemTags.BANNERS)) {
            ItemStack remaining = insertStack(chestInventory, mainHand);
            villager.setStackInHand(Hand.MAIN_HAND, remaining);
        }

        villagerInventory.markDirty();
        chestInventory.markDirty();
    }

    private List<SheepEntity> findSheepTargets(ServerWorld world) {
        List<SheepEntity> sheep = world.getEntitiesByClass(SheepEntity.class, new Box(villager.getBlockPos()).expand(SHEEP_SCAN_RANGE), SheepEntity::isAlive);
        sheep.sort(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(villager)));
        return new ArrayList<>(sheep);
    }

    private SheepEntity getSheepTarget() {
        while (sheepTargetIndex < sheepTargets.size()) {
            SheepEntity target = sheepTargets.get(sheepTargetIndex);
            if (target != null && target.isAlive()) {
                return target;
            }
            sheepTargetIndex++;
        }
        return null;
    }

    private void equipShearsFromInventory() {
        if (villager.getMainHandStack().isOf(Items.SHEARS)) {
            return;
        }
        Inventory inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !stack.isOf(Items.SHEARS)) {
                continue;
            }
            villager.setStackInHand(Hand.MAIN_HAND, stack);
            inventory.setStack(slot, ItemStack.EMPTY);
            inventory.markDirty();
            return;
        }
    }

    private void shearSheep(ServerWorld world, SheepEntity sheep) {
        LOGGER.info("Shepherd {} attempting to shear sheep {} at {} (alive={}, shearable={}, sheared={})",
                villager.getUuidAsString(),
                sheep.getUuidAsString(),
                sheep.getBlockPos().toShortString(),
                sheep.isAlive(),
                sheep.isShearable(),
                sheep.isSheared());
        if (!sheep.isAlive() || !sheep.isShearable()) {
            return;
        }

        sheep.setSheared(true);
        sheep.playSound(SoundEvents.ENTITY_SHEEP_SHEAR, 1.0F, 1.0F);
        int dropCount = 1 + sheep.getRandom().nextInt(3);
        ItemStack woolStack = new ItemStack(woolFromColor(sheep.getColor()), dropCount);
        ItemStack remaining = insertStack(villager.getInventory(), woolStack);
        if (!remaining.isEmpty()) {
            sheep.dropStack(remaining);
        }
        villager.getInventory().markDirty();
        LOGGER.info("Shepherd {} sheared sheep {} at {} (now sheared={})",
                villager.getUuidAsString(),
                sheep.getUuidAsString(),
                sheep.getBlockPos().toShortString(),
                sheep.isSheared());
        collectNearbyWool(world, sheep.getBlockPos());
    }

    private void collectNearbyWool(ServerWorld world, BlockPos center) {
        Box box = new Box(center).expand(3.0D);
        List<ItemEntity> items = world.getEntitiesByClass(ItemEntity.class, box, entity -> entity.isAlive() && entity.getStack().isIn(ItemTags.WOOL));
        for (ItemEntity itemEntity : items) {
            ItemStack remaining = insertStack(villager.getInventory(), itemEntity.getStack());
            if (remaining.isEmpty()) {
                itemEntity.discard();
            } else {
                itemEntity.setStack(remaining);
            }
        }
        villager.getInventory().markDirty();
    }

    private long nextRandomCheckInterval() {
        return CHECK_INTERVAL_MIN_TICKS + villager.getRandom().nextInt(CHECK_INTERVAL_VARIANCE_TICKS + 1);
    }

    private Item woolFromColor(DyeColor color) {
        return switch (color) {
            case ORANGE -> Items.ORANGE_WOOL;
            case MAGENTA -> Items.MAGENTA_WOOL;
            case LIGHT_BLUE -> Items.LIGHT_BLUE_WOOL;
            case YELLOW -> Items.YELLOW_WOOL;
            case LIME -> Items.LIME_WOOL;
            case PINK -> Items.PINK_WOOL;
            case GRAY -> Items.GRAY_WOOL;
            case LIGHT_GRAY -> Items.LIGHT_GRAY_WOOL;
            case CYAN -> Items.CYAN_WOOL;
            case PURPLE -> Items.PURPLE_WOOL;
            case BLUE -> Items.BLUE_WOOL;
            case BROWN -> Items.BROWN_WOOL;
            case GREEN -> Items.GREEN_WOOL;
            case RED -> Items.RED_WOOL;
            case BLACK -> Items.BLACK_WOOL;
            default -> Items.WHITE_WOOL;
        };
    }

    private BlockPos findNearestPenTarget(ServerWorld world) {
        BlockPos start = villager.getBlockPos().add(-PEN_SCAN_RANGE, -PEN_SCAN_RANGE, -PEN_SCAN_RANGE);
        BlockPos end = villager.getBlockPos().add(PEN_SCAN_RANGE, PEN_SCAN_RANGE, PEN_SCAN_RANGE);
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.iterate(start, end)) {
            BlockState state = world.getBlockState(pos);
            if (!(state.getBlock() instanceof FenceGateBlock)) {
                continue;
            }

            BlockPos insidePos = getPenInterior(world, pos, state);
            if (insidePos == null) {
                continue;
            }

            if (!isInsideFencePen(world, insidePos)) {
                continue;
            }

            if (hasBannerInPen(world, insidePos)) {
                continue;
            }

            double distance = villager.squaredDistanceTo(insidePos.getX() + 0.5D, insidePos.getY() + 0.5D, insidePos.getZ() + 0.5D);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = insidePos.toImmutable();
            }
        }

        return nearest;
    }

    private BlockPos getPenInterior(ServerWorld world, BlockPos gatePos, BlockState state) {
        if (state.contains(FenceGateBlock.FACING)) {
            Direction facing = state.get(FenceGateBlock.FACING);
            BlockPos front = gatePos.offset(facing);
            BlockPos back = gatePos.offset(facing.getOpposite());
            if (isInsideFencePen(world, front)) {
                return front;
            }
            if (isInsideFencePen(world, back)) {
                return back;
            }
        }
        return null;
    }

    private boolean hasBannerInPen(ServerWorld world, BlockPos penPos) {
        BlockPos start = penPos.add(-PEN_FENCE_RANGE, -PEN_FENCE_RANGE, -PEN_FENCE_RANGE);
        BlockPos end = penPos.add(PEN_FENCE_RANGE, PEN_FENCE_RANGE, PEN_FENCE_RANGE);
        for (BlockPos pos : BlockPos.iterate(start, end)) {
            if (!penPos.isWithinDistance(pos, PEN_FENCE_RANGE)) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (!state.isIn(BlockTags.BANNERS)) {
                continue;
            }
            if (isInsideFencePen(world, pos)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInsideFencePen(ServerWorld world, BlockPos pos) {
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            if (!hasFenceInDirection(world, pos, direction, PEN_FENCE_RANGE)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasFenceInDirection(ServerWorld world, BlockPos start, Direction direction, int maxDistance) {
        for (int i = 1; i <= maxDistance; i++) {
            BlockPos pos = start.offset(direction, i);
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof FenceBlock || state.getBlock() instanceof FenceGateBlock) {
                return true;
            }
        }
        return false;
    }

    private void triggerBannerPairing(ServerWorld world, BlockPos bannerPos) {
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, new Box(bannerPos).expand(FARMER_BANNER_PAIR_RANGE), villager -> villager.isAlive() && villager.getVillagerData().getProfession() == VillagerProfession.FARMER)) {
            Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
            if (jobSite.isEmpty()) {
                continue;
            }

            GlobalPos globalPos = jobSite.get();
            if (!globalPos.dimension().equals(world.getRegistryKey())) {
                continue;
            }

            BlockPos farmerJobPos = globalPos.pos();
            if (!world.getBlockState(farmerJobPos).isOf(Blocks.COMPOSTER)) {
                continue;
            }

            if (JobBlockPairingHelper.findNearbyChest(world, farmerJobPos).isEmpty()) {
                continue;
            }

            JobBlockPairingHelper.playPairingAnimation(world, bannerPos, villager, farmerJobPos);
            FarmerBannerTracker.setBanner(villager, bannerPos);
        }
    }

    private Optional<Inventory> getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        Inventory inventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
        return Optional.ofNullable(inventory);
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

    private void moveTo(BlockPos target) {
        villager.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
    }

    private boolean isNear(BlockPos target) {
        return villager.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private enum Stage {
        IDLE,
        GO_TO_SHEEP,
        GO_TO_PEN,
        RETURN_TO_CHEST,
        DONE
    }

    private enum TaskType {
        SHEARS,
        BANNER
    }
}
