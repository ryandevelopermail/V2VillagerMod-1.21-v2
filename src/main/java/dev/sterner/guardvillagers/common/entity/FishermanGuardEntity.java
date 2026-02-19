package dev.sterner.guardvillagers.common.entity;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.goal.ArmorerRepairGuardArmorGoal;
import dev.sterner.guardvillagers.common.entity.goal.FishermanGuardFishingGoal;
import dev.sterner.guardvillagers.common.entity.goal.FollowShieldGuards;
import dev.sterner.guardvillagers.common.entity.goal.GuardEatFoodGoal;
import dev.sterner.guardvillagers.common.entity.goal.GuardInteractDoorGoal;
import dev.sterner.guardvillagers.common.entity.goal.GuardLookAtAndStopMovingWhenBeingTheInteractionTarget;
import dev.sterner.guardvillagers.common.entity.goal.GuardRunToEatGoal;
import dev.sterner.guardvillagers.common.entity.goal.KickGoal;
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
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;

public class FishermanGuardEntity extends GuardEntity {
    private BlockPos pairedChestPos;
    private BlockPos pairedJobPos;

    public FishermanGuardEntity(EntityType<? extends GuardEntity> type, World world) {
        super(type, world);
    }

    public void setPairedChestPos(BlockPos chestPos) {
        this.pairedChestPos = chestPos == null ? null : chestPos.toImmutable();
    }

    public void setPairedJobPos(BlockPos jobPos) {
        this.pairedJobPos = jobPos == null ? null : jobPos.toImmutable();
    }

    public BlockPos getPairedChestPos() {
        return pairedChestPos;
    }

    public BlockPos getPairedJobPos() {
        return pairedJobPos;
    }

    @Override
    public List<ItemStack> getStacksFromLootTable(EquipmentSlot slot, ServerWorld serverWorld) {
        if (slot == EquipmentSlot.MAINHAND) {
            return List.of();
        }
        return super.getStacksFromLootTable(slot, serverWorld);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(0, new KickGoal(this));
        this.goalSelector.add(0, new GuardEatFoodGoal(this));
        this.goalSelector.add(0, new RaiseShieldGoal(this));
        this.goalSelector.add(1, new RespondToHornGoal(this, 1.0D));
        this.goalSelector.add(1, new GuardRunToEatGoal(this));
        if (GuardVillagersConfig.guardEntitysRunFromPolarBears) {
            this.goalSelector.add(3, new FleeEntityGoal<>(this, PolarBearEntity.class, 12.0F, 1.0D, 1.2D));
        }
        this.goalSelector.add(2, new FishermanGuardFishingGoal(this));
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
        if (nbt.contains("FishermanPairedChestX")) {
            this.pairedChestPos = new BlockPos(nbt.getInt("FishermanPairedChestX"), nbt.getInt("FishermanPairedChestY"), nbt.getInt("FishermanPairedChestZ"));
        } else {
            this.pairedChestPos = null;
        }

        if (nbt.contains("FishermanPairedJobX")) {
            this.pairedJobPos = new BlockPos(nbt.getInt("FishermanPairedJobX"), nbt.getInt("FishermanPairedJobY"), nbt.getInt("FishermanPairedJobZ"));
        } else {
            this.pairedJobPos = null;
        }
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (this.pairedChestPos != null) {
            nbt.putInt("FishermanPairedChestX", this.pairedChestPos.getX());
            nbt.putInt("FishermanPairedChestY", this.pairedChestPos.getY());
            nbt.putInt("FishermanPairedChestZ", this.pairedChestPos.getZ());
        }

        if (this.pairedJobPos != null) {
            nbt.putInt("FishermanPairedJobX", this.pairedJobPos.getX());
            nbt.putInt("FishermanPairedJobY", this.pairedJobPos.getY());
            nbt.putInt("FishermanPairedJobZ", this.pairedJobPos.getZ());
        }
    }
}
