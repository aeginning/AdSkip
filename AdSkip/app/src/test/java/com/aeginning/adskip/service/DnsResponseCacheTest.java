package com.aeginning.adskip.service;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class DnsResponseCacheTest {
    private byte[] query(String domain, int id, int type) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) (id >> 8), (byte) id, 0x01, 0x00,
                0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        for (String label : domain.split("\\.")) {
            byte[] value = label.getBytes(StandardCharsets.US_ASCII);
            out.write(value.length);
            out.write(value);
        }
        out.write(0);
        out.write(new byte[]{(byte) (type >> 8), (byte) type, 0, 1});
        return out.toByteArray();
    }

    private byte[] answer(byte[] query, int ttl) throws Exception {
        byte[] response = new byte[query.length + 16];
        System.arraycopy(query, 0, response, 0, query.length);
        response[2] = (byte) 0x81;
        response[3] = (byte) 0x80;
        DnsPacketCodec.put16(response, 6, 1);
        int p = query.length;
        response[p++] = (byte) 0xC0;
        response[p++] = 0x0C;
        response[p++] = 0;
        response[p++] = 1; // A
        response[p++] = 0;
        response[p++] = 1; // IN
        response[p++] = (byte) (ttl >> 24);
        response[p++] = (byte) (ttl >> 16);
        response[p++] = (byte) (ttl >> 8);
        response[p++] = (byte) ttl;
        response[p++] = 0;
        response[p++] = 4;
        response[p++] = (byte) 192;
        response[p++] = 0;
        response[p++] = 2;
        response[p] = 10;
        return response;
    }

    @Test public void validatesUpstreamTransactionAndQuestion() throws Exception {
        byte[] q = query("cache.example.test", 0x1234, 1);
        byte[] r = answer(q, 60);
        assertTrue(DnsPacketCodec.isValidResponse(q, r));
        r[1]++;
        assertFalse(DnsPacketCodec.isValidResponse(q, r));
    }

    @Test public void rejectsMultipleQuestionQueries() throws Exception {
        byte[] q = query("multi.example.test", 0x1010, 1);
        DnsPacketCodec.put16(q, 4, 2);
        assertNull(DnsPacketCodec.blockedNxDomain(q));
        assertNull(DnsPacketCodec.servFail(q));
        assertNull(DnsPacketCodec.cacheKey(q));
    }

    @Test public void positiveTtlIsExtractedFromAnswer() throws Exception {
        byte[] q = query("ttl.example.test", 0x1111, 1);
        assertEquals(60, DnsPacketCodec.positiveTtlSeconds(answer(q, 60)));
        assertEquals(0, DnsPacketCodec.positiveTtlSeconds(answer(q, 0)));
    }

    @Test public void cacheUsesTtlAndRewritesTransactionId() throws Exception {
        DnsResponseCache cache = new DnsResponseCache();
        byte[] q1 = query("cache.example.test", 0x1111, 1);
        byte[] r1 = answer(q1, 60);
        assertTrue(cache.put(q1, r1, 10_000L));

        byte[] q2 = query("cache.example.test", 0x2222, 1);
        byte[] cached = cache.get(q2, 10_500L);
        assertNotNull(cached);
        assertEquals(0x2222, DnsPacketCodec.u16(cached, 0));
        assertEquals(1, DnsPacketCodec.u16(cached, 6));
        assertNull(cache.get(q2, 70_001L));
    }

    @Test public void cachedAnswerTtlDecreasesWithAge() throws Exception {
        byte[] q = query("age.example.test", 0x1212, 1);
        byte[] r = answer(q, 60);
        DnsResponseCache cache = new DnsResponseCache();
        assertTrue(cache.put(q, r, 2_000L));
        byte[] cached = cache.get(query("age.example.test", 0x3434, 1), 12_000L);
        assertNotNull(cached);
        assertEquals(50, DnsPacketCodec.u16(new byte[]{cached[q.length + 8], cached[q.length + 9]}, 0));
    }

    @Test public void ednsOptionsAreSeparatedAndOptFlagsAreNotDecremented() throws Exception {
        byte[] q = query("edns.example.test", 0x5151, 1);
        byte[] withOpt = new byte[q.length + 11];
        System.arraycopy(q, 0, withOpt, 0, q.length);
        DnsPacketCodec.put16(withOpt, 10, 1);
        int o = q.length;
        withOpt[o] = 0; // Root owner.
        DnsPacketCodec.put16(withOpt, o + 1, 41);
        DnsPacketCodec.put16(withOpt, o + 3, 1232);
        withOpt[o + 7] = (byte) 0x80; // EDNS DO flag in OPT TTL field.
        DnsPacketCodec.put16(withOpt, o + 9, 0);

        byte[] response = answer(q, 60);
        byte[] responseWithOpt = new byte[response.length + 11];
        System.arraycopy(response, 0, responseWithOpt, 0, response.length);
        DnsPacketCodec.put16(responseWithOpt, 10, 1);
        int ro = response.length;
        responseWithOpt[ro] = 0;
        DnsPacketCodec.put16(responseWithOpt, ro + 1, 41);
        DnsPacketCodec.put16(responseWithOpt, ro + 3, 1232);
        responseWithOpt[ro + 7] = (byte) 0x80;
        DnsPacketCodec.put16(responseWithOpt, ro + 9, 0);

        DnsResponseCache cache = new DnsResponseCache();
        assertTrue(cache.put(withOpt, responseWithOpt, 5_000L));
        byte[] secondQuery = withOpt.clone();
        secondQuery[0] = 0x61;
        secondQuery[1] = 0x62;
        byte[] cached = cache.get(secondQuery, 15_000L);
        assertNotNull(cached);
        assertEquals(0x80, cached[ro + 7] & 0xFF);
        assertEquals(50, DnsPacketCodec.u16(new byte[]{cached[q.length + 8], cached[q.length + 9]}, 0));

        byte[] noDo = withOpt.clone();
        noDo[o + 7] = 0;
        assertNull(cache.get(noDo, 15_000L));
    }

    @Test public void cacheSeparatesRecordTypesAndDoesNotCacheInvalidResponses() throws Exception {
        DnsResponseCache cache = new DnsResponseCache();
        byte[] a = query("cache.example.test", 0x1111, 1);
        byte[] aaaa = query("cache.example.test", 0x2222, 28);
        byte[] invalid = answer(a, 60);
        invalid[0]++;
        assertFalse(cache.put(a, invalid, 100L));
        assertTrue(cache.put(a, answer(a, 60), 100L));
        assertNull(cache.get(aaaa, 200L));
    }
}
