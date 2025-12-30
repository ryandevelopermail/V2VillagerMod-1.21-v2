package dev.sterner.guardvillagers.common.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.context.LootContextParameterSet;
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import java.util.List;

public class AxeGuardEntity extends GuardEntity {
    public AxeGuardEntity(EntityType<? extends GuardEntity> type, World world) {
        super(type, world);
    }

    @Override
    public List<ItemStack> getStacksFromLootTable(EquipmentSlot slot, ServerWorld serverWorld) {
        if (slot == EquipmentSlot.MAINHAND) {
            LootTable loot = serverWorld.getServer()
                    .getReloadableRegistries()
                    .getLootTable(GuardEntityLootTables.AXE_GUARD_MAIN_HAND);
            LootContextParameterSet.Builder lootContextBuilder =
                    new LootContextParameterSet.Builder(serverWorld).add(LootContextParameters.THIS_ENTITY, this);
            return loot.generateLoot(lootContextBuilder.build(GuardEntityLootTables.SLOT));
        }
        return super.getStacksFromLootTable(slot, serverWorld);
    }
}
