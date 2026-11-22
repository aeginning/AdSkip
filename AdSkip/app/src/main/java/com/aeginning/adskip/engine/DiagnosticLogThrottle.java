package com.aeginning.adskip.engine;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small in-memory throttle for repetitive accessibility diagnostics. */
public final class DiagnosticLogThrottle {
    private static final int MAX_KEYS = 256;
    private final LinkedHashMap<String, Long> lastLogged = new LinkedHashMap<>(32, 0.75f, true);

    public synchronized boolean shouldLog(String key, long nowMs, long intervalMs) {
        if (key == null || key.trim().isEmpty() || intervalMs < 0) return false;
        Long previous = lastLogged.get(key);
        if (previous != null && nowMs >= previous && nowMs - previous < intervalMs) return false;
        lastLogged.put(key, nowMs);
        while (lastLogged.size() > MAX_KEYS) {
            Iterator<Map.Entry<String, Long>> iterator = lastLogged.entrySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
        return true;
    }

    public synchronized void clear() {
        lastLogged.clear();
    }
}
