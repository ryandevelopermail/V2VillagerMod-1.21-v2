package dev.sterner.guardvillagers.common.entity;

import dev.sterner.guardvillagers.common.entity.goal.AxeGuardBootstrapGoal;
import dev.sterner.guardvillagers.common.entity.goal.AxeGuardCraftingGoal;
import dev.sterner.guardvillagers.common.entity.goal.AxeGuardDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.AxeGuardFurnaceGoal;
import dev.sterner.guardvillagers.common.entity.goal.AxeGuardGatheringGoal;
import dev.sterner.guardvillagers.common.entity.goal.AxeGuardWorkflowRegistry;
import dev.sterner.guardvillagers.common.villager.LumberjackLifecyclePhase;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.FurnaceBlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.context.LootContextParameterSet;
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class AxeGuardEntity extends GuardEntity {
    private BlockPos jobPos;
    private BlockPos chestPos;
    private BlockPos craftingTablePos;
    private BlockPos pairedFurnacePos;

    private long workflowCountdownStartTick;
    private long workflowCountdownTotalTicks;
    private long nextSessionStartTick;
    private int sessionsCompletedToday;
    private int furnaceBatchInputLogs;
    private LumberjackLifecyclePhase lumberjackLifecyclePhase = LumberjackLifecyclePhase.CONVERTED_ACTIVE;

    private AxeGuardGatheringGoal gatheringGoal;
    private AxeGuardCraftingGoal craftingGoal;
    private AxeGuardFurnaceGoal furnaceGoal;
    private AxeGuardDistributionGoal distributionGoal;

    public AxeGuardEntity(EntityType<? extends GuardEntity> type, World world) {
        super(type, world);
    }

    @Override
    public List<ItemStack> getStacksFromLootTable(EquipmentSlot slot, ServerWorld serverWorld) {
        if (slot == EquipmentSlot.MAINHAND) {
            LootTable loot = serverWorld.getServer()
                    .getReloadableRegistries()
                    .getLootTable(GuardEntityLootTables.AXE_GUARD_MAIN_HAND);
            LootContextParameterSet.Builder lootContextBuilder =
                    new LootContextParameterSet.Builder(serverWorld).add(LootContextParameters.THIS_ENTITY, this);
            return loot.generateLoot(lootContextBuilder.build(GuardEntityLootTables.SLOT));
        }
        return super.getStacksFromLootTable(slot, serverWorld);
    }

    @Override
    protected void initGoals() {
        super.initGoals();
        AxeGuardBootstrapGoal bootstrapGoal = new AxeGuardBootstrapGoal(this);
        this.gatheringGoal = new AxeGuardGatheringGoal(this);
        this.furnaceGoal = new AxeGuardFurnaceGoal(this);
        this.craftingGoal = new AxeGuardCraftingGoal(this);
        this.distributionGoal = new AxeGuardDistributionGoal(this);

        this.goalSelector.add(2, bootstrapGoal);
        this.goalSelector.add(3, this.gatheringGoal);
        this.goalSelector.add(4, this.furnaceGoal);
        this.goalSelector.add(5, this.craftingGoal);
        this.goalSelector.add(6, this.distributionGoal);
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.getWorld().isClient) {
            AxeGuardWorkflowRegistry.updateWatch(this, chestPos);
        }
    }

    public void notifyWorkflowChestMutation() {
        if (gatheringGoal != null) {
            gatheringGoal.requestImmediateCheck();
        }
        if (craftingGoal != null) {
            craftingGoal.requestImmediateCheck();
        }
        if (furnaceGoal != null) {
            furnaceGoal.requestImmediateCheck();
        }
        if (distributionGoal != null) {
            distributionGoal.requestImmediateDistribution();
        }
    }

    public Optional<Inventory> getPairedChestInventory(ServerWorld world) {
        if (chestPos == null) {
            return Optional.empty();
        }
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, chestPos, true));
    }

    public Optional<FurnaceBlockEntity> getPairedFurnace(ServerWorld world) {
        if (pairedFurnacePos == null) {
            return Optional.empty();
        }
        return world.getBlockEntity(pairedFurnacePos, net.minecraft.block.entity.BlockEntityType.FURNACE);
    }

    public ItemStack insertIntoInventory(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (remaining.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                if (!inventory.isValid(slot, remaining)) continue;
                int moved = Math.min(remaining.getCount(), remaining.getMaxCount());
                ItemStack toInsert = remaining.copy();
                toInsert.setCount(moved);
                inventory.setStack(slot, toInsert);
                remaining.decrement(moved);
                continue;
            }
            if (!ItemStack.areItemsAndComponentsEqual(existing, remaining) || !inventory.isValid(slot, remaining)) {
                continue;
            }
            int space = existing.getMaxCount() - existing.getCount();
            if (space <= 0) continue;
            int moved = Math.min(space, remaining.getCount());
            existing.increment(moved);
            remaining.decrement(moved);
        }
        return remaining;
    }

    public int countMatching(Inventory inventory, Predicate<ItemStack> matcher) {
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && matcher.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public int consumeMatching(Inventory inventory, Predicate<ItemStack> matcher, int requestedCount) {
        int remaining = requestedCount;
        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !matcher.test(stack)) continue;
            int removed = Math.min(remaining, stack.getCount());
            stack.decrement(removed);
            remaining -= removed;
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
        }
        return requestedCount - remaining;
    }

    public int extractToStack(Inventory inventory, Predicate<ItemStack> matcher, int count, Consumer<ItemStack> consumer) {
        int remaining = count;
        int moved = 0;
        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !matcher.test(stack)) continue;
            int take = Math.min(remaining, stack.getCount());
            ItemStack extracted = stack.copy();
            extracted.setCount(take);
            consumer.accept(extracted);
            stack.decrement(take);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            moved += take;
            remaining -= take;
        }
        return moved;
    }

    public void initializeConvertedWorkflow(ServerWorld world, long transferredCountdownTicks) {
        this.lumberjackLifecyclePhase = LumberjackLifecyclePhase.CONVERTED_ACTIVE;
        startWorkflowCountdown(world, transferredCountdownTicks);
    }

    public LumberjackLifecyclePhase getLumberjackLifecyclePhase() {
        return lumberjackLifecyclePhase;
    }

    public void setLumberjackLifecyclePhase(LumberjackLifecyclePhase lumberjackLifecyclePhase) {
        this.lumberjackLifecyclePhase = lumberjackLifecyclePhase == null
                ? LumberjackLifecyclePhase.CONVERTED_ACTIVE
                : lumberjackLifecyclePhase;
    }

    public void startWorkflowCountdown(ServerWorld world, long totalTicks) {
        this.workflowCountdownStartTick = world.getTime();
        this.workflowCountdownTotalTicks = Math.max(20L, totalTicks);
        this.nextSessionStartTick = this.workflowCountdownStartTick + this.workflowCountdownTotalTicks;
    }

    public void incrementSessionsCompletedToday() {
        this.sessionsCompletedToday++;
    }

    public BlockPos getJobPos() {
        return jobPos;
    }

    public void setJobPos(BlockPos jobPos) {
        this.jobPos = jobPos == null ? null : jobPos.toImmutable();
    }

    public BlockPos getChestPos() {
        return chestPos;
    }

    public void setChestPos(BlockPos chestPos) {
        this.chestPos = chestPos == null ? null : chestPos.toImmutable();
    }

    public BlockPos getCraftingTablePos() {
        return craftingTablePos;
    }

    public void setCraftingTablePos(BlockPos craftingTablePos) {
        this.craftingTablePos = craftingTablePos == null ? null : craftingTablePos.toImmutable();
    }

    public BlockPos getPairedFurnacePos() {
        return pairedFurnacePos;
    }

    public void setPairedFurnacePos(BlockPos pairedFurnacePos) {
        this.pairedFurnacePos = pairedFurnacePos == null ? null : pairedFurnacePos.toImmutable();
    }

    public long getNextSessionStartTick() {
        return nextSessionStartTick;
    }

    public void setFurnaceBatchInputLogs(int furnaceBatchInputLogs) {
        this.furnaceBatchInputLogs = furnaceBatchInputLogs;
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        this.jobPos = readPos(nbt, "AxeGuardJob");
        this.chestPos = readPos(nbt, "AxeGuardChest");
        this.craftingTablePos = readPos(nbt, "AxeGuardCraftingTable");
        this.pairedFurnacePos = readPos(nbt, "AxeGuardFurnace");
        this.workflowCountdownStartTick = nbt.getLong("AxeGuardCountdownStart");
        this.workflowCountdownTotalTicks = nbt.getLong("AxeGuardCountdownTotal");
        this.nextSessionStartTick = nbt.getLong("AxeGuardNextSessionStart");
        this.sessionsCompletedToday = nbt.getInt("AxeGuardSessionsCompletedToday");
        this.furnaceBatchInputLogs = nbt.getInt("AxeGuardFurnaceBatchInput");
        if (nbt.contains("AxeGuardLifecyclePhase")) {
            try {
                this.lumberjackLifecyclePhase = LumberjackLifecyclePhase.valueOf(nbt.getString("AxeGuardLifecyclePhase"));
            } catch (IllegalArgumentException ignored) {
                this.lumberjackLifecyclePhase = LumberjackLifecyclePhase.CONVERTED_ACTIVE;
            }
        } else {
            this.lumberjackLifecyclePhase = LumberjackLifecyclePhase.CONVERTED_ACTIVE;
        }
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        writePos(nbt, "AxeGuardJob", this.jobPos);
        writePos(nbt, "AxeGuardChest", this.chestPos);
        writePos(nbt, "AxeGuardCraftingTable", this.craftingTablePos);
        writePos(nbt, "AxeGuardFurnace", this.pairedFurnacePos);
        nbt.putLong("AxeGuardCountdownStart", this.workflowCountdownStartTick);
        nbt.putLong("AxeGuardCountdownTotal", this.workflowCountdownTotalTicks);
        nbt.putLong("AxeGuardNextSessionStart", this.nextSessionStartTick);
        nbt.putInt("AxeGuardSessionsCompletedToday", this.sessionsCompletedToday);
        nbt.putInt("AxeGuardFurnaceBatchInput", this.furnaceBatchInputLogs);
        nbt.putString("AxeGuardLifecyclePhase", this.lumberjackLifecyclePhase.name());
    }

    private static void writePos(NbtCompound nbt, String key, BlockPos pos) {
        if (pos == null) return;
        nbt.putInt(key + "X", pos.getX());
        nbt.putInt(key + "Y", pos.getY());
        nbt.putInt(key + "Z", pos.getZ());
    }

    private static BlockPos readPos(NbtCompound nbt, String key) {
        if (!nbt.contains(key + "X")) {
            return null;
        }
        return new BlockPos(nbt.getInt(key + "X"), nbt.getInt(key + "Y"), nbt.getInt(key + "Z"));
    }
}
