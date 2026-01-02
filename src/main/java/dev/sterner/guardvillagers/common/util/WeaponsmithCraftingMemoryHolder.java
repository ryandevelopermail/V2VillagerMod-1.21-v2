package dev.sterner.guardvillagers.common.util;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

public interface WeaponsmithCraftingMemoryHolder {
    @Nullable
    Identifier guardvillagers$getLastWeaponsmithCrafted();

    void guardvillagers$setLastWeaponsmithCrafted(@Nullable Identifier identifier);
}
