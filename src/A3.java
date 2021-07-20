import com.wjholden.ospf.Lsa;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.RelationshipType;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.SecurityProtocols;
import org.soulwing.snmp.*;
import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

public class A3 {

    public static void main (String args[]) throws IOException {
        final Path databaseDirectory = Files.createTempDirectory(DEFAULT_DATABASE_NAME);
        System.out.println(databaseDirectory);

        // create the database
        final DatabaseManagementService managementService = new DatabaseManagementServiceBuilder(databaseDirectory).build();
        final GraphDatabaseService graphDb = managementService.database(DEFAULT_DATABASE_NAME);
        registerShutdownHook(managementService);

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

    private static void registerShutdownHook( final DatabaseManagementService managementService )
    {
        // Registers a shutdown hook for the Neo4j instance so that it
        // shuts down nicely when the VM exits (even if you "Ctrl-C" the
        // running application).
        Runtime.getRuntime().addShutdownHook( new Thread()
        {
            @Override
            public void run()
            {
                managementService.shutdown();
            }
        } );
    }

    private enum RelTypes implements RelationshipType {
        LINKED
    }
}
