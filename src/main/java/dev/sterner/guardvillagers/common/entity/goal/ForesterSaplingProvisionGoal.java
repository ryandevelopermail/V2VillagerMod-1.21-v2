package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.block.ChestBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.passive.VillagerType;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Each new Minecraft day, the Forester walks to their paired chest and deposits
 * 4 biome-appropriate saplings, representing the day's planting supply.
 *
 * <p>Biome mapping uses {@link VillagerType#forBiome} (the same mechanism
 * used by {@code GuardEntity.getRandomTypeForBiome}) plus a supplementary
 * registry-key check for cherry, birch, and dark-oak biomes that VillagerType
 * doesn't distinguish.
 */
public class ForesterSaplingProvisionGoal extends Goal {

    private static final Logger LOGGER = LoggerFactory.getLogger(ForesterSaplingProvisionGoal.class);

    private static final double MOVE_SPEED = 0.6D;
    private static final double REACH_SQ = 3.5D * 3.5D;
    private static final int SAPLINGS_PER_DAY = 4;
    private static final int PATH_RETRY_TICKS = 20;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;

    private Stage stage = Stage.IDLE;
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

    // -------------------------------------------------------------------------
    // Goal lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) return false;
        if (!villager.isAlive() || !world.isDay()) return false;
        if (jobPos == null) return false;

        long currentDay = world.getTimeOfDay() / 24000L;
        if (currentDay == lastProvisionDay) return false;

        if (chestPos == null) {
            // V1 mode: provision directly into villager inventory
            return hasEmptySlot(villager.getInventory());
        }

        Optional<Inventory> inv = getChestInventory(world);
        return inv.isPresent() && hasEmptySlot(inv.get());
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
                ItemStack toInsert = new ItemStack(sapling, SAPLINGS_PER_DAY);
                if (chestPos == null) {
                    // V1 mode: place saplings directly into villager inventory
                    insertIntoInventory(villager.getInventory(), toInsert);
                    LOGGER.debug("[forester] {} provisioned {}x {} into own inventory",
                            villager.getUuidAsString(), SAPLINGS_PER_DAY, sapling);
                } else {
                    Optional<Inventory> invOpt = getChestInventory(world);
                    if (invOpt.isEmpty()) {
                        LOGGER.debug("[forester] {} chest gone during provision", villager.getUuidAsString());
                        stage = Stage.DONE;
                        return;
                    }
                    insertIntoInventory(invOpt.get(), toInsert);
                    LOGGER.debug("[forester] {} provisioned {}x {} into chest at {}",
                            villager.getUuidAsString(), SAPLINGS_PER_DAY, sapling, chestPos.toShortString());
                }
                lastProvisionDay = world.getTimeOfDay() / 24000L;
                stage = Stage.DONE;
            }
            case DONE -> stage = Stage.IDLE;
            default -> {}
        }
    }

    // -------------------------------------------------------------------------
    // Biome selection
    // -------------------------------------------------------------------------

    /**
     * Selects the most appropriate sapling type for the villager's current biome.
     * Uses VillagerType as the primary classifier, with a supplementary biome-key
     * check for cherry, birch-forest, and dark-forest that VillagerType doesn't
     * distinguish from plains/forest.
     */
    private Item selectSaplingForBiome(ServerWorld world) {
        BlockPos pos = villager.getBlockPos();

        // Supplementary fine-grained check first (biome registry key path)
        String biomePath = world.getBiome(pos).getKey()
                .map(k -> k.getValue().getPath())
                .orElse("");

        if (biomePath.contains("cherry")) {
            return Items.CHERRY_SAPLING;
        }
        if (biomePath.contains("dark_forest")) {
            return Items.DARK_OAK_SAPLING;
        }
        if (biomePath.contains("birch_forest")) {
            return Items.BIRCH_SAPLING;
        }
        if (biomePath.contains("mangrove")) {
            // Mangrove swamp — propagule isn't a sapling in the standard sense; use oak as fallback
            return Items.OAK_SAPLING;
        }

        // VillagerType-based classification covers the remaining major biome categories
        VillagerType type = VillagerType.forBiome(world.getBiome(pos));
        if (type == VillagerType.TAIGA || type == VillagerType.SNOW) {
            return Items.SPRUCE_SAPLING;
        }
        if (type == VillagerType.JUNGLE) {
            return Items.JUNGLE_SAPLING;
        }
        if (type == VillagerType.SAVANNA) {
            return Items.ACACIA_SAPLING;
        }

        return Items.OAK_SAPLING;
    }

    // -------------------------------------------------------------------------
    // Inventory helpers
    // -------------------------------------------------------------------------

    private Optional<Inventory> getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        Inventory inv = ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
        return Optional.ofNullable(inv);
    }

    private boolean hasEmptySlot(Inventory inv) {
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isEmpty()) return true;
        }
        return false;
    }

    /** Inserts {@code stack} into the first available slot(s) of {@code inv}. */
    private void insertIntoInventory(Inventory inv, ItemStack stack) {
        // Try to merge with existing stacks of the same type first
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

    // -------------------------------------------------------------------------
    // Nav helpers
    // -------------------------------------------------------------------------

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
