package dev.sterner.guardvillagers.common.villager;

import dev.sterner.guardvillagers.common.entity.goal.PlaceOwnJobBlockNearJobSiteGoal;
import net.minecraft.block.Block;
import net.minecraft.entity.ai.goal.PrioritizedGoal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

public final class VillagerProfessionBehaviorRegistry {
    private static final Map<VillagerProfession, VillagerProfessionBehavior> BEHAVIORS = new HashMap<>();
    private static final Map<Block, SpecialModifier> SPECIAL_MODIFIERS = new IdentityHashMap<>();
    private static final int UNIVERSAL_JOB_BLOCK_GOAL_PRIORITY = 5;

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

    public static void ensureUniversalJobBlockGoal(VillagerEntity villager, BlockPos jobPos) {
        if (!ProfessionDefinitions.hasDefinition(villager.getVillagerData().getProfession())) {
            return;
        }

        boolean hasGoal = villager.goalSelector.getGoals().stream()
                .map(PrioritizedGoal::getGoal)
                .anyMatch(goal -> goal instanceof PlaceOwnJobBlockNearJobSiteGoal);

        if (!hasGoal) {
            villager.goalSelector.add(UNIVERSAL_JOB_BLOCK_GOAL_PRIORITY, new PlaceOwnJobBlockNearJobSiteGoal(villager, jobPos));
        }
    }

    public static void notifyChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        ensureUniversalJobBlockGoal(villager, jobPos);
        getBehavior(villager.getVillagerData().getProfession())
                .ifPresent(behavior -> behavior.onChestPaired(world, villager, jobPos, chestPos));
    }

    public static void notifyCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        ensureUniversalJobBlockGoal(villager, jobPos);
        getBehavior(villager.getVillagerData().getProfession())
                .ifPresent(behavior -> behavior.onCraftingTablePaired(world, villager, jobPos, chestPos, craftingTablePos));
    }

    public static void notifySpecialModifierPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, SpecialModifier modifier, BlockPos modifierPos) {
        ensureUniversalJobBlockGoal(villager, jobPos);
        getBehavior(villager.getVillagerData().getProfession())
                .ifPresent(behavior -> behavior.onSpecialModifierPaired(world, villager, jobPos, chestPos, modifier, modifierPos));
    }
}
