import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.SecurityProtocols;
import org.soulwing.snmp.*;

import java.io.IOException;
import java.util.List;

public class A3 {
    public static void main (String args[]) throws IOException {
        Mib mib = MibFactory.getInstance().newMib();
        mib.load("SNMPv2-MIB");
        mib.load("IF-MIB");
        mib.load("OSPF-MIB");

        SecurityProtocols.getInstance().addAuthenticationProtocol(new AuthSHA());
        SimpleSnmpV3Target target = new SimpleSnmpV3Target();
        target.setAddress("192.168.0.1");
        target.setSecurityName("admin");
        target.setAuthType(SnmpV3Target.AuthType.SHA);
        target.setPrivType(SnmpV3Target.PrivType.AES128);
        target.setAuthPassphrase(System.getProperty("tnm4j.agent.auth.password", "cisco123"));
        target.setPrivPassphrase(System.getProperty("tnm4j.agent.priv.password", "cisco123"));
        //target.setAuthPassphrase("cisco123");
        //target.setPrivPassphrase("cisco123");

        SnmpContext context = SnmpFactory.getInstance().newContext(target, mib);
        try {
            //VarbindCollection result = context.getNext("sysUpTime").get();
            //System.out.println(result.get("sysUpTime"));

            //List<VarbindCollection> rows = context.getBulk(1, 50,
            //        "sysUpTime", "ifName", "ifInOctets", "ifOutOctets", "ospfLsdbAdvertisement").get();
            //for (VarbindCollection row : rows) {
            //    System.out.println(row);
            //}

            int i = 0;
            final String[] columns = { "ospfLsdbAdvertisement" };
            VarbindCollection row = context.getNext(columns).get();
            while (row.get("ospfLsdbAdvertisement") != null) {
                System.out.println(row.get("ospfLsdbAdvertisement"));
                row = context.getNext(row.nextIdentifiers("ospfLsdbAdvertisement")).get();
                i++;
            }
            System.out.println("LSDB size is " + i);
        } finally {
            context.close();
        }
    }
}
