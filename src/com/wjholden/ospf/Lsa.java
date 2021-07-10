package com.wjholden.ospf;

import java.util.List;

public abstract class Lsa {
    public static Lsa getInstance(List<Short> octetString) {
        final int type = octetString.get(3);
        switch (octetString.get(3)) {
            case 1:
                return new RouterLsa(octetString);
            case 2:
                return new NetworkLsa(octetString);
            default:
                throw new UnsupportedOperationException("Type " + type + " is not supported.");
        }
    }

    private static int getType(List<Short> octetString) {
        return octetString.get(3);
    }

}
