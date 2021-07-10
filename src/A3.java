import org.apache.shiro.codec.Hex;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.SecurityProtocols;
import org.soulwing.snmp.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class A3 {

    public static List<Integer> octetString(String s) {
        // Note: Byte does not work in Java because Byte is unsigned and cannot accept a value above +127.
        return Arrays.stream(s.split(":")).map(i -> Integer.valueOf(i, 16)).collect(Collectors.toList());
    }

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

        try (SnmpContext context = SnmpFactory.getInstance().newContext(target, mib)) {
            //VarbindCollection result = context.getNext("sysUpTime").get();
            //System.out.println(result.get("sysUpTime"));

            //List<VarbindCollection> rows = context.getBulk(1, 50,
            //        "sysUpTime", "ifName", "ifInOctets", "ifOutOctets", "ospfLsdbAdvertisement").get();
            //for (VarbindCollection row : rows) {
            //    System.out.println(row);
            //}

            //int i = 0;
            //final String[] columns = { "ospfLsdbAdvertisement" };
            //VarbindCollection row = context.getNext(columns).get();
            //while (row.get("ospfLsdbAdvertisement") != null) {
            //    System.out.println(row.get("ospfLsdbAdvertisement"));
            //    row = context.getNext(row.nextIdentifiers("ospfLsdbAdvertisement")).get();
            //    i++;
            //}
            //System.out.println("LSDB size is " + i);

            int i = 0;
            SnmpWalker<VarbindCollection> walker = context.walk(1, "sysName", "ospfLsdbAdvertisement");
            //SnmpWalker<VarbindCollection> walker = context.walk(1, "ospfLsdbAdvertisement");
            VarbindCollection row = walker.next().get();
            System.out.println(row.getClass().getName());
            while (row != null) {
                System.out.println(Arrays.toString((byte[]) row.get("ospfLsdbAdvertisement").toObject()));
                String octets = row.get("ospfLsdbAdvertisement").asString();
                List<Integer> lsa = octetString(octets);
                System.out.println(lsa);
                //System.out.println(row.get("sysName") + " " + row.get("ospfLsdbAdvertisement"));
                row = walker.next().get();
                i++;
            }
            System.out.println("Count: " + i);
        }

    }
}
