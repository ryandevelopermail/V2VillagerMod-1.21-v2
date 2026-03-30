package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.villager.behavior.ShepherdBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.ButcherBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.MasonBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.ArmorerBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.CartographerBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.FarmerBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.FishermanBehavior;
import dev.sterner.guardvillagers.common.villager.behavior.LumberjackChestTriggerBehavior;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LootableContainerBlockEntity.class)
public class ChestBlockEntityMixin {

    @Unique
    private long guardvillagers$lastChestMutationNotifyTick = Long.MIN_VALUE;

    @Inject(method = "setStack(ILnet/minecraft/item/ItemStack;)V", at = @At("TAIL"), require = 0)
    private void guardvillagers$onChestSetStack(int slot, ItemStack stack, CallbackInfo ci) {
        guardvillagers$notifyChestMutation();
    }

    @Inject(method = "removeStack(I)Lnet/minecraft/item/ItemStack;", at = @At("TAIL"), require = 0)
    private void guardvillagers$onChestRemoveStack(int slot, CallbackInfoReturnable<ItemStack> cir) {
        guardvillagers$notifyChestMutation();
    }

    @Inject(method = "removeStack(II)Lnet/minecraft/item/ItemStack;", at = @At("TAIL"), require = 0)
    private void guardvillagers$onChestRemoveStackAmount(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
        guardvillagers$notifyChestMutation();
    }

    @Unique
    private void guardvillagers$notifyChestMutation() {
        if (!((Object) this instanceof ChestBlockEntity chest)) {
            return;
        }
        if (!(chest.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }
        long currentTick = serverWorld.getTime();
        if (currentTick == guardvillagers$lastChestMutationNotifyTick) {
            return;
        }
        guardvillagers$lastChestMutationNotifyTick = currentTick;
        BlockPos pos = chest.getPos();
        ShepherdBehavior.onChestInventoryMutated(serverWorld, pos);
        ButcherBehavior.onChestInventoryMutated(serverWorld, pos);
        MasonBehavior.onChestInventoryMutated(serverWorld, pos);
        ArmorerBehavior.onChestInventoryMutated(serverWorld, pos);
        CartographerBehavior.onChestInventoryMutated(serverWorld, pos);
        FarmerBehavior.onChestInventoryMutated(serverWorld, pos);
        FishermanBehavior.onChestInventoryMutated(serverWorld, pos);
        LumberjackChestTriggerBehavior.onChestInventoryMutated(serverWorld, pos);
    }
}
