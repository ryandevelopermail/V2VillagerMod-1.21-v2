package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.villager.CraftingCheckLogger;
import dev.sterner.guardvillagers.common.util.ArmorerStandManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ArmorerCraftingGoal extends Goal {
    private static final int CHECK_INTERVAL_TICKS = CraftingCheckLogger.MATERIAL_CHECK_INTERVAL_TICKS;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final double MOVE_SPEED = 0.6D;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private BlockPos craftingTablePos;
    private BlockPos standTargetPos;
    private ItemStack pendingStandItem = ItemStack.EMPTY;
    private EquipmentSlot pendingSlot;
    private UUID pendingStandId;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;
    private long lastCraftDay = -1L;
    private int dailyCraftLimit;
    private int craftedToday;
    private int lastCheckCount;
    private boolean immediateCheckPending;
    private RegistryEntry<net.minecraft.item.ArmorMaterial> plannedMaterial;
    private final List<EquipmentSlot> remainingSlots = new ArrayList<>();

    public ArmorerCraftingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
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
        if (villager.getVillagerData().getProfession() != VillagerProfession.ARMORER) {
            return false;
        }
        if (craftingTablePos == null || chestPos == null) {
            return false;
        }
        if (!world.getBlockState(craftingTablePos).isOf(Blocks.CRAFTING_TABLE)) {
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
        CraftingCheckLogger.report(villager, "Armorer", immediateCheckPending ? "immediate request" : "natural interval", formatCheckResult(lastCheckCount));
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
        if (villager.getWorld() instanceof ServerWorld world) {
            initializeCraftingSession(world);
        }
        stage = Stage.GO_TO_TABLE;
        moveTo(craftingTablePos);
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        clearCraftingSession();
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
                craftSet(world);
                stage = pendingStandItem.isEmpty() ? Stage.DONE : Stage.GO_TO_STAND;
            }
            case GO_TO_STAND -> {
                if (pendingStandItem.isEmpty()) {
                    stage = Stage.DONE;
                    return;
                }
                ArmorStandEntity stand = resolveTargetStand(world);
                if (stand == null || !ArmorerStandManager.isStandAvailableForSlot(villager, stand, pendingSlot)) {
                    if (!selectNextStand(world)) {
                        returnPendingItem(world);
                        stage = Stage.DONE;
                        return;
                    }
                    moveTo(standTargetPos);
                    return;
                }
                if (isNear(standTargetPos)) {
                    stage = Stage.PLACE_ON_STAND;
                } else {
                    moveTo(standTargetPos);
                }
            }
            case PLACE_ON_STAND -> {
                if (pendingStandItem.isEmpty()) {
                    stage = Stage.DONE;
                    return;
                }
                ArmorStandEntity stand = resolveTargetStand(world);
                if (stand == null || !ArmorerStandManager.isStandAvailableForSlot(villager, stand, pendingSlot)) {
                    if (!selectNextStand(world)) {
                        returnPendingItem(world);
                        stage = Stage.DONE;
                        return;
                    }
                    stage = Stage.GO_TO_STAND;
                    return;
                }
                if (ArmorerStandManager.placeArmorOnStand(world, villager, stand, pendingStandItem)) {
                    clearPendingStand();
                    if (hasRemainingSetPieces() && craftedToday < dailyCraftLimit) {
                        stage = Stage.GO_TO_TABLE;
                    } else {
                        clearCraftingSession();
                        stage = Stage.DONE;
                    }
                    return;
                }
                if (selectNextStand(world)) {
                    stage = Stage.GO_TO_STAND;
                    return;
                }
                returnPendingItem(world);
                clearCraftingSession();
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

    private void craftSet(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            clearCraftingSession();
            return;
        }

        if (!initializeCraftingSession(world)) {
            return;
        }

        while (hasRemainingSetPieces()) {
            if (craftedToday >= dailyCraftLimit) {
                clearCraftingSession();
                return;
            }

            EquipmentSlot slot = remainingSlots.get(0);
            ArmorRecipe recipe = findCraftableRecipeForSlot(world, inventory, plannedMaterial, slot);
            if (recipe == null || !consumeIngredients(inventory, recipe.recipe)) {
                clearCraftingSession();
                return;
            }

            ItemStack crafted = recipe.output.copy();
            if (crafted.getItem() instanceof ArmorItem armorItem) {
                Optional<ArmorStandEntity> stand = ArmorerStandManager.findPlacementStand(world, villager, craftingTablePos, armorItem.getSlotType());
                if (stand.isPresent()) {
                    pendingStandItem = crafted.copy();
                    pendingStandItem.setCount(1);
                    pendingSlot = armorItem.getSlotType();
                    pendingStandId = stand.get().getUuid();
                    standTargetPos = stand.get().getBlockPos();
                    crafted.decrement(1);
                }
            }
            ItemStack remaining = insertStack(inventory, crafted);
            if (!remaining.isEmpty()) {
                ItemStack villagerRemaining = insertStack(villager.getInventory(), remaining);
                if (!villagerRemaining.isEmpty()) {
                    villager.dropStack(villagerRemaining);
                }
                villager.getInventory().markDirty();
            }
            inventory.markDirty();
            craftedToday++;
            remainingSlots.remove(0);
            CraftingCheckLogger.report(villager, "Armorer", formatCraftedResult(lastCheckCount, recipe.output));

            if (!pendingStandItem.isEmpty()) {
                return;
            }
        }

        clearCraftingSession();
    }

    private List<ArmorRecipe> getCraftableRecipes(ServerWorld world, Inventory inventory) {
        List<ArmorRecipe> recipes = new ArrayList<>();
        for (RecipeEntry<CraftingRecipe> entry : world.getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = entry.value();
            ItemStack result = recipe.getResult(world.getRegistryManager());
            if (result.isEmpty() || !(result.getItem() instanceof ArmorItem)) {
                continue;
            }
            if (canCraft(inventory, recipe)) {
                recipes.add(new ArmorRecipe(recipe, result));
            }
        }
        return recipes;
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
        GO_TO_STAND,
        PLACE_ON_STAND,
        DONE
    }

    private record ArmorRecipe(CraftingRecipe recipe, ItemStack output) {
    }

    private String formatCheckResult(int craftableCount) {
        if (craftableCount == 1) {
            return "1 armor item available to craft";
        }
        return craftableCount + " armor items available to craft";
    }

    private String formatCraftedResult(int craftableCount, ItemStack crafted) {
        String craftedName = crafted.getName().getString();
        if (craftableCount == 1) {
            return "1 armor item available to craft - 1 " + craftedName + " crafted";
        }
        return craftableCount + " armor items available to craft - 1 " + craftedName + " crafted";
    }

    private void clearPendingStand() {
        pendingStandItem = ItemStack.EMPTY;
        pendingStandId = null;
        standTargetPos = null;
        pendingSlot = null;
    }

    private ArmorStandEntity resolveTargetStand(ServerWorld world) {
        if (pendingStandId == null) {
            return null;
        }
        return world.getEntity(pendingStandId) instanceof ArmorStandEntity stand ? stand : null;
    }

    private boolean selectNextStand(ServerWorld world) {
        Optional<ArmorStandEntity> stand = ArmorerStandManager.findPlacementStand(world, villager, craftingTablePos, pendingSlot);
        if (stand.isEmpty()) {
            return false;
        }
        pendingStandId = stand.get().getUuid();
        standTargetPos = stand.get().getBlockPos();
        return true;
    }

    private void returnPendingItem(ServerWorld world) {
        if (pendingStandItem.isEmpty()) {
            return;
        }
        ItemStack remaining = insertStack(getChestInventory(world).orElse(villager.getInventory()), pendingStandItem);
        if (!remaining.isEmpty()) {
            ItemStack villagerRemaining = insertStack(villager.getInventory(), remaining);
            if (!villagerRemaining.isEmpty()) {
                villager.dropStack(villagerRemaining);
            }
            villager.getInventory().markDirty();
        }
        clearPendingStand();
    }

    private boolean initializeCraftingSession(ServerWorld world) {
        if (plannedMaterial != null && !remainingSlots.isEmpty()) {
            return true;
        }
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            clearCraftingSession();
            return false;
        }

        List<ArmorRecipe> craftable = getCraftableRecipes(world, inventory);
        if (craftable.isEmpty()) {
            clearCraftingSession();
            return false;
        }

        ItemStack result = craftable.get(0).output;
        if (!(result.getItem() instanceof ArmorItem armorItem)) {
            clearCraftingSession();
            return false;
        }

        plannedMaterial = armorItem.getMaterial();
        remainingSlots.clear();
        remainingSlots.add(EquipmentSlot.HEAD);
        remainingSlots.add(EquipmentSlot.CHEST);
        remainingSlots.add(EquipmentSlot.LEGS);
        remainingSlots.add(EquipmentSlot.FEET);
        return true;
    }

    private ArmorRecipe findCraftableRecipeForSlot(ServerWorld world, Inventory inventory, RegistryEntry<net.minecraft.item.ArmorMaterial> material, EquipmentSlot slot) {
        for (RecipeEntry<CraftingRecipe> entry : world.getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = entry.value();
            ItemStack result = recipe.getResult(world.getRegistryManager());
            if (result.isEmpty() || !(result.getItem() instanceof ArmorItem armorItem)) {
                continue;
            }
            if (!armorItem.getMaterial().equals(material) || armorItem.getSlotType() != slot) {
                continue;
            }
            if (canCraft(inventory, recipe)) {
                return new ArmorRecipe(recipe, result);
            }
        }
        return null;
    }

    private boolean hasRemainingSetPieces() {
        return !remainingSlots.isEmpty();
    }

    private void clearCraftingSession() {
        plannedMaterial = null;
        remainingSlots.clear();
    }
}
