package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Makes the Shepherd craft colored beds when villagers nearby lack a sleeping spot.
 *
 * <p>Recipe: 3 planks (any) + 3 matching-color wool → 1 colored bed.</p>
 * <p>Only activates when at least one nearby villager has no claimed sleeping position.</p>
 */
public class ShepherdBedCraftingGoal extends AbstractCraftingGoal<ShepherdBedCraftingGoal.BedRecipe> {

    /** How many beds ahead of current bedless count we are willing to stockpile. */
    private static final int BED_SURPLUS_BUFFER = 1;
    /** Scan range to count bedless villagers. */
    private static final double BEDLESS_SCAN_RANGE = 64.0D;
    /** Max beds to craft per day. */
    private static final int DAILY_LIMIT = 2;
    /** Min planks in chest before we consider crafting. */
    private static final int MIN_PLANKS = 3;

    public ShepherdBedCraftingGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        super(villager, jobPos, chestPos, craftingTablePos);
    }

    @Override
    protected boolean hasRequiredProfession() {
        return villager.getVillagerData().getProfession() == VillagerProfession.SHEPHERD;
    }

    @Override
    protected String getGoalName() {
        return "ShepherdBed";
    }

    @Override
    protected int getDailyCraftLimit(ServerWorld world) {
        return DAILY_LIMIT;
    }

    @Override
    protected List<BedRecipe> discoverRecipes(ServerWorld world, Inventory inventory) {
        // Call countBedlessVillagersNearby once and reuse the result for both the
        // demand gate and the surplus check. Previously hasBedlessVillagerNearby()
        // called countBedlessVillagersNearby() internally, causing a second full
        // entity scan on the same tick whenever demand was positive.
        int bedlessCount = countBedlessVillagersNearby(world);
        if (bedlessCount <= 0) {
            return List.of();
        }
        // Don't over-stockpile — count beds already in chest
        int bedsInChest = countBedsInChest(inventory);
        if (bedsInChest >= bedlessCount + BED_SURPLUS_BUFFER) {
            return List.of();
        }

        List<BedRecipe> recipes = new ArrayList<>();
        for (BedRecipe recipe : BedRecipe.values()) {
            if (hasIngredients(inventory, recipe)) {
                recipes.add(recipe);
            }
        }
        return recipes;
    }

    @Override
    protected boolean canStillCraftRecipe(ServerWorld world, Inventory inventory, BedRecipe recipe) {
        return hasIngredients(inventory, recipe);
    }

    @Override
    protected boolean craftRecipe(ServerWorld world, Inventory inventory, BedRecipe recipe) {
        // Consume 3 planks (any kind) + 3 matching wool
        if (!consumePlanks(inventory, MIN_PLANKS)) {
            return false;
        }
        if (!consumeWool(inventory, recipe.woolMatcher, 3)) {
            return false;
        }
        insertStack(inventory, recipe.output.copy());
        return true;
    }

    @Override
    protected ItemStack getRecipeOutput(BedRecipe recipe) {
        return recipe.output;
    }

    @Override
    protected String formatCheckResult(int craftableCount) {
        return craftableCount == 1 ? "1 bed recipe available" : craftableCount + " bed recipes available";
    }

    @Override
    protected String formatCraftedResult(int craftableCount, ItemStack crafted) {
        return craftableCount + " bed recipes available - crafted " + crafted.getName().getString();
    }

    // -------------------------------------------------------------------------
    // Ingredient helpers
    // -------------------------------------------------------------------------

    private boolean hasIngredients(Inventory inventory, BedRecipe recipe) {
        return countPlanks(inventory) >= MIN_PLANKS
                && countMatching(inventory, recipe.woolMatcher) >= 3;
    }

    private int countPlanks(Inventory inventory) {
        return countMatching(inventory, stack -> stack.isIn(ItemTags.PLANKS));
    }

    private boolean consumePlanks(Inventory inventory, int count) {
        return consumeMatching(inventory, stack -> stack.isIn(ItemTags.PLANKS), count);
    }

    private boolean consumeWool(Inventory inventory, Predicate<ItemStack> woolMatcher, int count) {
        return consumeMatching(inventory, woolMatcher, count);
    }

    private int countMatching(Inventory inventory, Predicate<ItemStack> matcher) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && matcher.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private boolean consumeMatching(Inventory inventory, Predicate<ItemStack> matcher, int count) {
        int remaining = count;
        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !matcher.test(stack)) {
                continue;
            }
            int removed = Math.min(remaining, stack.getCount());
            stack.decrement(removed);
            remaining -= removed;
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
        }
        return remaining <= 0;
    }

    private int countBedsInChest(Inventory inventory) {
        return countMatching(inventory, stack -> stack.isIn(ItemTags.BEDS));
    }

    // -------------------------------------------------------------------------
    // Demand check
    // -------------------------------------------------------------------------

    private boolean hasBedlessVillagerNearby(ServerWorld world) {
        return countBedlessVillagersNearby(world) > 0;
    }

    private int countBedlessVillagersNearby(ServerWorld world) {
        Box box = new Box(villager.getBlockPos()).expand(BEDLESS_SCAN_RANGE);
        List<VillagerEntity> villagers = world.getEntitiesByClass(VillagerEntity.class, box, VillagerEntity::isAlive);
        int bedless = 0;
        for (VillagerEntity v : villagers) {
            // Exclude the shepherd itself: it is the craftsman, not a recipient.
            // Without this exclusion the shepherd always counts as bedless (shepherds rarely
            // have a HOME memory), causing perpetual crafting even when all *other* villagers
            // have sleeping spots.
            if (v == villager) continue;
            if (!hasSleepingPos(v)) {
                bedless++;
            }
        }
        return bedless;
    }

    /** Returns true if the villager has a claimed sleeping position. */
    private boolean hasSleepingPos(VillagerEntity v) {
        // VillagerEntity inherits PathedTargetGoal-related sleeping state via Brain memory
        // The cleanest way on Fabric 1.21: check the brain memory for HOME or SLEEPING (MemoryModuleType.HOME)
        // Actually on 1.21 VillagerEntity.getSleepingPosition() is inherited from MobEntity via Entity
        // The vanilla approach: villager.getSleepingPosition() returns Optional<BlockPos>
        return v.getSleepingPosition().isPresent()
                || v.getBrain().getOptionalRegisteredMemory(net.minecraft.entity.ai.brain.MemoryModuleType.HOME).isPresent();
    }

    // -------------------------------------------------------------------------
    // Bed recipe enum — one entry per wool color
    // -------------------------------------------------------------------------

    public enum BedRecipe {
        WHITE(new ItemStack(Items.WHITE_BED), stack -> stack.isOf(Items.WHITE_WOOL)),
        ORANGE(new ItemStack(Items.ORANGE_BED), stack -> stack.isOf(Items.ORANGE_WOOL)),
        MAGENTA(new ItemStack(Items.MAGENTA_BED), stack -> stack.isOf(Items.MAGENTA_WOOL)),
        LIGHT_BLUE(new ItemStack(Items.LIGHT_BLUE_BED), stack -> stack.isOf(Items.LIGHT_BLUE_WOOL)),
        YELLOW(new ItemStack(Items.YELLOW_BED), stack -> stack.isOf(Items.YELLOW_WOOL)),
        LIME(new ItemStack(Items.LIME_BED), stack -> stack.isOf(Items.LIME_WOOL)),
        PINK(new ItemStack(Items.PINK_BED), stack -> stack.isOf(Items.PINK_WOOL)),
        GRAY(new ItemStack(Items.GRAY_BED), stack -> stack.isOf(Items.GRAY_WOOL)),
        LIGHT_GRAY(new ItemStack(Items.LIGHT_GRAY_BED), stack -> stack.isOf(Items.LIGHT_GRAY_WOOL)),
        CYAN(new ItemStack(Items.CYAN_BED), stack -> stack.isOf(Items.CYAN_WOOL)),
        PURPLE(new ItemStack(Items.PURPLE_BED), stack -> stack.isOf(Items.PURPLE_WOOL)),
        BLUE(new ItemStack(Items.BLUE_BED), stack -> stack.isOf(Items.BLUE_WOOL)),
        BROWN(new ItemStack(Items.BROWN_BED), stack -> stack.isOf(Items.BROWN_WOOL)),
        GREEN(new ItemStack(Items.GREEN_BED), stack -> stack.isOf(Items.GREEN_WOOL)),
        RED(new ItemStack(Items.RED_BED), stack -> stack.isOf(Items.RED_WOOL)),
        BLACK(new ItemStack(Items.BLACK_BED), stack -> stack.isOf(Items.BLACK_WOOL));

        final ItemStack output;
        final Predicate<ItemStack> woolMatcher;

        BedRecipe(ItemStack output, Predicate<ItemStack> woolMatcher) {
            this.output = output;
            this.woolMatcher = woolMatcher;
        }
    }
}
