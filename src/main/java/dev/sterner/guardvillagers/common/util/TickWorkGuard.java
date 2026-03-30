package dev.sterner.guardvillagers.common.util;

import java.util.function.LongSupplier;

/**
 * Tick-scoped loop guard for work that must be bounded per tick.
 */
public final class TickWorkGuard {
    private final int maxIterations;
    private final long maxElapsedNanos;
    private final LongSupplier nanoTimeSupplier;
    private final long startNanos;

    public TickWorkGuard(int maxIterations, long maxElapsedMillis) {
        this(maxIterations, maxElapsedMillis, System::nanoTime);
    }

    TickWorkGuard(int maxIterations, long maxElapsedMillis, LongSupplier nanoTimeSupplier) {
        this.maxIterations = Math.max(0, maxIterations);
        this.maxElapsedNanos = Math.max(0L, maxElapsedMillis) * 1_000_000L;
        this.nanoTimeSupplier = nanoTimeSupplier;
        this.startNanos = nanoTimeSupplier.getAsLong();
    }

    public boolean shouldContinue(int processedCount) {
        return processedCount < maxIterations && elapsedNanos() < maxElapsedNanos;
    }

    public boolean hitTimeCap() {
        return elapsedNanos() >= maxElapsedNanos;
    }

    public boolean hitIterationCap(int processedCount, boolean hasLeftoverWork) {
        return hasLeftoverWork && processedCount >= maxIterations;
    }

    private long elapsedNanos() {
        return nanoTimeSupplier.getAsLong() - startNanos;
    }
}
