package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.villager.CraftingCheckLogger;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MaceItem;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolItem;
import net.minecraft.item.TridentItem;
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

public class WeaponsmithRepairGoal extends Goal {
    private static final Logger LOGGER = LoggerFactory.getLogger(WeaponsmithRepairGoal.class);
    private static final int CHECK_INTERVAL_TICKS = CraftingCheckLogger.MATERIAL_CHECK_INTERVAL_TICKS;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final double MOVE_SPEED = 0.6D;

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private Stage stage = Stage.IDLE;
    private long nextCheckTime;
    private long lastRepairDay = -1L;
    private int dailyRepairLimit;
    private int repairedToday;
    private boolean immediateCheckPending;

    public WeaponsmithRepairGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        setTargets(jobPos, chestPos);
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.stage = Stage.IDLE;
    }

    public void requestImmediateRepairCheck(ServerWorld world) {
        refreshDailyLimit(world);
        immediateCheckPending = true;
        nextCheckTime = 0L;
    }

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!world.isDay()) {
            return false;
        }
        if (villager.getVillagerData().getProfession() != VillagerProfession.WEAPONSMITH) {
            return false;
        }
        if (chestPos == null || !world.getBlockState(jobPos).isOf(Blocks.GRINDSTONE)) {
            return false;
        }

        refreshDailyLimit(world);
        if (repairedToday >= dailyRepairLimit) {
            return false;
        }

        if (!immediateCheckPending && world.getTime() < nextCheckTime) {
            return false;
        }

        RepairPlan plan = findRepairPlan(world).orElse(null);
        nextCheckTime = world.getTime() + CHECK_INTERVAL_TICKS;
        immediateCheckPending = false;

        if (plan == null) {
            LOGGER.info("Weaponsmith {} skipped repair check: no valid damaged pair found", villager.getUuidAsString());
            return false;
        }

        LOGGER.info("Weaponsmith {} detected repair candidate: {} + {}",
                villager.getUuidAsString(),
                plan.firstStack().getName().getString(),
                plan.secondStack().getName().getString());
        return true;
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.DONE && villager.isAlive();
    }

    @Override
    public void start() {
        stage = Stage.GO_TO_GRINDSTONE;
        moveTo(jobPos);
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
            case GO_TO_GRINDSTONE -> {
                if (isNear(jobPos)) {
                    stage = Stage.REPAIR;
                } else {
                    moveTo(jobPos);
                }
            }
            case REPAIR -> {
                repairOnce(world);
                stage = Stage.DONE;
            }
            case IDLE, DONE -> {
            }
        }
    }

    private void refreshDailyLimit(ServerWorld world) {
        long day = world.getTimeOfDay() / 24000L;
        if (day != lastRepairDay) {
            lastRepairDay = day;
            dailyRepairLimit = 4;
            repairedToday = 0;
            immediateCheckPending = false;
        }
    }

    private void repairOnce(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            LOGGER.info("Weaponsmith {} skipped repair: paired chest unavailable", villager.getUuidAsString());
            return;
        }

        RepairPlan plan = findRepairPlan(inventory).orElse(null);
        if (plan == null) {
            LOGGER.info("Weaponsmith {} skipped repair: no valid pair at execution time", villager.getUuidAsString());
            return;
        }

        ItemStack repaired = createRepairedStack(plan.firstStack(), plan.secondStack());
        if (!canInsertAfterConsume(inventory, plan.firstSlot(), plan.secondSlot(), repaired)) {
            LOGGER.info("Weaponsmith {} skipped repair: output insertion failure for {}",
                    villager.getUuidAsString(),
                    repaired.getName().getString());
            return;
        }

        if (!consumeRepairInputs(inventory, plan.firstSlot(), plan.secondSlot())) {
            LOGGER.info("Weaponsmith {} skipped repair: input stacks changed before consume", villager.getUuidAsString());
            return;
        }

        ItemStack remaining = insertStack(inventory, repaired.copy());
        if (!remaining.isEmpty()) {
            LOGGER.info("Weaponsmith {} skipped repair: output insertion failure for {}",
                    villager.getUuidAsString(),
                    repaired.getName().getString());
            insertStack(inventory, remaining);
            return;
        }

        inventory.markDirty();
        repairedToday++;
        LOGGER.info("Weaponsmith {} completed repair: {}",
                villager.getUuidAsString(),
                repaired.getName().getString());
    }

    private Optional<RepairPlan> findRepairPlan(ServerWorld world) {
        Inventory inventory = getChestInventory(world).orElse(null);
        if (inventory == null) {
            return Optional.empty();
        }
        return findRepairPlan(inventory);
    }

    private Optional<RepairPlan> findRepairPlan(Inventory inventory) {
        for (int slotA = 0; slotA < inventory.size(); slotA++) {
            ItemStack a = inventory.getStack(slotA);
            if (!isRepairInput(a)) {
                continue;
            }
            for (int slotB = slotA + 1; slotB < inventory.size(); slotB++) {
                ItemStack b = inventory.getStack(slotB);
                if (!isRepairInput(b)) {
                    continue;
                }
                if (a.getItem() == b.getItem()) {
                    return Optional.of(new RepairPlan(slotA, slotB, a.copyWithCount(1), b.copyWithCount(1)));
                }
            }
        }
        return Optional.empty();
    }

    private boolean isRepairInput(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageable() || !stack.isDamaged()) {
            return false;
        }
        return supportsRepairType(stack.getItem());
    }

    private boolean supportsRepairType(Item item) {
        return item instanceof SwordItem
                || item instanceof AxeItem
                || item instanceof BowItem
                || item instanceof CrossbowItem
                || item instanceof TridentItem
                || item instanceof MaceItem
                || item instanceof ToolItem;
    }

    private ItemStack createRepairedStack(ItemStack first, ItemStack second) {
        ItemStack repaired = first.copyWithCount(1);
        int maxDamage = repaired.getMaxDamage();
        int remainingA = maxDamage - first.getDamage();
        int remainingB = maxDamage - second.getDamage();
        int bonus = Math.max(1, maxDamage * 5 / 100);
        int repairedDurability = Math.min(maxDamage, remainingA + remainingB + bonus);
        int repairedDamage = MathHelper.clamp(maxDamage - repairedDurability, 0, maxDamage);
        repaired.setDamage(repairedDamage);
        return repaired;
    }

    private boolean consumeRepairInputs(Inventory inventory, int slotA, int slotB) {
        ItemStack a = inventory.getStack(slotA);
        ItemStack b = inventory.getStack(slotB);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        a.decrement(1);
        b.decrement(1);
        if (a.isEmpty()) {
            inventory.setStack(slotA, ItemStack.EMPTY);
        }
        if (b.isEmpty()) {
            inventory.setStack(slotB, ItemStack.EMPTY);
        }
        return true;
    }

    private boolean canInsertAfterConsume(Inventory inventory, int slotA, int slotB, ItemStack output) {
        List<ItemStack> simulated = new ArrayList<>(inventory.size());
        for (int slot = 0; slot < inventory.size(); slot++) {
            simulated.add(inventory.getStack(slot).copy());
        }

        if (!simulateDecrement(simulated, slotA) || !simulateDecrement(simulated, slotB)) {
            return false;
        }

        ItemStack remaining = output.copy();
        for (int slot = 0; slot < simulated.size(); slot++) {
            if (remaining.isEmpty()) {
                return true;
            }

            ItemStack existing = simulated.get(slot);
            if (existing.isEmpty()) {
                if (!inventory.isValid(slot, remaining)) {
                    continue;
                }
                int move = Math.min(remaining.getCount(), remaining.getMaxCount());
                ItemStack placed = remaining.copyWithCount(move);
                simulated.set(slot, placed);
                remaining.decrement(move);
            } else if (canMerge(existing, remaining)) {
                int room = existing.getMaxCount() - existing.getCount();
                if (room > 0) {
                    int move = Math.min(room, remaining.getCount());
                    existing.increment(move);
                    remaining.decrement(move);
                }
            }
        }

        return remaining.isEmpty();
    }

    private boolean simulateDecrement(List<ItemStack> simulated, int slot) {
        ItemStack stack = simulated.get(slot);
        if (stack.isEmpty()) {
            return false;
        }
        stack.decrement(1);
        if (stack.isEmpty()) {
            simulated.set(slot, ItemStack.EMPTY);
        }
        return true;
    }

    private ItemStack insertStack(Inventory inventory, ItemStack stack) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (stack.isEmpty()) {
                break;
            }

            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                if (!inventory.isValid(slot, stack)) {
                    continue;
                }
                int move = Math.min(stack.getCount(), stack.getMaxCount());
                inventory.setStack(slot, stack.copyWithCount(move));
                stack.decrement(move);
            } else if (canMerge(existing, stack)) {
                int room = existing.getMaxCount() - existing.getCount();
                if (room > 0) {
                    int move = Math.min(room, stack.getCount());
                    existing.increment(move);
                    stack.decrement(move);
                }
            }
        }
        return stack;
    }

    private boolean canMerge(ItemStack first, ItemStack second) {
        return first.getItem() == second.getItem()
                && first.getCount() < first.getMaxCount()
                && ItemStack.areItemsAndComponentsEqual(first, second);
    }

    private Optional<Inventory> getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, chestPos, true));
    }

    private boolean isNear(BlockPos pos) {
        return villager.squaredDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private void moveTo(BlockPos pos) {
        villager.getNavigation().startMovingTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, MOVE_SPEED);
    }

    private enum Stage {
        IDLE,
        GO_TO_GRINDSTONE,
        REPAIR,
        DONE
    }

    private record RepairPlan(int firstSlot, int secondSlot, ItemStack firstStack, ItemStack secondStack) {
    }
}
