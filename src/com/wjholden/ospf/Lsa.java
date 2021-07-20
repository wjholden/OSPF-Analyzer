package com.wjholden.ospf;

import java.net.UnknownHostException;

public abstract class Lsa {
    public final int length;

    public Lsa(byte[] lsa) {
        length = (lsa[18] << 8) | lsa[19];
        assert(length == lsa.length);
    }

    public static Lsa getInstance(byte[] lsa) {
        try {
            // The LSA type is in the third byte.
            // https://datatracker.ietf.org/doc/html/rfc2328#appendix-A.4.1
            switch (lsa[3]) {
                case 1:
                    return new RouterLsa(lsa);
                case 2:
                    return new NetworkLsa(lsa);
                default:
                    throw new UnsupportedOperationException("Type " + lsa[3] + " is not supported.");
            }
        } catch (UnknownHostException ex) {
            ex.printStackTrace();
            return null;
        }
    }
}
