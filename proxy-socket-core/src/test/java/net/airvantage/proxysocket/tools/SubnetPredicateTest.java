/*
 * MIT License
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.tools;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

class SubnetPredicateTest {

    // ========== IPv4 Tests ==========

    @Test
    void testIPv4_SingleHost_32BitMask() throws UnknownHostException {
        SubnetPredicate predicate = new SubnetPredicate("192.168.1.100/32");

        assertTrue(predicate.test(addr("192.168.1.100", 8080)));
        assertFalse(predicate.test(addr("192.168.1.101", 8080)));
        assertFalse(predicate.test(addr("192.168.1.99", 8080)));
    }

    @Test
    void testIPv4_ClassC_24BitMask() throws UnknownHostException {
        SubnetPredicate predicate = new SubnetPredicate("192.168.1.0/24");

        assertTrue(predicate.test(addr("192.168.1.0", 8080)));
        assertTrue(predicate.test(addr("192.168.1.1", 8080)));
        assertTrue(predicate.test(addr("192.168.1.100", 8080)));
        assertTrue(predicate.test(addr("192.168.1.255", 8080)));

        assertFalse(predicate.test(addr("192.168.0.255", 8080)));
        assertFalse(predicate.test(addr("192.168.2.0", 8080)));
        assertFalse(predicate.test(addr("192.167.1.1", 8080)));
    }

    @Test
    void testIPv4_ClassB_16BitMask() throws UnknownHostException {
        SubnetPredicate predicate = new SubnetPredicate("172.16.0.0/16");

        assertTrue(predicate.test(addr("172.16.0.0", 8080)));
        assertTrue(predicate.test(addr("172.16.1.1", 8080)));
        assertTrue(predicate.test(addr("172.16.255.255", 8080)));

        assertFalse(predicate.test(addr("172.15.255.255", 8080)));
        assertFalse(predicate.test(addr("172.17.0.0", 8080)));
    }

    @Test
    void testIPv4_ClassA_8BitMask() throws UnknownHostException {
        SubnetPredicate predicate = new SubnetPredicate("10.0.0.0/8");

        assertTrue(predicate.test(addr("10.0.0.0", 8080)));
        assertTrue(predicate.test(addr("10.0.0.1", 8080)));
        assertTrue(predicate.test(addr("10.255.255.255", 8080)));
        assertTrue(predicate.test(addr("10.123.45.67", 8080)));

        assertFalse(predicate.test(addr("9.255.255.255", 8080)));
        assertFalse(predicate.test(addr("11.0.0.0", 8080)));
    }

    @Test
    void testIPv4_NonStandardMask_25Bits() throws UnknownHostException {
        SubnetPredicate predicate = new SubnetPredicate("192.168.1.0/25");

        // First half: 192.168.1.0 - 192.168.1.127
        assertTrue(predicate.test(addr("192.168.1.0", 8080)));
        assertTrue(predicate.test(addr("192.168.1.127", 8080)));

        // Second half: 192.168.1.128 - 192.168.1.255
        assertFalse(predicate.test(addr("192.168.1.128", 8080)));
        assertFalse(predicate.test(addr("192.168.1.255", 8080)));
    }

    @Test
    void testIPv4_NonStandardMask_23Bits() throws UnknownHostException {
        SubnetPredicate predicate = new SubnetPredicate("192.168.0.0/23");

        assertTrue(predicate.test(addr("192.168.0.0", 8080)));
        assertTrue(predicate.test(addr("192.168.0.255", 8080)));
        assertTrue(predicate.test(addr("192.168.1.0", 8080)));
        assertTrue(predicate.test(addr("192.168.1.255", 8080)));

        assertFalse(predicate.test(addr("192.168.2.0", 8080)));
        assertFalse(predicate.test(addr("192.167.255.255", 8080)));
    }

    @Test
    void testIPv4_ZeroMask_MatchesAll() throws UnknownHostException {
        SubnetPredicate predicate = new SubnetPredicate("0.0.0.0/0");

        assertTrue(predicate.test(addr("0.0.0.0", 8080)));
        assertTrue(predicate.test(addr("1.2.3.4", 8080)));
        assertTrue(predicate.test(addr("192.168.1.1", 8080)));
        assertTrue(predicate.test(addr("255.255.255.255", 8080)));

        // But not IPv6
        assertFalse(predicate.test(addr("::1", 8080)));
    }

    // ========== IPv6 Tests ==========

    @Test
    void testIPv6_SingleHost_128BitMask() throws UnknownHostException {
        SubnetPredicate predicate = new SubnetPredicate("2001:db8::1/128");

        assertTrue(predicate.test(addr("2001:db8::1", 8080)));
        assertFalse(predicate.test(addr("2001:db8::2", 8080)));
    }

    @Test
    void testIPv6_CommonSubnet_64BitMask() throws UnknownHostException {
        SubnetPredicate predicate = new SubnetPredicate("2001:db8::/64");

        assertTrue(predicate.test(addr("2001:db8::1", 8080)));
        assertTrue(predicate.test(addr("2001:db8::ffff:ffff:ffff:ffff", 8080)));
        assertTrue(predicate.test(addr("2001:db8:0:0:1234:5678:9abc:def0", 8080)));

        assertFalse(predicate.test(addr("2001:db8:0:1::1", 8080)));
    }

    @Test
    void testIPv6_WideSubnet_32BitMask() throws UnknownHostException {
        SubnetPredicate predicate = new SubnetPredicate("2001:db8::/32");

        assertTrue(predicate.test(addr("2001:db8::", 8080)));
        assertTrue(predicate.test(addr("2001:db8:ffff:ffff:ffff:ffff:ffff:ffff", 8080)));

        assertFalse(predicate.test(addr("2001:db9::", 8080)));
        assertFalse(predicate.test(addr("2001:db7:ffff:ffff:ffff:ffff:ffff:ffff", 8080)));
    }

    @Test
    void testIPv6_Loopback() throws UnknownHostException {
        SubnetPredicate predicate = new SubnetPredicate("::1/128");

        assertTrue(predicate.test(addr("::1", 8080)));
        assertFalse(predicate.test(addr("::2", 8080)));
    }

    @Test
    void testIPv6_ZeroMask_MatchesAll() throws UnknownHostException {
        SubnetPredicate predicate = new SubnetPredicate("::/0");

        assertTrue(predicate.test(addr("::", 8080)));
        assertTrue(predicate.test(addr("::1", 8080)));
        assertTrue(predicate.test(addr("2001:db8::1", 8080)));
        assertTrue(predicate.test(addr("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 8080)));

        // But not IPv4
        assertFalse(predicate.test(addr("192.168.1.1", 8080)));
    }

    // ========== Edge Cases ==========

    @Test
    void testNullSocketAddress() {
        SubnetPredicate predicate = new SubnetPredicate("192.168.1.0/24");
        assertFalse(predicate.test(null));
    }

    @Test
    void testPortIsIgnored() throws UnknownHostException {
        SubnetPredicate predicate = new SubnetPredicate("192.168.1.0/24");

        assertTrue(predicate.test(addr("192.168.1.100", 80)));
        assertTrue(predicate.test(addr("192.168.1.100", 443)));
        assertTrue(predicate.test(addr("192.168.1.100", 8080)));
        assertTrue(predicate.test(addr("192.168.1.100", 65535)));
    }

    @Test
    void testIPv4vsIPv6_NoMatch() throws UnknownHostException {
        SubnetPredicate ipv4Predicate = new SubnetPredicate("192.168.1.0/24");
        SubnetPredicate ipv6Predicate = new SubnetPredicate("2001:db8::/32");

        // IPv4 predicate doesn't match IPv6 address
        assertFalse(ipv4Predicate.test(addr("2001:db8::1", 8080)));

        // IPv6 predicate doesn't match IPv4 address
        assertFalse(ipv6Predicate.test(addr("192.168.1.1", 8080)));
    }

    @Test
    void testPredicateComposition_Or() throws UnknownHostException {
        SubnetPredicate predicate1 = new SubnetPredicate("192.168.1.0/24");
        SubnetPredicate predicate2 = new SubnetPredicate("10.0.0.0/8");
        SubnetPredicate combined = predicate1.or(predicate2);

        assertTrue(combined.test(addr("192.168.1.100", 8080)));
        assertTrue(combined.test(addr("10.20.30.40", 8080)));
        assertFalse(combined.test(addr("172.16.0.1", 8080)));
    }

    @Test
    void testPredicateComposition_And() throws UnknownHostException {
        SubnetPredicate predicate1 = new SubnetPredicate("192.168.0.0/16");
        SubnetPredicate predicate2 = new SubnetPredicate("192.168.1.0/24");
        SubnetPredicate combined = predicate1.and(predicate2);

        assertTrue(combined.test(addr("192.168.1.100", 8080)));
        assertFalse(combined.test(addr("192.168.2.100", 8080)));
    }

    @Test
    void testPredicateComposition_Negate() throws UnknownHostException {
        SubnetPredicate predicate = new SubnetPredicate("192.168.1.0/24");

        assertTrue(predicate.test(addr("192.168.1.100", 8080)));
        assertFalse(predicate.negate().test(addr("192.168.1.100", 8080)));

        assertFalse(predicate.test(addr("192.168.2.100", 8080)));
        assertTrue(predicate.negate().test(addr("192.168.2.100", 8080)));
    }

    @Test
    void testToString_IPv4() {
        SubnetPredicate predicate = new SubnetPredicate("192.168.1.0/24");
        assertEquals("192.168.1.0/24", predicate.toString());
    }

    @Test
    void testToString_IPv6() {
        SubnetPredicate predicate = new SubnetPredicate("2001:db8::/32");
        assertTrue(predicate.toString().contains("2001:db8"));
        assertTrue(predicate.toString().contains("/32"));
    }

    // ========== Invalid Input Tests ==========

    @Test
    void testInvalidCIDR_NullInput() {
        assertThrows(IllegalArgumentException.class, () -> new SubnetPredicate(null));
    }

    @Test
    void testInvalidCIDR_EmptyString() {
        assertThrows(IllegalArgumentException.class, () -> new SubnetPredicate(""));
    }

    @Test
    void testInvalidCIDR_MissingSlash() {
        assertThrows(IllegalArgumentException.class, () -> new SubnetPredicate("192.168.1.0"));
    }

    @Test
    void testInvalidCIDR_InvalidPrefix() {
        assertThrows(IllegalArgumentException.class, () -> new SubnetPredicate("192.168.1.0/abc"));
    }

    @Test
    void testInvalidCIDR_PrefixTooLarge_IPv4() {
        assertThrows(IllegalArgumentException.class, () -> new SubnetPredicate("192.168.1.0/33"));
    }

    @Test
    void testInvalidCIDR_PrefixTooLarge_IPv6() {
        assertThrows(IllegalArgumentException.class, () -> new SubnetPredicate("2001:db8::/129"));
    }

    @Test
    void testInvalidCIDR_NegativePrefix() {
        assertThrows(IllegalArgumentException.class, () -> new SubnetPredicate("192.168.1.0/-1"));
    }

    @Test
    void testInvalidCIDR_InvalidIPAddress() {
        assertThrows(IllegalArgumentException.class, () -> new SubnetPredicate("256.256.256.256/24"));
    }

    @Test
    void testNonNormalizedCIDR_StillWorks() throws UnknownHostException {
        // 192.168.1.100/24 is not normalized (should be 192.168.1.0/24)
        // but the implementation should normalize it
        SubnetPredicate predicate = new SubnetPredicate("192.168.1.100/24");

        assertTrue(predicate.test(addr("192.168.1.0", 8080)));
        assertTrue(predicate.test(addr("192.168.1.100", 8080)));
        assertTrue(predicate.test(addr("192.168.1.255", 8080)));
        assertFalse(predicate.test(addr("192.168.2.0", 8080)));
    }

    // ========== Helper Methods ==========

    private InetSocketAddress addr(String host, int port) throws UnknownHostException {
        return new InetSocketAddress(InetAddress.getByName(host), port);
    }
}

