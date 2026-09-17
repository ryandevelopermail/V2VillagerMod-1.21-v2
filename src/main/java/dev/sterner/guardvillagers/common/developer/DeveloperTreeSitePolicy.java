package dev.sterner.guardvillagers.common.developer;

/** Pure policy used by the developer tree generator and its focused tests. */
final class DeveloperTreeSitePolicy {
    private DeveloperTreeSitePolicy() {
    }

    static boolean allowsClearanceBlock(boolean air, boolean replaceable, boolean fluidEmpty) {
        return air || replaceable && fluidEmpty;
    }

    static FailureKind classifyFailure(int preflightRejections, int vanillaGrowthFailures) {
        if (vanillaGrowthFailures > 0) {
            return FailureKind.VANILLA_GROWTH_FAILED;
        }
        if (preflightRejections > 0) {
            return FailureKind.PREFLIGHT_REJECTED;
        }
        return FailureKind.NO_USABLE_CANDIDATE;
    }

    enum FailureKind {
        NO_USABLE_CANDIDATE,
        PREFLIGHT_REJECTED,
        VANILLA_GROWTH_FAILED
    }
}
