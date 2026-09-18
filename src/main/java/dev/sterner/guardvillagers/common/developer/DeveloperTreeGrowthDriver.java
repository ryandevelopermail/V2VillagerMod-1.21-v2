package dev.sterner.guardvillagers.common.developer;

import java.util.function.BooleanSupplier;

/** Drives the vanilla sapling state machine without relying on random world ticks. */
final class DeveloperTreeGrowthDriver {
    private DeveloperTreeGrowthDriver() {
    }

    static boolean growToMaturity(
            int maxGrowthInvocations,
            BooleanSupplier saplingPresent,
            BooleanSupplier matureTreePresent,
            Runnable invokeVanillaGrowth,
            Runnable removeLeftoverSapling
    ) {
        int invocations = Math.max(1, maxGrowthInvocations);
        for (int attempt = 0; attempt < invocations; attempt++) {
            if (matureTreePresent.getAsBoolean()) {
                return true;
            }
            if (!saplingPresent.getAsBoolean()) {
                break;
            }
            invokeVanillaGrowth.run();
        }

        if (matureTreePresent.getAsBoolean()) {
            return true;
        }
        if (saplingPresent.getAsBoolean()) {
            removeLeftoverSapling.run();
        }
        return false;
    }
}
