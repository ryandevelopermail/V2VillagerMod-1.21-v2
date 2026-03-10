package dev.sterner.guardvillagers.common.entity;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.goal.ArmorerRepairGuardArmorGoal;
import dev.sterner.guardvillagers.common.entity.goal.FollowShieldGuards;
import dev.sterner.guardvillagers.common.entity.goal.GuardEatFoodGoal;
import dev.sterner.guardvillagers.common.entity.goal.GuardInteractDoorGoal;
import dev.sterner.guardvillagers.common.entity.goal.GuardLookAtAndStopMovingWhenBeingTheInteractionTarget;
import dev.sterner.guardvillagers.common.entity.goal.GuardRunToEatGoal;
import dev.sterner.guardvillagers.common.entity.goal.KickGoal;
import dev.sterner.guardvillagers.common.entity.goal.LumberjackGuardChopTreesGoal;
import dev.sterner.guardvillagers.common.entity.goal.LumberjackGuardDepositLogsGoal;
import dev.sterner.guardvillagers.common.entity.goal.RaiseShieldGoal;
import dev.sterner.guardvillagers.common.entity.goal.RunToClericGoal;
import dev.sterner.guardvillagers.common.entity.goal.WalkBackToCheckPointGoal;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.goal.FleeEntityGoal;
import net.minecraft.entity.ai.goal.IronGolemWanderAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MoveThroughVillageGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.ai.goal.WanderAroundPointOfInterestGoal;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.passive.PolarBearEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.World;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.List;

public class LumberjackGuardEntity extends GuardEntity {
    private BlockPos pairedCraftingTablePos;
    private BlockPos pairedChestPos;
    private long nextChopTick;
    private boolean activeSession;
    private int sessionTargetsRemaining;
    private long chopCountdownTotalTicks;
    private long chopCountdownStartTick;
    private int chopCountdownLastLogStep;
    private boolean chopCountdownActive;
    private final List<BlockPos> selectedTreeTargets = new ArrayList<>();
    private final List<ItemStack> gatheredStackBuffer = new ArrayList<>();
    private WorkflowStage workflowStage = WorkflowStage.IDLE;

    public LumberjackGuardEntity(EntityType<? extends GuardEntity> type, World world) {
        super(type, world);
    }

    public void setPairedCraftingTablePos(BlockPos pos) {
        this.pairedCraftingTablePos = pos == null ? null : pos.toImmutable();
    }

    public BlockPos getPairedCraftingTablePos() {
        return this.pairedCraftingTablePos;
    }

    public void setPairedChestPos(BlockPos pos) {
        this.pairedChestPos = pos == null ? null : pos.toImmutable();
    }

    public BlockPos getPairedChestPos() {
        return this.pairedChestPos;
    }

    public long getNextChopTick() {
        return this.nextChopTick;
    }

    public void setNextChopTick(long nextChopTick) {
        this.nextChopTick = nextChopTick;
    }

    public boolean isActiveSession() {
        return this.activeSession;
    }

    public void setActiveSession(boolean activeSession) {
        this.activeSession = activeSession;
    }

    public List<BlockPos> getSelectedTreeTargets() {
        return this.selectedTreeTargets;
    }

    public int getSessionTargetsRemaining() {
        return this.sessionTargetsRemaining;
    }

    public void setSessionTargetsRemaining(int sessionTargetsRemaining) {
        this.sessionTargetsRemaining = Math.max(sessionTargetsRemaining, 0);
    }

    public long getChopCountdownTotalTicks() {
        return this.chopCountdownTotalTicks;
    }

    public long getChopCountdownStartTick() {
        return this.chopCountdownStartTick;
    }

    public int getChopCountdownLastLogStep() {
        return this.chopCountdownLastLogStep;
    }

    public void setChopCountdownLastLogStep(int chopCountdownLastLogStep) {
        this.chopCountdownLastLogStep = Math.max(chopCountdownLastLogStep, 0);
    }

    public boolean isChopCountdownActive() {
        return this.chopCountdownActive;
    }

    public void startChopCountdown(long startTick, long totalTicks) {
        this.chopCountdownStartTick = startTick;
        this.chopCountdownTotalTicks = Math.max(totalTicks, 0L);
        this.nextChopTick = startTick + this.chopCountdownTotalTicks;
        this.chopCountdownLastLogStep = 0;
        this.chopCountdownActive = true;
    }

    public void clearChopCountdown() {
        this.chopCountdownStartTick = 0L;
        this.chopCountdownTotalTicks = 0L;
        this.chopCountdownLastLogStep = 0;
        this.chopCountdownActive = false;
    }

