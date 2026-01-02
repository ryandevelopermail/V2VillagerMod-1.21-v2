package dev.sterner.guardvillagers.common.util;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

public interface LeatherworkerCraftingMemoryHolder {
    @Nullable
    Identifier guardvillagers$getLastLeatherworkerCrafted();

    void guardvillagers$setLastLeatherworkerCrafted(@Nullable Identifier identifier);
}
