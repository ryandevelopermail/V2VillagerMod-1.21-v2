package dev.sterner.guardvillagers.common.util;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Tags every villager within {@link VillageGuardStandManager#BELL_EFFECT_RANGE} blocks of a
 * primary bell with that bell's {@link GlobalPos}.
 *
 * <p>The tag is written to the villager's NBT via {@link VillageMembershipHolder} (implemented
 * through {@code VillagerEntityMixin}) and persists across chunk reloads.
 *
 * <p>This is the foundation for Quartermaster village pairing, mason wall boundary scans, and any
 * future inter-village logic that needs to know which bell "owns" a given villager.
 */
public final class VillageMembershipTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger(VillageMembershipTracker.class);

    private VillageMembershipTracker() {
    }

    /**
     * Called on every bell ring (after primary-bell resolution).  Tags all living villagers within
     * {@link VillageGuardStandManager#BELL_EFFECT_RANGE} with {@code primaryBellPos}.
     *
     * @param world         the server world
     * @param primaryBellPos the resolved primary bell position
     */
    public static void tagVillagersNearBell(ServerWorld world, BlockPos primaryBellPos) {
        GlobalPos bellGlobalPos = GlobalPos.create(world.getRegistryKey(), primaryBellPos.toImmutable());
        Box searchBox = new Box(primaryBellPos).expand(VillageGuardStandManager.BELL_EFFECT_RANGE);
        List<VillagerEntity> villagers = world.getEntitiesByClass(VillagerEntity.class, searchBox, Entity::isAlive);

        int tagged = 0;
        for (VillagerEntity villager : villagers) {
            if (villager instanceof VillageMembershipHolder holder) {
                GlobalPos existing = holder.guardvillagers$getHomeBellPos();
                if (!bellGlobalPos.equals(existing)) {
                    holder.guardvillagers$setHomeBellPos(bellGlobalPos);
                    tagged++;
                }
            }
        }

        if (tagged > 0) {
            LOGGER.debug("VillageMembership: tagged {} villager(s) with primary bell {} in {}",
                    tagged,
                    primaryBellPos.toShortString(),
                    world.getRegistryKey().getValue());
        }
    }

    /**
     * Returns the home bell {@link GlobalPos} for a villager, or {@code null} if not yet tagged.
     */
    public static GlobalPos getHomeBell(VillagerEntity villager) {
        if (villager instanceof VillageMembershipHolder holder) {
            return holder.guardvillagers$getHomeBellPos();
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Accessor interface — implemented by VillagerEntityMixin
    // -------------------------------------------------------------------------

    /**
     * Mixin accessor interface that {@code VillagerEntityMixin} implements to expose the
     * persisted home-bell field without reflection.
     */
    public interface VillageMembershipHolder {
        GlobalPos guardvillagers$getHomeBellPos();
        void guardvillagers$setHomeBellPos(GlobalPos bellPos);
    }
}
