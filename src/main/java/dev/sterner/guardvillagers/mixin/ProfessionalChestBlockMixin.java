package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.professionalstorage.ProfessionalStorageScreenHandlerFactory;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.OptionalInt;

@Mixin(ChestBlock.class)
public class ProfessionalChestBlockMixin {
    @Redirect(
            method = "onUse",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/PlayerEntity;openHandledScreen(Lnet/minecraft/screen/NamedScreenHandlerFactory;)Ljava/util/OptionalInt;"))
    private OptionalInt guardvillagers$openProfessionalStorage(
            PlayerEntity opener,
            NamedScreenHandlerFactory factory,
            BlockState state,
            World world,
            BlockPos pos,
            PlayerEntity player,
            BlockHitResult hit
    ) {
        return opener.openHandledScreen(ProfessionalStorageScreenHandlerFactory.wrap(world, pos, factory));
    }
}
