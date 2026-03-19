package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Distributes surplus charcoal from the lumberjack's paired chest to nearby
 * fuel-consuming professions (butcher, armorer, toolsmith, weaponsmith).
 *
 * <p>The lumberjack always retains at least {@value #CHARCOAL_RESERVE} charcoal in its
 * chest so the furnace has continuous fuel to keep producing more. Only charcoal
 * beyond that reserve threshold is distributed.</p>
 *
 * <p>Distribution is round-robin across discovered recipients, one item at a time,
 * walking source→target on each transfer cycle.</p>
 */
public class LumberjackCharcoalDistributionGoal extends Goal {

    private static final Logger LOGGER = LoggerFactory.getLogger(LumberjackCharcoalDistributionGoal.class);

    /** Minimum charcoal kept in chest so the furnace can keep burning. */
    static final int CHARCOAL_RESERVE = 16;

    /** How often to poll the chest when idle (ticks). */
    private static final int POLL_INTERVAL_TICKS = 100;

    /** Movement speed. */
    private static final double MOVE_SPEED = 0.6D;

    /** Squared reach for chest interaction. */
    private static final double REACH_SQ = 4.0D;

    /** Recipient scan radius (blocks). */
    private static final double SCAN_RADIUS = 48.0D;

    /** Path retry interval (ticks). */
    private static final int PATH_RETRY_TICKS = 20;

    // -----------------------------------------------------------------------

    private final LumberjackGuardEntity guard;

    private Stage stage = Stage.IDLE;
    private long nextPollTick = 0L;
    private BlockPos currentNavTarget = null;
    private long lastPathRequestTick = Long.MIN_VALUE;

    /** Extracted charcoal being carried to the recipient. */
    private ItemStack carriedCharcoal = ItemStack.EMPTY;
    /** Target recipient chest for the current transfer. */
    private BlockPos targetChestPos = null;
    /** Round-robin cursor per recipient list. */
    private final Map<VillagerProfession, Integer> recipientCursor = new HashMap<>();

    public LumberjackCharcoalDistributionGoal(LumberjackGuardEntity guard) {
        this.guard = guard;
        setControls(EnumSet.of(Control.MOVE));
    }

    // -----------------------------------------------------------------------
    // Goal lifecycle
    // -----------------------------------------------------------------------

    @Override
    public boolean canStart() {
        if (!(guard.getWorld() instanceof ServerWorld world)) return false;
        if (!guard.isAlive()) return false;
        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos == null) return false;
        if (world.getTime() < nextPollTick) return false;

        nextPollTick = world.getTime() + POLL_INTERVAL_TICKS;

        Inventory chest = getChestInventory(world, chestPos);
        if (chest == null) return false;

        int charcoalInChest = countCharcoal(chest);
        int surplus = charcoalInChest - CHARCOAL_RESERVE;
        if (surplus <= 0) {
            LOGGER.debug("LumberjackCharcoal {}: charcoal={} reserve={} — no surplus, skipping",
                    guard.getUuidAsString(), charcoalInChest, CHARCOAL_RESERVE);
            return false;
        }

        // Find a recipient that actually needs charcoal
        BlockPos recipient = findRecipient(world);
        if (recipient == null) {
            LOGGER.debug("LumberjackCharcoal {}: surplus={} but no recipients found within {} blocks",
                    guard.getUuidAsString(), surplus, SCAN_RADIUS);
            return false;
        }

        this.targetChestPos = recipient;
        LOGGER.info("LumberjackCharcoal {}: charcoal={} surplus={} → distributing to chest at {}",
                guard.getUuidAsString(), charcoalInChest, surplus, recipient.toShortString());
        return true;
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.DONE && stage != Stage.IDLE && guard.isAlive();
    }

    @Override
    public void start() {
        stage = Stage.MOVE_TO_SOURCE;
        moveTo(guard.getPairedChestPos());
    }

    @Override
    public void stop() {
        guard.getNavigation().stop();
        currentNavTarget = null;
        lastPathRequestTick = Long.MIN_VALUE;
        // Return any carried charcoal to source chest if the goal is interrupted
        if (!carriedCharcoal.isEmpty()) {
            if (guard.getWorld() instanceof ServerWorld world) {
                returnCarriedToSource(world);
            }
        }
        carriedCharcoal = ItemStack.EMPTY;
        targetChestPos = null;
        stage = Stage.DONE;
    }

    @Override
    public void tick() {
        if (!(guard.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case MOVE_TO_SOURCE -> {
                BlockPos chestPos = guard.getPairedChestPos();
                if (chestPos == null) { stage = Stage.DONE; return; }
                if (isNear(chestPos)) {
                    if (!extractCharcoal(world, chestPos)) {
                        stage = Stage.DONE;
                        return;
                    }
                    stage = Stage.MOVE_TO_TARGET;
                    moveTo(targetChestPos);
                } else {
                    moveTo(chestPos);
                }
            }
            case MOVE_TO_TARGET -> {
                if (carriedCharcoal.isEmpty()) { stage = Stage.DONE; return; }
                if (isNear(targetChestPos)) {
                    stage = Stage.DEPOSIT;
                } else {
                    moveTo(targetChestPos);
                }
            }
            case DEPOSIT -> {
                depositCarried(world);
                stage = Stage.DONE;
            }
            default -> {}
        }
    }

    // -----------------------------------------------------------------------
    // Transfer helpers
    // -----------------------------------------------------------------------

    /**
     * Extracts exactly 1 charcoal from the source chest (capped by surplus above reserve).
     * Returns false if nothing could be extracted.
     */
    private boolean extractCharcoal(ServerWorld world, BlockPos chestPos) {
        Inventory chest = getChestInventory(world, chestPos);
        if (chest == null) return false;

        int charcoalInChest = countCharcoal(chest);
        int surplus = charcoalInChest - CHARCOAL_RESERVE;
        if (surplus <= 0) return false;

        // Extract exactly 1 — the target doesn't need a flood
        for (int slot = 0; slot < chest.size(); slot++) {
            ItemStack stack = chest.getStack(slot);
            if (stack.isEmpty() || !stack.isOf(Items.CHARCOAL)) continue;
            stack.decrement(1);
            if (stack.isEmpty()) chest.setStack(slot, ItemStack.EMPTY);
            chest.markDirty();
            carriedCharcoal = new ItemStack(Items.CHARCOAL, 1);
            return true;
        }
        return false;
    }

    private void depositCarried(ServerWorld world) {
        if (carriedCharcoal.isEmpty() || targetChestPos == null) return;

        Inventory target = getChestInventory(world, targetChestPos);
        if (target == null) {
            returnCarriedToSource(world);
            return;
        }

        ItemStack remaining = insertStack(target, carriedCharcoal.copy());
        carriedCharcoal = ItemStack.EMPTY;

        if (!remaining.isEmpty()) {
            // Target is full — put it back
            returnCarriedToSource(world);
        }
    }

    private void returnCarriedToSource(ServerWorld world) {
        if (carriedCharcoal.isEmpty()) return;
        BlockPos chestPos = guard.getPairedChestPos();
        if (chestPos != null) {
            Inventory chest = getChestInventory(world, chestPos);
            if (chest != null) {
                ItemStack remaining = insertStack(chest, carriedCharcoal.copy());
                if (remaining.isEmpty()) {
                    carriedCharcoal = ItemStack.EMPTY;
                    return;
                }
            }
        }
        // Drop on the ground as a last resort
        if (!carriedCharcoal.isEmpty()) {
            guard.dropStack(carriedCharcoal);
            carriedCharcoal = ItemStack.EMPTY;
        }
    }

    // -----------------------------------------------------------------------
    // Recipient discovery
    // -----------------------------------------------------------------------

    /**
     * Finds the next recipient chest (round-robin) that has space for charcoal.
     * Recipients: butcher (smoker), armorer (blast furnace), toolsmith (smithing table),
     * weaponsmith (grindstone).
     */
    private BlockPos findRecipient(ServerWorld world) {
        // Build a combined ranked list across all recipient professions
        List<RecipientEntry> all = new ArrayList<>();
        all.addAll(findRecipientsByProfession(world, VillagerProfession.BUTCHER, Blocks.SMOKER));
        all.addAll(findRecipientsByProfession(world, VillagerProfession.ARMORER, Blocks.BLAST_FURNACE));
        all.addAll(findRecipientsByProfession(world, VillagerProfession.TOOLSMITH, Blocks.SMITHING_TABLE));
        all.addAll(findRecipientsByProfession(world, VillagerProfession.WEAPONSMITH, Blocks.GRINDSTONE));

        // Filter: only recipients whose chest has room for charcoal
        all.removeIf(e -> !hasSpaceForCharcoal(e.inventory()));

        if (all.isEmpty()) return null;

        // Sort deterministically (by chest pos) so the cursor is stable
        all.sort(Comparator.comparingInt((RecipientEntry e) -> e.chestPos().getX())
                .thenComparingInt(e -> e.chestPos().getZ())
                .thenComparingInt(e -> e.chestPos().getY()));

        int cursor = recipientCursor.getOrDefault(VillagerProfession.NONE, 0);
        int index = Math.floorMod(cursor, all.size());
        recipientCursor.put(VillagerProfession.NONE, index + 1);

        return all.get(index).chestPos();
    }

    private List<RecipientEntry> findRecipientsByProfession(ServerWorld world,
                                                             VillagerProfession profession,
                                                             net.minecraft.block.Block expectedJobBlock) {
        List<RecipientEntry> result = new ArrayList<>();
        BlockPos guardPos = guard.getBlockPos();
        Box scanBox = new Box(guardPos).expand(SCAN_RADIUS);

        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, scanBox,
                v -> v.isAlive() && !v.isBaby() && v.getVillagerData().getProfession() == profession)) {

            Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
            if (jobSite.isEmpty() || !Objects.equals(jobSite.get().dimension(), world.getRegistryKey())) continue;

            BlockPos jobPos = jobSite.get().pos();
            if (!world.getBlockState(jobPos).isOf(expectedJobBlock)) continue;

            Optional<BlockPos> chestPos = JobBlockPairingHelper.findNearbyChest(world, jobPos);
            if (chestPos.isEmpty()) continue;

            // Don't deliver to ourselves
            if (chestPos.get().equals(guard.getPairedChestPos())) continue;

            Inventory inv = getChestInventory(world, chestPos.get());
            if (inv == null) continue;

            result.add(new RecipientEntry(inv, chestPos.get().toImmutable()));
        }
        return result;
    }

    private boolean hasSpaceForCharcoal(Inventory inv) {
        for (int slot = 0; slot < inv.size(); slot++) {
            ItemStack stack = inv.getStack(slot);
            if (stack.isEmpty()) return true;
            if (stack.isOf(Items.CHARCOAL) && stack.getCount() < stack.getMaxCount()) return true;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Inventory helpers
    // -----------------------------------------------------------------------

    private int countCharcoal(Inventory inv) {
        int total = 0;
        for (int slot = 0; slot < inv.size(); slot++) {
            ItemStack stack = inv.getStack(slot);
            if (!stack.isEmpty() && stack.isOf(Items.CHARCOAL)) total += stack.getCount();
        }
        return total;
    }

    private Inventory getChestInventory(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) return null;
        return ChestBlock.getInventory(chestBlock, state, world, pos, false);
    }

    private ItemStack insertStack(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (remaining.isEmpty()) break;
            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                if (!inventory.isValid(slot, remaining)) continue;
                int moved = Math.min(remaining.getCount(), remaining.getMaxCount());
                ItemStack toInsert = remaining.copy();
                toInsert.setCount(moved);
                inventory.setStack(slot, toInsert);
                remaining.decrement(moved);
            } else if (ItemStack.areItemsAndComponentsEqual(existing, remaining)) {
                if (!inventory.isValid(slot, remaining)) continue;
                int space = existing.getMaxCount() - existing.getCount();
                if (space <= 0) continue;
                int moved = Math.min(space, remaining.getCount());
                existing.increment(moved);
                remaining.decrement(moved);
            }
        }
        inventory.markDirty();
        return remaining;
    }

    // -----------------------------------------------------------------------
    // Nav helpers
    // -----------------------------------------------------------------------

    private void moveTo(BlockPos target) {
        if (target == null) return;
        long now = guard.getWorld().getTime();
        boolean shouldPath = !target.equals(currentNavTarget)
                || guard.getNavigation().isIdle()
                || now - lastPathRequestTick >= PATH_RETRY_TICKS;
        if (!shouldPath) return;
        guard.getNavigation().startMovingTo(
                target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, MOVE_SPEED);
        currentNavTarget = target.toImmutable();
        lastPathRequestTick = now;
    }

    private boolean isNear(BlockPos target) {
        return target != null
                && guard.squaredDistanceTo(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D) <= REACH_SQ;
    }

    // -----------------------------------------------------------------------
    // Records / enums
    // -----------------------------------------------------------------------

    private record RecipientEntry(Inventory inventory, BlockPos chestPos) {}

    private enum Stage {
        IDLE,
        MOVE_TO_SOURCE,
        MOVE_TO_TARGET,
        DEPOSIT,
        DONE
    }
}
