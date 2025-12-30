package dev.sterner.guardvillagers.common.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
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
import net.minecraft.item.BowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

public class ButcherGuardEntity extends GuardEntity {
    private static final int DAY_LENGTH = 24000;
    private static final int HUNT_SESSION_MAX_TICKS = 20 * 60;
    private static final int RANDOM_HUNT_CHANCES_MIN = 1;
    private static final int RANDOM_HUNT_CHANCES_MAX = 2;
    private static final int HUNT_TARGET_MIN = 1;
    private static final int HUNT_TARGET_MAX = 3;

    private boolean huntOnSpawn;
    private boolean huntSessionActive;
    private int huntTargetsRemaining;
    private long huntSessionEndTick;
    private int lastRecordedDay = -1;
    private final List<Long> randomHuntTimes = new ArrayList<>();

    public ButcherGuardEntity(EntityType<? extends GuardEntity> type, World world) {
        super(type, world);
    }

    public void setHuntOnSpawn() {
        this.huntOnSpawn = true;
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

        updateDailySchedule();
        if (this.huntOnSpawn) {
            this.huntOnSpawn = false;
            startHuntSession();
        }

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

    private void updateDailySchedule() {
        long timeOfDay = this.getWorld().getTimeOfDay();
        long day = timeOfDay / DAY_LENGTH;
        if ((int) day != this.lastRecordedDay) {
            int previousDay = this.lastRecordedDay;
            this.lastRecordedDay = (int) day;
            this.randomHuntTimes.clear();
            int randomSessions = MathHelper.nextInt(this.random, RANDOM_HUNT_CHANCES_MIN, RANDOM_HUNT_CHANCES_MAX);
            long minOffset = previousDay == -1 ? timeOfDay % DAY_LENGTH : 0;
            for (int i = 0; i < randomSessions; i++) {
                long randomOffset = MathHelper.nextInt(this.random, (int) minOffset, DAY_LENGTH - 1);
                this.randomHuntTimes.add(day * DAY_LENGTH + randomOffset);
            }
            this.randomHuntTimes.sort(Comparator.naturalOrder());
            if (previousDay != -1) {
                startHuntSession();
            }
        }

        if (!this.huntSessionActive && !this.randomHuntTimes.isEmpty()) {
            long currentTime = this.getWorld().getTimeOfDay();
            long nextTime = this.randomHuntTimes.get(0);
            if (currentTime >= nextTime) {
                this.randomHuntTimes.remove(0);
                startHuntSession();
            }
        }
    }

    private void startHuntSession() {
        if (this.huntSessionActive) {
            return;
        }
        this.huntSessionActive = true;
        this.huntTargetsRemaining = MathHelper.nextInt(this.random, HUNT_TARGET_MIN, HUNT_TARGET_MAX);
        this.huntSessionEndTick = this.getWorld().getTime() + HUNT_SESSION_MAX_TICKS;
    }

    private void endHuntSession() {
        this.huntSessionActive = false;
        this.huntTargetsRemaining = 0;
        this.setTarget(null);
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
}
