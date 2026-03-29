package dev.sterner.guardvillagers.common.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CartographerMapChestUtilTest {

    @Test
    void deduplicateMapBounds_collapsesDuplicateMapCopiesBySignature() {
        List<CartographerMapChestUtil.MapSignature> signatures = List.of(
                CartographerMapChestUtil.MapSignature.of(0, 0, 2),
                CartographerMapChestUtil.MapSignature.of(0, 0, 2),
                CartographerMapChestUtil.MapSignature.of(256, 0, 2)
        );

        List<VillageMappedBoundsState.MappedBounds> deduped = CartographerMapChestUtil.deduplicateMapBounds(signatures);
        assertEquals(2, deduped.size());
    }
}
