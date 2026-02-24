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
import net.minecraft.item.BlockItem;
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
    private static final int SHEAR_CHECK_INTERVAL_TICKS = 1200;
    private static final int SHEAR_CHECK_INTERVAL_VARIANCE_TICKS = 200;
    private static final int SHEEP_SENSOR_INTERVAL_TICKS = 80;
    private static final double MOVE_SPEED = 0.6D;
    private static final double SLOW_GUIDE_SPEED = 0.45D;
    private static final double FAST_GATE_CLOSE_SPEED = 0.9D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int SHEEP_SCAN_RANGE = 50;
    private static final int PEN_SCAN_RANGE = 100;
    private static final int PEN_FENCE_RANGE = 16;
    private static final double FARMER_BANNER_PAIR_RANGE = 500.0D;
    private static final double GATE_INTERACT_RANGE_SQUARED = 9.0D;
    private static final int GATHER_RADIUS = 50;
    private static final int GATHER_MIN_SESSION_TICKS = 1200;
    private static final int GATHER_MAX_SESSION_TICKS = 2200;
    private static final int GATHER_WANDER_REPATH_TICKS = 120;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;
    private long nextSheepSensorCheckTime;
    private long nextChestShearTriggerTime;
    private long shearCountdownTotalTicks;
    private long shearCountdownStartTime;
    private int lastShearsInChestCount;
    private int lastShearCountdownLogStep;
    private boolean shearCountdownActive;
    private long lastShearDay = -1L;
    private boolean hadShearsInChest;
    private int lastBannersInChestCount;
    private TaskType taskType;
    private List<SheepEntity> sheepTargets = new ArrayList<>();
    private int sheepTargetIndex;
    private BlockPos penTarget;
    private BlockPos penGatePos;
    private ItemStack carriedItem = ItemStack.EMPTY;
    private boolean openedPenGate;
    private boolean wasInsidePen;
    private BlockPos gatherBannerPos;
    private long gatherSessionStartTick;
    private long gatherSessionDurationTicks;
    private long nextGatherRepathTick;
    private boolean gatherHalfLogged;

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
        updateShearsCountdown(world);
        if (jobPos == null || chestPos == null) {
            return false;
        }
        long day = world.getTimeOfDay() / 24000L;
        boolean dayChanged = day != lastShearDay;
        if (dayChanged) {
            lastShearDay = day;
            hadShearsInChest = false;
            nextChestShearTriggerTime = 0L;
            shearCountdownTotalTicks = 0L;
            shearCountdownStartTime = 0L;
            lastShearsInChestCount = 0;
            lastBannersInChestCount = 0;
            lastShearCountdownLogStep = 0;
            shearCountdownActive = false;
        }

        TaskType nextTask = findTaskType(world);
        if (nextTask == null) {
            hadShearsInChest = false;
            nextCheckTime = world.getTime() + nextRandomCheckInterval();
            return false;
        }

        if (nextTask != TaskType.SHEARS && !world.isDay()) {
            return false;
        }

        if (dayChanged && nextTask == TaskType.SHEARS && nextCheckTime == 0L) {
            nextCheckTime = world.getTime();
        }

        int shearsInChestCount = countShearsInChest(world);
        boolean hasShearsInChest = shearsInChestCount > 0;
        boolean shearsAddedToChest = shearsInChestCount > lastShearsInChestCount;

        if (nextTask == TaskType.SHEARS && hasShearsInChest) {
            lastShearsInChestCount = countShearsInChest(world);
        } else if (!hasShearsInChest) {
            lastShearsInChestCount = 0;
        }

        if (nextTask == TaskType.SHEARS && hasShearsInChest && !hadShearsInChest) {
            hadShearsInChest = true;
            if (nextChestShearTriggerTime == 0L) {
                nextChestShearTriggerTime = world.getTime() + nextRandomCheckInterval();
            }
            nextCheckTime = 0L;
        } else if (nextTask == TaskType.BANNER) {
            int bannersInChestCount = countBannersInChest(world);
            boolean bannersAddedToChest = bannersInChestCount > lastBannersInChestCount;
            if (bannersAddedToChest) {
                nextCheckTime = 0L;
            }
            lastBannersInChestCount = bannersInChestCount;
        }

        if (nextTask == TaskType.SHEARS && nextCheckTime == 0L && hasShearsInInventoryOrHand()) {
            nextCheckTime = world.getTime();
        }

        if (world.getTime() >= nextSheepSensorCheckTime && (stage == Stage.IDLE || stage == Stage.DONE)) {
            nextSheepSensorCheckTime = world.getTime() + SHEEP_SENSOR_INTERVAL_TICKS;
        if (nextCheckTime > world.getTime()
                && hasShearableSheepNearby(world)
                && (hasShearsInInventoryOrHand() || hasShearsInChest)) {
            nextCheckTime = 0L;
        }
        }

        if (world.getTime() < nextCheckTime) {
            return false;
        }

        if (nextTask == TaskType.SHEARS) {
            int sheepCount = countSheepNearby(world);
            boolean needsShearsFromChest = !hasShearsInInventoryOrHand() && hasShearsInChest;
            if (sheepCount < 1 && !needsShearsFromChest) {
                nextCheckTime = world.getTime() + nextRandomCheckInterval();
                return false;
            }
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

        if (taskType == TaskType.SHEARS) {
            if (hasShearsInInventoryOrHand() || hasShearsInChest(world)) {
                carriedItem = ItemStack.EMPTY;
            } else {
                nextCheckTime = world.getTime() + nextRandomCheckInterval();
                stage = Stage.DONE;
                return;
            }
        } else if (taskType == TaskType.BANNER) {
            if (hasBannerInInventoryOrHand()) {
                carriedItem = getBannerInInventoryOrHand();
            } else {
                carriedItem = takeItemFromChest(world, taskType);
            }
            if (carriedItem.isEmpty() && !hasBannerInInventoryOrHand()) {
                nextCheckTime = world.getTime() + nextRandomCheckInterval();
                stage = Stage.DONE;
                return;
            }
        } else {
            if (!equipWheatForGathering(world)) {
                nextCheckTime = world.getTime() + nextRandomCheckInterval();
                stage = Stage.DONE;
                return;
            }
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

        if (taskType == TaskType.BANNER) {
            penTarget = findNearestPenTarget(world);
            if (penTarget == null) {
                nextCheckTime = world.getTime() + nextRandomCheckInterval();
                stage = Stage.DONE;
                return;
            }
            stage = Stage.GO_TO_PEN;
            moveTo(penTarget);
            return;
        }

        gatherBannerPos = findNearestGroundBanner(world);
        if (gatherBannerPos == null) {
            stage = Stage.DONE;
            nextCheckTime = world.getTime() + nextRandomCheckInterval();
            return;
        }

        gatherSessionStartTick = world.getTime();
        gatherSessionDurationTicks = randomGatherSessionDuration();
        gatherHalfLogged = false;
        nextGatherRepathTick = 0L;
        LOGGER.info("Shepherd {} started wheat gather session near banner {} for {} ticks", villager.getUuidAsString(), gatherBannerPos.toShortString(), gatherSessionDurationTicks);
        stage = Stage.GATHER_WANDER;
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        stage = Stage.DONE;
        taskType = null;
        sheepTargets = new ArrayList<>();
        sheepTargetIndex = 0;
        penTarget = null;
        penGatePos = null;
        carriedItem = ItemStack.EMPTY;
        openedPenGate = false;
        wasInsidePen = false;
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        updateShearsCountdown(world);

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
                        scheduleNextShearCheck(world, "after shearing run");
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

                updatePenGateAccess(world, penGatePos);

                if (isNear(penTarget)) {
                    BlockPos placedBannerPos = placeBannerInPen(world, penTarget);
                    if (placedBannerPos != null) {
                        triggerBannerPairing(world, placedBannerPos);
                    }
                    stage = Stage.RETURN_TO_CHEST;
                    moveTo(chestPos);
                } else {
                    moveTo(penTarget);
                }
            }
            case GATHER_WANDER -> {
                if (gatherBannerPos == null || !villager.getOffHandStack().isOf(Items.WHEAT)) {
                    stage = Stage.DONE;
                    return;
                }

                long elapsed = world.getTime() - gatherSessionStartTick;
                if (!gatherHalfLogged && elapsed >= gatherSessionDurationTicks / 2L) {
                    gatherHalfLogged = true;
                    LOGGER.info("Shepherd {} wheat gather session 50% complete", villager.getUuidAsString());
                }

                if (elapsed >= gatherSessionDurationTicks) {
                    PenTarget penWithBanner = findNearestPenWithBanner(world, gatherBannerPos);
                    if (penWithBanner == null) {
                        stage = Stage.DONE;
                        nextCheckTime = world.getTime() + nextRandomCheckInterval();
                        return;
                    }
                    penTarget = penWithBanner.center();
                    penGatePos = penWithBanner.gate();
                    stage = Stage.GUIDE_TO_PEN_CENTER;
                    return;
                }

                if (world.getTime() >= nextGatherRepathTick || villager.getNavigation().isIdle()) {
                    moveTo(randomGatherWanderTarget(world, gatherBannerPos), SLOW_GUIDE_SPEED);
                    nextGatherRepathTick = world.getTime() + GATHER_WANDER_REPATH_TICKS;
                }
            }
            case GUIDE_TO_PEN_CENTER -> {
                if (penTarget == null || penGatePos == null) {
                    stage = Stage.DONE;
                    return;
                }

                ensureGateOpen(world, penGatePos);
                if (isNear(penTarget)) {
                    villager.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);
                    stage = Stage.RUSH_TO_GATE_AND_CLOSE;
                } else {
                    moveTo(penTarget, SLOW_GUIDE_SPEED);
                }
            }
            case RUSH_TO_GATE_AND_CLOSE -> {
                if (penGatePos == null) {
                    stage = Stage.DONE;
                    return;
                }

                if (isNear(penGatePos)) {
                    openGate(world, penGatePos, false);
                    LOGGER.info("Shepherd {} closed pen gate at {} after wheat gather", villager.getUuidAsString(), penGatePos.toShortString());
                    nextCheckTime = world.getTime() + nextRandomCheckInterval();
                    stage = Stage.DONE;
                } else {
                    moveTo(penGatePos, FAST_GATE_CLOSE_SPEED);
                }
            }
            case RETURN_TO_CHEST -> {
                updatePenGateAccess(world, penGatePos);
                if (isNear(chestPos)) {
                    depositSpecialItems(world);
                    if (taskType != TaskType.SHEARS) {
                        nextCheckTime = world.getTime() + nextRandomCheckInterval();
                    }
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
            if (hasShearsInInventoryOrHand()) {
                return TaskType.SHEARS;
            }
            if (hasWheatInInventoryOrOffhand() && hasGroundBannerNearby(world)) {
                return TaskType.WHEAT_GATHER;
            }
            return hasBannerInInventoryOrHand() ? TaskType.BANNER : null;
        }

        if (hasShearsInChestOrInventory(inventory)) {
            return TaskType.SHEARS;
        }

        if ((hasMatchingItem(inventory, stack -> stack.isOf(Items.WHEAT)) || hasWheatInInventoryOrOffhand())
                && hasGroundBannerNearby(world)) {
            return TaskType.WHEAT_GATHER;
        }

        if (hasBannerInInventoryOrHand() || hasMatchingItem(inventory, stack -> stack.isIn(ItemTags.BANNERS))) {
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

    private boolean hasShearsInChestOrInventory(Inventory chestInventory) {
        return hasMatchingItem(chestInventory, stack -> stack.isOf(Items.SHEARS))
                || hasShearsInInventoryOrHand();
    }

    private boolean hasShearsInInventoryOrHand() {
        Inventory villagerInventory = villager.getInventory();
        return hasMatchingItem(villagerInventory, stack -> stack.isOf(Items.SHEARS))
                || villager.getMainHandStack().isOf(Items.SHEARS);
    }

    private boolean hasBannerInInventoryOrHand() {
        Inventory villagerInventory = villager.getInventory();
        return hasMatchingItem(villagerInventory, stack -> stack.isIn(ItemTags.BANNERS))
                || villager.getMainHandStack().isIn(ItemTags.BANNERS);
    }

    private boolean hasWheatInInventoryOrOffhand() {
        Inventory villagerInventory = villager.getInventory();
        return hasMatchingItem(villagerInventory, stack -> stack.isOf(Items.WHEAT))
                || villager.getMainHandStack().isOf(Items.WHEAT)
                || villager.getOffHandStack().isOf(Items.WHEAT);
    }

    private ItemStack getBannerInInventoryOrHand() {
        ItemStack mainHand = villager.getMainHandStack();
        if (mainHand.isIn(ItemTags.BANNERS)) {
            return mainHand.copy();
        }
        Inventory villagerInventory = villager.getInventory();
        for (int slot = 0; slot < villagerInventory.size(); slot++) {
            ItemStack stack = villagerInventory.getStack(slot);
            if (!stack.isEmpty() && stack.isIn(ItemTags.BANNERS)) {
                return stack.copy();
            }
        }
        return ItemStack.EMPTY;
    }

    private boolean hasShearsInChest(ServerWorld world) {
        Inventory chestInventory = getChestInventory(world).orElse(null);
        if (chestInventory == null) {
            return false;
        }
        return hasMatchingItem(chestInventory, stack -> stack.isOf(Items.SHEARS));
    }

    private int countBannersInChest(ServerWorld world) {
        Inventory chestInventory = getChestInventory(world).orElse(null);
        if (chestInventory == null) {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < chestInventory.size(); slot++) {
            ItemStack stack = chestInventory.getStack(slot);
            if (!stack.isEmpty() && stack.isIn(ItemTags.BANNERS)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countShearsInChest(ServerWorld world) {
        Inventory chestInventory = getChestInventory(world).orElse(null);
        if (chestInventory == null) {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < chestInventory.size(); slot++) {
            ItemStack stack = chestInventory.getStack(slot);
            if (!stack.isEmpty() && stack.isOf(Items.SHEARS)) {
                count += stack.getCount();
            }
        }
        return count;
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

    private boolean equipWheatForGathering(ServerWorld world) {
        if (villager.getOffHandStack().isOf(Items.WHEAT)) {
            return true;
        }

        Inventory inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !stack.isOf(Items.WHEAT)) {
                continue;
            }
            ItemStack extracted = stack.split(1);
            villager.setStackInHand(Hand.OFF_HAND, extracted);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            inventory.markDirty();
            return true;
        }

        Inventory chestInventory = getChestInventory(world).orElse(null);
        if (chestInventory == null) {
            return false;
        }
        for (int slot = 0; slot < chestInventory.size(); slot++) {
            ItemStack stack = chestInventory.getStack(slot);
            if (stack.isEmpty() || !stack.isOf(Items.WHEAT)) {
                continue;
            }
            ItemStack extracted = stack.split(1);
            villager.setStackInHand(Hand.OFF_HAND, extracted);
            chestInventory.setStack(slot, stack);
            chestInventory.markDirty();
            return true;
        }
        return false;
    }

    private int countSheepNearby(ServerWorld world) {
        Box box = new Box(villager.getBlockPos()).expand(SHEEP_SCAN_RANGE);
        List<SheepEntity> sheep = world.getEntitiesByClass(SheepEntity.class, box, SheepEntity::isAlive);
        return sheep.size();
    }

    private boolean hasShearableSheepNearby(ServerWorld world) {
        Box box = new Box(villager.getBlockPos()).expand(SHEEP_SCAN_RANGE);
        return !world.getEntitiesByClass(SheepEntity.class, box, entity -> entity.isAlive() && entity.isShearable()).isEmpty();
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
            if (!(stack.isIn(ItemTags.BANNERS) || stack.isIn(ItemTags.WOOL) || stack.isOf(Items.WHEAT))) {
                continue;
            }

            ItemStack remaining = insertStack(chestInventory, stack);
            villagerInventory.setStack(slot, remaining);
        }

        ItemStack mainHand = villager.getMainHandStack();
        if (mainHand.isIn(ItemTags.BANNERS) || mainHand.isIn(ItemTags.WOOL) || mainHand.isOf(Items.WHEAT)) {
            ItemStack remaining = insertStack(chestInventory, mainHand);
            villager.setStackInHand(Hand.MAIN_HAND, remaining);
        }

        ItemStack offHand = villager.getOffHandStack();
        if (offHand.isOf(Items.WHEAT)) {
            ItemStack remaining = insertStack(chestInventory, offHand);
            villager.setStackInHand(Hand.OFF_HAND, remaining);
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

    private void triggerShearsPlacedInChest(ServerWorld world) {
        Inventory chestInventory = getChestInventory(world).orElse(null);
        if (chestInventory == null) {
            return;
        }

        ItemStack shears = new ItemStack(Items.SHEARS);
        ItemStack remaining = insertStack(chestInventory, shears);
        if (remaining.isEmpty()) {
            chestInventory.markDirty();
            LOGGER.info("Shepherd {} inserted shears into chest at {}",
                    villager.getUuidAsString(),
                    chestPos.toShortString());
        } else {
            LOGGER.info("Shepherd {} failed to insert shears into chest at {} (no space)",
                    villager.getUuidAsString(),
                chestPos.toShortString());
        }
    }

    private void updateShearsCountdown(ServerWorld world) {
        if (chestPos == null) {
            return;
        }
        int shearsInChestCount = countShearsInChest(world);
        boolean hasShearsInChest = shearsInChestCount > 0;
        boolean shearsAddedToChest = shearsInChestCount > lastShearsInChestCount;
        boolean shouldContinueCountdown = hasShearsInChest || hasShearsInInventoryOrHand();

        if (shearsAddedToChest) {
            startShearCountdown(world, "shears added to chest");
            hadShearsInChest = false;
            shearCountdownActive = true;
        } else if (!shearCountdownActive && hasShearsInChest) {
            startShearCountdown(world, "shears detected in chest");
            shearCountdownActive = true;
        }

        if (shearCountdownActive && nextChestShearTriggerTime > 0L) {
            logShearCountdownProgress(world, nextChestShearTriggerTime);
        }

        if (shearCountdownActive && nextChestShearTriggerTime > 0L && world.getTime() >= nextChestShearTriggerTime) {
            triggerShearsPlacedInChest(world);
            hadShearsInChest = false;
            if (shouldContinueCountdown) {
                startShearCountdown(world, "shears insertion complete");
            } else {
                shearCountdownActive = false;
            }
        }

        if (!shouldContinueCountdown) {
            nextChestShearTriggerTime = 0L;
            shearCountdownTotalTicks = 0L;
            shearCountdownStartTime = 0L;
            lastShearCountdownLogStep = 0;
            shearCountdownActive = false;
        }

        lastShearsInChestCount = shearsInChestCount;
    }

    private void startShearCountdown(ServerWorld world, String reason) {
        shearCountdownTotalTicks = nextRandomCheckInterval();
        shearCountdownStartTime = world.getTime();
        nextChestShearTriggerTime = shearCountdownStartTime + shearCountdownTotalTicks;
        lastShearCountdownLogStep = 0;
        shearCountdownActive = true;
        LOGGER.info("Shepherd {} shears countdown started ({} ticks) {}",
                villager.getUuidAsString(),
                shearCountdownTotalTicks,
                reason);
    }

    private void logShearCountdownProgress(ServerWorld world, long triggerTime) {
        if (shearCountdownTotalTicks <= 0L) {
            return;
        }
        long remainingTicks = triggerTime - world.getTime();
        long elapsedTicks = world.getTime() - shearCountdownStartTime;
        int step = Math.min(4, (int) ((elapsedTicks * 4L) / shearCountdownTotalTicks));
        if (step <= lastShearCountdownLogStep || step == 0) {
            return;
        }
        lastShearCountdownLogStep = step;
        int percent = step * 25;
        LOGGER.info("Shepherd {} shears countdown {}% ({} ticks remaining)",
                villager.getUuidAsString(),
                percent,
                Math.max(remainingTicks, 0L));
    }

    private long nextRandomCheckInterval() {
        return SHEAR_CHECK_INTERVAL_TICKS + villager.getRandom().nextInt(SHEAR_CHECK_INTERVAL_VARIANCE_TICKS + 1);
    }

    private void scheduleNextShearCheck(ServerWorld world, String reason) {
        long interval = nextRandomCheckInterval();
        nextCheckTime = world.getTime() + interval;
        LOGGER.info("Shepherd {} scheduled next shearing session in {} ticks ({} min) {}",
                villager.getUuidAsString(),
                interval,
                String.format("%.2f", interval / 1200.0D),
                reason);
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
        BlockPos nearestGate = null;
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

            BlockPos penCenter = getPenCenter(world, insidePos);
            if (penCenter == null) {
                continue;
            }

            if (hasBannerInPen(world, penCenter)) {
                continue;
            }

            double distance = villager.squaredDistanceTo(penCenter.getX() + 0.5D, penCenter.getY() + 0.5D, penCenter.getZ() + 0.5D);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = penCenter.toImmutable();
                nearestGate = pos.toImmutable();
            }
        }

        penGatePos = nearestGate;
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
        BlockPos center = getPenCenter(world, penPos);
        if (center == null) {
            return false;
        }
        int scanRange = 2;
        BlockPos start = center.add(-scanRange, -1, -scanRange);
        BlockPos end = center.add(scanRange, 1, scanRange);
        for (BlockPos pos : BlockPos.iterate(start, end)) {
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

    private BlockPos getPenCenter(ServerWorld world, BlockPos insidePos) {
        BlockPos westFence = findFenceInDirection(world, insidePos, Direction.WEST, PEN_FENCE_RANGE);
        BlockPos eastFence = findFenceInDirection(world, insidePos, Direction.EAST, PEN_FENCE_RANGE);
        BlockPos northFence = findFenceInDirection(world, insidePos, Direction.NORTH, PEN_FENCE_RANGE);
        BlockPos southFence = findFenceInDirection(world, insidePos, Direction.SOUTH, PEN_FENCE_RANGE);
        if (westFence == null || eastFence == null || northFence == null || southFence == null) {
            return null;
        }

        int minX = westFence.getX() + 1;
        int maxX = eastFence.getX() - 1;
        int minZ = northFence.getZ() + 1;
        int maxZ = southFence.getZ() - 1;
        if (minX > maxX || minZ > maxZ) {
            return null;
        }

        int centerX = (minX + maxX) / 2;
        int centerZ = (minZ + maxZ) / 2;
        return new BlockPos(centerX, insidePos.getY(), centerZ);
    }

    private BlockPos findFenceInDirection(ServerWorld world, BlockPos start, Direction direction, int maxDistance) {
        for (int i = 1; i <= maxDistance; i++) {
            BlockPos pos = start.offset(direction, i);
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof FenceBlock || state.getBlock() instanceof FenceGateBlock) {
                return pos;
            }
        }
        return null;
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

    private BlockPos placeBannerInPen(ServerWorld world, BlockPos centerPos) {
        if (!carriedItem.isIn(ItemTags.BANNERS)) {
            return null;
        }
        if (!(carriedItem.getItem() instanceof BlockItem blockItem)) {
            return null;
        }

        BlockPos placed = tryPlaceBanner(world, centerPos, blockItem);
        if (placed == null) {
            placed = tryPlaceBanner(world, centerPos.up(), blockItem);
        }
        if (placed != null && consumeBannerFromInventory(carriedItem)) {
            carriedItem.decrement(1);
            if (carriedItem.isEmpty()) {
                carriedItem = ItemStack.EMPTY;
            }
        }
        return placed;
    }

    private BlockPos tryPlaceBanner(ServerWorld world, BlockPos pos, BlockItem blockItem) {
        if (!world.getBlockState(pos).isAir()) {
            return null;
        }
        BlockState bannerState = blockItem.getBlock().getDefaultState();
        if (!bannerState.canPlaceAt(world, pos)) {
            return null;
        }
        if (world.setBlockState(pos, bannerState)) {
            world.playSound(null, pos, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 1.0F, 1.0F);
            return pos;
        }
        return null;
    }

    private boolean consumeBannerFromInventory(ItemStack bannerStack) {
        if (bannerStack.isEmpty()) {
            return false;
        }
        ItemStack mainHand = villager.getMainHandStack();
        if (!mainHand.isEmpty()
                && mainHand.isIn(ItemTags.BANNERS)
                && ItemStack.areItemsAndComponentsEqual(mainHand, bannerStack)) {
            mainHand.decrement(1);
            if (mainHand.isEmpty()) {
                villager.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
            }
            return true;
        }
        Inventory inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !stack.isIn(ItemTags.BANNERS)) {
                continue;
            }
            if (!ItemStack.areItemsAndComponentsEqual(stack, bannerStack)) {
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

    private void updatePenGateAccess(ServerWorld world, BlockPos gatePos) {
        if (taskType == TaskType.WHEAT_GATHER) {
            return;
        }
        if (gatePos == null) {
            return;
        }
        BlockState state = world.getBlockState(gatePos);
        if (!(state.getBlock() instanceof FenceGateBlock)) {
            return;
        }

        boolean isOpen = state.get(FenceGateBlock.OPEN);
        boolean isNearGate = villager.squaredDistanceTo(gatePos.getX() + 0.5D, gatePos.getY() + 0.5D, gatePos.getZ() + 0.5D) <= GATE_INTERACT_RANGE_SQUARED;
        boolean isInsidePen = penTarget != null && isInsideFencePen(world, villager.getBlockPos());

        if (isNearGate && (!isOpen || !openedPenGate)) {
            openGate(world, gatePos, true);
            openedPenGate = true;
            wasInsidePen = isInsidePen;
            return;
        }

        if (openedPenGate && isOpen && !isInsidePen && stage == Stage.RETURN_TO_CHEST) {
            openGate(world, gatePos, false);
            openedPenGate = false;
        } else if (openedPenGate && isOpen && wasInsidePen && !isInsidePen && isNearGate) {
            openGate(world, gatePos, false);
            openedPenGate = false;
        }
        wasInsidePen = isInsidePen;
    }

    private void openGate(ServerWorld world, BlockPos pos, boolean open) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof FenceGateBlock)) {
            return;
        }
        if (state.get(FenceGateBlock.OPEN) == open) {
            return;
        }
        world.setBlockState(pos, state.with(FenceGateBlock.OPEN, open), 2);
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
        moveTo(target, MOVE_SPEED);
    }

    private void moveTo(BlockPos target, double speed) {
        villager.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, speed);
    }

    private boolean isNear(BlockPos target) {
        return villager.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private void ensureGateOpen(ServerWorld world, BlockPos gatePos) {
        if (gatePos == null) {
            return;
        }
        BlockState state = world.getBlockState(gatePos);
        if (!(state.getBlock() instanceof FenceGateBlock)) {
            return;
        }
        if (!state.get(FenceGateBlock.OPEN)) {
            openGate(world, gatePos, true);
        }
    }

    private boolean hasGroundBannerNearby(ServerWorld world) {
        return findNearestGroundBanner(world) != null;
    }

    private BlockPos findNearestGroundBanner(ServerWorld world) {
        BlockPos start = villager.getBlockPos().add(-PEN_SCAN_RANGE, -PEN_SCAN_RANGE, -PEN_SCAN_RANGE);
        BlockPos end = villager.getBlockPos().add(PEN_SCAN_RANGE, PEN_SCAN_RANGE, PEN_SCAN_RANGE);
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(start, end)) {
            BlockState state = world.getBlockState(pos);
            if (!state.isIn(BlockTags.BANNERS)) {
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

    private PenTarget findNearestPenWithBanner(ServerWorld world, BlockPos bannerPos) {
        BlockPos start = bannerPos.add(-PEN_SCAN_RANGE, -PEN_SCAN_RANGE, -PEN_SCAN_RANGE);
        BlockPos end = bannerPos.add(PEN_SCAN_RANGE, PEN_SCAN_RANGE, PEN_SCAN_RANGE);
        PenTarget nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(start, end)) {
            BlockState state = world.getBlockState(pos);
            if (!(state.getBlock() instanceof FenceGateBlock)) {
                continue;
            }

            BlockPos insidePos = getPenInterior(world, pos, state);
            if (insidePos == null || !isInsideFencePen(world, insidePos)) {
                continue;
            }

            BlockPos center = getPenCenter(world, insidePos);
            if (center == null || !hasBannerInPen(world, center)) {
                continue;
            }

            double distanceToBanner = center.getSquaredDistance(bannerPos);
            if (distanceToBanner < nearestDistance) {
                nearestDistance = distanceToBanner;
                nearest = new PenTarget(center.toImmutable(), pos.toImmutable());
            }
        }
        return nearest;
    }

    private BlockPos randomGatherWanderTarget(ServerWorld world, BlockPos origin) {
        int dx = villager.getRandom().nextBetween(-GATHER_RADIUS, GATHER_RADIUS);
        int dz = villager.getRandom().nextBetween(-GATHER_RADIUS, GATHER_RADIUS);
        BlockPos target = origin.add(dx, 0, dz);
        int topY = world.getTopY() - 1;
        int clampedY = Math.max(world.getBottomY(), Math.min(topY, target.getY()));
        return new BlockPos(target.getX(), clampedY, target.getZ());
    }

    private long randomGatherSessionDuration() {
        return GATHER_MIN_SESSION_TICKS + villager.getRandom().nextInt(GATHER_MAX_SESSION_TICKS - GATHER_MIN_SESSION_TICKS + 1);
    }

    private enum Stage {
        IDLE,
        GO_TO_SHEEP,
        GO_TO_PEN,
        GATHER_WANDER,
        GUIDE_TO_PEN_CENTER,
        RUSH_TO_GATE_AND_CLOSE,
        RETURN_TO_CHEST,
        DONE
    }

    private enum TaskType {
        SHEARS,
        BANNER,
        WHEAT_GATHER
    }

    private record PenTarget(BlockPos center, BlockPos gate) {
    }
}
