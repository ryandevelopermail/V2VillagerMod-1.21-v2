package dev.sterner.guardvillagers.common.entity;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.goal.ArmorerRepairGuardArmorGoal;
import dev.sterner.guardvillagers.common.entity.goal.FollowShieldGuards;
import dev.sterner.guardvillagers.common.entity.goal.GuardEatFoodGoal;
import dev.sterner.guardvillagers.common.entity.goal.GuardInteractDoorGoal;
import dev.sterner.guardvillagers.common.entity.goal.GuardLookAtAndStopMovingWhenBeingTheInteractionTarget;
import dev.sterner.guardvillagers.common.entity.goal.GuardRunToEatGoal;
import dev.sterner.guardvillagers.common.entity.goal.KickGoal;
import dev.sterner.guardvillagers.common.entity.goal.MasonMiningStairGoal;
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
import net.minecraft.registry.Registries;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class MasonGuardEntity extends GuardEntity {
    private static final Logger LOGGER = LoggerFactory.getLogger(MasonGuardEntity.class);

    private BlockPos pairedChestPos;
    private BlockPos pairedJobPos;
    private ItemStack expectedMiningTool = ItemStack.EMPTY;
    private boolean loggedSpawnValidation;

    public MasonGuardEntity(EntityType<? extends GuardEntity> type, World world) {
        super(type, world);
    }

    public void setPairedChestPos(BlockPos chestPos) {
        this.pairedChestPos = chestPos == null ? null : chestPos.toImmutable();
    }

    public void setPairedJobPos(BlockPos jobPos) {
        this.pairedJobPos = jobPos == null ? null : jobPos.toImmutable();
    }

    public void setExpectedMiningTool(ItemStack stack) {
        this.expectedMiningTool = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
    }

    public BlockPos getPairedChestPos() {
        return pairedChestPos;
    }

    public BlockPos getPairedJobPos() {
        return pairedJobPos;
    }

    public ItemStack getExpectedMiningTool() {
        return expectedMiningTool;
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
        this.goalSelector.add(3, new FollowHeroGoal(this));
        if (GuardVillagersConfig.guardEntitysRunFromPolarBears) {
            this.goalSelector.add(3, new FleeEntityGoal<>(this, PolarBearEntity.class, 12.0F, 1.0D, 1.2D));
        }
        this.goalSelector.add(2, new MasonMiningStairGoal(this));
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
        if (!this.getWorld().isClient && !this.loggedSpawnValidation && this.age > 1) {
            this.loggedSpawnValidation = true;
            LOGGER.info("Mason Guard {} spawn validation: selectedChestTool={}, equippedItem={}, pairedChestPos={}, pairedJobPos={}, roleType={}",
                    this.getUuidAsString(),
                    this.expectedMiningTool.isEmpty() ? "empty" : Registries.ITEM.getId(this.expectedMiningTool.getItem()),
                    this.getMainHandStack().isEmpty() ? "empty" : Registries.ITEM.getId(this.getMainHandStack().getItem()),
                    this.pairedChestPos == null ? "none" : this.pairedChestPos.toShortString(),
                    this.pairedJobPos == null ? "none" : this.pairedJobPos.toShortString(),
                    "mason");
        }

        this.setTarget(null);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        this.loggedSpawnValidation = nbt.getBoolean("MasonLoggedSpawnValidation");
        if (nbt.contains("MasonPairedChestX")) {
            this.pairedChestPos = new BlockPos(nbt.getInt("MasonPairedChestX"), nbt.getInt("MasonPairedChestY"), nbt.getInt("MasonPairedChestZ"));
        } else {
            this.pairedChestPos = null;
        }
        if (nbt.contains("MasonPairedJobX")) {
            this.pairedJobPos = new BlockPos(nbt.getInt("MasonPairedJobX"), nbt.getInt("MasonPairedJobY"), nbt.getInt("MasonPairedJobZ"));
        } else {
            this.pairedJobPos = null;
        }
        if (nbt.contains("MasonExpectedTool")) {
            this.expectedMiningTool = ItemStack.fromNbtOrEmpty(this.getRegistryManager(), nbt.getCompound("MasonExpectedTool"));
        } else {
            this.expectedMiningTool = ItemStack.EMPTY;
        }
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putBoolean("MasonLoggedSpawnValidation", this.loggedSpawnValidation);
        if (this.pairedChestPos != null) {
            nbt.putInt("MasonPairedChestX", this.pairedChestPos.getX());
            nbt.putInt("MasonPairedChestY", this.pairedChestPos.getY());
            nbt.putInt("MasonPairedChestZ", this.pairedChestPos.getZ());
        }
        if (this.pairedJobPos != null) {
            nbt.putInt("MasonPairedJobX", this.pairedJobPos.getX());
            nbt.putInt("MasonPairedJobY", this.pairedJobPos.getY());
            nbt.putInt("MasonPairedJobZ", this.pairedJobPos.getZ());
        }
        if (!this.expectedMiningTool.isEmpty()) {
            NbtCompound toolNbt = new NbtCompound();
            nbt.put("MasonExpectedTool", this.expectedMiningTool.encode(this.getRegistryManager(), toolNbt));
        }
    }
}
