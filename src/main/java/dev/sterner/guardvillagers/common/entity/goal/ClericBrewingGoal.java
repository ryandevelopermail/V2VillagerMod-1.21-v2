package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.mixin.BrewingStandBlockEntityAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BrewingStandBlockEntity;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class ClericBrewingGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClericBrewingGoal.class);
    private static final double MOVE_SPEED = 0.7D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int MIN_CHECK_INTERVAL_TICKS = 20 * 60 * 2;
    private static final int MAX_CHECK_INTERVAL_TICKS = 20 * 60 * 4;
    private static final int INGREDIENT_SLOT = 3;
    private static final int FUEL_SLOT = 4;
    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;
    private boolean immediateCheckPending;
    private ItemStack targetPotion = ItemStack.EMPTY;

    private enum Stage {
        IDLE,
        MOVE_TO_CHEST,
        MOVE_TO_STAND,
        DONE
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

    public void requestImmediateCheck() {
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
        if (villager.getVillagerData().getProfession() != VillagerProfession.CLERIC) {
            return false;
        }
        if (jobPos == null || chestPos == null) {
            return false;
        }
        if (!jobPos.isWithinDistance(chestPos, 3.0D)) {
            return false;
        }
        if (!world.getBlockState(jobPos).isOf(Blocks.BREWING_STAND)) {
            return false;
        }
        if (!immediateCheckPending && world.getTimeOfDay() < nextCheckTime) {
            return false;
        }
        scheduleNextCheck(world);
        immediateCheckPending = false;
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
        Inventory chestInventory = getChestInventory(world);
        Optional<BrewingStandBlockEntity> standOpt = getBrewingStand(world);
        if (chestInventory == null || standOpt.isEmpty()) {
            LOGGER.info("Cleric {} checking chest for potion ingredients: reachable potions none (chest={}, stand={})",
                    villager.getUuidAsString(),
                    chestInventory != null,
                    standOpt.isPresent());
            return false;
        }
        BrewingStandBlockEntity stand = standOpt.get();
        List<ItemStack> reachablePotions = collectReachablePotions(world, chestInventory, stand);
        LOGGER.info("Cleric {} checking chest for potion ingredients: reachable potions {}",
                villager.getUuidAsString(),
                formatReachablePotions(reachablePotions));
        ItemStack resolvedTargetPotion = selectTargetPotion(reachablePotions);
        if (shouldExtractPotions(stand)) {
            return true;
        }
        if (needsFuel(stand) && hasFuelInChest(chestInventory)) {
            return true;
        }
        return needsIngredient(stand) && hasIngredientInChest(world, chestInventory, stand, resolvedTargetPotion);
    }

    private boolean shouldExtractPotions(BrewingStandBlockEntity stand) {
        if (((BrewingStandBlockEntityAccessor) stand).guardvillagers$getBrewTime() > 0) {
            return false;
        }
        if (!stand.getStack(INGREDIENT_SLOT).isEmpty()) {
            return false;
        }
        return hasAnyPotion(stand);
    }

    private boolean needsFuel(BrewingStandBlockEntity stand) {
        return stand.getStack(FUEL_SLOT).isEmpty();
    }

    private boolean hasFuelInChest(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(Items.BLAZE_POWDER)) {
                return true;
            }
        }
        return false;
    }

    private boolean needsIngredient(BrewingStandBlockEntity stand) {
        return stand.getStack(INGREDIENT_SLOT).isEmpty() && hasAnyPotion(stand);
    }

    private boolean hasIngredientInChest(ServerWorld world, Inventory inventory, BrewingStandBlockEntity stand, ItemStack targetPotion) {
        if (targetPotion.isEmpty()) {
            return false;
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && world.getServer().getBrewingRecipeRegistry().isValidIngredient(stack)) {
                if (isIngredientForTarget(world, inventory, stand, stack, targetPotion)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasAnyPotion(BrewingStandBlockEntity stand) {
        for (int slot = 0; slot < 3; slot++) {
            if (!stand.getStack(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void transferItems(ServerWorld world) {
        Inventory chestInventory = getChestInventory(world);
        Optional<BrewingStandBlockEntity> standOpt = getBrewingStand(world);
        if (chestInventory == null || standOpt.isEmpty()) {
            return;
        }
        BrewingStandBlockEntity stand = standOpt.get();
        List<ItemStack> reachablePotions = collectReachablePotions(world, chestInventory, stand);
        ItemStack resolvedTargetPotion = selectTargetPotion(reachablePotions);
        if (shouldExtractPotions(stand)) {
            extractPotions(chestInventory, stand);
        }
        if (needsIngredient(stand)) {
            insertIngredient(world, chestInventory, stand, resolvedTargetPotion);
        }
        if (needsFuel(stand)) {
            insertFuel(chestInventory, stand);
        }
        chestInventory.markDirty();
        stand.markDirty();
    }

    private void extractPotions(Inventory chestInventory, BrewingStandBlockEntity stand) {
        boolean finishedBrewing = ((BrewingStandBlockEntityAccessor) stand).guardvillagers$getBrewTime() == 0;
        for (int slot = 0; slot < 3; slot++) {
            ItemStack potionStack = stand.getStack(slot);
            if (potionStack.isEmpty()) {
                continue;
            }
            ItemStack remaining = insertIntoInventory(chestInventory, potionStack.copy());
            if (finishedBrewing) {
                String qualifier = "";
                if (!remaining.isEmpty()) {
                    if (remaining.getCount() == potionStack.getCount()) {
                        qualifier = " (chest full)";
                    } else {
                        qualifier = " (partial insert)";
                    }
                }
                LOGGER.info("Finished brewing {} {} and loaded it into chest {}{}",
                        potionStack.getItem(),
                        getPotionContents(potionStack),
                        chestPos,
                        qualifier);
            }
            if (remaining.isEmpty()) {
                stand.setStack(slot, ItemStack.EMPTY);
            } else if (remaining.getCount() != potionStack.getCount()) {
                stand.setStack(slot, remaining);
            }
        }
    }

    private void insertIngredient(ServerWorld world, Inventory chestInventory, BrewingStandBlockEntity stand, ItemStack targetPotion) {
        if (!stand.getStack(INGREDIENT_SLOT).isEmpty()) {
            return;
        }
        if (targetPotion.isEmpty()) {
            return;
        }
        for (int slot = 0; slot < chestInventory.size(); slot++) {
            ItemStack stack = chestInventory.getStack(slot);
            if (stack.isEmpty() || !world.getServer().getBrewingRecipeRegistry().isValidIngredient(stack)) {
                continue;
            }
            if (!isIngredientForTarget(world, chestInventory, stand, stack, targetPotion)) {
                continue;
            }
            ItemStack toInsert = stack.copy();
            toInsert.setCount(1);
            stand.setStack(INGREDIENT_SLOT, toInsert);
            stack.decrement(1);
            if (stack.isEmpty()) {
                chestInventory.setStack(slot, ItemStack.EMPTY);
            }
            return;
        }
    }

    private void insertFuel(Inventory chestInventory, BrewingStandBlockEntity stand) {
        if (!stand.getStack(FUEL_SLOT).isEmpty()) {
            return;
        }
        for (int slot = 0; slot < chestInventory.size(); slot++) {
            ItemStack stack = chestInventory.getStack(slot);
            if (!stack.isOf(Items.BLAZE_POWDER)) {
                continue;
            }
            ItemStack toInsert = stack.copy();
            toInsert.setCount(1);
            stand.setStack(FUEL_SLOT, toInsert);
            stack.decrement(1);
            if (stack.isEmpty()) {
                chestInventory.setStack(slot, ItemStack.EMPTY);
            }
            return;
        }
    }

    private ItemStack insertIntoInventory(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                inventory.setStack(slot, remaining);
                return ItemStack.EMPTY;
            }
            if (!ItemStack.areItemsAndComponentsEqual(existing, remaining)) {
                continue;
            }
            int maxStack = Math.min(existing.getMaxCount(), inventory.getMaxCountPerStack());
            int space = maxStack - existing.getCount();
            if (space <= 0) {
                continue;
            }
            int toMove = Math.min(space, remaining.getCount());
            existing.increment(toMove);
            remaining.decrement(toMove);
            if (remaining.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }
        return remaining;
    }

    private ItemStack selectTargetPotion(List<ItemStack> reachablePotions) {
        if (reachablePotions.isEmpty()) {
            targetPotion = ItemStack.EMPTY;
            return ItemStack.EMPTY;
        }
        if (!targetPotion.isEmpty() && containsPotion(reachablePotions, targetPotion)) {
            return targetPotion;
        }
        ItemStack selected = reachablePotions.get(villager.getRandom().nextInt(reachablePotions.size())).copy();
        selected.setCount(1);
        targetPotion = selected;
        return selected;
    }

    private String formatReachablePotions(List<ItemStack> reachablePotions) {
        if (reachablePotions.isEmpty()) {
            return "none";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < reachablePotions.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            ItemStack potion = reachablePotions.get(index);
            builder.append(potion.getItem());
            builder.append(" ");
            builder.append(getPotionContents(potion));
        }
        return builder.toString();
    }

    private boolean isIngredientForTarget(ServerWorld world, Inventory inventory, BrewingStandBlockEntity stand, ItemStack ingredient, ItemStack targetPotion) {
        var registry = world.getServer().getBrewingRecipeRegistry();
        for (int slot = 0; slot < 3; slot++) {
            ItemStack inputPotion = stand.getStack(slot);
            if (inputPotion.isEmpty()) {
                continue;
            }
            if (!registry.hasRecipe(inputPotion, ingredient)) {
                continue;
            }
            ItemStack output = registry.craft(ingredient, inputPotion);
            if (output.isEmpty()) {
                continue;
            }
            if (matchesTargetPotion(output, targetPotion)) {
                return true;
            }
            if (canBrewIntoTarget(world, inventory, output, targetPotion)) {
                return true;
            }
        }
        return false;
    }

    private boolean canBrewIntoTarget(ServerWorld world, Inventory inventory, ItemStack inputPotion, ItemStack targetPotion) {
        var registry = world.getServer().getBrewingRecipeRegistry();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack ingredient = inventory.getStack(slot);
            if (ingredient.isEmpty() || !registry.isValidIngredient(ingredient)) {
                continue;
            }
            if (!registry.hasRecipe(inputPotion, ingredient)) {
                continue;
            }
            ItemStack output = registry.craft(ingredient, inputPotion);
            if (!output.isEmpty() && matchesTargetPotion(output, targetPotion)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesTargetPotion(ItemStack candidate, ItemStack targetPotion) {
        if (candidate.isEmpty() || targetPotion.isEmpty()) {
            return false;
        }
        if (!candidate.isOf(targetPotion.getItem())) {
            return false;
        }
        var candidateContents = getPotionContents(candidate);
        var targetContents = getPotionContents(targetPotion);
        return candidateContents.equals(targetContents);
    }

    private PotionContentsComponent getPotionContents(ItemStack stack) {
        for (var component : stack.getComponents()) {
            Object value = component.value();
            if (value instanceof PotionContentsComponent potionContents) {
                return potionContents;
            }
        }
        return PotionContentsComponent.DEFAULT;
    }

    private List<ItemStack> collectReachablePotions(ServerWorld world, Inventory chestInventory, BrewingStandBlockEntity stand) {
        var registry = world.getServer().getBrewingRecipeRegistry();
        List<ItemStack> reachable = new ArrayList<>();
        List<ItemStack> visited = new ArrayList<>();
        ArrayDeque<ItemStack> queue = new ArrayDeque<>();
        boolean hasStandInput = false;
        for (int slot = 0; slot < 3; slot++) {
            ItemStack potion = stand.getStack(slot);
            if (seedPotionQueue(visited, queue, potion)) {
                hasStandInput = true;
            }
        }
        if (!hasStandInput) {
            for (int slot = 0; slot < chestInventory.size(); slot++) {
                seedPotionQueue(visited, queue, chestInventory.getStack(slot));
            }
        }

        while (!queue.isEmpty()) {
            ItemStack inputPotion = queue.removeFirst();
            for (int slot = 0; slot < chestInventory.size(); slot++) {
                ItemStack ingredient = chestInventory.getStack(slot);
                if (ingredient.isEmpty() || !registry.isValidIngredient(ingredient)) {
                    continue;
                }
                if (!registry.hasRecipe(inputPotion, ingredient)) {
                    continue;
                }
                ItemStack output = registry.craft(ingredient, inputPotion);
                if (output.isEmpty() || !isPotionItem(output)) {
                    continue;
                }
                output.setCount(1);
                if (!containsPotion(reachable, output)) {
                    reachable.add(output.copy());
                }
                if (containsPotion(visited, output)) {
                    continue;
                }
                visited.add(output.copy());
                queue.add(output.copy());
            }
        }
        return reachable;
    }

    private boolean seedPotionQueue(List<ItemStack> visited, ArrayDeque<ItemStack> queue, ItemStack potion) {
        if (potion.isEmpty() || !isPotionItem(potion)) {
            return false;
        }
        ItemStack normalized = potion.copy();
        normalized.setCount(1);
        if (containsPotion(visited, normalized)) {
            return false;
        }
        visited.add(normalized);
        queue.add(normalized);
        return true;
    }

    private boolean containsPotion(List<ItemStack> potions, ItemStack candidate) {
        if (candidate.isEmpty()) {
            return false;
        }
        for (ItemStack potion : potions) {
            if (matchesTargetPotion(candidate, potion)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPotionItem(ItemStack stack) {
        return stack.isOf(Items.POTION) || stack.isOf(Items.SPLASH_POTION) || stack.isOf(Items.LINGERING_POTION);
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

    private void moveTo(BlockPos pos) {
        if (pos == null) {
            return;
        }
        villager.getNavigation().startMovingTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, MOVE_SPEED);
    }

    private boolean isNear(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        return villager.squaredDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private void scheduleNextCheck(ServerWorld world) {
        int range = MAX_CHECK_INTERVAL_TICKS - MIN_CHECK_INTERVAL_TICKS + 1;
        int interval = MIN_CHECK_INTERVAL_TICKS + villager.getRandom().nextInt(range);
        nextCheckTime = world.getTimeOfDay() + interval;
    }
}
