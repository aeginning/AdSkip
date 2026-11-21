package com.aeginning.adskip.service;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class DnsPacketCodecTest {
    private byte[] makeDnsQuery(String domain) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        for (String label : domain.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            out.write(bytes.length);
            out.write(bytes);
        }
        out.write(0);
        out.write(new byte[]{0, 1, 0, 1}); // A / IN
        return out.toByteArray();
    }

    private byte[] makeIpv4UdpPacket(byte[] dns) {
        int udpLength = 8 + dns.length;
        int total = 20 + udpLength;
        byte[] packet = new byte[total];
        packet[0] = 0x45;
        DnsPacketCodec.put16(packet, 2, total);
        packet[8] = 64;
        packet[9] = 17;
        packet[12] = 10; packet[13] = 0; packet[14] = 0; packet[15] = 3;
        packet[16] = 10; packet[17] = 111; packet[18] = 0; packet[19] = 2;
        DnsPacketCodec.put16(packet, 10, DnsPacketCodec.checksum(packet, 0, 20));
        DnsPacketCodec.put16(packet, 20, 53000);
        DnsPacketCodec.put16(packet, 22, 53);
        DnsPacketCodec.put16(packet, 24, udpLength);
        System.arraycopy(dns, 0, packet, 28, dns.length);
        return packet;
    }

    @Test public void parsesIpv4UdpDnsQuery() throws Exception {
        byte[] query = makeIpv4UdpPacket(makeDnsQuery("ads.example.test"));
        DnsPacketCodec.Query parsed = DnsPacketCodec.parseQuery(query, query.length);
        assertNotNull(parsed);
        assertEquals("ads.example.test", parsed.domain);
        assertEquals(53000, parsed.sourcePort);
        assertEquals(53, parsed.destinationPort);
    }

    @Test public void createsNxDomainResponse() throws Exception {
        byte[] dnsQuery = makeDnsQuery("ads.example.test");
        byte[] blocked = DnsPacketCodec.blockedNxDomain(dnsQuery);
        assertNotNull(blocked);
        assertEquals(0x81, blocked[2] & 0xFF);
        assertEquals(0x83, blocked[3] & 0xFF);
        assertEquals(1, DnsPacketCodec.u16(blocked, 4));
        assertEquals(0, DnsPacketCodec.u16(blocked, 6));
        assertEquals("ads.example.test", DnsPacketCodec.readQuestionName(blocked));
    }

    @Test public void createsServFailResponse() throws Exception {
        byte[] dnsQuery = makeDnsQuery("normal.example.test");
        byte[] failed = DnsPacketCodec.servFail(dnsQuery);
        assertNotNull(failed);
        assertEquals(0x81, failed[2] & 0xFF);
        assertEquals(0x82, failed[3] & 0xFF);
    }

    @Test public void wrapsDnsResponseAndReversesIpv4UdpTuple() throws Exception {
        byte[] packet = makeIpv4UdpPacket(makeDnsQuery("ok.example.test"));
        DnsPacketCodec.Query query = DnsPacketCodec.parseQuery(packet, packet.length);
        byte[] response = DnsPacketCodec.buildResponse(query, query.dnsPayload);
        assertEquals(10, response[12] & 0xFF);
        assertEquals(111, response[13] & 0xFF);
        assertEquals(53000, DnsPacketCodec.u16(response, 22));
        assertEquals(53, DnsPacketCodec.u16(response, 20));
        assertEquals(17, response[9] & 0xFF);
    }

    private byte[] makeIpv6UdpPacket(byte[] dns) {
        int udpLength = 8 + dns.length;
        byte[] packet = new byte[40 + udpLength];
        packet[0] = 0x60;
        DnsPacketCodec.put16(packet, 4, udpLength);
        packet[6] = 17;
        packet[7] = 64;
        packet[8] = (byte) 0xFD; packet[9] = 0; packet[10] = 0; packet[11] = 0;
        packet[23] = 3;
        packet[24] = (byte) 0xFD; packet[25] = 0; packet[26] = 0; packet[27] = 0;
        packet[39] = 2;
        DnsPacketCodec.put16(packet, 40, 53001);
        DnsPacketCodec.put16(packet, 42, 53);
        DnsPacketCodec.put16(packet, 44, udpLength);
        System.arraycopy(dns, 0, packet, 48, dns.length);
        return packet;
    }

    @Test public void parsesIpv6UdpDnsQuery() throws Exception {
        byte[] packet = makeIpv6UdpPacket(makeDnsQuery("ads6.example.test"));
        DnsPacketCodec.Query parsed = DnsPacketCodec.parseQuery(packet, packet.length);
        assertNotNull(parsed);
        assertEquals(6, parsed.ipVersion);
        assertEquals("ads6.example.test", parsed.domain);
        assertEquals(53001, parsed.sourcePort);
    }

    @Test public void buildsIpv6UdpResponseWithRequiredChecksum() throws Exception {
        byte[] packet = makeIpv6UdpPacket(makeDnsQuery("ok6.example.test"));
        DnsPacketCodec.Query parsed = DnsPacketCodec.parseQuery(packet, packet.length);
        byte[] response = DnsPacketCodec.buildResponse(parsed, parsed.dnsPayload);
        assertNotNull(response);
        assertEquals(6, (response[0] >> 4) & 0x0F);
        assertEquals(17, response[6] & 0xFF);
        assertEquals(53, DnsPacketCodec.u16(response, 40));
        assertEquals(53001, DnsPacketCodec.u16(response, 42));
        assertNotEquals(0, DnsPacketCodec.u16(response, 46));
        assertTrue(validIpv6UdpChecksum(response));
    }

    private boolean validIpv6UdpChecksum(byte[] packet) {
        long sum = 0;
        for (int i = 8; i < 40; i += 2) sum += DnsPacketCodec.u16(packet, i);
        int udpLength = DnsPacketCodec.u16(packet, 44);
        sum += (udpLength >>> 16) & 0xFFFF;
        sum += udpLength & 0xFFFF;
        sum += 17;
        for (int i = 40; i + 1 < 40 + udpLength; i += 2) sum += DnsPacketCodec.u16(packet, i);
        if (((udpLength & 1) != 0)) sum += (packet[40 + udpLength - 1] & 0xFF) << 8;
        while ((sum >>> 16) != 0) sum = (sum & 0xFFFF) + (sum >>> 16);
        return (sum & 0xFFFF) == 0xFFFF;
    }

    @Test public void parsesIpv6HopByHopExtensionHeader() throws Exception {
        byte[] dns = makeDnsQuery("extension.example.test");
        int udpLength = 8 + dns.length;
        byte[] packet = new byte[40 + 8 + udpLength];
        packet[0] = 0x60;
        DnsPacketCodec.put16(packet, 4, 8 + udpLength);
        packet[6] = 0; // Hop-by-hop options.
        packet[40] = 17; // Next header UDP.
        packet[41] = 0; // Eight-byte extension header.
        DnsPacketCodec.put16(packet, 48, 53002);
        DnsPacketCodec.put16(packet, 50, 53);
        DnsPacketCodec.put16(packet, 52, udpLength);
        System.arraycopy(dns, 0, packet, 56, dns.length);
        DnsPacketCodec.Query parsed = DnsPacketCodec.parseQuery(packet, packet.length);
        assertNotNull(parsed);
        assertEquals("extension.example.test", parsed.domain);
    }

    @Test public void rejectsFragmentedIpv6DnsPackets() throws Exception {
        byte[] dns = makeDnsQuery("fragment.example.test");
        int udpLength = 8 + dns.length;
        byte[] packet = new byte[40 + 8 + udpLength];
        packet[0] = 0x60;
        DnsPacketCodec.put16(packet, 4, 8 + udpLength);
        packet[6] = 44; // Fragment header.
        packet[40] = 17;
        DnsPacketCodec.put16(packet, 42, 1); // More fragments bit.
        DnsPacketCodec.put16(packet, 48, 53003);
        DnsPacketCodec.put16(packet, 50, 53);
        DnsPacketCodec.put16(packet, 52, udpLength);
        System.arraycopy(dns, 0, packet, 56, dns.length);
        assertNull(DnsPacketCodec.parseQuery(packet, packet.length));
    }

    @Test public void rejectsInvalidIpv4PacketLength() throws Exception {
        byte[] packet = makeIpv4UdpPacket(makeDnsQuery("bad.example.test"));
        DnsPacketCodec.put16(packet, 2, packet.length + 20);
        assertNull(DnsPacketCodec.parseQuery(packet, packet.length));
    }

    @Test public void rejectsIpv4ReservedFragmentFlagAndTrailingBytes() throws Exception {
        byte[] reserved = makeIpv4UdpPacket(makeDnsQuery("reserved.example.test"));
        DnsPacketCodec.put16(reserved, 6, 0x8000);
        assertNull(DnsPacketCodec.parseQuery(reserved, reserved.length));

        byte[] trailing = makeIpv4UdpPacket(makeDnsQuery("trailing.example.test"));
        byte[] padded = java.util.Arrays.copyOf(trailing, trailing.length + 1);
        assertNull(DnsPacketCodec.parseQuery(padded, padded.length));
    }

    @Test public void rejectsUnsupportedDnsOpcode() throws Exception {
        byte[] packet = makeIpv4UdpPacket(makeDnsQuery("update.example.test"));
        packet[30] = 0x08; // Opcode 1 (inverse query), not a standard DNS query.
        assertNull(DnsPacketCodec.parseQuery(packet, packet.length));
    }

    @Test public void blockedResponsesPreserveRecursionAndCheckingFlags() throws Exception {
        byte[] query = makeDnsQuery("blocked.example.test");
        query[2] = 0x01; // RD
        query[3] = 0x10; // CD

        byte[] nxdomain = DnsPacketCodec.blockedNxDomain(query);
        assertNotNull(nxdomain);
        assertEquals(0x81, nxdomain[2] & 0xFF);
        assertEquals(0x93, nxdomain[3] & 0xFF);

        byte[] servfail = DnsPacketCodec.servFail(query);
        assertNotNull(servfail);
        assertEquals(0x92, servfail[3] & 0xFF);
    }

    @Test public void detectsTruncatedDnsUdpResponse() throws Exception {
        byte[] dns = makeDnsQuery("large.example.test");
        dns[2] = (byte) (dns[2] | 0x02);
        assertTrue(DnsPacketCodec.isTruncatedResponse(dns));
        assertFalse(DnsPacketCodec.isTruncatedResponse(makeDnsQuery("ok.example.test")));
    }
}
