/**
 * BSD-3-Clause License.
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.tools;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.function.Predicate;

/**
 * Predicate compatible class that tests whether an InetSocketAddress belongs to a given subnet (CIDR).
 * Supports both IPv4 and IPv6 CIDR notation.
 *
 * <p>Example usage:
 * <pre>
 * // Single subnet
 * Predicate<InetSocketAddress> predicate = new SubnetPredicate("10.0.0.0/8");
 *
 * // Multiple subnets
 * Predicate<InetSocketAddress> predicate =
 *     new SubnetPredicate("10.0.0.0/8")
 *         .or(new SubnetPredicate("192.168.0.0/16"))
 *         .or(new SubnetPredicate("2001:db8::/32"))
 * );
 * </pre>
 *
 * Note: it's possible to create a SubnetPredicate with a hostname instead of an IP address,
 * the address will be resolved to an IP address using InetAddress.getByName(hostname).
 * If the hostname is not resolvable, an IllegalArgumentException will be thrown.
 * But if the hostname resolves to a mix of IPv4/IPv6 addresses or multiple addresses,
 * the predicate will only match the first address found.
 *
 * Thread-safety: This class is immutable and thread-safe.
 */
public class SubnetPredicate implements Predicate<InetSocketAddress> {
    private final byte[] networkAddress;
    private final int prefixLength;
    private final int addressLength; // 4 for IPv4, 16 for IPv6

    /**
     * Creates a predicate for the given CIDR subnet.
     *
     * @param cidr CIDR notation string (e.g., "10.0.0.0/8" or "2001:db8::/32")
     * @throws IllegalArgumentException if the CIDR notation is invalid
     */
    public SubnetPredicate(String cidr) {
        if (cidr == null || cidr.isEmpty()) {
            throw new IllegalArgumentException("CIDR notation cannot be null or empty");
        }

        int slashIndex = cidr.indexOf('/');
        if (slashIndex == -1) {
            throw new IllegalArgumentException("Invalid CIDR notation: missing '/' separator");
        }

        String addressPart = cidr.substring(0, slashIndex);
        String prefixLengthPart = cidr.substring(slashIndex + 1);

        try {
            this.prefixLength = Integer.parseInt(prefixLengthPart);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid prefix length: " + prefixLengthPart, e);
        }

        byte[] rawAddress;
        try {
            InetAddress addr = InetAddress.getByName(addressPart);
            rawAddress = addr.getAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid IP address: " + addressPart, e);
        }

        this.addressLength = rawAddress.length;

        // Validate prefix length
        int maxPrefixLength = addressLength * 8; // converts address length to bits
        if (prefixLength < 0 || prefixLength > maxPrefixLength) {
            throw new IllegalArgumentException(
                "Invalid prefix length " + prefixLength + " for address type (must be 0-" + maxPrefixLength + ")"
            );
        }

        // Apply the mask to the network address to normalize it
        this.networkAddress = applyMask(rawAddress);
    }

    /**
     * Tests whether the given socket address belongs to this subnet.
     *
     * @param socketAddress the socket address to test
     * @return true if the address is in this subnet, false otherwise
     */
    @Override
    public boolean test(InetSocketAddress socketAddress) {
        if (socketAddress == null) {
            return false;
        }

        InetAddress address = socketAddress.getAddress();
        if (address == null) {
            return false;
        }

        byte[] testAddress = address.getAddress();

        // Different address families don't match
        if (testAddress.length != addressLength) {
            return false;
        }

        byte[] maskedTestAddress = applyMask(testAddress);

        // Compare network portions
        for (int i = 0; i < networkAddress.length; i++) {
            if (networkAddress[i] != maskedTestAddress[i]) {
                return false;
            }
        }

        return true;
    }

    /**
     * Applies a subnet mask to an IP address.
     *
     * @param address the raw IP address bytes
     * @return the masked address bytes
     */
    private byte[] applyMask(byte[] address) {
        byte[] result = new byte[address.length];

        int fullBytes = prefixLength / 8;
        int remainingBits = prefixLength % 8;

        // Copy the full bytes
        System.arraycopy(address, 0, result, 0, fullBytes);

        // Apply mask to the partial byte if any
        if (remainingBits > 0) {
            int mask = 0xFF << (8 - remainingBits);
            result[fullBytes] = (byte) (address[fullBytes] & mask);
        }

        // Remaining bytes are already 0
        return result;
    }

    @Override
    public String toString() {
        try {
            InetAddress addr = InetAddress.getByAddress(networkAddress);
            return addr.getHostAddress() + "/" + prefixLength;
        } catch (UnknownHostException e) {
            return "SubnetPredicate[invalid]";
        }
    }
}

