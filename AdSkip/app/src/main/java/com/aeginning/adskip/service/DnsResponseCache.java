package com.aeginning.adskip.service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small process-local positive DNS cache. Entries are keyed by QNAME/QTYPE/QCLASS. */
final class DnsResponseCache {
    private static final int MAX_ENTRIES = 256;
    private final LinkedHashMap<String, CacheEntry> entries = new LinkedHashMap<String, Entry>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    synchronized byte[] get(byte[] query, long nowElapsedRealtime) {
        String key = DnsPacketCodec.cacheKey(query);
        if (key == null) return null;
        CacheEntry entry = entries.get(key);
        if (entry == null) return null;
        if (entry.expiresAt <= nowElapsedRealtime) {
            entries.remove(key);
            return null;
        }
        byte[] response = entry.response.clone();
        // DNS transaction IDs are per request, even when the answer is cached.
        response[0] = query[0];
        response[1] = query[1];
        DnsPacketCodec.decrementTtls(response, Math.max(0L, (nowElapsedRealtime - entry.storedAt) / 1000L));
        return response;
    }

    synchronized boolean put(byte[] query, byte[] response, long nowElapsedRealtime) {
        String key = DnsPacketCodec.cacheKey(query);
        if (key == null || !DnsPacketCodec.isValidResponse(query, response)) return false;
        int ttl = DnsPacketCodec.positiveTtlSeconds(response);
        if (ttl <= 0) return false; // Do not cache NXDOMAIN, SERVFAIL or zero-TTL answers.
        entries.put(key, new CacheEntry(response.clone(), nowElapsedRealtime, nowElapsedRealtime + ttl * 1000L));
        return true;
    }

    synchronized int size() { return entries.size(); }

    synchronized void clear() { entries.clear(); }

    private static final class CacheEntry {
        final byte[] response;
        final long storedAt;
        final long expiresAt;
        CacheEntry(byte[] response, long storedAt, long expiresAt) {
            this.response = response;
            this.storedAt = storedAt;
            this.expiresAt = expiresAt;
        }
    }
}
