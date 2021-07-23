package com.wjholden.ospf;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NetworkLsa extends Lsa {
    public final InetAddress designatedRouter, prefix, mask;
    public List<InetAddress> attachedRouters = new ArrayList<>();
    public final int prefixLength;

    public NetworkLsa(byte[] lsa) throws UnknownHostException {
        super(lsa);
        designatedRouter = InetAddress.getByAddress(Arrays.copyOfRange(lsa, 4, 8));
        mask =  InetAddress.getByAddress(Arrays.copyOfRange(lsa, 20, 24));

        final int base = 6 * 4;
        for (int i = base ; i < lsa.length ; i += 4) {
            InetAddress r = InetAddress.getByAddress(Arrays.copyOfRange(lsa, i, i + 4));
            attachedRouters.add(r);
        }
        assert(attachedRouters.size() == (lsa.length - base) / 4);

        this.prefixLength = getPrefixLength(mask);
        this.prefix = getPrefixAddress(designatedRouter, mask);
    }

    @Override
    public String toString() {
        String s = String.format("[Network %s/%d; DR=%s]",
                prefix.getHostAddress(),
                prefixLength,
                designatedRouter.getHostAddress());
        for (InetAddress r : attachedRouters) {
            s += "\n * " + r.getHostAddress();
        }
        return s;
    }

    public String getPrefix() {
        return this.prefix.getHostAddress() + "/" + prefixLength;
    }
}
