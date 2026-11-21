package com.aeginning.adskip.service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Defensive IPv4/IPv6 UDP DNS packet codec for the DNS-only VPN. */
final class DnsPacketCodec {
    private DnsPacketCodec() { }

    static Query parseQuery(byte[] packet, int length) {
        if (packet == null || length < 28) return null;
        int version = (packet[0] >> 4) & 0x0F;
        if (version == 4) return parseIpv4Udp(packet, length);
        if (version == 6) return parseIpv6Udp(packet, length);
        return null;
    }

    private static Query parseIpv4Udp(byte[] packet, int length) {
        if (length < 28) return null;
        int ihl = (packet[0] & 0x0F) * 4;
        if (ihl < 20 || length < ihl + 8 || (packet[9] & 0xFF) != 17) return null;
        if ((u16(packet, 6) & 0xBFFF) != 0) return null; // Reject reserved flag and all fragments.
        int totalLen = u16(packet, 2);
        if (totalLen != length || totalLen < ihl + 8) return null;
        return parseUdp(packet, totalLen, ihl, 4,
                Arrays.copyOfRange(packet, 12, 16), Arrays.copyOfRange(packet, 16, 20));
    }

    private static Query parseIpv6Udp(byte[] packet, int length) {
        if (length < 48) return null;
        int payloadLength = u16(packet, 4);
        int totalLength = 40 + payloadLength;
        if (payloadLength < 8 || totalLength != length) return null;
        int nextHeader = packet[6] & 0xFF;
        int offset = 40;
        int headers = 0;
        while (nextHeader != 17 && headers++ < 8) {
            if (nextHeader == 44) { // Fragment header: only allow unfragmented atomic packets.
                if (offset + 8 > totalLength) return null;
                int fragment = u16(packet, offset + 2);
                if ((fragment & 0xFFF9) != 0) return null;
                nextHeader = packet[offset];
                offset += 8;
            } else if (nextHeader == 0 || nextHeader == 43 || nextHeader == 60 || nextHeader == 135) {
                if (offset + 2 > totalLength) return null;
                int headerLength = ((packet[offset + 1] & 0xFF) + 1) * 8;
                if (headerLength < 8 || offset + headerLength > totalLength) return null;
                nextHeader = packet[offset];
                offset += headerLength;
            } else if (nextHeader == 51) { // Authentication header.
                if (offset + 2 > totalLength) return null;
                int headerLength = ((packet[offset + 1] & 0xFF) + 2) * 4;
                if (headerLength < 8 || offset + headerLength > totalLength) return null;
                nextHeader = packet[offset];
                offset += headerLength;
            } else {
                return null;
            }
        }
        if (nextHeader != 17) return null;
        return parseUdp(packet, totalLength, offset, 6,
                Arrays.copyOfRange(packet, 8, 24), Arrays.copyOfRange(packet, 24, 40));
    }

    private static Query parseUdp(byte[] packet, int totalLength, int udpOffset, int ipVersion,
                                  byte[] sourceAddress, byte[] destinationAddress) {
        if (udpOffset + 8 > totalLength) return null;
        int sourcePort = u16(packet, udpOffset);
        int destinationPort = u16(packet, udpOffset + 2);
        if (destinationPort != 53) return null;
        int udpLength = u16(packet, udpOffset + 4);
        if (udpLength < 8 || udpOffset + udpLength != totalLength) return null;
        int dnsOffset = udpOffset + 8;
        int dnsLength = udpLength - 8;
        if (dnsLength < 17 || dnsLength > 4096) return null;
        byte[] dns = Arrays.copyOfRange(packet, dnsOffset, dnsOffset + dnsLength);
        String domain = readQuestionName(dns);
        if (domain.isEmpty() || (dns[2] & 0x80) != 0 || (dns[2] & 0x78) != 0) return null;
        // Only standard DNS queries are filtered; UPDATE and other opcodes pass through untouched.
        return new Query(ipVersion, sourceAddress, destinationAddress, sourcePort, destinationPort, dns);
    }

