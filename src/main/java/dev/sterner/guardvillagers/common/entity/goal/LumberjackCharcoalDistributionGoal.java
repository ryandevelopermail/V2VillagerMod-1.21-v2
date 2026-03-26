package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.SmokerBlockEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.input.SingleStackRecipeInput;
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
 * <p>Distribution is round-robin across discovered recipients, moving adaptive-sized
 * batches based on recipient urgency, while still preserving source reserves.</p>
 */
public class LumberjackCharcoalDistributionGoal extends Goal {

    private static final Logger LOGGER = LoggerFactory.getLogger(LumberjackCharcoalDistributionGoal.class);

    /** Minimum charcoal kept in chest so the furnace can keep burning. */
    static final int CHARCOAL_RESERVE = 16;

    /** How often to poll the chest when idle (ticks). */
    private static final int POLL_INTERVAL_TICKS = 40;

    /** Movement speed. */
    private static final double MOVE_SPEED = 0.6D;

    /** Squared reach for chest interaction. */
    private static final double REACH_SQ = 4.0D;

    /** Recipient scan radius (blocks). */
    private static final double SCAN_RADIUS = 48.0D;

    /** Path retry interval (ticks). */
    private static final int PATH_RETRY_TICKS = 20;
    /** If coal/charcoal fuel is below this count, top-up is allowed (when work exists). */
    private static final int LOW_FUEL_THRESHOLD = 8;
    /** Baseline transfer size for normal top-ups. */
    private static final int BASELINE_BATCH = 4;
    /** Larger transfer used for urgent, empty-fuel recipients with pending work. */
    private static final int URGENT_BATCH = 12;
    /** Charcoal reserve target kept in butcher recipient chests. */
    private static final int BUTCHER_CHARCOAL_RESERVE_TARGET = 16;
    /** Charcoal reserve target kept in armorer recipient chests. */
    private static final int ARMORER_CHARCOAL_RESERVE_TARGET = 16;

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
    /** Current transfer size plan selected during recipient discovery. */
    private int targetTransferCount = BASELINE_BATCH;
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

        // Find a recipient (prioritize active fuel demand; fallback to chest-space recipients)
        RecipientEntry recipient = findRecipient(world);
        if (recipient == null) {
            LOGGER.debug("LumberjackCharcoal {}: surplus={} but no recipients found within {} blocks",
                    guard.getUuidAsString(), surplus, SCAN_RADIUS);
            return false;
        }

        this.targetChestPos = recipient.chestPos();
        this.targetTransferCount = recipient.urgentFuelNeed()
                ? URGENT_BATCH
                : Math.max(BASELINE_BATCH, Math.min(URGENT_BATCH, recipient.storageShortfall()));
        LOGGER.info("LumberjackCharcoal {}: charcoal={} surplus={} → distributing to chest at {}",
                guard.getUuidAsString(), charcoalInChest, surplus, recipient.chestPos().toShortString());
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
        targetTransferCount = BASELINE_BATCH;
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
     * Extracts a batch of charcoal from the source chest, capped by source surplus
     * above {@link #CHARCOAL_RESERVE} and by current transfer urgency.
     */
    private boolean extractCharcoal(ServerWorld world, BlockPos chestPos) {
        Inventory chest = getChestInventory(world, chestPos);
        if (chest == null) return false;

        int charcoalInChest = countCharcoal(chest);
        int surplus = charcoalInChest - CHARCOAL_RESERVE;
        if (surplus <= 0) return false;

        int toExtract = Math.min(Math.max(1, targetTransferCount), surplus);
        int extracted = 0;
        for (int slot = 0; slot < chest.size(); slot++) {
            if (extracted >= toExtract) break;
            ItemStack stack = chest.getStack(slot);
            if (stack.isEmpty() || !stack.isOf(Items.CHARCOAL)) continue;
            int take = Math.min(stack.getCount(), toExtract - extracted);
            stack.decrement(take);
            if (stack.isEmpty()) chest.setStack(slot, ItemStack.EMPTY);
            extracted += take;
        }
        if (extracted <= 0) return false;
        chest.markDirty();
        carriedCharcoal = new ItemStack(Items.CHARCOAL, extracted);
        return true;
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
            return;
        }
        // Successful delivery: allow immediate reevaluation for rapid follow-up runs.
        nextPollTick = world.getTime();
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
     * Finds the next recipient chest (round-robin) with space for charcoal.
     * Demand-true recipients are ranked ahead of recipients that merely have chest space.
     * Recipients: butcher (smoker), armorer (blast furnace), toolsmith (smithing table),
     * weaponsmith (grindstone).
     */
    private RecipientEntry findRecipient(ServerWorld world) {
        // Build a combined ranked list across all recipient professions
        List<RecipientEntry> all = new ArrayList<>();
        all.addAll(findRecipientsByProfession(world, VillagerProfession.BUTCHER, Blocks.SMOKER));
        all.addAll(findRecipientsByProfession(world, VillagerProfession.ARMORER, Blocks.BLAST_FURNACE));
        all.addAll(findRecipientsByProfession(world, VillagerProfession.TOOLSMITH, Blocks.SMITHING_TABLE));
        all.addAll(findRecipientsByProfession(world, VillagerProfession.WEAPONSMITH, Blocks.GRINDSTONE));

        // Filter: only recipients whose chest has room for charcoal
        all.removeIf(e -> !hasSpaceForCharcoal(e.inventory()));

        if (all.isEmpty()) return null;

        sortRecipientsForSelection(all);

        int cursor = recipientCursor.getOrDefault(VillagerProfession.NONE, 0);
        int index = Math.floorMod(cursor, all.size());
        recipientCursor.put(VillagerProfession.NONE, index + 1);

        return all.get(index);
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

            int recipientCharcoalCount = countCharcoal(inv);
            int storageShortfall = computeStorageShortfall(profession, recipientCharcoalCount);
            boolean storageNeeded = storageShortfall > 0;
            boolean fuelNeeded = false;
            boolean urgentFuelNeed = false;
            BlockEntity jobBlockEntity = world.getBlockEntity(jobPos);
            if (profession == VillagerProfession.BUTCHER) {
                if (!(jobBlockEntity instanceof SmokerBlockEntity smoker)) continue;
                fuelNeeded = isFuelNeeded(world, smoker, inv);
                urgentFuelNeed = isUrgentFuelNeed(world, smoker.getStack(1), smoker.getStack(0), inv, true);
            } else if (profession == VillagerProfession.ARMORER) {
                if (!(jobBlockEntity instanceof BlastFurnaceBlockEntity blastFurnace)) continue;
                fuelNeeded = isFuelNeeded(world, blastFurnace, inv);
                urgentFuelNeed = isUrgentFuelNeed(world, blastFurnace.getStack(1), blastFurnace.getStack(0), inv, false);
            }

            result.add(new RecipientEntry(inv, chestPos.get().toImmutable(), fuelNeeded, urgentFuelNeed, storageNeeded, storageShortfall));
        }
        return result;
    }

