package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.DistributionRecipientHelper;
import dev.sterner.guardvillagers.common.util.ToolsmithCraftingMemoryHolder;
import dev.sterner.guardvillagers.common.villager.CraftingCheckLogger;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.ShearsItem;
import net.minecraft.item.ShovelItem;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class ToolsmithCraftingGoal extends Goal {
    private static final int CHECK_INTERVAL_TICKS = CraftingCheckLogger.MATERIAL_CHECK_INTERVAL_TICKS;
    private static final int NON_TABLE_GRID_SIZE = 2;
    private static final int SHEARS_PER_SHEPHERD_CHEST = 1;
    private static final int RODS_PER_FISHERMAN_STORAGE = 1;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final double MOVE_SPEED = 0.6D;
    private static final int PATH_RETRY_INTERVAL_TICKS = 20;
    private static final int TOOLS_PER_MASON_CHEST = 1;
    private static final int TOOLS_PER_FARMER_CHEST = 1;

    private final VillagerEntity villager;
    private BlockPos currentNavigationTarget;
    private long lastPathRequestTick = Long.MIN_VALUE;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private @Nullable BlockPos craftingTablePos;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;
    private long lastCraftDay = -1L;
    private int dailyCraftLimit;
    private int craftedToday;
    private int lastCheckCount;
    private boolean immediateCheckPending;

    public ToolsmithCraftingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, @Nullable BlockPos craftingTablePos) {
        this.villager = villager;
        setTargets(jobPos, chestPos, craftingTablePos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos, @Nullable BlockPos craftingTablePos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.craftingTablePos = craftingTablePos == null ? null : craftingTablePos.toImmutable();
        this.stage = Stage.IDLE;
    }

    public @Nullable BlockPos getCraftingTablePos() {
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
        if (chestPos == null) {
            return false;
        }
        boolean hasCraftingTable = hasValidCraftingTable(world);
        if (!hasCraftingTable && !hasNonTableCraftableRecipe(world)) {
            return false;
        }
        refreshDailyLimit(world);
        if (craftedToday >= dailyCraftLimit) {
            return false;
        }

        if (!immediateCheckPending && world.getTime() < nextCheckTime) {
            return false;
        }

        lastCheckCount = countCraftableRecipes(world);
        CraftingCheckLogger.report(world, "Toolsmith", immediateCheckPending ? "immediate request" : "natural interval", formatCheckResult(lastCheckCount));
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
        if (hasValidCraftingTable(villager.getWorld() instanceof ServerWorld serverWorld ? serverWorld : null)) {
            stage = Stage.GO_TO_TABLE;
            moveTo(craftingTablePos);
            return;
        }
        stage = Stage.CRAFT;
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        currentNavigationTarget = null;
        lastPathRequestTick = Long.MIN_VALUE;
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
                craftOnce(world);
                stage = Stage.DONE;
            }
            case IDLE, DONE -> {
            }
        }
    }

    private void refreshDailyLimit(ServerWorld world) {
        long day = world.getTimeOfDay() / 24000L;
        if (day != lastCraftDay) {
            lastCraftDay = day;
            dailyCraftLimit = 4;
            craftedToday = 0;
            immediateCheckPending = false;
        }
    }

    public void requestImmediateCraft(ServerWorld world) {
        refreshDailyLimit(world);
        immediateCheckPending = true;
        nextCheckTime = 0L;
    }

    private int countCraftableRecipes(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return 0;
        }
        return getCraftableRecipes(world, inventory).size();
    }

    private void craftOnce(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return;
        }

        List<ToolRecipe> craftable = getCraftableRecipes(world, inventory);
        if (craftable.isEmpty()) {
            return;
        }

        ToolRecipe recipe = craftable.getFirst();
        if (!canInsertOutput(inventory, recipe.output)) {
            return;
        }
        if (consumeIngredients(inventory, recipe.recipe)) {
            insertStack(inventory, recipe.output.copy());
            inventory.markDirty();
            craftedToday++;
            recordLastCrafted(recipe.output);
            CraftingCheckLogger.report(world, "Toolsmith", formatCraftedResult(lastCheckCount, recipe.output));
            if (recipe.output.isOf(Items.FISHING_ROD)) {
                CraftingCheckLogger.report(world, "Toolsmith", "crafted fishing rod due to fisherman recipient demand");
            }
        }
    }

    private List<ToolRecipe> getCraftableRecipes(ServerWorld world, Inventory inventory) {
        List<ToolRecipe> recipes = new ArrayList<>();
        ToolDemand demand = calculateToolDemand(world, inventory);
        for (RecipeEntry<CraftingRecipe> entry : world.getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = entry.value();
            ItemStack result = recipe.getResult(world.getRegistryManager());
            if (result.isEmpty() || !isToolItem(result)) {
                continue;
            }
            if (!canUseRecipeWithoutCraftingTable(recipe) && !hasValidCraftingTable(world)) {
                continue;
            }
            ToolType toolType = ToolType.fromOutput(result);
            if (toolType == null) {
                continue;
            }
            int deficit = demand.deficitFor(toolType);
            if (toolType == ToolType.SHEARS && deficit <= 0) {
                continue;
            }
            if (toolType == ToolType.FISHING_ROD && deficit <= 0) {
                CraftingCheckLogger.report(world, "Toolsmith", "skipped fishing rod craft: stock cap reached (" + demand.fishingRodStock + "/" + demand.eligibleFishermanCount + ")");
                continue;
            }
            if (canCraft(inventory, recipe)) {
                recipes.add(new ToolRecipe(recipe, result, toolType, deficit, toolType.fallbackPriority));
            }
        }
        recipes.sort(Comparator
                .comparingInt(ToolRecipe::isPositiveDemand).reversed()
                .thenComparing(Comparator.comparingInt(ToolRecipe::demandDeficit).reversed())
                .thenComparingInt(ToolRecipe::tieBreakWeight));
        return filterLastCrafted(recipes);
    }

    private ToolDemand calculateToolDemand(ServerWorld world, Inventory inventory) {
        int eligibleFishermanCount = DistributionRecipientHelper.findEligibleFishermanRecipients(world, villager, 24.0D).size();
        int eligibleMasonCount = DistributionRecipientHelper.findEligibleMasonRecipients(world, villager, 24.0D).size();
        int eligibleFarmerCount = DistributionRecipientHelper.findEligibleFarmerRecipients(world, villager, 24.0D).size();
        int eligibleShepherdCount = DistributionRecipientHelper.findEligibleShepherdRecipients(world, villager, 24.0D).size();

        int fishingRodStock = countItemInInventory(inventory, Items.FISHING_ROD);
        int pickaxeStock = countItemInInventory(inventory, PickaxeItem.class);
        int hoeStock = countItemInInventory(inventory, HoeItem.class);
        int shearsStock = countItemInInventory(inventory, ShearsItem.class);

        return new ToolDemand(
                fishingRodStock,
                eligibleFishermanCount,
                eligibleFishermanCount - (fishingRodStock / RODS_PER_FISHERMAN_STORAGE),
                eligibleMasonCount - (pickaxeStock / TOOLS_PER_MASON_CHEST),
                eligibleFarmerCount - (hoeStock / TOOLS_PER_FARMER_CHEST),
                eligibleShepherdCount - (shearsStock / SHEARS_PER_SHEPHERD_CHEST)
        );
    }

    private boolean isToolItem(ItemStack stack) {
        return stack.getItem() instanceof PickaxeItem
                || stack.getItem() instanceof ShovelItem
                || stack.getItem() instanceof HoeItem
                || stack.getItem() instanceof ShearsItem
                || stack.isOf(Items.FISHING_ROD);
    }

    private int countItemInInventory(Inventory inventory, Item item) {
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countItemInInventory(Inventory inventory, Class<?> itemType) {
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (itemType.isInstance(stack.getItem())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean hasNonTableCraftableRecipe(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return false;
        }
        for (RecipeEntry<CraftingRecipe> entry : world.getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = entry.value();
            ItemStack result = recipe.getResult(world.getRegistryManager());
            if (result.isEmpty() || !isToolItem(result)) {
                continue;
            }
            if (!canUseRecipeWithoutCraftingTable(recipe)) {
                continue;
            }
            if (canCraft(inventory, recipe)) {
                return true;
            }
        }
        return false;
    }

    private boolean canUseRecipeWithoutCraftingTable(CraftingRecipe recipe) {
        return recipe.fits(NON_TABLE_GRID_SIZE, NON_TABLE_GRID_SIZE);
    }

    private boolean hasValidCraftingTable(@Nullable ServerWorld world) {
        if (world == null || craftingTablePos == null) {
            return false;
        }
        return world.getBlockState(craftingTablePos).isOf(Blocks.CRAFTING_TABLE);
    }

    private List<ToolRecipe> filterLastCrafted(List<ToolRecipe> recipes) {
        Identifier lastCrafted = getLastCraftedId();
        if (lastCrafted == null || recipes.size() <= 1) {
            return recipes;
        }
        List<ToolRecipe> filtered = new ArrayList<>();
        for (ToolRecipe recipe : recipes) {
            Identifier resultId = Registries.ITEM.getId(recipe.output.getItem());
            if (!lastCrafted.equals(resultId)) {
                filtered.add(recipe);
            }
        }
        return filtered.isEmpty() ? recipes : filtered;
    }

    private Identifier getLastCraftedId() {
        if (villager instanceof ToolsmithCraftingMemoryHolder holder) {
            return holder.guardvillagers$getLastToolsmithCrafted();
        }
        return null;
    }

    private void recordLastCrafted(ItemStack stack) {
        if (villager instanceof ToolsmithCraftingMemoryHolder holder) {
            holder.guardvillagers$setLastToolsmithCrafted(Registries.ITEM.getId(stack.getItem()));
        }
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
        moveTo(target, MOVE_SPEED);
    }

    private void moveTo(BlockPos target, double speed) {
        if (target == null) {
            return;
        }

        long currentTick = villager.getWorld().getTime();
        boolean shouldRequestPath = !target.equals(currentNavigationTarget)
                || villager.getNavigation().isIdle()
                || currentTick - lastPathRequestTick >= PATH_RETRY_INTERVAL_TICKS;
        if (!shouldRequestPath) {
            return;
        }

        villager.getNavigation().startMovingTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, speed);
        currentNavigationTarget = target.toImmutable();
        lastPathRequestTick = currentTick;
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

    private boolean canInsertOutput(Inventory inventory, ItemStack stack) {
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

    private enum Stage {
        IDLE,
        GO_TO_TABLE,
        CRAFT,
        DONE
    }

    private record ToolRecipe(CraftingRecipe recipe, ItemStack output, ToolType toolType, int demandDeficit, int tieBreakWeight) {
        int isPositiveDemand() {
            return demandDeficit > 0 ? 1 : 0;
        }
    }

    private record ToolDemand(
            int fishingRodStock,
            int eligibleFishermanCount,
            int fishingRodDeficit,
            int pickaxeDeficit,
            int hoeDeficit,
            int shearsDeficit
    ) {
        int deficitFor(ToolType toolType) {
            return switch (toolType) {
                case PICKAXE -> pickaxeDeficit;
                case FISHING_ROD -> fishingRodDeficit;
                case HOE -> hoeDeficit;
                case SHEARS -> shearsDeficit;
                case SHOVEL -> 0;
            };
        }
    }

    private enum ToolType {
        PICKAXE(0),
        FISHING_ROD(1),
        HOE(2),
        SHEARS(3),
        SHOVEL(4);

        private final int fallbackPriority;

        ToolType(int fallbackPriority) {
            this.fallbackPriority = fallbackPriority;
        }

        @Nullable
        static ToolType fromOutput(ItemStack output) {
            if (output.getItem() instanceof PickaxeItem) {
                return PICKAXE;
            }
            if (output.isOf(Items.FISHING_ROD)) {
                return FISHING_ROD;
            }
            if (output.getItem() instanceof HoeItem) {
                return HOE;
            }
            if (output.getItem() instanceof ShearsItem) {
                return SHEARS;
            }
            if (output.getItem() instanceof ShovelItem) {
                return SHOVEL;
            }
            return null;
        }
    }

    private String formatCheckResult(int craftableCount) {
        if (craftableCount == 1) {
            return "1 tool available to craft";
        }
        return craftableCount + " tools available to craft";
    }

    private String formatCraftedResult(int craftableCount, ItemStack crafted) {
        String craftedName = crafted.getName().getString();
        if (craftableCount == 1) {
            return "1 tool available to craft - 1 " + craftedName + " crafted";
        }
        return craftableCount + " tools available to craft - 1 " + craftedName + " crafted";
    }
}
