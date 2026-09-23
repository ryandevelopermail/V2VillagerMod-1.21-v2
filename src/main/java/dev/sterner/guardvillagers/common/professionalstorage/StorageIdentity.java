package dev.sterner.guardvillagers.common.professionalstorage;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Objects;

/** Dimension-aware canonical identity shared by every member block of one storage container. */
public record StorageIdentity(RegistryKey<World> dimension, BlockPos canonicalPos) {
    public StorageIdentity {
        Objects.requireNonNull(dimension, "dimension");
        canonicalPos = Objects.requireNonNull(canonicalPos, "canonicalPos").toImmutable();
    }
}
