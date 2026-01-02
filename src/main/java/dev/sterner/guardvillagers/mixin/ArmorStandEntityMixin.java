package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.util.GearGradeComparator;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ArmorStandEntity.class)
public class ArmorStandEntityMixin {
    @Inject(method = "interactAt", at = @At("HEAD"), cancellable = true)
    private void guardvillagers$blockGuardStandInteractions(PlayerEntity player, Vec3d hitPos, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        ArmorStandEntity stand = (ArmorStandEntity) (Object) this;
        if (!stand.isAlive() || !stand.getCommandTags().contains(VillageGuardStandManager.GUARD_STAND_TAG)) {
            return;
        }

        ItemStack stackInHand = player.getStackInHand(hand);
        if (stackInHand.isEmpty()) {
            cir.setReturnValue(ActionResult.FAIL);
            return;
        }

        if (!(stackInHand.getItem() instanceof ArmorItem armorItem)) {
            cir.setReturnValue(ActionResult.FAIL);
            return;
        }

        EquipmentSlot slot = armorItem.getSlotType();
        ItemStack current = stand.getEquippedStack(slot);
        if (!current.isEmpty() && !GearGradeComparator.isUpgrade(stackInHand, current, slot)) {
            cir.setReturnValue(ActionResult.FAIL);
        }
    }
}