    public List<ItemStack> getGatheredStackBuffer() {
        return this.gatheredStackBuffer;
    }

    public WorkflowStage getWorkflowStage() {
        return this.workflowStage;
    }

    public void setWorkflowStage(WorkflowStage workflowStage) {
        this.workflowStage = workflowStage == null ? WorkflowStage.IDLE : workflowStage;
    }

    @Override
    protected void initEquipment(Random random, LocalDifficulty localDifficulty) {
        super.initEquipment(random, localDifficulty);
        this.spawnWithArmor = false;
        this.equipStack(EquipmentSlot.HEAD, ItemStack.EMPTY);
        this.equipStack(EquipmentSlot.CHEST, ItemStack.EMPTY);
        this.equipStack(EquipmentSlot.LEGS, ItemStack.EMPTY);
        this.equipStack(EquipmentSlot.FEET, ItemStack.EMPTY);
        this.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        this.equipStack(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
    }

    @Override
    public List<ItemStack> getStacksFromLootTable(EquipmentSlot slot, ServerWorld serverWorld) {
        return List.of();
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(0, new KickGoal(this));
        this.goalSelector.add(0, new GuardEatFoodGoal(this));
        this.goalSelector.add(0, new RaiseShieldGoal(this));
        this.goalSelector.add(1, new RespondToHornGoal(this, 1.0D));
        this.goalSelector.add(1, new GuardRunToEatGoal(this));
        this.goalSelector.add(2, new LumberjackGuardChopTreesGoal(this));
        this.goalSelector.add(3, new LumberjackGuardDepositLogsGoal(this));
        if (GuardVillagersConfig.guardEntitysRunFromPolarBears) {
            this.goalSelector.add(3, new FleeEntityGoal<>(this, PolarBearEntity.class, 12.0F, 1.0D, 1.2D));
        }
        this.goalSelector.add(3, new WanderAroundPointOfInterestGoal(this, 0.5D, false));
        this.goalSelector.add(3, new IronGolemWanderAroundGoal(this, 0.5D));
        this.goalSelector.add(3, new MoveThroughVillageGoal(this, 0.5D, false, 4, () -> false));
        if (GuardVillagersConfig.guardEntitysOpenDoors) {
            this.goalSelector.add(3, new GuardInteractDoorGoal(this, true));
        }
        if (GuardVillagersConfig.guardEntityFormation) {
            this.goalSelector.add(5, new FollowShieldGuards(this));
        }
        if (GuardVillagersConfig.clericHealing) {
            this.goalSelector.add(6, new RunToClericGoal(this));
        }
        if (GuardVillagersConfig.armorerRepairGuardEntityArmor) {
            this.goalSelector.add(6, new ArmorerRepairGuardArmorGoal(this));
        }
        this.goalSelector.add(4, new WalkBackToCheckPointGoal(this, 0.5D));
        this.goalSelector.add(5, new WanderAroundFarGoal(this, 0.5D));
        this.goalSelector.add(8, new LookAtEntityGoal(this, MerchantEntity.class, 8.0F));
        this.goalSelector.add(8, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));
        this.goalSelector.add(8, new GuardLookAtAndStopMovingWhenBeingTheInteractionTarget(this));
    }

    @Override
    public void tick() {
        super.tick();
        this.setTarget(null);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("LumberjackPairedCraftingTableX")) {
            this.pairedCraftingTablePos = new BlockPos(nbt.getInt("LumberjackPairedCraftingTableX"), nbt.getInt("LumberjackPairedCraftingTableY"), nbt.getInt("LumberjackPairedCraftingTableZ"));
        } else {
            this.pairedCraftingTablePos = null;
        }

        if (nbt.contains("LumberjackPairedChestX")) {
            this.pairedChestPos = new BlockPos(nbt.getInt("LumberjackPairedChestX"), nbt.getInt("LumberjackPairedChestY"), nbt.getInt("LumberjackPairedChestZ"));
        } else {
            this.pairedChestPos = null;
        }

