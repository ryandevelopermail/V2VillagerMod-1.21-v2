package dev.sterner.guardvillagers.common.entity;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.goal.ArmorerRepairGuardArmorGoal;
import dev.sterner.guardvillagers.common.entity.goal.FollowShieldGuards;
import dev.sterner.guardvillagers.common.entity.goal.GuardEatFoodGoal;
import dev.sterner.guardvillagers.common.entity.goal.GuardInteractDoorGoal;
import dev.sterner.guardvillagers.common.entity.goal.GuardLookAtAndStopMovingWhenBeingTheInteractionTarget;
import dev.sterner.guardvillagers.common.entity.goal.GuardRunToEatGoal;
import dev.sterner.guardvillagers.common.entity.goal.KickGoal;
import dev.sterner.guardvillagers.common.entity.goal.RaiseShieldGoal;
import dev.sterner.guardvillagers.common.entity.goal.RangedBowAttackPassiveGoal;
import dev.sterner.guardvillagers.common.entity.goal.RangedCrossbowAttackPassiveGoal;
import dev.sterner.guardvillagers.common.entity.goal.RunToClericGoal;
import dev.sterner.guardvillagers.common.entity.goal.WalkBackToCheckPointGoal;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.goal.FleeEntityGoal;
import net.minecraft.entity.ai.goal.IronGolemWanderAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MoveThroughVillageGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.UniversalAngerGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.ai.goal.WanderAroundPointOfInterestGoal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.passive.PolarBearEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;

public class MasonGuardEntity extends GuardEntity {
    private BlockPos pairedChestPos;
    private BlockPos pairedStonecutterPos;

    public MasonGuardEntity(EntityType<? extends GuardEntity> type, World world) {
        super(type, world);
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
        this.goalSelector.add(1, new GuardEntity.RespondToHornGoal(this, 1.0D));
        this.goalSelector.add(1, new GuardRunToEatGoal(this));
        this.goalSelector.add(2, new RangedCrossbowAttackPassiveGoal<>(this, 1.0D, 8.0F));
        this.goalSelector.add(3, new RangedBowAttackPassiveGoal<GuardEntity>(this, 0.5D, 20, 15.0F) {
            @Override
            public boolean canStart() {
                return MasonGuardEntity.this.getTarget() != null && this.isBowInMainhand() && !MasonGuardEntity.this.isEating() && !MasonGuardEntity.this.isBlocking();
            }

            protected boolean isBowInMainhand() {
                return MasonGuardEntity.this.getMainHandStack().getItem() instanceof BowItem;
            }

            @Override
            public void tick() {
                super.tick();
                if (MasonGuardEntity.this.isPatrolling()) {
                    MasonGuardEntity.this.getNavigation().stop();
                    MasonGuardEntity.this.getMoveControl().strafeTo(0.0F, 0.0F);
                }
            }

            @Override
            public boolean shouldContinue() {
                return (this.canStart() || !MasonGuardEntity.this.getNavigation().isIdle()) && this.isBowInMainhand();
            }
        });
        this.goalSelector.add(2, new GuardEntity.GuardEntityMeleeGoal(this, 0.8D, true));
        this.goalSelector.add(3, new GuardEntity.FollowHeroGoal(this));
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

        this.targetSelector.add(1, new RevengeGoal(this, GuardEntity.class, MobEntity.class));
        this.targetSelector.add(3, new UniversalAngerGoal<>(this, false));
    }

    public void setPairedChestPos(BlockPos chestPos) {
        this.pairedChestPos = chestPos == null ? null : chestPos.toImmutable();
    }

    public void setPairedStonecutterPos(BlockPos stonecutterPos) {
        this.pairedStonecutterPos = stonecutterPos == null ? null : stonecutterPos.toImmutable();
    }

    public BlockPos getPairedChestPos() {
        return this.pairedChestPos;
    }

    public BlockPos getPairedStonecutterPos() {
        return this.pairedStonecutterPos;
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("PairedChestX")) {
            this.pairedChestPos = new BlockPos(nbt.getInt("PairedChestX"), nbt.getInt("PairedChestY"), nbt.getInt("PairedChestZ"));
        } else {
            this.pairedChestPos = null;
        }
        if (nbt.contains("PairedStonecutterX")) {
            this.pairedStonecutterPos = new BlockPos(nbt.getInt("PairedStonecutterX"), nbt.getInt("PairedStonecutterY"), nbt.getInt("PairedStonecutterZ"));
        } else {
            this.pairedStonecutterPos = null;
        }
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (this.pairedChestPos != null) {
            nbt.putInt("PairedChestX", this.pairedChestPos.getX());
            nbt.putInt("PairedChestY", this.pairedChestPos.getY());
            nbt.putInt("PairedChestZ", this.pairedChestPos.getZ());
        }
        if (this.pairedStonecutterPos != null) {
            nbt.putInt("PairedStonecutterX", this.pairedStonecutterPos.getX());
            nbt.putInt("PairedStonecutterY", this.pairedStonecutterPos.getY());
            nbt.putInt("PairedStonecutterZ", this.pairedStonecutterPos.getZ());
        }
    }
}
