package dev.sterner.guardvillagers.common.handler;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.villager.SpecialModifier;
import dev.sterner.guardvillagers.common.villager.VillagerProfessionBehaviorRegistry;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.Optional;

public final class GuardModifierApplicationService {

    public void applySpecialModifierToNearbyConvertedGuards(ServerWorld world, BlockPos placedPos, BlockState placedState) {
        Optional<SpecialModifier> modifier = VillagerProfessionBehaviorRegistry.getSpecialModifier(placedState.getBlock());
        if (modifier.isEmpty()) {
            return;
        }

        double range = modifier.get().range();
        Box searchBox = new Box(placedPos).expand(range);
        for (GuardEntity guard : world.getEntitiesByClass(GuardEntity.class, searchBox, Entity::isAlive)) {
            if (guard.isConvertedFromArmorStand()) {
                applySpecialModifierToGuard(guard, modifier.get().block());
            }
        }
    }

    public void applySpecialModifierFromNearbyBlocks(ServerWorld world, GuardEntity guard) {
        applyModifierFromNearbyBlocks(world, guard, GuardVillagers.GUARD_STAND_MODIFIER);
        applyModifierFromNearbyBlocks(world, guard, GuardVillagers.GUARD_STAND_ANCHOR);
    }

    private void applyModifierFromNearbyBlocks(ServerWorld world, GuardEntity guard, Block modifierBlock) {
        Optional<SpecialModifier> modifier = VillagerProfessionBehaviorRegistry.getSpecialModifier(modifierBlock);
        if (modifier.isEmpty()) {
            return;
        }

        double range = modifier.get().range();
        int checkRange = (int) Math.ceil(range);
        BlockPos guardPos = guard.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(guardPos.add(-checkRange, -checkRange, -checkRange), guardPos.add(checkRange, checkRange, checkRange))) {
            if (!guardPos.isWithinDistance(pos, range)) {
                continue;
            }
            if (world.getBlockState(pos).isOf(modifier.get().block())) {
                applySpecialModifierToGuard(guard, modifier.get().block());
                return;
            }
        }
    }

    private void applySpecialModifierToGuard(GuardEntity guard, Block modifierBlock) {
        if (modifierBlock == GuardVillagers.GUARD_STAND_MODIFIER) {
            guard.setStandCustomizationEnabled(true);
        } else if (modifierBlock == GuardVillagers.GUARD_STAND_ANCHOR) {
            guard.setStandAnchorEnabled(true);
        }
    }
}
