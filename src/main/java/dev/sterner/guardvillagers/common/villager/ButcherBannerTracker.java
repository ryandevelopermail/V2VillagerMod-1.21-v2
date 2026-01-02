package dev.sterner.guardvillagers.common.villager;

import dev.sterner.guardvillagers.common.entity.ButcherGuardEntity;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

public final class ButcherBannerTracker {
    private static final Map<ButcherGuardEntity, BlockPos> BANNERS = new WeakHashMap<>();

    private ButcherBannerTracker() {
    }

    public static void setBanner(ButcherGuardEntity guard, BlockPos bannerPos) {
        BANNERS.put(guard, bannerPos.toImmutable());
    }

    public static Optional<BlockPos> getBanner(ButcherGuardEntity guard) {
        return Optional.ofNullable(BANNERS.get(guard));
    }
}
