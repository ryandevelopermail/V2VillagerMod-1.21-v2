package dev.sterner.guardvillagers.common.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.world.World;

public class ButcherGuardEntity extends GuardEntity {
    public ButcherGuardEntity(EntityType<? extends GuardEntity> type, World world) {
        super(type, world);
    }
}
