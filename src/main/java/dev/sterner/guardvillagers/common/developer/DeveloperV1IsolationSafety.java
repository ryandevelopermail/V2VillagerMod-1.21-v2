package dev.sterner.guardvillagers.common.developer;

import java.util.Collection;

/** Pure collision rules for temporary V1 terrain isolation. */
final class DeveloperV1IsolationSafety {
    private DeveloperV1IsolationSafety() {
    }

    static <P> boolean avoidsProtectedPositions(
            Collection<P> barrierPositions,
            Collection<P> clearPositions,
            Collection<P> protectedPositions
    ) {
        return barrierPositions.stream().noneMatch(protectedPositions::contains)
                && clearPositions.stream().noneMatch(protectedPositions::contains);
    }

    static <P> boolean canRemoveTemporaryPosition(P position, Collection<P> protectedPositions) {
        return !protectedPositions.contains(position);
    }
}
