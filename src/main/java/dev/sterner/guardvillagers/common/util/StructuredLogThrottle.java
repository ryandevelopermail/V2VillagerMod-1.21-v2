package dev.sterner.guardvillagers.common.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal per-key log throttle helper for hot-path logging.
 */
public final class StructuredLogThrottle {
    private static final Map<String, Long> NEXT_ALLOWED_LOG_TIME = new ConcurrentHashMap<>();

    private StructuredLogThrottle() {
    }

    /**
     * Returns {@code true} when a log for {@code key} should be emitted at {@code now},
     * and records the next allowed timestamp as {@code now + minimumInterval}.
     */
    public static boolean shouldLog(String key, long now, long minimumInterval) {
        long nextAllowed = NEXT_ALLOWED_LOG_TIME.getOrDefault(key, Long.MIN_VALUE);
        if (now < nextAllowed) {
            return false;
        }
        NEXT_ALLOWED_LOG_TIME.put(key, now + Math.max(0L, minimumInterval));
        return true;
    }
}
