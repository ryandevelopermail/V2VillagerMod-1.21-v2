package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.block.ChestBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.village.VillagerType;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Each day (or when the chest/inventory runs low), the Forester tops up their
 * sapling supply with {@link #SAPLINGS_PER_PROVISION} biome-appropriate saplings.
 *
 * <p><b>V2 mode (paired chest):</b> saplings are deposited into the paired chest
 * up to {@link #CHEST_SAPLING_CAP}. Provisioning is gated by a per-day limit —
 * once per Minecraft day at most — so the chest is never flooded faster than the
 * planting goal can drain it.
 *
 * <p><b>V1 mode (no chest):</b> saplings are placed directly into the villager's
 * own inventory. A cap of {@link #V1_SAPLING_CAP} prevents unbounded accumulation.
 *
 * <p>The {@link #reportNoPlantingSpots()} and {@link #reportPlantingSucceeded()}
 * callbacks are still present for compatibility with the linked planting goal, but
 * they no longer suppress provisioning — the planting goal manages its own retry
 * cooldown independently.
 */
public class ForesterSaplingProvisionGoal extends Goal {

    private static final Logger LOGGER = LoggerFactory.getLogger(ForesterSaplingProvisionGoal.class);

    private static final double MOVE_SPEED = 0.6D;
    private static final double REACH_SQ = 3.5D * 3.5D;
    /** Saplings inserted per provision run. */
    private static final int SAPLINGS_PER_PROVISION = 4;
    private static final int PATH_RETRY_TICKS = 20;

    /** Maximum saplings kept in villager inventory in V1 (chestless) mode. */
    private static final int V1_SAPLING_CAP = 8;
    /**
     * Maximum saplings kept in paired chest before provisioning is skipped.
     * Raised from 8 to 32 so a stockpile can build up for batch planting when
     * the player drops in saplings or the forester picks up tree drops.
     */
    private static final int CHEST_SAPLING_CAP = 32;
    /** Saplings in this reserve band are considered protected baseline stock. */
    private static final int PROTECTED_CHEST_SAPLING_STOCK = 8;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;

    private Stage stage = Stage.IDLE;
    /** The Minecraft day on which provisioning last ran (prevents multiple runs per day). */
    private long lastProvisionDay = -1L;
    private BlockPos currentNavTarget = null;
    private long lastPathRequestTick = Long.MIN_VALUE;

    private enum Stage { IDLE, MOVE_TO_CHEST, INSERT, DONE }

    public ForesterSaplingProvisionGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos != null ? chestPos.toImmutable() : null;
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos != null ? chestPos.toImmutable() : null;
        this.stage = Stage.IDLE;
    }

    // -----------------------------------------------------------------------------------------
    // Feedback callbacks (called by ForesterSaplingPlantingGoal)
    // No-ops kept for API compatibility — planting goal manages its own cooldown now.
    // -----------------------------------------------------------------------------------------

    /** Called by linked planting goal when no planting spots were found. No-op. */
    public void reportNoPlantingSpots() {
        // Planting goal manages its own retry cooldown; nothing to suppress here.
    }

    /** Called by linked planting goal when at least one sapling was successfully planted. No-op. */
    public void reportPlantingSucceeded() {
        // No state to reset.
    }

    // -----------------------------------------------------------------------------------------
    // Goal lifecycle
    // -----------------------------------------------------------------------------------------

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) return false;
        if (!villager.isAlive() || !world.isDay()) return false;
        if (jobPos == null) return false;

        // At most once per Minecraft day
        // world.getTime() is the absolute tick counter (ever-increasing); dividing by 24000
        // gives the current day number. world.getTimeOfDay() would be wrong here — it wraps
        // at 24000 so getTimeOfDay()/24000 is always 0, permanently blocking provisioning
        // after the first run.
        long currentDay = world.getTime() / 24000L;
        if (currentDay == lastProvisionDay) {
            return false;
        }

        if (chestPos == null) {
            // V1 mode: only provision if villager has room and is under the cap
            int currentSaplings = countSaplingsInInventory(villager.getInventory());
            if (currentSaplings >= V1_SAPLING_CAP) return false;
            if (!hasEmptySlot(villager.getInventory())) return false;
            return true;
        }

        // V2 mode: only provision if chest exists, has room, and is under the cap
        Optional<Inventory> inv = getChestInventory(world);
        if (inv.isEmpty()) {
            LOGGER.debug("[forester-provision] {} skipping — paired chest at {} not found",
                    villager.getUuidAsString(), chestPos.toShortString());
            return false;
        }
        int chestSaplings = countSaplingsInInventory(inv.get());
        if (chestSaplings >= CHEST_SAPLING_CAP) {
            LOGGER.debug("[forester-provision] {} skipping — chest at cap ({}/{} saplings)",
                    villager.getUuidAsString(), chestSaplings, CHEST_SAPLING_CAP);
            return false;
        }
        if (!hasInsertCapacityForSapling(inv.get())) {
            LOGGER.debug("[forester-provision] {} skipping — chest has no sapling insert capacity",
                    villager.getUuidAsString());
            return false;
        }
        if (chestSaplings < PROTECTED_CHEST_SAPLING_STOCK) {
            LOGGER.info("[forester-provision] {} protected-stock recovery active — chest saplings {}/{} (protected minimum={})",
                    villager.getUuidAsString(), chestSaplings, CHEST_SAPLING_CAP, PROTECTED_CHEST_SAPLING_STOCK);
        }
        LOGGER.info("[forester-provision] {} READY to provision — chest has {}/{} saplings, day={}",
                villager.getUuidAsString(), chestSaplings, CHEST_SAPLING_CAP, currentDay);
        return true;
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.IDLE && stage != Stage.DONE && villager.isAlive();
    }

    @Override
    public void start() {
        if (chestPos == null) {
            // V1 mode: no chest, insert directly into inventory without walking anywhere
            stage = Stage.INSERT;
        } else {
            stage = Stage.MOVE_TO_CHEST;
            moveTo(chestPos);
        }
        LOGGER.debug("[forester] {} starting sapling provision run (chestless={})", villager.getUuidAsString(), chestPos == null);
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        currentNavTarget = null;
        lastPathRequestTick = Long.MIN_VALUE;
        stage = Stage.IDLE;
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case MOVE_TO_CHEST -> {
                if (isNear(chestPos)) {
                    stage = Stage.INSERT;
                } else {
                    moveTo(chestPos);
                }
            }
            case INSERT -> {
                Item sapling = selectSaplingForBiome(world);
                ItemStack toInsert = new ItemStack(sapling, SAPLINGS_PER_PROVISION);
                if (chestPos == null) {
                    // V1 mode: place saplings directly into villager inventory
                    insertIntoInventory(villager.getInventory(), toInsert);
                    LOGGER.info("[forester-provision] {} INSERTED {}x {} into own inventory (V1 mode)",
                            villager.getUuidAsString(), SAPLINGS_PER_PROVISION, sapling);
                } else {
                    Optional<Inventory> invOpt = getChestInventory(world);
                    if (invOpt.isEmpty()) {
                        LOGGER.debug("[forester] {} chest gone during provision", villager.getUuidAsString());
                        stage = Stage.DONE;
                        return;
                    }
                    insertIntoInventory(invOpt.get(), toInsert);
                    LOGGER.info("[forester-provision] {} INSERTED {}x {} into paired chest at {}",
                            villager.getUuidAsString(), SAPLINGS_PER_PROVISION, sapling, chestPos.toShortString());
                }
                lastProvisionDay = world.getTime() / 24000L;
                stage = Stage.DONE;
            }
            case DONE -> stage = Stage.IDLE;
            default -> {}
        }
    }

    // -----------------------------------------------------------------------------------------
    // Biome selection
    // -----------------------------------------------------------------------------------------

    /**
     * Selects the most appropriate sapling type for the villager's current biome.
     */
    private Item selectSaplingForBiome(ServerWorld world) {
        BlockPos pos = villager.getBlockPos();

        // Fine-grained biome-key check first
        String biomePath = world.getBiome(pos).getKey()
                .map(k -> k.getValue().getPath())
                .orElse("");

        if (biomePath.contains("cherry")) return Items.CHERRY_SAPLING;
        if (biomePath.contains("dark_forest")) return Items.DARK_OAK_SAPLING;
        if (biomePath.contains("birch_forest")) return Items.BIRCH_SAPLING;
        if (biomePath.contains("mangrove")) return Items.OAK_SAPLING; // propagule edge case

        VillagerType type = VillagerType.forBiome(world.getBiome(pos));
        if (type == VillagerType.TAIGA || type == VillagerType.SNOW) return Items.SPRUCE_SAPLING;
        if (type == VillagerType.JUNGLE) return Items.JUNGLE_SAPLING;
        if (type == VillagerType.SAVANNA) return Items.ACACIA_SAPLING;

        return Items.OAK_SAPLING;
    }

    // -----------------------------------------------------------------------------------------
    // Inventory helpers
    // -----------------------------------------------------------------------------------------

    private Optional<Inventory> getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) return Optional.empty();
        Inventory inv = ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
        return Optional.ofNullable(inv);
    }

    private boolean hasEmptySlot(Inventory inv) {
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isEmpty()) return true;
        }
        return false;
    }

    private boolean hasInsertCapacityForSapling(Inventory inv) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) {
                return true;
            }
            if (stack.isIn(ItemTags.SAPLINGS) && stack.getCount() < stack.getMaxCount()) {
                return true;
            }
        }
        return false;
    }

    private int countSaplingsInInventory(Inventory inv) {
        int count = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isIn(ItemTags.SAPLINGS)) count += s.getCount();
        }
        return count;
    }

    /** Inserts {@code stack} into the first available slot(s) of {@code inv}. */
    private void insertIntoInventory(Inventory inv, ItemStack stack) {
        // Try to merge with existing stacks first
        for (int i = 0; i < inv.size() && !stack.isEmpty(); i++) {
            ItemStack existing = inv.getStack(i);
            if (!existing.isEmpty() && ItemStack.areItemsEqual(existing, stack)) {
                int space = existing.getMaxCount() - existing.getCount();
                if (space > 0) {
                    int transfer = Math.min(space, stack.getCount());
                    existing.increment(transfer);
                    stack.decrement(transfer);
                    inv.markDirty();
                }
            }
        }
        // Place remainder in empty slots
        for (int i = 0; i < inv.size() && !stack.isEmpty(); i++) {
            if (inv.getStack(i).isEmpty()) {
                inv.setStack(i, stack.copy());
                stack = ItemStack.EMPTY;
                inv.markDirty();
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Nav helpers
    // -----------------------------------------------------------------------------------------

    private void moveTo(BlockPos target) {
        if (target == null) return;
        long now = villager.getWorld().getTime();
        boolean shouldPath = !target.equals(currentNavTarget)
                || villager.getNavigation().isIdle()
                || now - lastPathRequestTick >= PATH_RETRY_TICKS;
        if (!shouldPath) return;
        villager.getNavigation().startMovingTo(
                target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
        currentNavTarget = target.toImmutable();
        lastPathRequestTick = now;
    }

    private boolean isNear(BlockPos target) {
        return target != null
                && villager.squaredDistanceTo(
                        target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= REACH_SQ;
    }
}
