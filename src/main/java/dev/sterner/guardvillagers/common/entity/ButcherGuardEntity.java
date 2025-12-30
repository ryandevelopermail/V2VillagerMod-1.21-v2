package dev.sterner.guardvillagers.common.entity;

import java.util.List;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

public class ButcherGuardEntity extends GuardEntity {
    public ButcherGuardEntity(EntityType<? extends GuardEntity> type, World world) {
        super(type, world);
    }

    @Override
    public List<ItemStack> getStacksFromLootTable(EquipmentSlot slot, ServerWorld serverWorld) {
        if (slot == EquipmentSlot.MAINHAND) {
            return List.of();
        }
        return super.getStacksFromLootTable(slot, serverWorld);
    }
}
