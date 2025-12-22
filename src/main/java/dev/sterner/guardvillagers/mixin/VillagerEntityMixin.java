package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.logging.VillagePopulationLogger;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(VillagerEntity.class)
public abstract class VillagerEntityMixin extends LivingEntity {
    @Unique
    private Optional<GlobalPos> guardvillagers$lastJobSite = Optional.empty();
    @Unique
    private int guardvillagers$jobCheckCooldown = 20;

    protected VillagerEntityMixin(EntityType<? extends LivingEntity> entityType, World world) {
        super(entityType, world);
    }

    @Inject(method = "mobTick", at = @At("TAIL"))
    private void guardvillagers$trackJobSite(CallbackInfo ci) {
        if (this.getWorld().isClient()) {
            return;
        }

        guardvillagers$jobCheckCooldown--;
        if (guardvillagers$jobCheckCooldown > 0) {
            return;
        }

        guardvillagers$jobCheckCooldown = 20;
        guardvillagers$lastJobSite = VillagePopulationLogger.handleJobSiteUpdate((VillagerEntity) (Object) this, guardvillagers$lastJobSite);
    }
}
