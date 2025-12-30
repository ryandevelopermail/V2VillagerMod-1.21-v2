package dev.sterner.guardvillagers.common.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.goal.ArmorerRepairGuardArmorGoal;
import dev.sterner.guardvillagers.common.entity.goal.ButcherGuardSmokerGoal;
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
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
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
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.passive.PolarBearEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ButcherGuardEntity extends GuardEntity {
    private static final Logger LOGGER = LoggerFactory.getLogger(ButcherGuardEntity.class);
    private static final int HUNT_SESSION_MAX_TICKS = 20 * 60;
    private static final int HUNT_TARGET_MIN = 1;
    private static final int HUNT_TARGET_MAX = 5;
    private static final int HUNT_INTERVAL_MIN_TICKS = 20 * 60 * 3;
    private static final int HUNT_INTERVAL_MAX_TICKS = 20 * 60 * 10;
    private static final int LOOT_SCAN_INTERVAL = 20;
    private static final double LOOT_SCAN_RANGE = 6.0D;

    private boolean huntOnSpawn;
    private boolean huntSessionActive;
    private int huntTargetsRemaining;
    private long huntSessionEndTick;
    private long nextHuntTriggerTime;
    private long huntCountdownTotalTicks;
    private long huntCountdownStartTime;
    private int lastHuntCountdownLogStep;
    private boolean huntCountdownActive;
    private final List<ItemStack> collectedLoot = new ArrayList<>();
    private BlockPos pairedChestPos;
    private BlockPos pairedSmokerPos;

    public ButcherGuardEntity(EntityType<? extends GuardEntity> type, World world) {
        super(type, world);
    }

    public void setHuntOnSpawn() {
        this.huntOnSpawn = true;
    }

    public void setPairedChestPos(BlockPos chestPos) {
        this.pairedChestPos = chestPos == null ? null : chestPos.toImmutable();
    }

    public void setPairedSmokerPos(BlockPos smokerPos) {
        this.pairedSmokerPos = smokerPos == null ? null : smokerPos.toImmutable();
    }

    public BlockPos getPairedChestPos() {
        return pairedChestPos;
    }

    public BlockPos getPairedSmokerPos() {
        return pairedSmokerPos;
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
        this.goalSelector.add(2, new ButcherGuardSmokerGoal(this));
        this.goalSelector.add(2, new RangedCrossbowAttackPassiveGoal<>(this, 1.0D, 8.0F));
        this.goalSelector.add(3, new RangedBowAttackPassiveGoal<GuardEntity>(this, 0.5D, 20, 15.0F) {
            @Override
            public boolean canStart() {
                return ButcherGuardEntity.this.getTarget() != null && this.isBowInMainhand() && !ButcherGuardEntity.this.isEating() && !ButcherGuardEntity.this.isBlocking();
            }

            protected boolean isBowInMainhand() {
                return ButcherGuardEntity.this.getMainHandStack().getItem() instanceof BowItem;
            }

            @Override
            public void tick() {
                super.tick();
                if (ButcherGuardEntity.this.isPatrolling()) {
                    ButcherGuardEntity.this.getNavigation().stop();
                    ButcherGuardEntity.this.getMoveControl().strafeTo(0.0F, 0.0F);
                }
            }

            @Override
            public boolean shouldContinue() {
                return (this.canStart() || !ButcherGuardEntity.this.getNavigation().isIdle()) && this.isBowInMainhand();
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
        this.targetSelector.add(2, new ActiveTargetGoal<>(this, AnimalEntity.class, true, this::shouldTargetAnimal));
        this.targetSelector.add(3, new UniversalAngerGoal<>(this, false));
    }

    @Override
    public void tick() {
        super.tick();
        if (this.getWorld().isClient) {
            return;
        }

        if (this.huntOnSpawn) {
            this.huntOnSpawn = false;
            startHuntSession();
        }

        updateHuntCountdown();
        if (this.huntSessionActive && this.getWorld().getTime() > this.huntSessionEndTick) {
            endHuntSession();
        }
    }

    @Override
    public void tickMovement() {
        super.tickMovement();
        if (this.huntSessionActive && this.getTarget() == null) {
            findNearbyAnimalTarget();
        }
        if (this.huntSessionActive && this.age % LOOT_SCAN_INTERVAL == 0) {
            collectNearbyLoot();
        }
    }

    @Override
    public boolean onKilledOther(ServerWorld world, LivingEntity other) {
        boolean result = super.onKilledOther(world, other);
        if (this.huntSessionActive && other instanceof AnimalEntity) {
            this.huntTargetsRemaining--;
            if (this.huntTargetsRemaining <= 0) {
                endHuntSession();
            }
        }
        return result;
    }

    private void updateHuntCountdown() {
        if (this.huntSessionActive) {
            return;
        }

        if (!this.huntCountdownActive) {
            startHuntCountdown("initial schedule");
        }

        if (this.huntCountdownActive && this.nextHuntTriggerTime > 0L) {
            logHuntCountdownProgress();
        }

        if (this.huntCountdownActive && this.nextHuntTriggerTime > 0L && this.getWorld().getTime() >= this.nextHuntTriggerTime) {
            clearHuntCountdown();
            startHuntSession();
        }
    }

    private void startHuntSession() {
        if (this.huntSessionActive) {
            return;
        }
        clearHuntCountdown();
        this.huntSessionActive = true;
        this.huntTargetsRemaining = MathHelper.nextInt(this.random, HUNT_TARGET_MIN, HUNT_TARGET_MAX);
        this.huntSessionEndTick = this.getWorld().getTime() + HUNT_SESSION_MAX_TICKS;
        LOGGER.info("Butcher Guard {} starting hunt session ({} target(s))",
                this.getUuidAsString(),
                this.huntTargetsRemaining);
    }

    private void endHuntSession() {
        this.huntSessionActive = false;
        this.huntTargetsRemaining = 0;
        this.setTarget(null);
        depositLootToChest();
        startHuntCountdown("hunt session ended");
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("PairedChestX")) {
            int x = nbt.getInt("PairedChestX");
            int y = nbt.getInt("PairedChestY");
            int z = nbt.getInt("PairedChestZ");
            this.pairedChestPos = new BlockPos(x, y, z);
        } else {
            this.pairedChestPos = null;
        }
        if (nbt.contains("PairedSmokerX")) {
            int x = nbt.getInt("PairedSmokerX");
            int y = nbt.getInt("PairedSmokerY");
            int z = nbt.getInt("PairedSmokerZ");
            this.pairedSmokerPos = new BlockPos(x, y, z);
        } else {
            this.pairedSmokerPos = null;
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
        if (this.pairedSmokerPos != null) {
            nbt.putInt("PairedSmokerX", this.pairedSmokerPos.getX());
            nbt.putInt("PairedSmokerY", this.pairedSmokerPos.getY());
            nbt.putInt("PairedSmokerZ", this.pairedSmokerPos.getZ());
        }
    }

    private boolean shouldTargetAnimal(LivingEntity entity) {
        return this.huntSessionActive && this.huntTargetsRemaining > 0;
    }

    private void findNearbyAnimalTarget() {
        Box searchBox = this.getBoundingBox().expand(16.0D);
        List<AnimalEntity> animals = this.getWorld().getEntitiesByClass(AnimalEntity.class, searchBox, Entity::isAlive);
        animals.sort(Comparator.comparingDouble(this::squaredDistanceTo));
        if (!animals.isEmpty()) {
            this.setTarget(animals.get(0));
        }
    }

    private void collectNearbyLoot() {
        Box searchBox = this.getBoundingBox().expand(LOOT_SCAN_RANGE);
        List<ItemEntity> items = this.getWorld().getEntitiesByClass(ItemEntity.class, searchBox, entity -> entity.isAlive() && !entity.getStack().isEmpty());
        for (ItemEntity item : items) {
            ItemStack stack = item.getStack();
            if (!stack.isEmpty()) {
                this.collectedLoot.add(stack.copy());
                item.discard();
            }
        }
    }

    private void depositLootToChest() {
        if (this.collectedLoot.isEmpty() || this.pairedChestPos == null) {
            this.collectedLoot.clear();
            return;
        }

        if (!(this.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        Inventory inventory = getChestInventory(serverWorld);
        if (inventory == null) {
            dropRemainingLoot(serverWorld, this.pairedChestPos);
            return;
        }

        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack stack : this.collectedLoot) {
            ItemStack remainder = insertIntoInventory(inventory, stack);
            if (!remainder.isEmpty()) {
                remaining.add(remainder);
            }
        }
        this.collectedLoot.clear();
        if (!remaining.isEmpty()) {
            this.collectedLoot.addAll(remaining);
            dropRemainingLoot(serverWorld, this.pairedChestPos);
        }
    }

    private Inventory getChestInventory(ServerWorld world) {
        if (this.pairedChestPos == null) {
            return null;
        }
        var blockEntity = world.getBlockEntity(this.pairedChestPos);
        if (blockEntity instanceof Inventory inventory) {
            return inventory;
        }
        return null;
    }

    private ItemStack insertIntoInventory(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < inventory.size() && !remaining.isEmpty(); i++) {
            ItemStack slotStack = inventory.getStack(i);
            if (slotStack.isEmpty()) {
                inventory.setStack(i, remaining);
                remaining = ItemStack.EMPTY;
            } else if (ItemStack.areItemsAndComponentsEqual(slotStack, remaining)) {
                int maxTransfer = Math.min(slotStack.getMaxCount() - slotStack.getCount(), remaining.getCount());
                if (maxTransfer > 0) {
                    slotStack.increment(maxTransfer);
                    remaining.decrement(maxTransfer);
                    inventory.setStack(i, slotStack);
                }
            }
        }
        inventory.markDirty();
        return remaining;
    }

    private void dropRemainingLoot(ServerWorld world, BlockPos pos) {
        for (ItemStack stack : this.collectedLoot) {
            if (!stack.isEmpty()) {
                ItemEntity itemEntity = new ItemEntity(world, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, stack);
                world.spawnEntity(itemEntity);
            }
        }
        this.collectedLoot.clear();
    }

    private void startHuntCountdown(String reason) {
        this.huntCountdownTotalTicks = nextRandomHuntInterval();
        this.huntCountdownStartTime = this.getWorld().getTime();
        this.nextHuntTriggerTime = this.huntCountdownStartTime + this.huntCountdownTotalTicks;
        this.lastHuntCountdownLogStep = 0;
        this.huntCountdownActive = true;
        LOGGER.info("Butcher Guard {} hunt countdown started ({} ticks) {}",
                this.getUuidAsString(),
                this.huntCountdownTotalTicks,
                reason);
    }

    private void logHuntCountdownProgress() {
        if (this.huntCountdownTotalTicks <= 0L) {
            return;
        }
        long remainingTicks = this.nextHuntTriggerTime - this.getWorld().getTime();
        long elapsedTicks = this.getWorld().getTime() - this.huntCountdownStartTime;
        int step = Math.min(4, (int) ((elapsedTicks * 4L) / this.huntCountdownTotalTicks));
        if (step <= this.lastHuntCountdownLogStep || step == 0) {
            return;
        }
        this.lastHuntCountdownLogStep = step;
        int percent = step * 25;
        LOGGER.info("Butcher Guard {} hunt countdown {}% ({} ticks remaining)",
                this.getUuidAsString(),
                percent,
                Math.max(remainingTicks, 0L));
    }

    private long nextRandomHuntInterval() {
        return MathHelper.nextInt(this.random, HUNT_INTERVAL_MIN_TICKS, HUNT_INTERVAL_MAX_TICKS);
    }

    private void clearHuntCountdown() {
        this.nextHuntTriggerTime = 0L;
        this.huntCountdownTotalTicks = 0L;
        this.huntCountdownStartTime = 0L;
        this.lastHuntCountdownLogStep = 0;
        this.huntCountdownActive = false;
    }
}
