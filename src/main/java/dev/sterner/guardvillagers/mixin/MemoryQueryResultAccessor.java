package dev.sterner.guardvillagers.mixin;

import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.MemoryQueryResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MemoryQueryResult.class)
public interface MemoryQueryResultAccessor {
    @Accessor("memory")
    MemoryModuleType<?> guardvillagers$getMemoryModuleType();
}
