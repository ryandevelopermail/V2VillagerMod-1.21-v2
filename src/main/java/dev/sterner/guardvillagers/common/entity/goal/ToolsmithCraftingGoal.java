package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.villager.CraftingCheckLogger;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.BrushItem;
import net.minecraft.item.FishingRodItem;
import net.minecraft.item.FlintAndSteelItem;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.MiningToolItem;
import net.minecraft.item.ShearsItem;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class ToolsmithCraftingGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolsmithCraftingGoal.class);
    private static final int SESSION_MIN_COUNT = 1;
    private static final int SESSION_MAX_COUNT = 5;
    private static final int SESSION_INTERVAL_MIN_TICKS = 20 * 60 * 6;
    private static final int SESSION_INTERVAL_MAX_TICKS = 20 * 60 * 10;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final double MOVE_SPEED = 0.6D;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private BlockPos craftingTablePos;
    private Stage stage = Stage.IDLE;
    private long nextSessionTriggerTime;
    private long sessionCountdownTotalTicks;
    private long sessionCountdownStartTime;
    private int lastCountdownLogStep;
    private boolean sessionCountdownActive;
    private int remainingSessionCrafts;
    private Item lastCraftedItem;

    public ToolsmithCraftingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        this.villager = villager;
        setTargets(jobPos, chestPos, craftingTablePos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.craftingTablePos = craftingTablePos.toImmutable();
        this.stage = Stage.IDLE;
    }

    public BlockPos getCraftingTablePos() {
        return craftingTablePos;
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
        if (craftingTablePos == null || chestPos == null) {
            return false;
        }
        if (!world.getBlockState(craftingTablePos).isOf(Blocks.CRAFTING_TABLE)) {
            return false;
        }
        updateSessionCountdown(world);
        return remainingSessionCrafts > 0;
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.DONE && villager.isAlive();
    }

    @Override
    public void start() {
        stage = Stage.GO_TO_TABLE;
        moveTo(craftingTablePos);
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
            case GO_TO_TABLE -> {
                if (isNear(craftingTablePos)) {
                    stage = Stage.CRAFT;
                } else {
                    moveTo(craftingTablePos);
                }
            }
            case CRAFT -> {
                if (craftNextTool(world)) {
                    remainingSessionCrafts--;
                } else {
                    remainingSessionCrafts = 0;
                }

                if (remainingSessionCrafts > 0) {
                    stage = Stage.CRAFT;
                } else {
                    stage = Stage.DONE;
                }
            }
            case IDLE, DONE -> {
            }
        }
    }

    private void updateSessionCountdown(ServerWorld world) {
        if (remainingSessionCrafts > 0) {
            return;
        }

        if (!sessionCountdownActive) {
            startSessionCountdown(world, "initial schedule");
        }

        if (sessionCountdownActive && nextSessionTriggerTime > 0L) {
            logSessionCountdownProgress(world);
        }

        if (sessionCountdownActive && nextSessionTriggerTime > 0L && world.getTime() >= nextSessionTriggerTime) {
            clearSessionCountdown();
            startCraftingSession(world);
        }
    }

    private void startCraftingSession(ServerWorld world) {
        remainingSessionCrafts = MathHelper.nextInt(villager.getRandom(), SESSION_MIN_COUNT, SESSION_MAX_COUNT);
        CraftingCheckLogger.report(world, "Toolsmith", "starting crafting session (" + remainingSessionCrafts + " tool(s))");
    }

    private void startSessionCountdown(ServerWorld world, String reason) {
        sessionCountdownTotalTicks = nextRandomSessionInterval(world);
        sessionCountdownStartTime = world.getTime();
        nextSessionTriggerTime = sessionCountdownStartTime + sessionCountdownTotalTicks;
        lastCountdownLogStep = 0;
        sessionCountdownActive = true;
        LOGGER.info("Toolsmith {} crafting countdown started ({} ticks) {}",
                villager.getUuidAsString(),
                sessionCountdownTotalTicks,
                reason);
    }

    private void logSessionCountdownProgress(ServerWorld world) {
        if (sessionCountdownTotalTicks <= 0L) {
            return;
        }
        long remainingTicks = nextSessionTriggerTime - world.getTime();
        long elapsedTicks = world.getTime() - sessionCountdownStartTime;
        int step = Math.min(4, (int) ((elapsedTicks * 4L) / sessionCountdownTotalTicks));
        if (step <= lastCountdownLogStep || step == 0) {
            return;
        }
        lastCountdownLogStep = step;
        int percent = step * 25;
        LOGGER.info("Toolsmith {} crafting countdown {}% ({} ticks remaining)",
                villager.getUuidAsString(),
                percent,
                Math.max(remainingTicks, 0L));
    }

    private long nextRandomSessionInterval(ServerWorld world) {
        return MathHelper.nextInt(world.random, SESSION_INTERVAL_MIN_TICKS, SESSION_INTERVAL_MAX_TICKS);
    }

    private void clearSessionCountdown() {
        nextSessionTriggerTime = 0L;
        sessionCountdownTotalTicks = 0L;
        sessionCountdownStartTime = 0L;
        lastCountdownLogStep = 0;
        sessionCountdownActive = false;
    }

    private boolean craftNextTool(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return false;
        }

        Optional<ToolRecipe> selected = selectCraftableRecipe(world, inventory);
        if (selected.isEmpty()) {
            CraftingCheckLogger.report(world, "Toolsmith", "no craftable tool recipes");
            return false;
        }

        ToolRecipe recipe = selected.get();
        if (!consumeIngredients(inventory, recipe.recipe)) {
            return false;
        }

        ItemStack crafted = recipe.output.copy();
        ItemStack remaining = insertStack(inventory, crafted);
        if (!remaining.isEmpty()) {
            ItemStack villagerRemaining = insertStack(villager.getInventory(), remaining);
            if (!villagerRemaining.isEmpty()) {
                villager.dropStack(villagerRemaining);
            }
            villager.getInventory().markDirty();
        }
        inventory.markDirty();
        lastCraftedItem = recipe.output.getItem();
        CraftingCheckLogger.report(world, "Toolsmith", "crafted " + crafted.getName().getString());
        return true;
    }

    private Optional<ToolRecipe> selectCraftableRecipe(ServerWorld world, Inventory inventory) {
        List<ToolRecipe> craftable = new ArrayList<>();
        for (RecipeEntry<CraftingRecipe> entry : world.getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = entry.value();
            ItemStack result = recipe.getResult(world.getRegistryManager());
            if (result.isEmpty()) {
                continue;
            }
            if (!isToolItem(result.getItem())) {
                continue;
            }
            if (canCraft(inventory, recipe)) {
                craftable.add(new ToolRecipe(recipe, result));
            }
        }

        if (craftable.isEmpty()) {
            return Optional.empty();
        }

        List<ToolRecipe> available = new ArrayList<>();
        for (ToolRecipe recipe : craftable) {
            if (lastCraftedItem == null || recipe.output.getItem() != lastCraftedItem) {
                available.add(recipe);
            }
        }

        List<ToolRecipe> selectionPool = available.isEmpty() ? craftable : available;
        ToolRecipe choice = selectionPool.get(MathHelper.nextInt(world.random, 0, selectionPool.size() - 1));
        return Optional.of(choice);
    }

    private boolean isToolItem(Item item) {
        return item instanceof MiningToolItem
                || item instanceof HoeItem
                || item instanceof ShearsItem
                || item instanceof FishingRodItem
                || item instanceof FlintAndSteelItem
                || item instanceof BrushItem;
    }

    private boolean canCraft(Inventory inventory, CraftingRecipe recipe) {
        List<ItemStack> available = new ArrayList<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty()) {
                available.add(stack.copy());
            }
        }

        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            int matchIndex = findMatchingStack(available, ingredient);
            if (matchIndex < 0) {
                return false;
            }
            ItemStack matched = available.get(matchIndex);
            matched.decrement(1);
            if (matched.isEmpty()) {
                available.remove(matchIndex);
            }
        }

        return true;
    }

    private int findMatchingStack(List<ItemStack> available, Ingredient ingredient) {
        for (int i = 0; i < available.size(); i++) {
            if (ingredient.test(available.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean consumeIngredients(Inventory inventory, CraftingRecipe recipe) {
        if (!canCraft(inventory, recipe)) {
            return false;
        }

        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            for (int slot = 0; slot < inventory.size(); slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (stack.isEmpty() || !ingredient.test(stack)) {
                    continue;
                }
                stack.decrement(1);
                if (stack.isEmpty()) {
                    inventory.setStack(slot, ItemStack.EMPTY);
                }
                break;
            }
        }

        return true;
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

    private enum Stage {
        IDLE,
        GO_TO_TABLE,
        CRAFT,
        DONE
    }

    private record ToolRecipe(CraftingRecipe recipe, ItemStack output) {
    }
}
