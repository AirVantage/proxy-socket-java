/**
 * BSD-3-Clause License.
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SubnetPredicateTest {

    // ========== Parameterized Tests for Matching Addresses ==========

    @ParameterizedTest(name = "{0} should match {1}")
    @CsvSource({
        // IPv4 - Single Host /32
        "192.168.1.100/32, 192.168.1.100",
        // IPv4 - Class C /24
        "192.168.1.0/24, 192.168.1.0",
        "192.168.1.0/24, 192.168.1.1",
        "192.168.1.0/24, 192.168.1.100",
        "192.168.1.0/24, 192.168.1.255",
        // IPv4 - Class B /16
        "172.16.0.0/16, 172.16.0.0",
        "172.16.0.0/16, 172.16.1.1",
        "172.16.0.0/16, 172.16.255.255",
        // IPv4 - Class A /8
        "10.0.0.0/8, 10.0.0.0",
        "10.0.0.0/8, 10.0.0.1",
        "10.0.0.0/8, 10.255.255.255",
        "10.0.0.0/8, 10.123.45.67",
        // IPv4 - /25
        "192.168.1.0/25, 192.168.1.0",
        "192.168.1.0/25, 192.168.1.127",
        // IPv4 - /23
        "192.168.0.0/23, 192.168.0.0",
        "192.168.0.0/23, 192.168.0.255",
        "192.168.0.0/23, 192.168.1.0",
        "192.168.0.0/23, 192.168.1.255",
        // IPv4 - /0 (matches all IPv4)
        "0.0.0.0/0, 0.0.0.0",
        "0.0.0.0/0, 1.2.3.4",
        "0.0.0.0/0, 192.168.1.1",
        "0.0.0.0/0, 255.255.255.255",
        // IPv6 - Single Host /128
        "2001:db8::1/128, 2001:db8::1",
        // IPv6 - /64
        "2001:db8::/64, 2001:db8::1",
        "2001:db8::/64, 2001:db8::ffff:ffff:ffff:ffff",
        "2001:db8::/64, 2001:db8:0:0:1234:5678:9abc:def0",
        // IPv6 - /32
        "2001:db8::/32, 2001:db8::",
        "2001:db8::/32, 2001:db8:ffff:ffff:ffff:ffff:ffff:ffff",
        // IPv6 - Loopback
        "::1/128, ::1",
        // IPv6 - /0 (matches all IPv6)
        "::/0, ::",
        "::/0, ::1",
        "::/0, 2001:db8::1",
        "::/0, ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"
    })
    void testAddressShouldMatch(String cidr, String address) throws UnknownHostException {
        SubnetPredicate predicate = new SubnetPredicate(cidr);
        assertTrue(predicate.test(addr(address, 8080)),
            address + " should match " + cidr);
    }

    @ParameterizedTest(name = "{0} should NOT match {1}")
    @CsvSource({
        // IPv4 - Single Host /32
        "192.168.1.100/32, 192.168.1.101",
        "192.168.1.100/32, 192.168.1.99",
        // IPv4 - Class C /24
        "192.168.1.0/24, 192.168.0.255",
        "192.168.1.0/24, 192.168.2.0",
        "192.168.1.0/24, 192.167.1.1",
        // IPv4 - Class B /16
        "172.16.0.0/16, 172.15.255.255",
        "172.16.0.0/16, 172.17.0.0",
        // IPv4 - Class A /8
        "10.0.0.0/8, 9.255.255.255",
        "10.0.0.0/8, 11.0.0.0",
        // IPv4 - /25
        "192.168.1.0/25, 192.168.1.128",
        "192.168.1.0/25, 192.168.1.255",
        // IPv4 - /23
        "192.168.0.0/23, 192.168.2.0",
        "192.168.0.0/23, 192.167.255.255",
        // IPv4 - /0 (doesn't match IPv6)
        "0.0.0.0/0, ::1",
        // IPv6 - Single Host /128
        "2001:db8::1/128, 2001:db8::2",
        // IPv6 - /64
        "2001:db8::/64, 2001:db8:0:1::1",
        // IPv6 - /32
        "2001:db8::/32, 2001:db9::",
        "2001:db8::/32, 2001:db7:ffff:ffff:ffff:ffff:ffff:ffff",
        // IPv6 - Loopback
        "::1/128, ::2",
        // IPv6 - /0 (doesn't match IPv4)
        "::/0, 192.168.1.1"
    })
    void testAddressShouldNotMatch(String cidr, String address) throws UnknownHostException {
        SubnetPredicate predicate = new SubnetPredicate(cidr);
        assertFalse(predicate.test(addr(address, 8080)),
            address + " should NOT match " + cidr);
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
        Predicate<InetSocketAddress> combined = predicate1.or(predicate2);

        assertTrue(combined.test(addr("192.168.1.100", 8080)));
        assertTrue(combined.test(addr("10.20.30.40", 8080)));
        assertFalse(combined.test(addr("172.16.0.1", 8080)));
    }

    @Test
    void testPredicateComposition_And() throws UnknownHostException {
        SubnetPredicate predicate1 = new SubnetPredicate("192.168.0.0/16");
        SubnetPredicate predicate2 = new SubnetPredicate("192.168.1.0/24");
        Predicate<InetSocketAddress> combined = predicate1.and(predicate2);

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

    @ParameterizedTest(name = "Invalid CIDR: ''{0}''")
    @ValueSource(strings = {
        "",
        "192.168.1.0",              // Missing slash
        "192.168.1.0/abc",          // Invalid prefix
        "192.168.1.0/33",           // Prefix too large for IPv4
        "2001:db8::/129",           // Prefix too large for IPv6
        "192.168.1.0/-1",           // Negative prefix
        "256.256.256.256/24"        // Invalid IP address
    })
    void testInvalidCIDR(String cidr) {
        assertThrows(IllegalArgumentException.class, () -> new SubnetPredicate(cidr));
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

