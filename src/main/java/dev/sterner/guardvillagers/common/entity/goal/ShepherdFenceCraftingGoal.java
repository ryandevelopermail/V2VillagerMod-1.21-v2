package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.util.VillagePenRegistry;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Makes the Shepherd craft fences and a fence gate when no pen exists near their job site.
 *
 * <p>Crafting phases:</p>
 * <ol>
 *   <li>Craft fences until chest has ≥ {@value #FENCE_TARGET} fences (any wood type).
 *       Recipe: 6 planks + 2 sticks → 3 fences.</li>
 *   <li>Once fence quota is met, craft 1 fence gate (2 planks + 2 sticks → 1 gate).</li>
 * </ol>
 *
 * <p>Stops when chest has ≥ {@value #FENCE_TARGET} fences AND ≥ 1 gate, which triggers
 * {@link ShepherdFencePlacerGoal} to take over and build the pen.</p>
 */
public class ShepherdFenceCraftingGoal extends AbstractCraftingGoal<ShepherdFenceCraftingGoal.FenceRecipe> {

    /** Fence count target for a 7×7 pen perimeter (28 fences + 1 gate gap). */
    static final int FENCE_TARGET = 28;
    /** One gate needed for the pen entrance. */
    private static final int GATE_TARGET = 1;

    /** Planks consumed per fence craft (yields 3 fences). */
    private static final int PLANKS_PER_FENCE_CRAFT = 6;
    /** Sticks consumed per fence craft (yields 3 fences). */
    private static final int STICKS_PER_FENCE_CRAFT = 2;
    /** Fences produced per craft cycle. */
    private static final int FENCES_PER_CRAFT = 3;

    /** Planks consumed per gate craft (yields 1 gate). */
    private static final int PLANKS_PER_GATE_CRAFT = 2;
    /** Sticks consumed per gate craft (yields 1 gate). */
    private static final int STICKS_PER_GATE_CRAFT = 2;

    /** Minimum sticks needed in chest before attempting fence crafting. */
    private static final int MIN_STICKS = 2;
    /** Minimum planks needed in chest before attempting fence crafting. */
    private static final int MIN_PLANKS_FOR_FENCE = 6;
    /** Minimum planks needed for gate crafting. */
    private static final int MIN_PLANKS_FOR_GATE = 2;

    /** Daily craft limit — allow enough cycles to reach FENCE_TARGET in one day. */
    private static final int DAILY_LIMIT = 16;

    /** Pen-scan radius for the no-pen check (bell radius first, job-site fallback). */
    private static final int PEN_SCAN_RADIUS = 300;

    public ShepherdFenceCraftingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    @Override
    protected boolean hasRequiredProfession() {
        return villager.getVillagerData().getProfession() == VillagerProfession.SHEPHERD;
    }

    @Override
    protected String getGoalName() {
        return "ShepherdFence";
    }

    @Override
    protected int getDailyCraftLimit(ServerWorld world) {
        return DAILY_LIMIT;
    }

    /**
     * Only run when:
     * <ul>
     *   <li>Overworld only</li>
     *   <li>No pen already exists near job site</li>
     *   <li>Fence or gate quota not yet met</li>
     *   <li>Ingredients available</li>
     * </ul>
     */
    @Override
    protected List<FenceRecipe> discoverRecipes(ServerWorld world, Inventory inventory) {
        // Only in the Overworld
        if (!world.getRegistryKey().equals(net.minecraft.world.World.OVERWORLD)) {
            return List.of();
        }

        // If a pen already exists, don't craft more materials
        VillagePenRegistry registry = VillagePenRegistry.get(world.getServer());
        if (registry.getNearestPen(world, jobPos, PEN_SCAN_RADIUS, PEN_SCAN_RADIUS).isPresent()) {
            return List.of();
        }

        int fencesInChest = countTag(inventory, ItemTags.FENCES);
        int gatesInChest = countTag(inventory, ItemTags.FENCE_GATES);

        List<FenceRecipe> recipes = new ArrayList<>();

        // Phase 1: craft fences until target reached
        if (fencesInChest < FENCE_TARGET) {
            if (hasFenceIngredients(inventory)) {
                recipes.add(FenceRecipe.FENCE);
            }
        }
        // Phase 2: craft gate once fence quota is met
        else if (gatesInChest < GATE_TARGET) {
            if (hasGateIngredients(inventory)) {
                recipes.add(FenceRecipe.GATE);
            }
        }

        return recipes;
    }

    @Override
    protected boolean canStillCraftRecipe(ServerWorld world, Inventory inventory, FenceRecipe recipe) {
        return recipe == FenceRecipe.FENCE ? hasFenceIngredients(inventory) : hasGateIngredients(inventory);
    }

    @Override
    protected boolean craftRecipe(ServerWorld world, Inventory inventory, FenceRecipe recipe) {
        if (recipe == FenceRecipe.FENCE) {
            if (!consumeMatching(inventory, stack -> stack.isIn(ItemTags.PLANKS), PLANKS_PER_FENCE_CRAFT)) return false;
            if (!consumeMatching(inventory, stack -> stack.isOf(Items.STICK), STICKS_PER_FENCE_CRAFT)) return false;
            // Output matching wood-type fences (use first plank type found)
            ItemStack fenceStack = new ItemStack(chooseFenceItem(inventory), FENCES_PER_CRAFT);
            insertStack(inventory, fenceStack);
        } else {
            if (!consumeMatching(inventory, stack -> stack.isIn(ItemTags.PLANKS), PLANKS_PER_GATE_CRAFT)) return false;
            if (!consumeMatching(inventory, stack -> stack.isOf(Items.STICK), STICKS_PER_GATE_CRAFT)) return false;
            ItemStack gateStack = new ItemStack(chooseGateItem(inventory), 1);
            insertStack(inventory, gateStack);
        }
        return true;
    }

    @Override
    protected ItemStack getRecipeOutput(FenceRecipe recipe) {
        return recipe == FenceRecipe.FENCE
                ? new ItemStack(Items.OAK_FENCE, FENCES_PER_CRAFT)
                : new ItemStack(Items.OAK_FENCE_GATE, 1);
    }

    @Override
    protected String formatCheckResult(int craftableCount) {
        return craftableCount == 0 ? "no fence recipes available" : craftableCount + " fence recipe(s) available";
    }

    @Override
    protected String formatCraftedResult(int craftableCount, ItemStack crafted) {
        return "crafted " + crafted.getCount() + "x " + crafted.getName().getString();
    }

    // -------------------------------------------------------------------------
    // Ingredient helpers
    // -------------------------------------------------------------------------

    private boolean hasFenceIngredients(Inventory inventory) {
        return countTag(inventory, ItemTags.PLANKS) >= MIN_PLANKS_FOR_FENCE
                && countMatching(inventory, stack -> stack.isOf(Items.STICK)) >= MIN_STICKS;
    }

    private boolean hasGateIngredients(Inventory inventory) {
        return countTag(inventory, ItemTags.PLANKS) >= MIN_PLANKS_FOR_GATE
                && countMatching(inventory, stack -> stack.isOf(Items.STICK)) >= STICKS_PER_GATE_CRAFT;
    }

    private int countTag(Inventory inventory, net.minecraft.registry.tag.TagKey<Item> tag) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && stack.isIn(tag)) total += stack.getCount();
        }
        return total;
    }

    private int countMatching(Inventory inventory, Predicate<ItemStack> matcher) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && matcher.test(stack)) total += stack.getCount();
        }
        return total;
    }

    private boolean consumeMatching(Inventory inventory, Predicate<ItemStack> matcher, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !matcher.test(stack)) continue;
            int removed = Math.min(remaining, stack.getCount());
            stack.decrement(removed);
            remaining -= removed;
            if (stack.isEmpty()) inventory.setStack(slot, ItemStack.EMPTY);
        }
        return remaining <= 0;
    }

    /**
     * Picks the fence item that matches the first plank type in the chest.
     * Falls back to oak fence if no plank-to-fence mapping is found.
     */
    private Item chooseFenceItem(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !stack.isIn(ItemTags.PLANKS)) continue;
            Item plank = stack.getItem();
            // Map planks → fences
            if (plank == Items.OAK_PLANKS)         return Items.OAK_FENCE;
            if (plank == Items.SPRUCE_PLANKS)       return Items.SPRUCE_FENCE;
            if (plank == Items.BIRCH_PLANKS)        return Items.BIRCH_FENCE;
            if (plank == Items.JUNGLE_PLANKS)       return Items.JUNGLE_FENCE;
            if (plank == Items.ACACIA_PLANKS)       return Items.ACACIA_FENCE;
            if (plank == Items.DARK_OAK_PLANKS)     return Items.DARK_OAK_FENCE;
            if (plank == Items.MANGROVE_PLANKS)     return Items.MANGROVE_FENCE;
            if (plank == Items.CHERRY_PLANKS)       return Items.CHERRY_FENCE;
            if (plank == Items.BAMBOO_PLANKS)       return Items.BAMBOO_FENCE;
            if (plank == Items.CRIMSON_PLANKS)      return Items.CRIMSON_FENCE;
            if (plank == Items.WARPED_PLANKS)       return Items.WARPED_FENCE;
        }
        return Items.OAK_FENCE;
    }

    /**
     * Picks the gate item matching the fence type in the chest (checks fences first, then planks).
     */
    private Item chooseGateItem(Inventory inventory) {
        // Check existing fences in chest to match the gate type
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !stack.isIn(ItemTags.FENCES)) continue;
            Item fence = stack.getItem();
            if (fence == Items.OAK_FENCE)       return Items.OAK_FENCE_GATE;
            if (fence == Items.SPRUCE_FENCE)     return Items.SPRUCE_FENCE_GATE;
            if (fence == Items.BIRCH_FENCE)      return Items.BIRCH_FENCE_GATE;
            if (fence == Items.JUNGLE_FENCE)     return Items.JUNGLE_FENCE_GATE;
            if (fence == Items.ACACIA_FENCE)     return Items.ACACIA_FENCE_GATE;
            if (fence == Items.DARK_OAK_FENCE)   return Items.DARK_OAK_FENCE_GATE;
            if (fence == Items.MANGROVE_FENCE)   return Items.MANGROVE_FENCE_GATE;
            if (fence == Items.CHERRY_FENCE)     return Items.CHERRY_FENCE_GATE;
            if (fence == Items.BAMBOO_FENCE)     return Items.BAMBOO_FENCE_GATE;
            if (fence == Items.CRIMSON_FENCE)    return Items.CRIMSON_FENCE_GATE;
            if (fence == Items.WARPED_FENCE)     return Items.WARPED_FENCE_GATE;
        }
        // Fall back to plank-based mapping
        return chooseFenceGateFromPlanks(inventory);
    }

    private Item chooseFenceGateFromPlanks(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !stack.isIn(ItemTags.PLANKS)) continue;
            Item plank = stack.getItem();
            if (plank == Items.OAK_PLANKS)         return Items.OAK_FENCE_GATE;
            if (plank == Items.SPRUCE_PLANKS)       return Items.SPRUCE_FENCE_GATE;
            if (plank == Items.BIRCH_PLANKS)        return Items.BIRCH_FENCE_GATE;
            if (plank == Items.JUNGLE_PLANKS)       return Items.JUNGLE_FENCE_GATE;
            if (plank == Items.ACACIA_PLANKS)       return Items.ACACIA_FENCE_GATE;
            if (plank == Items.DARK_OAK_PLANKS)     return Items.DARK_OAK_FENCE_GATE;
            if (plank == Items.MANGROVE_PLANKS)     return Items.MANGROVE_FENCE_GATE;
            if (plank == Items.CHERRY_PLANKS)       return Items.CHERRY_FENCE_GATE;
            if (plank == Items.BAMBOO_PLANKS)       return Items.BAMBOO_FENCE_GATE;
            if (plank == Items.CRIMSON_PLANKS)      return Items.CRIMSON_FENCE_GATE;
            if (plank == Items.WARPED_PLANKS)       return Items.WARPED_FENCE_GATE;
        }
        return Items.OAK_FENCE_GATE;
    }

    // -------------------------------------------------------------------------
    // Recipe enum
    // -------------------------------------------------------------------------

    public enum FenceRecipe {
        FENCE,
        GATE
    }
}