    static void sortRecipientsForSelection(List<RecipientEntry> recipients) {
        recipients.sort(Comparator.comparing(RecipientEntry::urgentFuelNeed).reversed()
                .thenComparing(Comparator.comparingInt(RecipientEntry::storageShortfall).reversed())
                .thenComparing(Comparator.comparing(RecipientEntry::fuelNeeded).reversed())
                .thenComparingInt(e -> e.chestPos().getX())
                .thenComparingInt(e -> e.chestPos().getZ())
                .thenComparingInt(e -> e.chestPos().getY()));
    }

    static int computeStorageShortfall(VillagerProfession profession, int recipientCharcoalCount) {
        int reserveTarget = charcoalReserveTargetFor(profession);
        return Math.max(0, reserveTarget - recipientCharcoalCount);
    }

    private static int charcoalReserveTargetFor(VillagerProfession profession) {
        if (profession == VillagerProfession.BUTCHER) return BUTCHER_CHARCOAL_RESERVE_TARGET;
        if (profession == VillagerProfession.ARMORER) return ARMORER_CHARCOAL_RESERVE_TARGET;
        return 0;
    }

    private boolean isFuelNeeded(ServerWorld world, SmokerBlockEntity smoker, Inventory chestInventory) {
        return isFuelNeeded(smoker.getStack(1), smoker.getStack(0), hasSmokableInputInChest(world, chestInventory));
    }

    private boolean isFuelNeeded(ServerWorld world, BlastFurnaceBlockEntity blastFurnace, Inventory chestInventory) {
        return isFuelNeeded(blastFurnace.getStack(1), blastFurnace.getStack(0), hasBlastableInputInChest(world, chestInventory));
    }

    private boolean isFuelNeeded(ItemStack fuelStack, ItemStack inputStack, boolean hasProcessableInputInChest) {
        boolean fuelLowOrEmpty = fuelStack.isEmpty()
                || ((fuelStack.isOf(Items.CHARCOAL) || fuelStack.isOf(Items.COAL))
                && fuelStack.getCount() < LOW_FUEL_THRESHOLD);
        if (!fuelLowOrEmpty) return false;

        boolean hasWork = !inputStack.isEmpty() || hasProcessableInputInChest;
        return hasWork;
    }

    private boolean isUrgentFuelNeed(ServerWorld world,
                                     ItemStack fuelStack,
                                     ItemStack inputStack,
                                     Inventory chestInventory,
                                     boolean smokerType) {
        if (!fuelStack.isEmpty()) return false;
        boolean hasProcessableInputInChest = smokerType
                ? hasSmokableInputInChest(world, chestInventory)
                : hasBlastableInputInChest(world, chestInventory);
        return !inputStack.isEmpty() || hasProcessableInputInChest;
    }

    private boolean hasSmokableInputInChest(ServerWorld world, Inventory chestInventory) {
        for (int slot = 0; slot < chestInventory.size(); slot++) {
            ItemStack stack = chestInventory.getStack(slot);
            if (stack.isEmpty()) continue;
            SingleStackRecipeInput input = new SingleStackRecipeInput(stack.copy());
            if (world.getRecipeManager().getFirstMatch(RecipeType.SMOKING, input, world).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBlastableInputInChest(ServerWorld world, Inventory chestInventory) {
        for (int slot = 0; slot < chestInventory.size(); slot++) {
            ItemStack stack = chestInventory.getStack(slot);
            if (stack.isEmpty()) continue;
            SingleStackRecipeInput input = new SingleStackRecipeInput(stack.copy());
            if (world.getRecipeManager().getFirstMatch(RecipeType.BLASTING, input, world).isPresent()) {
                return true;
            }
        }
        return false;
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
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            return ChestBlock.getInventory(chestBlock, state, world, pos, false);
        }
        if (state.getBlock() instanceof BarrelBlock) {
            if (world.getBlockEntity(pos) instanceof BarrelBlockEntity barrel) {
                return barrel;
            }
        }
        return null;
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

    static record RecipientEntry(Inventory inventory,
                                 BlockPos chestPos,
                                 boolean fuelNeeded,
                                 boolean urgentFuelNeed,
                                 boolean storageNeeded,
                                 int storageShortfall) {}

    private enum Stage {
        IDLE,
        MOVE_TO_SOURCE,
        MOVE_TO_TARGET,
        DEPOSIT,
        DONE
    }
}
