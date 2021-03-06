package com.wjholden.ospf;

import org.neo4j.cypher.internal.expressions.In;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

public class RouterLsa extends Lsa {
    public final InetAddress routerId;
    public final int links;
    public final List<Link> adjacencies = new ArrayList<>();

    public RouterLsa(byte[] lsa) throws UnknownHostException {
        super(lsa);

        routerId = InetAddress.getByAddress(Arrays.copyOfRange(lsa, 4, 8));

        links = bytesToUInt(lsa[22], lsa[23]);
        assert(links >= 0);

        final int base = 6 * 4;
        for (int i = 0 ; i < links ; i++) {
            final int offset = i * 12;
            Link link = new Link();
            link.linkId = InetAddress.getByAddress(Arrays.copyOfRange(lsa, base + offset, base + offset + 4));
            link.linkData = InetAddress.getByAddress(Arrays.copyOfRange(lsa, base + offset + 4, base + offset + 8));
            link.type = Byte.toUnsignedInt(lsa[base + offset + 8]);
            link.tos = Byte.toUnsignedInt(lsa[base + offset + 9]);
            link.metric = bytesToUInt(lsa[base + offset + 10], lsa[base + offset + 11]);
            assert(link.metric > 0);
            assert(link.metric <= 65535);
            adjacencies.add(link);
        }
    }

    public static class Link {
        public int type, tos, metric;
        public InetAddress linkId, linkData;

        private static final Map<Integer, String> networkLsaType = new HashMap<>();
        private static final Map<Integer, String> connectedObject = new HashMap<>();
        private static final Map<Integer, String> linkDataMeaning = new HashMap<>();
        static {
            networkLsaType.put(1, "Point-to-point connection to another router");
            networkLsaType.put(2, "Connection to a transit network");
            networkLsaType.put(3, "Connection to a stub network");
            networkLsaType.put(4, "Virtual link");

            connectedObject.put(1, "Neighboring router's Router ID");
            connectedObject.put(2, "IP address of Designated Router");
            connectedObject.put(3, "IP network/subnet number");
            connectedObject.put(4, "Neighboring router's Router ID");

            linkDataMeaning.put(1, "Router interface's IP address");
            linkDataMeaning.put(2, "Router interface's IP address");
            linkDataMeaning.put(3, "Network's IP address mask");
            linkDataMeaning.put(4, "Router interface's IP address");
        }

        @Override
        public String toString() {
            String s = "";
            s += "[Type " + type + "] [Metric = " + metric + "]. " + networkLsaType.get(type) + ". ";
            s += connectedObject.get(type) + " is " + linkId.getHostAddress() + ". ";
            s += linkDataMeaning.get(type) + " is " + linkData.getHostAddress();
            return s;
        }
    }

    @Override
    public String toString() {
        String s = String.format("[Router ID %s]", routerId.getHostAddress(), length,
                links);
        for (Link l : adjacencies) {
            s += "\n * Attached Router: " + l.toString();
        }
        return s;
    }

    public Map<InetAddress, Integer> getAdjacentRouters() {
        Map<InetAddress, Integer> adj = new HashMap<>();
        for (Link link : adjacencies) {
            if (link.type == 1) {
                adj.put(link.linkId, link.metric);
            }
        }
        return adj;
    }

    public Map<InetAddress, Integer> getAdjacentNetworks() {
        Map<InetAddress, Integer> net = new HashMap<>();
        for (Link link : adjacencies) {
            if (link.type == 2) {
                net.put(link.linkData, link.metric);
            }
        }
        return net;
    }

    public Map<String, Integer> getStubs() {
        Map<String, Integer> stubs = new HashMap<>();
        for (Link link : adjacencies) {
            if (link.type == 3) {
                String name = link.linkId.getHostAddress() + "/" + getPrefixLength(link.linkData);
                stubs.put(name, link.metric);
            }
        }
        return stubs;
    }
}
