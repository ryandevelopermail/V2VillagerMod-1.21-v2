package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.villager.behavior.ClericBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.ClericBehavior.ClericKnownPotion;
import java.util.EnumSet;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BrewingStandBlockEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionUtil;
import net.minecraft.potion.Potions;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClericBrewingGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClericBrewingGoal.class);
    private static final double MOVE_SPEED = 0.7D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int CHECK_INTERVAL_TICKS = 10;
    private static final int INGREDIENT_SLOT = 3;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;
    private boolean immediateCheckPending;

    private enum Stage {
        IDLE,
        MOVE_TO_CHEST,
        MOVE_TO_STAND,
        DONE
    }

    private enum BottleStage {
        EMPTY,
        WATER_PARTIAL,
        WATER_READY,
        AWKWARD_READY,
        HEALING_READY,
        SPLASH_HEALING,
        INVALID
    }

    public ClericBrewingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        setTargets(jobPos, chestPos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.stage = Stage.IDLE;
    }

    public void requestImmediateBrew() {
        immediateCheckPending = true;
        nextCheckTime = 0L;
    }

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!villager.isAlive() || villager.isSleeping()) {
            return false;
        }
        if (world.getTime() < nextCheckTime && !immediateCheckPending) {
            return false;
        }
        immediateCheckPending = false;
        nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
        return hasBrewingWork(world);
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.DONE && villager.isAlive();
    }

    @Override
    public void start() {
        stage = Stage.MOVE_TO_CHEST;
        moveTo(chestPos);
    }

    @Override
    public void stop() {
        stage = Stage.DONE;
        villager.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        if (stage == Stage.MOVE_TO_CHEST) {
            if (!hasBrewingWork(world)) {
                stage = Stage.DONE;
                return;
            }
            if (isNear(chestPos)) {
                stage = Stage.MOVE_TO_STAND;
                moveTo(jobPos);
            } else if (villager.getNavigation().isIdle()) {
                moveTo(chestPos);
            }
            return;
        }

        if (stage == Stage.MOVE_TO_STAND) {
            if (!hasBrewingWork(world)) {
                stage = Stage.DONE;
                return;
            }
            if (isNear(jobPos)) {
                transferItems(world);
                stage = Stage.DONE;
            } else if (villager.getNavigation().isIdle()) {
                moveTo(jobPos);
            }
        }
    }

    private boolean hasBrewingWork(ServerWorld world) {
        if (jobPos == null || chestPos == null) {
            return false;
        }
        Inventory chestInventory = getChestInventory(world);
        if (chestInventory == null) {
            return false;
        }
        Optional<BrewingStandBlockEntity> standOpt = getBrewingStand(world);
        if (standOpt.isEmpty()) {
            return false;
        }
        BrewingStandBlockEntity stand = standOpt.get();
        EnumSet<ClericKnownPotion> knownPotions = ClericBehavior.getKnownPotions(villager);
        EnumSet<ClericKnownPotion> reachablePotions = getReachablePotions(chestInventory, stand, knownPotions);
        LOGGER.info("Cleric {} reachable potions {}", villager.getUuidAsString(), reachablePotions);
        return !reachablePotions.isEmpty();
    }

    private void transferItems(ServerWorld world) {
        Inventory chestInventory = getChestInventory(world);
        Optional<BrewingStandBlockEntity> standOpt = getBrewingStand(world);
        if (chestInventory == null || standOpt.isEmpty()) {
            return;
        }
        BrewingStandBlockEntity stand = standOpt.get();
        boolean changed = extractFinishedPotions(chestInventory, stand);

        if (!stand.getStack(INGREDIENT_SLOT).isEmpty()) {
            if (changed) {
                chestInventory.markDirty();
                stand.markDirty();
            }
            return;
        }

        BottleStage stage = getBottleStage(stand);
        if (stage == BottleStage.EMPTY || stage == BottleStage.WATER_PARTIAL) {
            changed |= fillWaterBottles(chestInventory, stand);
            stage = getBottleStage(stand);
        }

        EnumSet<ClericKnownPotion> knownPotions = ClericBehavior.getKnownPotions(villager);

        if (stage == BottleStage.WATER_READY && !knownPotions.isEmpty()) {
            changed |= insertIngredient(chestInventory, stand, stack -> stack.isOf(Items.NETHER_WART));
            if (changed) {
                tryStartBrewing(world, stand);
            }
        } else if (stage == BottleStage.AWKWARD_READY && !knownPotions.isEmpty()) {
            changed |= insertIngredient(chestInventory, stand, stack -> stack.isOf(Items.GLISTERING_MELON_SLICE));
            if (changed) {
                tryStartBrewing(world, stand);
            }
        } else if (stage == BottleStage.HEALING_READY && knownPotions.contains(ClericKnownPotion.SPLASH_HEALING)) {
            changed |= insertIngredient(chestInventory, stand, stack -> stack.isOf(Items.GUNPOWDER));
            if (changed) {
                tryStartBrewing(world, stand);
            }
        }

        if (changed) {
            chestInventory.markDirty();
            stand.markDirty();
        }
    }

    private boolean extractFinishedPotions(Inventory chestInventory, BrewingStandBlockEntity stand) {
        boolean movedAny = false;
        for (int slot = 0; slot < 3; slot++) {
            ItemStack stack = stand.getStack(slot);
            if (!isHealingSplashPotion(stack)) {
                continue;
            }
            ItemStack remaining = insertStack(chestInventory, stack.copy());
            if (!remaining.isEmpty()) {
                remaining = insertStack(villager.getInventory(), remaining);
            }
            if (remaining.getCount() != stack.getCount()) {
                movedAny = true;
            }
            stand.setStack(slot, remaining);
        }
        return movedAny;
    }

    private boolean fillWaterBottles(Inventory chestInventory, BrewingStandBlockEntity stand) {
        boolean changed = false;
        for (int slot = 0; slot < 3; slot++) {
            if (!stand.getStack(slot).isEmpty()) {
                continue;
            }
            ItemStack waterBottle = extractOne(chestInventory, this::isWaterBottle);
            if (waterBottle.isEmpty()) {
                continue;
            }
            if (!stand.isValid(slot, waterBottle)) {
                insertStack(chestInventory, waterBottle);
                continue;
            }
            stand.setStack(slot, waterBottle);
            changed = true;
        }
        return changed;
    }

    private boolean insertIngredient(Inventory chestInventory, BrewingStandBlockEntity stand, Predicate<ItemStack> predicate) {
        if (!stand.getStack(INGREDIENT_SLOT).isEmpty()) {
            return false;
        }
        ItemStack ingredient = extractOne(chestInventory, predicate);
        if (ingredient.isEmpty()) {
            return false;
        }
        if (!stand.isValid(INGREDIENT_SLOT, ingredient)) {
            insertStack(chestInventory, ingredient);
            return false;
        }
        stand.setStack(INGREDIENT_SLOT, ingredient);
        return true;
    }

    private void tryStartBrewing(ServerWorld world, BrewingStandBlockEntity stand) {
        BlockState state = world.getBlockState(jobPos);
        stand.markDirty();
        BrewingStandBlockEntity.tick(world, jobPos, state, stand);
    }

    private boolean hasSplashHealingPotions(BrewingStandBlockEntity stand) {
        for (int slot = 0; slot < 3; slot++) {
            if (isHealingSplashPotion(stand.getStack(slot))) {
                return true;
            }
        }
        return false;
    }

    private EnumSet<ClericKnownPotion> getReachablePotions(Inventory chestInventory, BrewingStandBlockEntity stand,
                                                          EnumSet<ClericKnownPotion> knownPotions) {
        EnumSet<ClericKnownPotion> reachable = EnumSet.noneOf(ClericKnownPotion.class);
        if (knownPotions.contains(ClericKnownPotion.SPLASH_HEALING) && hasSplashHealingPotions(stand)) {
            reachable.add(ClericKnownPotion.SPLASH_HEALING);
        }
        if (!stand.getStack(INGREDIENT_SLOT).isEmpty()) {
            return reachable;
        }
        BottleStage stage = getBottleStage(stand);
        if (knownPotions.contains(ClericKnownPotion.HEALING) && canReachHealing(stage, chestInventory)) {
            reachable.add(ClericKnownPotion.HEALING);
        }
        if (knownPotions.contains(ClericKnownPotion.SPLASH_HEALING) && canReachSplashHealing(stage, chestInventory)) {
            reachable.add(ClericKnownPotion.SPLASH_HEALING);
        }
        return reachable;
    }

    private boolean canReachHealing(BottleStage stage, Inventory chestInventory) {
        return switch (stage) {
            case EMPTY, WATER_PARTIAL -> hasWaterBottle(chestInventory)
                    && hasItem(chestInventory, stack -> stack.isOf(Items.NETHER_WART))
                    && hasItem(chestInventory, stack -> stack.isOf(Items.GLISTERING_MELON_SLICE));
            case WATER_READY -> hasItem(chestInventory, stack -> stack.isOf(Items.NETHER_WART))
                    && hasItem(chestInventory, stack -> stack.isOf(Items.GLISTERING_MELON_SLICE));
            case AWKWARD_READY -> hasItem(chestInventory, stack -> stack.isOf(Items.GLISTERING_MELON_SLICE));
            default -> false;
        };
    }

    private boolean canReachSplashHealing(BottleStage stage, Inventory chestInventory) {
        return switch (stage) {
            case EMPTY, WATER_PARTIAL -> hasWaterBottle(chestInventory)
                    && hasItem(chestInventory, stack -> stack.isOf(Items.NETHER_WART))
                    && hasItem(chestInventory, stack -> stack.isOf(Items.GLISTERING_MELON_SLICE))
                    && hasItem(chestInventory, stack -> stack.isOf(Items.GUNPOWDER));
            case WATER_READY -> hasItem(chestInventory, stack -> stack.isOf(Items.NETHER_WART))
                    && hasItem(chestInventory, stack -> stack.isOf(Items.GLISTERING_MELON_SLICE))
                    && hasItem(chestInventory, stack -> stack.isOf(Items.GUNPOWDER));
            case AWKWARD_READY -> hasItem(chestInventory, stack -> stack.isOf(Items.GLISTERING_MELON_SLICE))
                    && hasItem(chestInventory, stack -> stack.isOf(Items.GUNPOWDER));
            case HEALING_READY -> hasItem(chestInventory, stack -> stack.isOf(Items.GUNPOWDER));
            case SPLASH_HEALING -> true;
            default -> false;
        };
    }

    private BottleStage getBottleStage(BrewingStandBlockEntity stand) {
        boolean anyEmpty = false;
        Potion potionType = null;
        boolean splash = false;

        for (int slot = 0; slot < 3; slot++) {
            ItemStack stack = stand.getStack(slot);
            if (stack.isEmpty()) {
                anyEmpty = true;
                continue;
            }
            if (stack.isOf(Items.SPLASH_POTION)) {
                if (PotionUtil.getPotion(stack) != Potions.HEALING) {
                    return BottleStage.INVALID;
                }
                if (potionType == null) {
                    potionType = Potions.HEALING;
                    splash = true;
                } else if (potionType != Potions.HEALING || !splash) {
                    return BottleStage.INVALID;
                }
                continue;
            }
            if (!stack.isOf(Items.POTION)) {
                return BottleStage.INVALID;
            }
            Potion potion = PotionUtil.getPotion(stack);
            if (potion != Potions.WATER && potion != Potions.AWKWARD && potion != Potions.HEALING) {
                return BottleStage.INVALID;
            }
            if (potionType == null) {
                potionType = potion;
                splash = false;
            } else if (potionType != potion || splash) {
                return BottleStage.INVALID;
            }
        }

        if (potionType == null) {
            return BottleStage.EMPTY;
        }
        if (splash) {
            return anyEmpty ? BottleStage.INVALID : BottleStage.SPLASH_HEALING;
        }
        if (potionType == Potions.WATER) {
            return anyEmpty ? BottleStage.WATER_PARTIAL : BottleStage.WATER_READY;
        }
        if (anyEmpty) {
            return BottleStage.INVALID;
        }
        if (potionType == Potions.AWKWARD) {
            return BottleStage.AWKWARD_READY;
        }
        if (potionType == Potions.HEALING) {
            return BottleStage.HEALING_READY;
        }
        return BottleStage.INVALID;
    }

    private boolean isWaterBottle(ItemStack stack) {
        return stack.isOf(Items.POTION) && PotionUtil.getPotion(stack) == Potions.WATER;
    }

    private boolean isHealingSplashPotion(ItemStack stack) {
        return stack.isOf(Items.SPLASH_POTION) && PotionUtil.getPotion(stack) == Potions.HEALING;
    }

    private boolean hasWaterBottle(Inventory inventory) {
        return hasItem(inventory, this::isWaterBottle);
    }

    private boolean hasItem(Inventory inventory, Predicate<ItemStack> predicate) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && predicate.test(stack)) {
                return true;
            }
        }
        return false;
    }

    private ItemStack extractOne(Inventory inventory, Predicate<ItemStack> predicate) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !predicate.test(stack)) {
                continue;
            }
            return stack.split(1);
        }
        return ItemStack.EMPTY;
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

    private Optional<BrewingStandBlockEntity> getBrewingStand(ServerWorld world) {
        BlockEntity blockEntity = world.getBlockEntity(jobPos);
        if (blockEntity instanceof BrewingStandBlockEntity stand) {
            return Optional.of(stand);
        }
        return Optional.empty();
    }

    private Inventory getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }
        return ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
    }

    private void moveTo(BlockPos target) {
        if (target == null) {
            return;
        }
        villager.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, MOVE_SPEED);
    }

    private boolean isNear(BlockPos target) {
        if (target == null) {
            return false;
        }
        return villager.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }
}