    static byte[] buildResponse(Query query, byte[] dnsPayload) {
        if (query == null || dnsPayload == null) return null;
        if (query.ipVersion == 6) return buildIpv6Response(query, dnsPayload);
        return buildIpv4Response(query, dnsPayload);
    }

    private static byte[] buildIpv4Response(Query query, byte[] dnsPayload) {
        int udpLength = 8 + dnsPayload.length;
        int totalLength = 20 + udpLength;
        if (totalLength > 65535) return null;
        byte[] packet = new byte[totalLength];
        packet[0] = 0x45;
        put16(packet, 2, totalLength);
        put16(packet, 6, 0x4000);
        packet[8] = 64;
        packet[9] = 17;
        System.arraycopy(query.destinationAddress, 0, packet, 12, 4);
        System.arraycopy(query.sourceAddress, 0, packet, 16, 4);
        put16(packet, 10, checksum(packet, 0, 20));
        put16(packet, 20, query.destinationPort);
        put16(packet, 22, query.sourcePort);
        put16(packet, 24, udpLength);
        System.arraycopy(dnsPayload, 0, packet, 28, dnsPayload.length);
        return packet;
    }

    private static byte[] buildIpv6Response(Query query, byte[] dnsPayload) {
        int udpLength = 8 + dnsPayload.length;
        if (udpLength > 65535) return null;
        byte[] packet = new byte[40 + udpLength];
        packet[0] = 0x60;
        put16(packet, 4, udpLength);
        packet[6] = 17;
        packet[7] = 64;
        System.arraycopy(query.destinationAddress, 0, packet, 8, 16);
        System.arraycopy(query.sourceAddress, 0, packet, 24, 16);
        put16(packet, 40, query.destinationPort);
        put16(packet, 42, query.sourcePort);
        put16(packet, 44, udpLength);
        System.arraycopy(dnsPayload, 0, packet, 48, dnsPayload.length);
        int checksum = ipv6UdpChecksum(packet, 8, 24, udpLength);
        put16(packet, 46, checksum == 0 ? 0xFFFF : checksum);
        return packet;
    }

    private static int ipv6UdpChecksum(byte[] packet, int sourceOffset, int destinationOffset, int udpLength) {
        long sum = 0;
        for (int i = 0; i < 16; i += 2) {
            sum += u16(packet, sourceOffset + i);
            sum += u16(packet, destinationOffset + i);
        }
        sum += (udpLength >>> 16) & 0xFFFF;
        sum += udpLength & 0xFFFF;
        sum += 17; // Next Header = UDP.
        sum += checksumSum(packet, 40, udpLength);
        while ((sum >>> 16) != 0) sum = (sum & 0xFFFF) + (sum >>> 16);
        return (int) (~sum) & 0xFFFF;
    }

    private static long checksumSum(byte[] data, int offset, int length) {
        long sum = 0;
        int i = offset;
        while (i + 1 < offset + length) {
            sum += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            i += 2;
        }
        if (i < offset + length) sum += (data[i] & 0xFF) << 8;
        return sum;
    }

    static boolean isTruncatedResponse(byte[] dns) {
        return dns != null && dns.length >= 4 && (dns[2] & 0x02) != 0;
    }

    /** Validate an upstream response against the exact query that was sent. */
    static boolean isValidResponse(byte[] query, byte[] response) {
        if (query == null || response == null || query.length < 17 || response.length < 17) return false;
        if (u16(query, 0) != u16(response, 0)) return false; // Transaction ID.
        if ((response[2] & 0x80) == 0) return false; // QR must mark a response.
        if ((query[2] & 0x78) != (response[2] & 0x78)) return false; // Same opcode.
        if (u16(query, 4) != 1 || u16(response, 4) != 1) return false;
        String queryName = readQuestionName(query);
        String responseName = readQuestionName(response);
        if (queryName.isEmpty() || !queryName.equals(responseName)) return false;
        int queryEnd = findQuestionEnd(query);
        int responseEnd = findQuestionEnd(response);
        if (queryEnd < 0 || responseEnd < 0) return false;
        return u16(query, queryEnd - 4) == u16(response, responseEnd - 4)
                && u16(query, queryEnd - 2) == u16(response, responseEnd - 2);
    }

