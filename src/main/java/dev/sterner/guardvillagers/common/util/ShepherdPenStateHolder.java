package dev.sterner.guardvillagers.common.util;

import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

public interface ShepherdPenStateHolder {
    boolean guardvillagers$hasConstructedPen();

    void guardvillagers$setHasConstructedPen(boolean hasConstructedPen);

    @Nullable
    BlockPos guardvillagers$getOwnedPenAnchor();

    void guardvillagers$setOwnedPenAnchor(@Nullable BlockPos ownedPenAnchor);
}
