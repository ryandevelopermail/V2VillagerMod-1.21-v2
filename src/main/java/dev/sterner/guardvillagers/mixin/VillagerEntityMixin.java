package dev.sterner.guardvillagers.mixin;

import net.minecraft.entity.EntityData;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.ServerWorldAccess;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VillagerEntity.class)
public abstract class VillagerEntityMixin extends MobEntity {

    protected VillagerEntityMixin(net.minecraft.entity.EntityType<? extends MobEntity> entityType, net.minecraft.world.World world) {
        super(entityType, world);
    }

    @Inject(method = "initialize", at = @At("TAIL"))
    private void guardvillagers$giveFisherRod(ServerWorldAccess world, LocalDifficulty difficulty, SpawnReason spawnReason, @Nullable EntityData entityData, CallbackInfoReturnable<EntityData> cir) {
        if (!(world instanceof ServerWorld) || !this.guardvillagers$isPlayerCreated(spawnReason)) {
            return;
        }
        VillagerEntity villager = (VillagerEntity) (Object) this;
        if (villager.getVillagerData().getProfession() != net.minecraft.village.VillagerProfession.FISHERMAN) {
            return;
        }
        ItemStack fishingRod = new ItemStack(Items.FISHING_ROD);
        if (villager.getMainHandStack().isEmpty()) {
            villager.equipStack(EquipmentSlot.MAINHAND, fishingRod.copy());
        }
        villager.getInventory().addStack(fishingRod);
    }

    private boolean guardvillagers$isPlayerCreated(SpawnReason spawnReason) {
        return spawnReason == SpawnReason.SPAWN_EGG
                || spawnReason == SpawnReason.COMMAND
                || spawnReason == SpawnReason.DISPENSER;
    }
}
