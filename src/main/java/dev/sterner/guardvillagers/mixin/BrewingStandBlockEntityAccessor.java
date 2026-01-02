package dev.sterner.guardvillagers.mixin;

import net.minecraft.block.entity.BrewingStandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BrewingStandBlockEntity.class)
public interface BrewingStandBlockEntityAccessor {
    @Accessor("brewTime")
    int guardvillagers$getBrewTime();
}
