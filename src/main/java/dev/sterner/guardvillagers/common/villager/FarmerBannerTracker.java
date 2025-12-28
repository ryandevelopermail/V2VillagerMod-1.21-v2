package dev.sterner.guardvillagers.common.villager;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

public final class FarmerBannerTracker {
    private static final Map<VillagerEntity, BlockPos> BANNERS = new WeakHashMap<>();

    private FarmerBannerTracker() {
    }

    public static void setBanner(VillagerEntity villager, BlockPos bannerPos) {
        BANNERS.put(villager, bannerPos.toImmutable());
    }

    public static Optional<BlockPos> getBanner(VillagerEntity villager) {
        return Optional.ofNullable(BANNERS.get(villager));
    }
}
