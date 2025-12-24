package dev.sterner.guardvillagers.common.villager;

import net.minecraft.block.Block;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

public final class VillagerProfessionBehaviorRegistry {
    private static final Map<VillagerProfession, VillagerProfessionBehavior> BEHAVIORS = new EnumMap<>(VillagerProfession.class);
    private static final Map<Block, SpecialModifier> SPECIAL_MODIFIERS = new IdentityHashMap<>();

    private VillagerProfessionBehaviorRegistry() {
    }

    public static void registerBehavior(VillagerProfession profession, VillagerProfessionBehavior behavior) {
        BEHAVIORS.put(profession, behavior);
    }

    public static Optional<VillagerProfessionBehavior> getBehavior(VillagerProfession profession) {
        return Optional.ofNullable(BEHAVIORS.get(profession));
    }

    public static void registerSpecialModifier(SpecialModifier modifier) {
        SPECIAL_MODIFIERS.put(modifier.block(), modifier);
    }

    public static Optional<SpecialModifier> getSpecialModifier(Block block) {
        return Optional.ofNullable(SPECIAL_MODIFIERS.get(block));
    }

    public static boolean isSpecialModifierBlock(Block block) {
        return SPECIAL_MODIFIERS.containsKey(block);
    }

    public static void notifyChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        getBehavior(villager.getVillagerData().getProfession())
                .ifPresent(behavior -> behavior.onChestPaired(world, villager, jobPos, chestPos));
    }

    public static void notifyCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        getBehavior(villager.getVillagerData().getProfession())
                .ifPresent(behavior -> behavior.onCraftingTablePaired(world, villager, jobPos, chestPos, craftingTablePos));
    }

    public static void notifySpecialModifierPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, SpecialModifier modifier, BlockPos modifierPos) {
        getBehavior(villager.getVillagerData().getProfession())
                .ifPresent(behavior -> behavior.onSpecialModifierPaired(world, villager, jobPos, chestPos, modifier, modifierPos));
    }
}