        this.nextChopTick = nbt.contains("LumberjackNextChopTick") ? nbt.getLong("LumberjackNextChopTick") : 0L;
        this.activeSession = nbt.getBoolean("LumberjackActiveSession");
        this.sessionTargetsRemaining = nbt.contains("LumberjackSessionTargetsRemaining") ? nbt.getInt("LumberjackSessionTargetsRemaining") : 0;
        this.chopCountdownTotalTicks = nbt.contains("LumberjackChopCountdownTotalTicks") ? nbt.getLong("LumberjackChopCountdownTotalTicks") : 0L;
        this.chopCountdownStartTick = nbt.contains("LumberjackChopCountdownStartTick") ? nbt.getLong("LumberjackChopCountdownStartTick") : 0L;
        this.chopCountdownLastLogStep = nbt.contains("LumberjackChopCountdownLastLogStep") ? nbt.getInt("LumberjackChopCountdownLastLogStep") : 0;
        this.chopCountdownActive = nbt.getBoolean("LumberjackChopCountdownActive");
        this.workflowStage = WorkflowStage.fromSerialized(nbt.getString("LumberjackWorkflowStage"));

        this.selectedTreeTargets.clear();
        NbtList targetList = nbt.getList("LumberjackSelectedTreeTargets", 10);
        for (int i = 0; i < targetList.size(); i++) {
            NbtCompound targetNbt = targetList.getCompound(i);
            if (!targetNbt.contains("X") || !targetNbt.contains("Y") || !targetNbt.contains("Z")) {
                continue;
            }
            this.selectedTreeTargets.add(new BlockPos(targetNbt.getInt("X"), targetNbt.getInt("Y"), targetNbt.getInt("Z")));
        }

        this.gatheredStackBuffer.clear();
        NbtList bufferList = nbt.getList("LumberjackGatheredStackBuffer", 10);
        for (int i = 0; i < bufferList.size(); i++) {
            ItemStack stack = ItemStack.fromNbtOrEmpty(this.getRegistryManager(), bufferList.getCompound(i));
            if (!stack.isEmpty()) {
                this.gatheredStackBuffer.add(stack);
            }
        }
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (this.pairedCraftingTablePos != null) {
            nbt.putInt("LumberjackPairedCraftingTableX", this.pairedCraftingTablePos.getX());
            nbt.putInt("LumberjackPairedCraftingTableY", this.pairedCraftingTablePos.getY());
            nbt.putInt("LumberjackPairedCraftingTableZ", this.pairedCraftingTablePos.getZ());
        }
        if (this.pairedChestPos != null) {
            nbt.putInt("LumberjackPairedChestX", this.pairedChestPos.getX());
            nbt.putInt("LumberjackPairedChestY", this.pairedChestPos.getY());
            nbt.putInt("LumberjackPairedChestZ", this.pairedChestPos.getZ());
        }
        nbt.putLong("LumberjackNextChopTick", this.nextChopTick);
        nbt.putBoolean("LumberjackActiveSession", this.activeSession);
        nbt.putInt("LumberjackSessionTargetsRemaining", this.sessionTargetsRemaining);
        nbt.putLong("LumberjackChopCountdownTotalTicks", this.chopCountdownTotalTicks);
        nbt.putLong("LumberjackChopCountdownStartTick", this.chopCountdownStartTick);
        nbt.putInt("LumberjackChopCountdownLastLogStep", this.chopCountdownLastLogStep);
        nbt.putBoolean("LumberjackChopCountdownActive", this.chopCountdownActive);
        nbt.putString("LumberjackWorkflowStage", this.workflowStage.getSerializedName());

        NbtList targetList = new NbtList();
        for (BlockPos pos : this.selectedTreeTargets) {
            NbtCompound targetNbt = new NbtCompound();
            targetNbt.putInt("X", pos.getX());
            targetNbt.putInt("Y", pos.getY());
            targetNbt.putInt("Z", pos.getZ());
            targetList.add(targetNbt);
        }
        nbt.put("LumberjackSelectedTreeTargets", targetList);

        NbtList bufferList = new NbtList();
        for (ItemStack stack : this.gatheredStackBuffer) {
            if (stack.isEmpty()) {
                continue;
            }
            NbtCompound stackNbt = new NbtCompound();
            bufferList.add(stack.encode(this.getRegistryManager(), stackNbt));
        }
        nbt.put("LumberjackGatheredStackBuffer", bufferList);
    }

    public enum WorkflowStage {
        IDLE,
        MOVING_TO_TREE,
        CHOPPING,
        RETURNING_TO_BASE,
        MOVING_TO_CHEST,
        DEPOSITING;

        public String getSerializedName() {
            return name().toLowerCase();
        }

        public static WorkflowStage fromSerialized(String value) {
            for (WorkflowStage stage : values()) {
                if (stage.getSerializedName().equals(value)) {
                    return stage;
                }
            }
            return IDLE;
        }
    }
}
