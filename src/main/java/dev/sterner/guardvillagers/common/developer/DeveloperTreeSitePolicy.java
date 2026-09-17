package dev.sterner.guardvillagers.common.developer;

/** Pure policy used by the developer tree generator and its focused tests. */
final class DeveloperTreeSitePolicy {
    private DeveloperTreeSitePolicy() {
    }

    static boolean allowsClearanceBlock(boolean air, boolean replaceable, boolean fluidEmpty) {
        return air || replaceable && fluidEmpty;
    }
}