    /** Cache key includes QNAME, QTYPE and QCLASS to avoid mixing A/AAAA and other records. */
    static String cacheKey(byte[] dns) {
        if (dns == null || dns.length < 17 || u16(dns, 4) != 1 || (dns[2] & 0x80) != 0) return null;
        String name = readQuestionName(dns);
        int end = findQuestionEnd(dns);
        if (name.isEmpty() || end < 0) return null;
        StringBuilder key = new StringBuilder(name.length() + 24 + (dns.length - end) * 2);
        key.append(name).append('|').append(u16(dns, end - 4)).append('|')
                .append(u16(dns, end - 2)).append('|').append(u16(dns, 2));
        // Include EDNS/additional bytes so DNSSEC flags, UDP payload size and options never collide.
        final char[] hex = "0123456789abcdef".toCharArray();
        for (int i = end; i < dns.length; i++) {
            int value = dns[i] & 0xFF;
            key.append(hex[value >>> 4]).append(hex[value & 0x0F]);
        }
        return key.toString();
    }

    /** Return the minimum positive TTL across answer records, or zero for malformed/negative responses. */
    static int positiveTtlSeconds(byte[] response) {
        if (response == null || response.length < 17 || (response[2] & 0x80) == 0
                || (response[2] & 0x02) != 0 || (response[3] & 0x0F) != 0
                || u16(response, 4) != 1 || u16(response, 6) < 1) return 0;
        int offset = findQuestionEnd(response);
        if (offset < 0) return 0;
        int answers = u16(response, 6);
        long minTtl = Long.MAX_VALUE;
        for (int i = 0; i < answers; i++) {
            offset = skipDnsName(response, offset);
            if (offset < 0 || offset + 10 > response.length) return 0;
            long ttl = ((long) (response[offset + 4] & 0xFF) << 24)
                    | ((long) (response[offset + 5] & 0xFF) << 16)
                    | ((long) (response[offset + 6] & 0xFF) << 8)
                    | (long) (response[offset + 7] & 0xFF);
            int dataLength = u16(response, offset + 8);
            offset += 10;
            if (offset + dataLength > response.length) return 0;
            minTtl = Math.min(minTtl, ttl);
            offset += dataLength;
        }
        if (minTtl == Long.MAX_VALUE) return 0;
        return (int) Math.min(300L, minTtl);
    }

    /** Decrease cached resource-record TTLs before returning the response to another client. */
    static void decrementTtls(byte[] response, long elapsedSeconds) {
        if (response == null || response.length < 12 || elapsedSeconds <= 0) return;
        int offset = findQuestionEnd(response);
        if (offset < 0) return;
        int records = u16(response, 6) + u16(response, 8) + u16(response, 10);
        if (records > 8192) return;
        for (int i = 0; i < records; i++) {
            offset = skipDnsName(response, offset);
            if (offset < 0 || offset + 10 > response.length) return;
            int recordType = u16(response, offset);
            if (recordType != 41) { // OPT pseudo-record TTL carries EDNS flags, not a resource TTL.
                long ttl = ((long) (response[offset + 4] & 0xFF) << 24)
                        | ((long) (response[offset + 5] & 0xFF) << 16)
                        | ((long) (response[offset + 6] & 0xFF) << 8)
                        | (long) (response[offset + 7] & 0xFF);
                long remaining = Math.max(0L, ttl - elapsedSeconds);
                response[offset + 4] = (byte) (remaining >>> 24);
                response[offset + 5] = (byte) (remaining >>> 16);
                response[offset + 6] = (byte) (remaining >>> 8);
                response[offset + 7] = (byte) remaining;
            }
            int dataLength = u16(response, offset + 8);
            offset += 10;
            if (offset + dataLength > response.length) return;
            offset += dataLength;
        }
    }

    private static int skipDnsName(byte[] dns, int start) {
        int offset = start;
        int labels = 0;
        while (offset < dns.length && labels++ < 128) {
            int size = dns[offset] & 0xFF;
            if (size == 0) return offset + 1;
            if ((size & 0xC0) == 0xC0) {
                if (offset + 1 >= dns.length) return -1;
                int pointer = ((size & 0x3F) << 8) | (dns[offset + 1] & 0xFF);
                return pointer < offset && pointer < dns.length ? offset + 2 : -1;
            }
            if ((size & 0xC0) != 0 || size > 63 || offset + 1 + size > dns.length) return -1;
            offset += 1 + size;
        }
        return -1;
    }

