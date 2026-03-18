package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.LumberjackGuardEntity;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.villager.ProfessionDefinitions;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class LumberjackFurnaceModifierGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(LumberjackFurnaceModifierGoal.class);
    private static final double MOVE_SPEED = 0.8D;
    private static final double REACH_DISTANCE_SQUARED = 9.0D;
    private static final int SEARCH_RADIUS = 3;
    private static final long PATH_REEVALUATION_INTERVAL_TICKS = 20L;
    private static final long SERVICE_REEVALUATION_INTERVAL_TICKS = 20L;
    private static final Set<VillagerProfession> CHECKED_JOB_PROFESSIONS = Set.of(
            VillagerProfession.NONE,
            VillagerProfession.ARMORER,
            VillagerProfession.BUTCHER,
            VillagerProfession.CARTOGRAPHER,
            VillagerProfession.CLERIC,
            VillagerProfession.FARMER,
            VillagerProfession.FISHERMAN,
            VillagerProfession.FLETCHER,
            VillagerProfession.LIBRARIAN,
            VillagerProfession.LEATHERWORKER,
            VillagerProfession.MASON,
            VillagerProfession.SHEPHERD,
            VillagerProfession.TOOLSMITH,
            VillagerProfession.WEAPONSMITH
    );

    private final LumberjackGuardEntity guard;
    private BlockPos targetFurnacePos;
    private long lastPathEvaluationTick;
    private long lastServiceEvaluationTick;
    private ServiceState lastServiceState = ServiceState.actionable("init");

    public LumberjackFurnaceModifierGoal(LumberjackGuardEntity guard) {
        this.guard = guard;
        this.setControls(EnumSet.of(Control.MOVE));
    }

    @Override
    public boolean canStart() {
        if (!(this.guard.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        // Charcoal production runs in any workflow stage — not gated on CRAFTING.
        // This ensures charcoal is continuously produced and distributed as the
        // reliable village fuel regardless of the lumberjack's chop cycle.
        if (!this.guard.isAlive()) {
            return false;
        }

        Optional<BlockPos> designatedFurnace = findDesignatedFurnace(world);
        if (designatedFurnace.isEmpty()) {
            return false;
        }

        this.targetFurnacePos = designatedFurnace.get();
        this.lastServiceState = evaluateServiceState(world, this.targetFurnacePos);
        return this.lastServiceState.actionable();
    }

    @Override
    public boolean shouldContinue() {
        if (!(this.guard.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (this.targetFurnacePos == null) {
            return false;
        }

        long now = world.getTime();
        if (now - this.lastServiceEvaluationTick >= SERVICE_REEVALUATION_INTERVAL_TICKS) {
            this.lastServiceState = evaluateServiceState(world, this.targetFurnacePos);
            this.lastServiceEvaluationTick = now;
        }
        return this.lastServiceState.actionable();
    }

    @Override
    public void tick() {
        if (!(this.guard.getWorld() instanceof ServerWorld world) || this.targetFurnacePos == null) {
            return;
        }

        long now = world.getTime();
        if (this.guard.squaredDistanceTo(Vec3d.ofCenter(this.targetFurnacePos)) > REACH_DISTANCE_SQUARED) {
            if (now - this.lastPathEvaluationTick >= PATH_REEVALUATION_INTERVAL_TICKS || this.guard.getNavigation().isIdle()) {
                this.guard.getNavigation().startMovingTo(this.targetFurnacePos.getX() + 0.5D, this.targetFurnacePos.getY(), this.targetFurnacePos.getZ() + 0.5D, MOVE_SPEED);
                this.lastPathEvaluationTick = now;
            }
            return;
        }

        Inventory chestInventory = resolveChestInventory(world);
        Optional<AbstractFurnaceBlockEntity> furnaceOpt = resolveFurnace(world, this.targetFurnacePos);
        if (furnaceOpt.isEmpty()) {
            stopWithReason("no_furnace_block_entity");
            return;
        }

        AbstractFurnaceBlockEntity furnace = furnaceOpt.get();
        serviceFurnace(chestInventory, furnace);
        if (chestInventory != null) {
            chestInventory.markDirty();
        }
        furnace.markDirty();

        if (now - this.lastServiceEvaluationTick >= SERVICE_REEVALUATION_INTERVAL_TICKS) {
            this.lastServiceState = evaluateServiceState(world, this.targetFurnacePos);
            this.lastServiceEvaluationTick = now;
        }
        if (!this.lastServiceState.actionable()) {
            stopWithReason(this.lastServiceState.reason());
        }
    }

    @Override
    public void stop() {
        this.guard.getNavigation().stop();
        this.targetFurnacePos = null;
        this.lastPathEvaluationTick = 0L;
        this.lastServiceEvaluationTick = 0L;
        this.lastServiceState = ServiceState.actionable("reset");
    }

    private void stopWithReason(String reason) {
        if (this.targetFurnacePos != null) {
            LOGGER.debug("Stop lumberjack furnace service: guard={} furnacePos={} reason={}",
                    this.guard.getUuidAsString(), this.targetFurnacePos.toShortString(), reason);
        }
        // Do NOT force WorkflowStage — furnace servicing now runs outside of the crafting stage
        // and should not disrupt the lumberjack's normal chop/deposit workflow.
    }

    private void serviceFurnace(Inventory chestInventory, AbstractFurnaceBlockEntity furnace) {
        moveHalfLogsToInput(chestInventory, furnace);
        refillFuelFromCharcoalOutput(chestInventory, furnace);
        if (furnace.getStack(1).isEmpty()) {
            moveLogsToFuel(chestInventory, furnace, 3);
        }
        refillFuelFromCharcoalOutput(chestInventory, furnace);
        recoverFuelFromInputIfStalled(furnace);
    }

    private void moveHalfLogsToInput(Inventory chestInventory, AbstractFurnaceBlockEntity furnace) {
        ItemStack input = furnace.getStack(0);
        if (!input.isEmpty() && !input.isIn(ItemTags.LOGS)) {
            return;
        }

        int logsAvailable = countLogs(chestInventory) + countLogs(this.guard.getGatheredStackBuffer());
        int logsToMove = logsAvailable / 2;
        if (logsToMove <= 0) {
            return;
        }

        int space = input.isEmpty() ? 64 : (input.getMaxCount() - input.getCount());
        logsToMove = Math.min(logsToMove, space);
        if (logsToMove <= 0) {
            return;
        }

        ItemStack extracted = extractLogs(chestInventory, logsToMove, input.isEmpty() ? null : input.getItem().getTranslationKey());
        if (extracted.isEmpty()) {
            return;
        }

        ItemStack remaining = insertIntoSlot(furnace, extracted, 0);
        if (!remaining.isEmpty()) {
            returnLogs(chestInventory, remaining);
        }
    }

    private void moveLogsToFuel(Inventory chestInventory, AbstractFurnaceBlockEntity furnace, int amount) {
        ItemStack fuel = furnace.getStack(1);
        if (!fuel.isEmpty() && !fuel.isIn(ItemTags.LOGS)) {
            return;
        }

        int space = fuel.isEmpty() ? 64 : (fuel.getMaxCount() - fuel.getCount());
        int requested = Math.min(amount, space);
        if (requested <= 0) {
            return;
        }

        ItemStack extracted = extractLogs(chestInventory, requested, fuel.isEmpty() ? null : fuel.getItem().getTranslationKey());
        if (extracted.isEmpty()) {
            return;
        }

        ItemStack remaining = insertIntoSlot(furnace, extracted, 1);
        if (!remaining.isEmpty()) {
            returnLogs(chestInventory, remaining);
        }
    }

    private void refillFuelFromCharcoalOutput(Inventory chestInventory, AbstractFurnaceBlockEntity furnace) {
        ItemStack fuel = furnace.getStack(1);
        ItemStack output = furnace.getStack(2);
        if (output.isEmpty() || !output.isOf(Items.CHARCOAL)) {
            return;
        }

        int remainingLogs = countLogs(chestInventory) + countLogs(this.guard.getGatheredStackBuffer());
        ItemStack input = furnace.getStack(0);
        if (!input.isEmpty() && input.isIn(ItemTags.LOGS)) {
            remainingLogs += input.getCount();
        }

        int fuelSpace;
        if (fuel.isEmpty()) {
            fuelSpace = output.getMaxCount();
        } else if (fuel.isOf(Items.CHARCOAL)) {
            fuelSpace = fuel.getMaxCount() - fuel.getCount();
        } else {
            fuelSpace = 0;
        }

        if (remainingLogs > 0 && fuelSpace > 0) {
            int moved = Math.min(output.getCount(), fuelSpace);
            if (moved <= 0) {
                return;
            }

            if (fuel.isEmpty()) {
                furnace.setStack(1, new ItemStack(Items.CHARCOAL, moved));
            } else {
                fuel.increment(moved);
                furnace.setStack(1, fuel);
            }

            output.decrement(moved);
            if (output.isEmpty()) {
                furnace.setStack(2, ItemStack.EMPTY);
            } else {
                furnace.setStack(2, output);
            }
            return;
        }

        boolean pendingBurnCycle = !furnace.getStack(0).isEmpty() || !furnace.getStack(1).isEmpty();
        if (remainingLogs <= 0 && !pendingBurnCycle) {
            returnLogs(chestInventory, output.copy());
            furnace.setStack(2, ItemStack.EMPTY);
        }
    }

    private void recoverFuelFromInputIfStalled(AbstractFurnaceBlockEntity furnace) {
        ItemStack fuel = furnace.getStack(1);
        if (!fuel.isEmpty()) {
            return;
        }

        ItemStack output = furnace.getStack(2);
        if (!output.isEmpty() && output.isOf(Items.CHARCOAL)) {
            return;
        }

        ItemStack input = furnace.getStack(0);
        if (input.isEmpty() || !input.isIn(ItemTags.LOGS)) {
            return;
        }

        int moved = Math.min(3, input.getCount());
        ItemStack correctiveFuel = input.copyWithCount(moved);
        ItemStack remaining = insertIntoSlot(furnace, correctiveFuel, 1);
        int inserted = moved - remaining.getCount();
        if (inserted > 0) {
            input.decrement(inserted);
            if (input.isEmpty()) {
                furnace.setStack(0, ItemStack.EMPTY);
            } else {
                furnace.setStack(0, input);
            }
        }
    }

    private ItemStack insertIntoSlot(AbstractFurnaceBlockEntity furnace, ItemStack stack, int slot) {
        ItemStack existing = furnace.getStack(slot);
        if (existing.isEmpty()) {
            furnace.setStack(slot, stack.copy());
            return ItemStack.EMPTY;
        }
        if (!ItemStack.areItemsAndComponentsEqual(existing, stack)) {
            return stack;
        }

        int movable = Math.min(stack.getCount(), existing.getMaxCount() - existing.getCount());
        if (movable <= 0) {
            return stack;
        }
        existing.increment(movable);
        stack.decrement(movable);
        furnace.setStack(slot, existing);
        return stack;
    }

    private int countLogs(Inventory inventory) {
        if (inventory == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && stack.isIn(ItemTags.LOGS)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countLogs(List<ItemStack> stacks) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty() && stack.isIn(ItemTags.LOGS)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private ItemStack extractLogs(Inventory chestInventory, int amount, String requiredTranslationKey) {
        ItemStack extracted = extractLogsFromInventory(chestInventory, amount, requiredTranslationKey);
        if (extracted.getCount() >= amount) {
            return extracted;
        }

        ItemStack fromBuffer = extractLogsFromBuffer(amount - extracted.getCount(), requiredTranslationKey == null && !extracted.isEmpty() ? extracted.getItem().getTranslationKey() : requiredTranslationKey, this.guard.getGatheredStackBuffer());
        if (extracted.isEmpty()) {
            return fromBuffer;
        }
        if (!fromBuffer.isEmpty() && ItemStack.areItemsAndComponentsEqual(extracted, fromBuffer)) {
            extracted.increment(fromBuffer.getCount());
        } else if (!fromBuffer.isEmpty()) {
            returnLogs(chestInventory, fromBuffer);
        }
        return extracted;
    }

    private ItemStack extractLogsFromInventory(Inventory inventory, int amount, String requiredTranslationKey) {
        if (inventory == null || amount <= 0) {
            return ItemStack.EMPTY;
        }

        ItemStack extracted = ItemStack.EMPTY;
        for (int slot = 0; slot < inventory.size() && amount > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !stack.isIn(ItemTags.LOGS)) {
                continue;
            }
            if (requiredTranslationKey != null && !stack.getItem().getTranslationKey().equals(requiredTranslationKey)) {
                continue;
            }

            if (requiredTranslationKey == null) {
                requiredTranslationKey = stack.getItem().getTranslationKey();
            }

            int moved = Math.min(stack.getCount(), amount);
            ItemStack movedStack = stack.copyWithCount(moved);
            stack.decrement(moved);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            amount -= moved;

            if (extracted.isEmpty()) {
                extracted = movedStack;
            } else {
                extracted.increment(movedStack.getCount());
            }
        }
        return extracted;
    }

    private ItemStack extractLogsFromBuffer(int amount, String requiredTranslationKey, List<ItemStack> buffer) {
        if (amount <= 0) {
            return ItemStack.EMPTY;
        }

        ItemStack extracted = ItemStack.EMPTY;
        for (int i = 0; i < buffer.size() && amount > 0; i++) {
            ItemStack stack = buffer.get(i);
            if (stack.isEmpty() || !stack.isIn(ItemTags.LOGS)) {
                continue;
            }
            if (requiredTranslationKey != null && !stack.getItem().getTranslationKey().equals(requiredTranslationKey)) {
                continue;
            }

            if (requiredTranslationKey == null) {
                requiredTranslationKey = stack.getItem().getTranslationKey();
            }

            int moved = Math.min(stack.getCount(), amount);
            ItemStack movedStack = stack.copyWithCount(moved);
            stack.decrement(moved);
            if (stack.isEmpty()) {
                buffer.set(i, ItemStack.EMPTY);
            }
            amount -= moved;

            if (extracted.isEmpty()) {
                extracted = movedStack;
            } else {
                extracted.increment(movedStack.getCount());
            }
        }

        buffer.removeIf(ItemStack::isEmpty);
        return extracted;
    }

    private void returnLogs(Inventory chestInventory, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }

        if (chestInventory != null) {
            ItemStack remaining = stack.copy();
            boolean chestChanged = false;
            for (int slot = 0; slot < chestInventory.size() && !remaining.isEmpty(); slot++) {
                ItemStack existing = chestInventory.getStack(slot);
                if (existing.isEmpty()) {
                    chestInventory.setStack(slot, remaining.copy());
                    remaining = ItemStack.EMPTY;
                    chestChanged = true;
                    break;
                }
                if (!ItemStack.areItemsAndComponentsEqual(existing, remaining) || existing.getCount() >= existing.getMaxCount()) {
                    continue;
                }
                int moved = Math.min(existing.getMaxCount() - existing.getCount(), remaining.getCount());
                existing.increment(moved);
                remaining.decrement(moved);
                chestChanged = true;
            }
            if (chestChanged) {
                chestInventory.markDirty();
            }
            if (remaining.isEmpty()) {
                return;
            }
            stack = remaining;
        }

        for (ItemStack buffered : this.guard.getGatheredStackBuffer()) {
            if (!ItemStack.areItemsAndComponentsEqual(buffered, stack) || buffered.getCount() >= buffered.getMaxCount()) {
                continue;
            }
            int moved = Math.min(buffered.getMaxCount() - buffered.getCount(), stack.getCount());
            buffered.increment(moved);
            stack.decrement(moved);
            if (stack.isEmpty()) {
                return;
            }
        }

        if (!stack.isEmpty()) {
            this.guard.getGatheredStackBuffer().add(stack.copy());
        }
    }

    private ServiceState evaluateServiceState(ServerWorld world, BlockPos furnacePos) {
        Inventory chestInventory = resolveChestInventory(world);
        Optional<AbstractFurnaceBlockEntity> furnaceOpt = resolveFurnace(world, furnacePos);
        if (furnaceOpt.isEmpty()) {
            return ServiceState.stop("no_furnace");
        }

        AbstractFurnaceBlockEntity furnace = furnaceOpt.get();
        ItemStack input = furnace.getStack(0);
        ItemStack fuel = furnace.getStack(1);
        ItemStack output = furnace.getStack(2);

        int logsInStorage = countLogs(chestInventory) + countLogs(this.guard.getGatheredStackBuffer());
        int logsInInput = input.isIn(ItemTags.LOGS) ? input.getCount() : 0;
        int totalLogs = logsInStorage + logsInInput;

        int inputSpace = input.isEmpty() ? 64 : (input.isIn(ItemTags.LOGS) ? input.getMaxCount() - input.getCount() : 0);
        int fuelSpaceForCharcoal = fuel.isEmpty() ? 64 : (fuel.isOf(Items.CHARCOAL) ? fuel.getMaxCount() - fuel.getCount() : 0);
        boolean fuelLow = fuel.isEmpty() || (fuel.isOf(Items.CHARCOAL) && fuel.getCount() < 3) || (fuel.isIn(ItemTags.LOGS) && fuel.getCount() < 3);

        boolean outputIsCharcoal = output.isOf(Items.CHARCOAL) && !output.isEmpty();
        boolean canRouteOutput = !outputIsCharcoal || fuelSpaceForCharcoal > 0 || hasReturnSpace(chestInventory, output);
        if (outputIsCharcoal && !canRouteOutput) {
            return ServiceState.stop("no_output_space");
        }

        if (outputIsCharcoal) {
            return ServiceState.actionable("route_output");
        }
        if (totalLogs > 0 && inputSpace > 0) {
            return ServiceState.actionable("load_input");
        }
        if (logsInStorage > 0 && fuelLow) {
            return ServiceState.actionable("fuel_low");
        }
        if (totalLogs <= 0) {
            return ServiceState.stop("no_logs");
        }
        return ServiceState.stop("fuel_saturated");
    }

    private boolean hasReturnSpace(Inventory chestInventory, ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        if (chestInventory != null) {
            for (int slot = 0; slot < chestInventory.size(); slot++) {
                ItemStack existing = chestInventory.getStack(slot);
                if (existing.isEmpty()) {
                    return true;
                }
                if (ItemStack.areItemsAndComponentsEqual(existing, stack) && existing.getCount() < existing.getMaxCount()) {
                    return true;
                }
            }
        }
        for (ItemStack buffered : this.guard.getGatheredStackBuffer()) {
            if (ItemStack.areItemsAndComponentsEqual(buffered, stack) && buffered.getCount() < buffered.getMaxCount()) {
                return true;
            }
        }
        return this.guard.getGatheredStackBuffer().size() < 64;
    }

    private Optional<BlockPos> findDesignatedFurnace(ServerWorld world) {
        BlockPos paired = this.guard.getPairedFurnaceModifierPos();
        if (paired != null && world.getBlockState(paired).isOf(Blocks.FURNACE)) {
            if (belongsToPairedLumberjackZone(world, paired)) {
                return Optional.of(paired.toImmutable());
            }
            this.guard.setPairedFurnaceModifierPos(null);
            LOGGER.debug("Clearing invalid paired furnace modifier: guard={} pos={} reason=outside_zone",
                    this.guard.getUuidAsString(), paired.toShortString());
        }

        BlockPos tablePos = this.guard.getPairedCraftingTablePos();
        BlockPos chestPos = this.guard.getPairedChestPos();
        if (tablePos == null || chestPos == null) {
            return Optional.empty();
        }

        List<BlockPos> candidates = new ArrayList<>();
        BlockPos min = BlockPos.ofFloored(
                Math.min(tablePos.getX(), chestPos.getX()) - SEARCH_RADIUS,
                Math.min(tablePos.getY(), chestPos.getY()) - 1,
                Math.min(tablePos.getZ(), chestPos.getZ()) - SEARCH_RADIUS
        );
        BlockPos max = BlockPos.ofFloored(
                Math.max(tablePos.getX(), chestPos.getX()) + SEARCH_RADIUS,
                Math.max(tablePos.getY(), chestPos.getY()) + 1,
                Math.max(tablePos.getZ(), chestPos.getZ()) + SEARCH_RADIUS
        );

        for (BlockPos pos : BlockPos.iterate(min, max)) {
            BlockState state = world.getBlockState(pos);
            if (!state.isOf(Blocks.FURNACE)) {
                continue;
            }
            if (!pos.isWithinDistance(tablePos, SEARCH_RADIUS + 1) || !pos.isWithinDistance(chestPos, SEARCH_RADIUS + 1)) {
                continue;
            }
            if (!hasNearbyModifier(world, pos)) {
                continue;
            }
            if (!belongsToPairedLumberjackZone(world, pos)) {
                continue;
            }
            candidates.add(pos.toImmutable());
        }

        Optional<BlockPos> selected = candidates.stream()
                .min(Comparator.comparing((BlockPos pos) -> pos.getSquaredDistance(tablePos)).thenComparing(pos -> Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).toString()));
        selected.ifPresent(pos -> this.guard.setPairedFurnaceModifierPos(pos.toImmutable()));
        return selected;
    }

    private boolean belongsToPairedLumberjackZone(ServerWorld world, BlockPos pos) {
        BlockPos chestPos = this.guard.getPairedChestPos();
        BlockPos tablePos = this.guard.getPairedCraftingTablePos();
        if (chestPos == null || tablePos == null) {
            return false;
        }
        double range = JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE + 2.0D;
        if (!pos.isWithinDistance(chestPos, range) || !pos.isWithinDistance(tablePos, range)) {
            return false;
        }
        return !isAdjacentToUnrelatedJobBlock(world, pos, chestPos, tablePos);
    }

    private boolean isAdjacentToUnrelatedJobBlock(ServerWorld world, BlockPos candidate, BlockPos chestPos, BlockPos tablePos) {
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = candidate.offset(direction);
            BlockState state = world.getBlockState(adjacent);
            if (!isAnyKnownJobBlock(state)) {
                continue;
            }
            if (adjacent.equals(tablePos) || adjacent.equals(chestPos)) {
                continue;
            }
            if (adjacent.isWithinDistance(tablePos, 1.5D) || adjacent.isWithinDistance(chestPos, 1.5D)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean isAnyKnownJobBlock(BlockState state) {
        for (VillagerProfession profession : CHECKED_JOB_PROFESSIONS) {
            if (ProfessionDefinitions.isExpectedJobBlock(profession, state)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNearbyModifier(ServerWorld world, BlockPos furnacePos) {
        for (BlockPos pos : BlockPos.iterateOutwards(furnacePos, 1, 1, 1)) {
            if (world.getBlockState(pos).isOf(GuardVillagers.GUARD_STAND_MODIFIER)) {
                return true;
            }
        }
        return false;
    }

    private Optional<AbstractFurnaceBlockEntity> resolveFurnace(ServerWorld world, BlockPos furnacePos) {
        BlockEntity blockEntity = world.getBlockEntity(furnacePos);
        if (blockEntity instanceof AbstractFurnaceBlockEntity furnace) {
            return Optional.of(furnace);
        }
        return Optional.empty();
    }

    private Inventory resolveChestInventory(ServerWorld world) {
        BlockPos chestPos = this.guard.getPairedChestPos();
        if (chestPos == null || !world.getBlockState(chestPos).isOf(Blocks.CHEST)) {
            return null;
        }

        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }

        return ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
    }

    private record ServiceState(boolean actionable, String reason) {
        private static ServiceState actionable(String reason) {
            return new ServiceState(true, reason);
        }

        private static ServiceState stop(String reason) {
            return new ServiceState(false, reason);
        }
    }
}
