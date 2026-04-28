package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

public class MoreVillagersHunterNoSleepGoal extends Goal {
    private static final Identifier HUNTER_PROFESSION_ID = Identifier.of("morevillagers", "hunter");

    private final VillagerEntity villager;

    public MoreVillagersHunterNoSleepGoal(VillagerEntity villager) {
        this.villager = villager;
    }

    @Override
    public boolean canStart() {
        return villager.isAlive()
                && villager.getWorld() instanceof ServerWorld
                && isHunter(villager)
                && (!villager.getWorld().isDay() || villager.isSleeping());
    }

    @Override
    public boolean shouldContinue() {
        return canStart();
    }

    @Override
    public void start() {
        wakeHunter();
    }

    @Override
    public void tick() {
        wakeHunter();
    }

    private void wakeHunter() {
        if (villager.isSleeping()) {
            villager.wakeUp();
        }
    }

    private static boolean isHunter(VillagerEntity villager) {
        return HUNTER_PROFESSION_ID.equals(Registries.VILLAGER_PROFESSION.getId(villager.getVillagerData().getProfession()));
    }
}