    static byte[] blockedNxDomain(byte[] query) {
        if (query == null || query.length < 17 || u16(query, 4) != 1) return null;
        int questionEnd = findQuestionEnd(query);
        if (questionEnd < 0) return null;
        byte[] response = Arrays.copyOf(query, questionEnd);
        response[2] = (byte) ((query[2] & 0x79) | 0x80); // QR + preserve opcode/RD.
        response[3] = (byte) ((query[3] & 0x10) | 0x80 | 0x03); // RA + preserve CD + NXDOMAIN.
        put16(response, 4, 1);
        put16(response, 6, 0);
        put16(response, 8, 0);
        put16(response, 10, 0);
        return response;
    }

    static byte[] servFail(byte[] query) {
        if (query == null || query.length < 17 || u16(query, 4) != 1) return null;
        int questionEnd = findQuestionEnd(query);
        if (questionEnd < 0) return null;
        byte[] response = Arrays.copyOf(query, questionEnd);
        response[2] = (byte) ((query[2] & 0x79) | 0x80); // QR + preserve opcode/RD.
        response[3] = (byte) ((query[3] & 0x10) | 0x80 | 0x02); // RA + preserve CD + SERVFAIL.
        put16(response, 4, 1);
        put16(response, 6, 0);
        put16(response, 8, 0);
        put16(response, 10, 0);
        return response;
    }

    static String readQuestionName(byte[] dns) {
        if (dns == null || dns.length < 17 || u16(dns, 4) != 1) return "";
        int offset = 12;
        StringBuilder name = new StringBuilder();
        int labels = 0;
        int totalNameBytes = 0;
        while (offset < dns.length) {
            int size = dns[offset++] & 0xFF;
            if (size == 0) break;
            if ((size & 0xC0) != 0 || size > 63 || offset + size > dns.length || ++labels > 127) return "";
            totalNameBytes += size + 1;
            if (totalNameBytes > 255) return "";
            if (name.length() > 0) name.append('.');
            name.append(new String(dns, offset, size, StandardCharsets.US_ASCII));
            offset += size;
        }
        if (offset + 4 > dns.length || name.length() == 0) return "";
        return name.toString().toLowerCase(java.util.Locale.ROOT);
    }

    private static int findQuestionEnd(byte[] dns) {
        if (dns == null || dns.length < 17 || u16(dns, 4) != 1) return -1;
        int offset = 12;
        int labels = 0;
        int totalNameBytes = 0;
        while (offset < dns.length && labels++ < 128) {
            int size = dns[offset++] & 0xFF;
            if (size == 0) return offset + 4 <= dns.length ? offset + 4 : -1;
            if ((size & 0xC0) != 0 || size > 63 || offset + size > dns.length) return -1;
            totalNameBytes += size + 1;
            if (totalNameBytes > 255) return -1;
            offset += size;
        }
        return -1;
    }

    static int checksum(byte[] data, int offset, int length) {
        long sum = checksumSum(data, offset, length);
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum) & 0xFFFF;
    }

    static int u16(byte[] data, int offset) { return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF); }
    static void put16(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }

    static final class Query {
        final int ipVersion;
        final byte[] sourceAddress;
        final byte[] destinationAddress;
        final int sourcePort;
        final int destinationPort;
        final byte[] dnsPayload;
        final String domain;
        Query(int ipVersion, byte[] sourceAddress, byte[] destinationAddress,
              int sourcePort, int destinationPort, byte[] dnsPayload) {
            this.ipVersion = ipVersion;
            this.sourceAddress = sourceAddress;
            this.destinationAddress = destinationAddress;
            this.sourcePort = sourcePort;
            this.destinationPort = destinationPort;
            this.dnsPayload = dnsPayload;
            this.domain = readQuestionName(dnsPayload);
        }
    }
}
