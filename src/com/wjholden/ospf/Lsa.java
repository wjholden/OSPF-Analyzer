package com.wjholden.ospf;

import java.net.UnknownHostException;

public abstract class Lsa {
    public static Lsa getInstance(byte[] lsa) {
        try {
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
