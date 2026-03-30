package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Targeter;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(MobEntity.class)
public abstract class MobEntityMixin extends LivingEntity implements Targeter {

    @Unique
    private static final ThreadLocal<Boolean> GUARDVILLAGERS_RALLY_SCAN_ACTIVE =
            ThreadLocal.withInitial(() -> false);

    @Unique
    private long guardvillagers$nextRallyScanTick = Long.MIN_VALUE;

    protected MobEntityMixin(EntityType<? extends LivingEntity> entityType, World world) {
        super(entityType, world);
    }

    @Inject(method = "setTarget", at = @At("TAIL"))
    private void onSetTarget(@Nullable LivingEntity target, CallbackInfo ci) {
        if (target == null || ((MobEntity)(Object)this) instanceof GuardEntity) {
            return;
        }

        World world = ((MobEntity)(Object)this).getWorld();

        // Guard 1: server-side only
        if (world.isClient()) {
            return;
        }

        // Guard 2: reentrancy — prevent recursive setTarget calls inside the scan loop
        // from re-triggering another full rally scan
        if (GUARDVILLAGERS_RALLY_SCAN_ACTIVE.get()) {
            return;
        }

        boolean isVillager = target.getType() == EntityType.VILLAGER || target instanceof GuardEntity;
        if (isVillager) {
            // Guard 3: per-entity cooldown — at most one rally scan per second per attacker
            long currentTick = ((ServerWorld) world).getTime();
            if (currentTick < guardvillagers$nextRallyScanTick) {
                return;
            }
            guardvillagers$nextRallyScanTick = currentTick + 20L;

            GUARDVILLAGERS_RALLY_SCAN_ACTIVE.set(true);
            try {
                List<MobEntity> list = world.getNonSpectatingEntities(MobEntity.class,
                        ((MobEntity)(Object)this).getBoundingBox().expand(
                                GuardVillagersConfig.guardVillagerHelpRange, 5.0D,
                                GuardVillagersConfig.guardVillagerHelpRange));
                for (MobEntity mobEntity : list) {
                    if ((mobEntity instanceof GuardEntity ||
                            ((MobEntity)(Object)this).getType() == EntityType.IRON_GOLEM)
                            && mobEntity.getTarget() == null) {
                        mobEntity.setTarget(((MobEntity)(Object)this));
                    }
                }
            } finally {
                GUARDVILLAGERS_RALLY_SCAN_ACTIVE.set(false);
            }
        }

        if (((MobEntity)(Object)this) instanceof IronGolemEntity golem && target instanceof GuardEntity) {
            golem.setTarget(null);
        }
    }
}
