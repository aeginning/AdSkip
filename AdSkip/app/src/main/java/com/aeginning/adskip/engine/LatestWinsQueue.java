package com.aeginning.adskip.engine;

/**
 * A bounded single-slot queue for time-sensitive recognition requests.
 * A newer observation replaces an older waiting observation; callers still
 * execute at most one expensive OCR task at a time.
 */
public final class LatestWinsQueue<T> {
    private T pending;

    /** @return true when an older waiting value was replaced. */
    public synchronized boolean offer(T value) {
        if (value == null) throw new IllegalArgumentException("value must not be null");
        boolean replaced = pending != null;
        pending = value;
        return replaced;
    }

    public synchronized T poll() {
        T value = pending;
        pending = null;
        return value;
    }

    public synchronized boolean isEmpty() {
        return pending == null;
    }

    public synchronized void clear() {
        pending = null;
    }
}
