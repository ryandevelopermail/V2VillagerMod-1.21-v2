package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.professionalstorage.ClericWorkMetrics;
import dev.sterner.guardvillagers.common.villager.behavior.ClericBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.TargetPredicate;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.task.LookTargetUtil;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.projectile.thrown.PotionEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potions;
import net.minecraft.sound.SoundEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.World;

import java.util.EnumSet;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class HealGuardAndPlayerGoal extends Goal {
    public static final double GUARD_SCAN_HORIZONTAL_RANGE = 14.0D;
    public static final double GUARD_SCAN_VERTICAL_RANGE = 4.0D;
    private final MobEntity healer;
    private LivingEntity mob;
    private int rangedAttackTime = -1;
    private final double entityMoveSpeed;
    private int seeTime;
    private final int attackIntervalMin;
    private final int maxRangedAttackTime;
    private final float attackRadius;
    private final float maxAttackDistance;
    protected final TargetPredicate predicate = TargetPredicate.createNonAttackable().setBaseMaxDistance(64.0D);

    public HealGuardAndPlayerGoal(MobEntity healer, double movespeed, int attackIntervalMin, int maxAttackTime, float maxAttackDistanceIn) {
        this.healer = healer;
        this.entityMoveSpeed = movespeed;
        this.attackIntervalMin = attackIntervalMin;
        this.maxRangedAttackTime = maxAttackTime;
        this.attackRadius = maxAttackDistanceIn;
        this.maxAttackDistance = maxAttackDistanceIn * maxAttackDistanceIn;
        this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (((VillagerEntity) this.healer).getVillagerData().getProfession() != VillagerProfession.CLERIC || this.healer.isSleeping()) {
            return false;
        }
        if (!hasHealingPotionAvailable()) {
            return false;
        }

        this.mob = findLowestHealthGuardTarget();
        return this.mob != null;
    }

    @Override
    public boolean shouldContinue() {
        if (((VillagerEntity) this.healer).getVillagerData().getProfession() != VillagerProfession.CLERIC || this.healer.isSleeping()) {
            return false;
        }
        if (mob == null || !mob.isAlive() || mob.getHealth() >= mob.getMaxHealth()) {
            mob = findLowestHealthGuardTarget();
        }
        return mob != null && hasHealingPotionAvailable();
    }

    @Override
    public void stop() {
        this.mob = null;
        this.seeTime = 0;
        this.healer.getBrain().forget(MemoryModuleType.LOOK_TARGET);
        this.rangedAttackTime = 0;
    }

    @Override
    public void tick() {
        if (mob == null)
            return;
        double d0 = this.healer.squaredDistanceTo(this.mob.getX(), this.mob.getY(), this.mob.getZ());
        boolean flag = this.healer.getVisibilityCache().canSee(mob);
        if (flag) {
            ++this.seeTime;
        } else {
            this.seeTime = 0;
        }
        LookTargetUtil.lookAt(healer, mob);
        if (!(d0 > (double) this.maxAttackDistance) && this.seeTime >= 5) {
            this.healer.getNavigation().stop();
        } else {
            this.healer.getNavigation().startMovingTo(this.mob, this.entityMoveSpeed);
        }
        if (mob.distanceTo(healer) <= 3.0D) {
            healer.getMoveControl().strafeTo(-0.5F, 0);
        }
        if (--this.rangedAttackTime == 0) {
            if (!flag) {
                return;
            }
            if (!hasHealingPotionAvailable()) {
                stop();
                return;
            }
            float f = this.attackRadius;
            float distanceFactor = MathHelper.clamp(f, 0.10F, 0.10F);
            this.throwPotion(mob, distanceFactor);
            this.rangedAttackTime = MathHelper.floor(f * (float) (this.maxRangedAttackTime - this.attackIntervalMin) + (float) this.attackIntervalMin);
        } else if (this.rangedAttackTime < 0) {
            this.rangedAttackTime = MathHelper.floor(MathHelper.lerp(Math.sqrt(d0) / (double) this.attackRadius, this.attackIntervalMin, this.maxAttackDistance));
        }
    }

    public void throwPotion(LivingEntity target, float distanceFactor) {
        boolean validTarget = isEligibleInjuredGuard(target);
        if (!validTarget || !consumeHealingPotion()) {
            return;
        }
        Vec3d vec3d = target.getVelocity();
        double d0 = target.getX() + vec3d.x - healer.getX();
        double d1 = target.getEyeY() - (double) 1.1F - healer.getY();
        double d2 = target.getZ() + vec3d.z - healer.getZ();
        float f = MathHelper.sqrt((float) (d0 * d0 + d2 * d2));
        PotionEntity potionentity = new PotionEntity(healer.getWorld(), healer);
        potionentity.setItem(PotionContentsComponent.createStack(Items.SPLASH_POTION, Potions.HEALING));
        potionentity.setPitch(-20.0F);
        potionentity.setVelocity(d0, d1 + (double) (f * 0.2F), d2, 0.75F, 8.0F);
        healer.getWorld().playSound(null, healer.getX(), healer.getY(), healer.getZ(), SoundEvents.ENTITY_SPLASH_POTION_THROW, healer.getSoundCategory(), 1.0F, 0.8F + healer.getRandom().nextFloat() * 0.4F);
        boolean spawned = healer.getWorld().spawnEntity(potionentity);
        recordConfirmedHealingThrow(
                true,
                validTarget,
                spawned,
                () -> {
                    if (healer.getWorld() instanceof ServerWorld world) {
                        ClericWorkMetrics.recordHealingPotionsThrown(world, healer.getUuid(), 1L);
                    }
                });
    }


    static boolean recordConfirmedHealingThrow(
            boolean potionConsumed,
            boolean validInjuredGuard,
            boolean projectileSpawned,
            Runnable confirmedSuccess
    ) {
        if (!potionConsumed || !validInjuredGuard || !projectileSpawned) {
            return false;
        }
        confirmedSuccess.run();
        return true;
    }


    private LivingEntity findLowestHealthGuardTarget() {
        List<GuardEntity> guards = findEligibleInjuredGuards(healer);

        if (guards.isEmpty()) {
            return null;
        }

        guards.sort(java.util.Comparator
                .comparingDouble((GuardEntity guard) -> guard.getHealth() / Math.max(guard.getMaxHealth(), 0.001F))
                .thenComparingDouble(this.healer::squaredDistanceTo));

        return guards.get(0);
    }

    public static int countEligibleInjuredGuardsReadOnly(MobEntity healer) {
        Set<UUID> unique = new HashSet<>();
        for (GuardEntity guard : findEligibleInjuredGuards(healer)) {
            unique.add(guard.getUuid());
        }
        return unique.size();
    }

    private static List<GuardEntity> findEligibleInjuredGuards(MobEntity healer) {
        return healer.getWorld().getEntitiesByClass(
                GuardEntity.class,
                healer.getBoundingBox().expand(
                        GUARD_SCAN_HORIZONTAL_RANGE,
                        GUARD_SCAN_VERTICAL_RANGE,
                        GUARD_SCAN_HORIZONTAL_RANGE),
                HealGuardAndPlayerGoal::isEligibleInjuredGuard);
    }

    public static boolean isEligibleInjuredGuard(LivingEntity entity) {
        return entity instanceof GuardEntity && entity.isAlive() && entity.getHealth() < entity.getMaxHealth();
    }

    static boolean isEligibleInjuredGuardShape(boolean guard, boolean alive, float health, float maxHealth) {
        return guard && alive && health < maxHealth;
    }

    private boolean hasHealingPotionAvailable() {
        return findHealingPotionSource() != null;
    }

    private boolean consumeHealingPotion() {
        PotionSource source = findHealingPotionSource();
        if (source == null) {
            return false;
        }
        ItemStack stack = source.inventory().getStack(source.slot());
        if (stack.isEmpty()) {
            return false;
        }
        stack.decrement(1);
        source.inventory().markDirty();
        return true;
    }

    private PotionSource findHealingPotionSource() {
        if (!(this.healer instanceof VillagerEntity villager)) {
            return null;
        }
        PotionSource source = findHealingPotionSource(villager.getInventory());
        if (source != null) {
            return source;
        }
        Inventory chestInventory = getPairedChestInventory(villager);
        if (chestInventory == null) {
            return null;
        }
        return findHealingPotionSource(chestInventory);
    }

    private PotionSource findHealingPotionSource(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (isHealingSplashPotion(stack)) {
                return new PotionSource(inventory, slot);
            }
        }
        return null;
    }

    public static boolean isHealingSplashPotion(ItemStack stack) {
        return ClericDistributionGoal.isHealingSplashPotion(stack);
    }

    public static long countHealingPotionsReadOnly(Inventory inventory) {
        long count = 0L;
        if (inventory == null) {
            return count;
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (isHealingSplashPotion(stack)) {
                count = count >= Long.MAX_VALUE - stack.getCount()
                        ? Long.MAX_VALUE
                        : count + stack.getCount();
            }
        }
        return count;
    }

    private Inventory getPairedChestInventory(VillagerEntity villager) {
        BlockPos chestPos = ClericBehavior.getPairedChestPos(villager);
        if (chestPos == null) {
            return null;
        }
        World world = villager.getWorld();
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }
        return ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
    }

    private record PotionSource(Inventory inventory, int slot) {
    }
}
