package dev.sterner.guardvillagers.common.entity.goal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BrewingStandBlockEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionUtil;
import net.minecraft.potion.Potions;
import net.minecraft.recipe.BrewingRecipeRegistry;
import net.minecraft.registry.Registries;
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
    private PotionTarget targetPotion;
    private Set<PotionTarget> lastReachablePotions = Set.of();
    private BottleStage lastReachableBottleStage;

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
        POTION_READY,
        SPLASH_READY,
        INVALID
    }

    public record PotionTarget(Potion potion, boolean splash) {
    }

    private record BottleState(BottleStage stage, Potion potion, boolean splash) {
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
        this.targetPotion = null;
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
        BottleState state = getBottleState(stand);
        Set<PotionTarget> reachablePotions = getReachableRecipes(chestInventory, stand);
        PotionTarget selectedTarget = selectTargetPotion(reachablePotions);
        if (shouldLogReachablePotions(state.stage(), reachablePotions)) {
            lastReachablePotions = reachablePotions.isEmpty()
                    ? Set.of()
                    : Set.copyOf(reachablePotions);
            lastReachableBottleStage = state.stage();
            LOGGER.debug("Cleric {} reachable potions count={} selected={} {}", villager.getUuidAsString(),
                    reachablePotions.size(), selectedTarget, reachablePotions);
        }
        return !reachablePotions.isEmpty();
    }

    private boolean shouldLogReachablePotions(BottleStage bottleStage, Set<PotionTarget> reachablePotions) {
        if (bottleStage != lastReachableBottleStage) {
            return true;
        }
        return !reachablePotions.equals(lastReachablePotions);
    }

    private void transferItems(ServerWorld world) {
        Inventory chestInventory = getChestInventory(world);
        Optional<BrewingStandBlockEntity> standOpt = getBrewingStand(world);
        if (chestInventory == null || standOpt.isEmpty()) {
            return;
        }
        BrewingStandBlockEntity stand = standOpt.get();
        BottleState state = getBottleState(stand);
        updateTargetPotion(state, chestInventory, stand);
        boolean changed = extractFinishedPotions(chestInventory, stand, state);

        if (!stand.getStack(INGREDIENT_SLOT).isEmpty()) {
            if (changed) {
                chestInventory.markDirty();
                stand.markDirty();
            }
            return;
        }

        state = getBottleState(stand);
        updateTargetPotion(state, chestInventory, stand);
        if (state.stage() == BottleStage.EMPTY || state.stage() == BottleStage.WATER_PARTIAL) {
            changed |= fillWaterBottles(chestInventory, stand);
            state = getBottleState(stand);
            updateTargetPotion(state, chestInventory, stand);
        }

        Item nextIngredient = getNextIngredient(state, chestInventory);
        if (nextIngredient != null) {
            changed |= insertIngredient(chestInventory, stand, stack -> stack.isOf(nextIngredient));
            if (changed) {
                tryStartBrewing(world, stand);
            }
        }

        if (changed) {
            chestInventory.markDirty();
            stand.markDirty();
        }
    }

    private boolean extractFinishedPotions(Inventory chestInventory, BrewingStandBlockEntity stand, BottleState state) {
        PotionTarget finishedTarget = getFinishedTarget(state);
        if (finishedTarget == null) {
            return false;
        }
        if (targetPotion != null && !targetPotion.equals(finishedTarget)) {
            return false;
        }
        boolean movedAny = false;
        int movedCount = 0;
        for (int slot = 0; slot < 3; slot++) {
            ItemStack stack = stand.getStack(slot);
            if (!isPotionMatch(stack, finishedTarget)) {
                continue;
            }
            ItemStack remaining = insertStack(chestInventory, stack.copy());
            if (!remaining.isEmpty()) {
                remaining = insertStack(villager.getInventory(), remaining);
            }
            int moved = stack.getCount() - remaining.getCount();
            if (moved > 0) {
                movedAny = true;
                movedCount += moved;
            }
            stand.setStack(slot, remaining);
        }
        if (movedAny) {
            chestInventory.markDirty();
            villager.getInventory().markDirty();
            LOGGER.info("Finished brewing {} {} and loaded it into chest {}", movedCount, finishedTarget, chestPos);
        }
        return movedAny;
    }

    private boolean fillWaterBottles(Inventory chestInventory, BrewingStandBlockEntity stand) {
        boolean changed = false;
        for (int slot = 0; slot < 3; slot++) {
            if (!stand.getStack(slot).isEmpty()) {
                continue;
            }
            ItemStack waterBottle = extractOne(chestInventory, ClericBrewingGoal::isWaterBottle);
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

    public static Set<PotionTarget> getReachableRecipes(Inventory chestInventory, BrewingStandBlockEntity stand) {
        BottleState state = getBottleState(stand);
        if (state.stage() == BottleStage.INVALID) {
            return Set.of();
        }
        Set<PotionTarget> reachable = new HashSet<>();
        PotionTarget finishedTarget = getFinishedTarget(state);
        if (finishedTarget != null) {
            reachable.add(finishedTarget);
        }
        if (!stand.getStack(INGREDIENT_SLOT).isEmpty() || state.stage() == BottleStage.SPLASH_READY) {
            return reachable;
        }
        Potion startPotion = getStartPotion(state, chestInventory);
        if (startPotion == null) {
            return reachable;
        }
        Set<Item> availableIngredients = getInventoryItems(chestInventory);
        boolean hasGunpowder = availableIngredients.remove(Items.GUNPOWDER);
        availableIngredients.remove(Items.POTION);
        availableIngredients.remove(Items.SPLASH_POTION);
        Map<Potion, List<Item>> reachablePotions = getReachablePotionPaths(startPotion, availableIngredients);
        for (Potion potion : reachablePotions.keySet()) {
            if (potion == Potions.WATER || potion == Potions.AWKWARD || potion == Potions.EMPTY) {
                continue;
            }
            reachable.add(new PotionTarget(potion, false));
            if (hasGunpowder) {
                reachable.add(new PotionTarget(potion, true));
            }
        }
        return reachable;
    }

    private static BottleState getBottleState(BrewingStandBlockEntity stand) {
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
                Potion potion = PotionUtil.getPotion(stack);
                if (potion == Potions.EMPTY) {
                    return new BottleState(BottleStage.INVALID, Potions.EMPTY, false);
                }
                if (potionType == null) {
                    potionType = potion;
                    splash = true;
                } else if (potionType != potion || !splash) {
                    return new BottleState(BottleStage.INVALID, Potions.EMPTY, false);
                }
                continue;
            }
            if (!stack.isOf(Items.POTION)) {
                return new BottleState(BottleStage.INVALID, Potions.EMPTY, false);
            }
            Potion potion = PotionUtil.getPotion(stack);
            if (potion == Potions.EMPTY) {
                return new BottleState(BottleStage.INVALID, Potions.EMPTY, false);
            }
            if (potionType == null) {
                potionType = potion;
                splash = false;
            } else if (potionType != potion || splash) {
                return new BottleState(BottleStage.INVALID, Potions.EMPTY, false);
            }
        }

        if (potionType == null) {
            return new BottleState(BottleStage.EMPTY, Potions.EMPTY, false);
        }
        if (splash) {
            return anyEmpty
                    ? new BottleState(BottleStage.INVALID, Potions.EMPTY, false)
                    : new BottleState(BottleStage.SPLASH_READY, potionType, true);
        }
        if (potionType == Potions.WATER) {
            return new BottleState(anyEmpty ? BottleStage.WATER_PARTIAL : BottleStage.WATER_READY, potionType, false);
        }
        if (anyEmpty) {
            return new BottleState(BottleStage.INVALID, Potions.EMPTY, false);
        }
        if (potionType == Potions.AWKWARD) {
            return new BottleState(BottleStage.AWKWARD_READY, potionType, false);
        }
        return new BottleState(BottleStage.POTION_READY, potionType, false);
    }

    private static boolean isWaterBottle(ItemStack stack) {
        return stack.isOf(Items.POTION) && PotionUtil.getPotion(stack) == Potions.WATER;
    }

    private boolean isPotionMatch(ItemStack stack, PotionTarget target) {
        if (target == null) {
            return false;
        }
        if (target.splash()) {
            return stack.isOf(Items.SPLASH_POTION) && PotionUtil.getPotion(stack) == target.potion();
        }
        return stack.isOf(Items.POTION) && PotionUtil.getPotion(stack) == target.potion();
    }

    private void updateTargetPotion(BottleState state, Inventory chestInventory, BrewingStandBlockEntity stand) {
        Set<PotionTarget> reachable = getReachableRecipes(chestInventory, stand);
        PotionTarget finishedTarget = getFinishedTarget(state);
        if (finishedTarget != null && reachable.contains(finishedTarget)) {
            setTargetPotion(finishedTarget);
            return;
        }
        if (targetPotion != null && reachable.contains(targetPotion)) {
            return;
        }
        setTargetPotion(selectTargetPotion(reachable));
    }

    private PotionTarget selectTargetPotion(Set<PotionTarget> reachable) {
        if (reachable.isEmpty()) {
            return null;
        }
        List<PotionTarget> targets = reachable.stream()
                .filter(target -> target.potion() != Potions.WATER && target.potion() != Potions.AWKWARD)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (targets.isEmpty()) {
            return null;
        }
        PotionTarget splashHealing = new PotionTarget(Potions.HEALING, true);
        if (targets.contains(splashHealing)) {
            return splashHealing;
        }
        PotionTarget healing = new PotionTarget(Potions.HEALING, false);
        if (targets.contains(healing)) {
            return healing;
        }
        targets.sort(Comparator.comparing((PotionTarget target) -> Registries.POTION.getId(target.potion()).toString())
                .thenComparing(PotionTarget::splash));
        return targets.get(0);
    }

    private void setTargetPotion(PotionTarget newTargetPotion) {
        if (targetPotion != null && targetPotion.equals(newTargetPotion)) {
            return;
        }
        if (targetPotion == null && newTargetPotion == null) {
            return;
        }
        targetPotion = newTargetPotion;
        LOGGER.debug("Cleric {} target potion {}", villager.getUuidAsString(), targetPotion);
    }

    private Item getNextIngredient(BottleState state, Inventory chestInventory) {
        if (targetPotion == null) {
            return null;
        }
        if (state.stage() == BottleStage.INVALID || state.stage() == BottleStage.SPLASH_READY) {
            return null;
        }
        Potion startPotion = getStartPotion(state, chestInventory);
        if (startPotion == null) {
            return null;
        }
        Set<Item> availableIngredients = getInventoryItems(chestInventory);
        boolean hasGunpowder = availableIngredients.remove(Items.GUNPOWDER);
        availableIngredients.remove(Items.POTION);
        availableIngredients.remove(Items.SPLASH_POTION);
        List<Item> path = getPotionPath(startPotion, targetPotion.potion(), availableIngredients);
        if (path == null) {
            return null;
        }
        if (!path.isEmpty()) {
            return path.get(0);
        }
        if (targetPotion.splash() && !state.splash()) {
            return hasGunpowder ? Items.GUNPOWDER : null;
        }
        return null;
    }

    private static PotionTarget getFinishedTarget(BottleState state) {
        return switch (state.stage()) {
            case POTION_READY -> new PotionTarget(state.potion(), false);
            case SPLASH_READY -> new PotionTarget(state.potion(), true);
            default -> null;
        };
    }

    private static Potion getStartPotion(BottleState state, Inventory chestInventory) {
        return switch (state.stage()) {
            case WATER_READY -> Potions.WATER;
            case WATER_PARTIAL, EMPTY -> hasWaterBottle(chestInventory) ? Potions.WATER : null;
            case AWKWARD_READY -> Potions.AWKWARD;
            case POTION_READY -> state.potion();
            default -> null;
        };
    }

    private static Set<Item> getInventoryItems(Inventory inventory) {
        Set<Item> items = new HashSet<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty()) {
                items.add(stack.getItem());
            }
        }
        return items;
    }

    private static Map<Potion, List<Item>> getReachablePotionPaths(Potion startPotion, Set<Item> ingredients) {
        Map<Potion, List<Item>> paths = new HashMap<>();
        Queue<Potion> queue = new ArrayDeque<>();
        paths.put(startPotion, List.of());
        queue.add(startPotion);
        while (!queue.isEmpty()) {
            Potion current = queue.remove();
            List<Item> currentPath = paths.get(current);
            for (Item ingredient : ingredients) {
                ItemStack input = PotionUtil.setPotion(new ItemStack(Items.POTION), current);
                ItemStack ingredientStack = new ItemStack(ingredient);
                if (!BrewingRecipeRegistry.hasRecipe(input, ingredientStack)) {
                    continue;
                }
                ItemStack outputStack = BrewingRecipeRegistry.craft(ingredientStack, input);
                if (!outputStack.isOf(Items.POTION)) {
                    continue;
                }
                Potion outputPotion = PotionUtil.getPotion(outputStack);
                if (outputPotion == Potions.EMPTY || paths.containsKey(outputPotion)) {
                    continue;
                }
                List<Item> nextPath = new ArrayList<>(currentPath);
                nextPath.add(ingredient);
                paths.put(outputPotion, nextPath);
                queue.add(outputPotion);
            }
        }
        return paths;
    }

    private static List<Item> getPotionPath(Potion startPotion, Potion targetPotion, Set<Item> ingredients) {
        if (startPotion == targetPotion) {
            return List.of();
        }
        Map<Potion, List<Item>> paths = getReachablePotionPaths(startPotion, ingredients);
        return paths.get(targetPotion);
    }

    private static boolean hasWaterBottle(Inventory inventory) {
        return hasItem(inventory, ClericBrewingGoal::isWaterBottle);
    }

    private static boolean hasItem(Inventory inventory, Predicate<ItemStack> predicate) {
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
