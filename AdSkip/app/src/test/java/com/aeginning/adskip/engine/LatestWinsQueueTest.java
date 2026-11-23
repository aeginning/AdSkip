package com.aeginning.adskip.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public class LatestWinsQueueTest {
    @Test public void keepsOnlyTheNewestWaitingRequest() {
        LatestWinsQueue<String> queue = new LatestWinsQueue<>();
        assertFalse(queue.offer("first"));
        assertTrue(queue.offer("second"));
        assertTrue(queue.offer("third"));
        assertEquals("third", queue.poll());
        assertTrue(queue.isEmpty());
    }

    @Test public void pollConsumesThePendingRequest() {
        LatestWinsQueue<Integer> queue = new LatestWinsQueue<>();
        queue.offer(42);
        assertEquals(Integer.valueOf(42), queue.poll());
        assertNull(queue.poll());
    }

    @Test public void clearDropsWaitingWork() {
        LatestWinsQueue<String> queue = new LatestWinsQueue<>();
        queue.offer("stale");
        queue.clear();
        assertTrue(queue.isEmpty());
        assertNull(queue.poll());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullRequestsAreRejected() {
        new LatestWinsQueue<>().offer(null);
    }
}
