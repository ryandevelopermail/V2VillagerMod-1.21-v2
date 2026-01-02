package dev.sterner.guardvillagers.common.util;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

public interface ToolsmithCraftingMemoryHolder {
    @Nullable
    Identifier guardvillagers$getLastToolsmithCrafted();

    void guardvillagers$setLastToolsmithCrafted(@Nullable Identifier identifier);
}
