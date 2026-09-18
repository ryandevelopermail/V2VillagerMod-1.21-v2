package dev.sterner.guardvillagers.common.entity.goal;

/** Pure bootstrap policy shared by Lumberjack crafting and charcoal processing. */
final class LumberjackAxeBootstrapPolicy {
    static final int AXE_PLANK_REQUIREMENT = 3;
    static final int AXE_STICK_REQUIREMENT = 2;
    private static final int PLANKS_PER_LOG = 4;
    private static final int PLANKS_PER_STICK_CRAFT = 2;

    private LumberjackAxeBootstrapPolicy() {
    }

    static boolean needsBootstrap(boolean hasValidPairedChest, boolean hasEquippedAxe) {
        return !hasValidPairedChest || !hasEquippedAxe;
    }

    static boolean shouldCraftChest(boolean hasValidPairedChest, int chestsOnHand) {
        return !hasValidPairedChest && chestsOnHand < 1;
    }

    static int requiredLogReserveForAxe(boolean axeAvailable, int availablePlanks, int availableSticks) {
        if (axeAvailable) {
            return 0;
        }
        int stickCraftPlanks = availableSticks >= AXE_STICK_REQUIREMENT ? 0 : PLANKS_PER_STICK_CRAFT;
        int requiredPlanks = AXE_PLANK_REQUIREMENT + stickCraftPlanks;
        int plankDeficit = Math.max(0, requiredPlanks - Math.max(0, availablePlanks));
        return (plankDeficit + PLANKS_PER_LOG - 1) / PLANKS_PER_LOG;
    }

    static int charcoalEligibleLogs(
            int availableLogs,
            boolean axeAvailable,
            int availablePlanks,
            int availableSticks
    ) {
        int reserve = requiredLogReserveForAxe(axeAvailable, availablePlanks, availableSticks);
        return Math.max(0, availableLogs - reserve);
    }
}
