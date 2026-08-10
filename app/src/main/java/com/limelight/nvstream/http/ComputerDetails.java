package com.limelight.nvstream.http;

import java.security.cert.X509Certificate;
import java.util.Objects;


/**
 * Everything known about one host: its addresses, identity, pairing state and what it is running.
 *
 * <p>A host can be reachable at several addresses at once — local, remote, IPv6, and a manually
 * entered one — which is why the addresses are a set of {@link AddressTuple}s rather than a single
 * value. The polling service tries them in parallel and records which one worked.
 *
 * <p>Mutable and updated in place by the polling service via {@link #update}, so the grid and the
 * database observe the same instance.
 */
public class ComputerDetails {
    public enum State {
        ONLINE, OFFLINE, UNKNOWN
    }

    /** One address and port a host may be reachable at. */
    public static class AddressTuple {
        public String address;
        public int port;

        public AddressTuple(String address, int port) {
            if (address == null) {
                throw new IllegalArgumentException("Address cannot be null");
            }
            if (port <= 0) {
                throw new IllegalArgumentException("Invalid port");
            }

            // If this was an escaped IPv6 address, remove the brackets
            if (address.startsWith("[") && address.endsWith("]")) {
                address = address.substring(1, address.length() - 1);
            }

            this.address = address;
            this.port = port;
        }

        @Override
        public int hashCode() {
            return Objects.hash(address, port);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof AddressTuple)) {
                return false;
            }

            AddressTuple that = (AddressTuple) obj;
            return address.equals(that.address) && port == that.port;
        }

        public String toString() {
            if (address.contains(":")) {
                // IPv6
                return "[" + address + "]:" + port;
            }
            else {
                // IPv4 and hostnames
                return address + ":" + port;
            }
        }
    }

    // Persistent attributes
    public String uuid;
    public String name;
    public AddressTuple localAddress;
    public AddressTuple manualAddress;
    public AddressTuple ipv6Address;
    public X509Certificate serverCert;

    // Transient attributes
    public State state;
    public AddressTuple activeAddress;
    public int httpsPort;
    public PairingManager.PairState pairState;
    public int runningGameId;
    public String rawAppList;

    /** Creates an empty host, to be filled in by a poll. */
    public ComputerDetails() {
        // Use defaults
        state = State.UNKNOWN;
    }

    /** Copy constructor, used to snapshot a host without holding the live instance. */
    public ComputerDetails(ComputerDetails details) {
        // Copy details from the other computer
        update(details);
    }

    /**
     * Merges a freshly polled snapshot into this instance, preserving fields the poll didn't
     * populate — a poll that reached the host over one address must not erase the others.
     */
    public void update(ComputerDetails details) {
        this.state = details.state;
        this.name = details.name;
        this.uuid = details.uuid;
        if (details.activeAddress != null) {
            this.activeAddress = details.activeAddress;
        }
        // We can get IPv4 loopback addresses with GS IPv6 Forwarder
        if (details.localAddress != null && !details.localAddress.address.startsWith("127.")) {
            this.localAddress = details.localAddress;
        }
        if (details.manualAddress != null) {
            this.manualAddress = details.manualAddress;
        }
        if (details.ipv6Address != null) {
            this.ipv6Address = details.ipv6Address;
        }
        if (details.serverCert != null) {
            this.serverCert = details.serverCert;
        }
        this.httpsPort = details.httpsPort;
        this.pairState = details.pairState;
        this.runningGameId = details.runningGameId;
        this.rawAppList = details.rawAppList;
    }

    /** @return a diagnostic summary of the host's addresses and state */
    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        str.append("Name: ").append(name).append("\n");
        str.append("State: ").append(state).append("\n");
        str.append("Active Address: ").append(activeAddress).append("\n");
        str.append("UUID: ").append(uuid).append("\n");
        str.append("Local Address: ").append(localAddress).append("\n");
        str.append("IPv6 Address: ").append(ipv6Address).append("\n");
        str.append("Manual Address: ").append(manualAddress).append("\n");
        str.append("Pair State: ").append(pairState).append("\n");
        str.append("Running Game ID: ").append(runningGameId).append("\n");
        str.append("HTTPS Port: ").append(httpsPort).append("\n");
        return str.toString();
    }
}
