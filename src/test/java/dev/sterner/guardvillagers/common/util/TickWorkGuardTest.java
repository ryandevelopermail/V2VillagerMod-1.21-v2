package dev.sterner.guardvillagers.common.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickWorkGuardTest {

    @Test
    void stopsWhenIterationCapReachedAndLeavesLeftoversQueued() {
        Deque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < 6; i++) {
            queue.addLast(i);
        }

        TickWorkGuard guard = new TickWorkGuard(3, 1_000L, () -> 0L);
        int processed = 0;
        while (!queue.isEmpty() && guard.shouldContinue(processed)) {
            queue.removeFirst();
            processed++;
        }

        assertEquals(3, processed);
        assertEquals(3, queue.size());
        assertTrue(guard.hitIterationCap(processed, !queue.isEmpty()));
        assertFalse(guard.hitTimeCap());
    }

    @Test
    void stopsWhenElapsedTimeCapReachedAndLeavesLeftoversQueued() {
        Deque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < 5; i++) {
            queue.addLast(i);
        }

        AtomicLong nanos = new AtomicLong();
        TickWorkGuard guard = new TickWorkGuard(10, 2L, nanos::get);
        int processed = 0;
        while (!queue.isEmpty() && guard.shouldContinue(processed)) {
            queue.removeFirst();
            processed++;
            nanos.addAndGet(1_500_000L);
        }

        assertTrue(processed < 5);
        assertTrue(guard.hitTimeCap());
        assertFalse(queue.isEmpty());
    }
}
