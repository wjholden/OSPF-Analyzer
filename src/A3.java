import com.wjholden.ospf.Lsa;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.SecurityProtocols;
import org.soulwing.snmp.*;

import java.io.IOException;

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

        try (SnmpContext context = SnmpFactory.getInstance().newContext(target, mib)) {
            int i = 0;
            SnmpWalker<VarbindCollection> walker = context.walk(1, "sysName", "ospfLsdbAdvertisement");
            //SnmpWalker<VarbindCollection> walker = context.walk(1, "ospfLsdbAdvertisement");

            VarbindCollection row = walker.next().get();
            System.out.println(row.getClass().getName());
            while (row != null) {
                byte[] lsa = (byte[]) row.get("ospfLsdbAdvertisement").toObject();
                //System.out.println(Arrays.toString(lsa));
                System.out.println(Lsa.getInstance(lsa));
                row = walker.next().get();
                i++;
            }
            System.out.println("Count: " + i);
        }

    }
}
