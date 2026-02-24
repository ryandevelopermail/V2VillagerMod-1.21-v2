package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.villager.CraftingCheckLogger;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.SmithingRecipe;
import net.minecraft.recipe.input.SmithingRecipeInput;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Comparator;
import java.util.Optional;

/**
 * Toolsmith smithing supports both smithing-transform (e.g. netherite upgrades)
 * and smithing-trim recipes as long as the base and output are armor pieces.
 */
public class ToolsmithSmithingGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolsmithSmithingGoal.class);
    private static final int CHECK_INTERVAL_TICKS = CraftingCheckLogger.MATERIAL_CHECK_INTERVAL_TICKS;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final double MOVE_SPEED = 0.6D;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;
    private long lastSmithDay = -1L;
    private int dailySmithLimit;
    private int smithedToday;
    private int lastCheckCount;
    private boolean immediateCheckPending;

    public ToolsmithSmithingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        setTargets(jobPos, chestPos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.stage = Stage.IDLE;
    }

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!world.isDay()) {
            return false;
        }
        if (villager.getVillagerData().getProfession() != VillagerProfession.TOOLSMITH) {
            return false;
        }
        if (!ProfessionDefinitions.isExpectedJobBlock(VillagerProfession.TOOLSMITH, world.getBlockState(jobPos))) {
            return false;
        }

        refreshDailyLimit(world);
        if (smithedToday >= dailySmithLimit) {
            return false;
        }

        if (!immediateCheckPending && world.getTime() < nextCheckTime) {
            return false;
        }

        lastCheckCount = countSmithableRecipes(world);
        CraftingCheckLogger.report(world, "Toolsmith", immediateCheckPending ? "immediate smithing request" : "natural smithing interval", formatCheckResult(lastCheckCount));
        nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
        immediateCheckPending = false;
        return lastCheckCount > 0;
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.DONE && villager.isAlive();
    }

    @Override
    public void start() {
        stage = Stage.GO_TO_SMITHING_TABLE;
        moveTo(jobPos);
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
            case GO_TO_SMITHING_TABLE -> {
                if (isNear(jobPos)) {
                    stage = Stage.SMITH;
                } else {
                    moveTo(jobPos);
                }
            }
            case SMITH -> {
                smithOnce(world);
                stage = Stage.DONE;
            }
            case IDLE, DONE -> {
            }
        }
    }

    public void requestImmediateSmithing(ServerWorld world) {
        refreshDailyLimit(world);
        immediateCheckPending = true;
        nextCheckTime = 0L;
    }

    private void refreshDailyLimit(ServerWorld world) {
        long day = world.getTimeOfDay() / 24000L;
        if (day != lastSmithDay) {
            lastSmithDay = day;
            dailySmithLimit = 4;
            smithedToday = 0;
            immediateCheckPending = false;
        }
    }

    private int countSmithableRecipes(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return 0;
        }

        int matches = 0;
        for (RecipeEntry<SmithingRecipe> entry : getSmithingRecipes(world)) {
            if (findMatchForRecipe(world, inventory, entry) != null) {
                matches++;
            }
        }
        return matches;
    }

    private void smithOnce(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            LOGGER.debug("Toolsmith {} cannot smith because paired chest is unavailable", villager.getUuidAsString());
            return;
        }

        SmithingPlan plan = findFirstValidPlan(world, inventory);
        if (plan == null) {
            LOGGER.debug("Toolsmith {} found no smithing plan", villager.getUuidAsString());
            return;
        }

        if (!canReinsertResult(inventory, plan)) {
            LOGGER.info("Toolsmith {} skipped smithing {} because output could not be reinserted",
                    villager.getUuidAsString(),
                    plan.output.getName().getString());
            return;
        }

        if (!consumeAndReplace(inventory, plan)) {
            LOGGER.info("Toolsmith {} failed to apply smithing recipe {} due to inventory mutation",
                    villager.getUuidAsString(),
                    plan.recipeEntry.id());
            return;
        }

        inventory.markDirty();
        smithedToday++;
        CraftingCheckLogger.report(world, "Toolsmith", formatSmithResult(lastCheckCount, plan.output));
        LOGGER.info("Toolsmith {} smithed {} via {}",
                villager.getUuidAsString(),
                plan.output.getName().getString(),
                plan.recipeEntry.id());
    }

    private SmithingPlan findFirstValidPlan(ServerWorld world, Inventory inventory) {
        for (RecipeEntry<SmithingRecipe> entry : getSmithingRecipes(world)) {
            SmithingPlan match = findMatchForRecipe(world, inventory, entry);
            if (match != null) {
                return match;
            }
        }
        return null;
    }


    private List<RecipeEntry<SmithingRecipe>> getSmithingRecipes(ServerWorld world) {
        List<RecipeEntry<SmithingRecipe>> entries = new ArrayList<>(world.getRecipeManager().listAllOfType(RecipeType.SMITHING));
        entries.sort(Comparator.comparing(entry -> entry.id().toString()));
        return entries;
    }

    private SmithingPlan findMatchForRecipe(ServerWorld world, Inventory inventory, RecipeEntry<SmithingRecipe> entry) {
        SmithingRecipe recipe = entry.value();
        List<Integer> nonEmptySlots = collectNonEmptySlots(inventory);

        for (int armorSlot : nonEmptySlots) {
            ItemStack armor = inventory.getStack(armorSlot);
            if (!(armor.getItem() instanceof ArmorItem)) {
                continue;
            }
            for (int templateSlot : nonEmptySlots) {
                if (templateSlot == armorSlot) {
                    continue;
                }
                for (int materialSlot : nonEmptySlots) {
                    if (materialSlot == armorSlot || materialSlot == templateSlot) {
                        continue;
                    }

                    SmithingRecipeInput input = new SmithingRecipeInput(
                            inventory.getStack(templateSlot).copyWithCount(1),
                            armor.copyWithCount(1),
                            inventory.getStack(materialSlot).copyWithCount(1)
                    );

                    if (!recipe.matches(input, world)) {
                        continue;
                    }

                    ItemStack output = recipe.craft(input, world.getRegistryManager());
                    if (output.isEmpty() || !(output.getItem() instanceof ArmorItem)) {
                        continue;
                    }

                    return new SmithingPlan(entry, templateSlot, armorSlot, materialSlot, output);
                }
            }
        }

        return null;
    }

    private List<Integer> collectNonEmptySlots(Inventory inventory) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (!inventory.getStack(slot).isEmpty()) {
                slots.add(slot);
            }
        }
        return slots;
    }

    private boolean consumeAndReplace(Inventory inventory, SmithingPlan plan) {
        if (inventory.getStack(plan.templateSlot).isEmpty()
                || inventory.getStack(plan.baseArmorSlot).isEmpty()
                || inventory.getStack(plan.materialSlot).isEmpty()) {
            return false;
        }

        decrementSlot(inventory, plan.templateSlot);
        decrementSlot(inventory, plan.materialSlot);
        decrementSlot(inventory, plan.baseArmorSlot);

        ItemStack remaining = insertStack(inventory, plan.output.copy());
        if (!remaining.isEmpty()) {
            LOGGER.warn("Toolsmith {} could not fully reinsert smithing output {} after consume; restoring best effort",
                    villager.getUuidAsString(),
                    plan.output.getName().getString());
            insertStack(inventory, remaining);
            return false;
        }

        return true;
    }

    private void decrementSlot(Inventory inventory, int slot) {
        ItemStack stack = inventory.getStack(slot);
        stack.decrement(1);
        if (stack.isEmpty()) {
            inventory.setStack(slot, ItemStack.EMPTY);
        }
    }

    private boolean canReinsertResult(Inventory inventory, SmithingPlan plan) {
        List<ItemStack> simulated = new ArrayList<>(inventory.size());
        for (int slot = 0; slot < inventory.size(); slot++) {
            simulated.add(inventory.getStack(slot).copy());
        }

        if (!simulateDecrement(simulated, plan.templateSlot)
                || !simulateDecrement(simulated, plan.materialSlot)
                || !simulateDecrement(simulated, plan.baseArmorSlot)) {
            return false;
        }

        ItemStack remaining = plan.output.copy();
        for (int slot = 0; slot < simulated.size(); slot++) {
            if (remaining.isEmpty()) {
                return true;
            }

            ItemStack existing = simulated.get(slot);
            if (existing.isEmpty()) {
                if (!inventory.isValid(slot, remaining)) {
                    continue;
                }
                int moved = Math.min(remaining.getCount(), remaining.getMaxCount());
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
            remaining.decrement(moved);
        }

        return remaining.isEmpty();
    }

    private boolean simulateDecrement(List<ItemStack> simulated, int slot) {
        ItemStack stack = simulated.get(slot);
        if (stack.isEmpty()) {
            return false;
        }
        stack.decrement(1);
        if (stack.isEmpty()) {
            simulated.set(slot, ItemStack.EMPTY);
        }
        return true;
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

    private Optional<Inventory> getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        Inventory inventory = ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
        return Optional.ofNullable(inventory);
    }

    private void moveTo(BlockPos target) {
        villager.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
    }

    private boolean isNear(BlockPos target) {
        return villager.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private enum Stage {
        IDLE,
        GO_TO_SMITHING_TABLE,
        SMITH,
        DONE
    }

    private record SmithingPlan(
            RecipeEntry<SmithingRecipe> recipeEntry,
            int templateSlot,
            int baseArmorSlot,
            int materialSlot,
            ItemStack output
    ) {
    }

    private String formatCheckResult(int smithableCount) {
        if (smithableCount == 1) {
            return "1 armor smithing recipe available";
        }
        return smithableCount + " armor smithing recipes available";
    }

    private String formatSmithResult(int smithableCount, ItemStack output) {
        String outputName = output.getName().getString();
        if (smithableCount == 1) {
            return "1 armor smithing recipe available - 1 " + outputName + " smithed";
        }
        return smithableCount + " armor smithing recipes available - 1 " + outputName + " smithed";
    }
}
