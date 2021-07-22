import com.wjholden.ospf.Lsa;
import org.neo4j.common.DependencyResolver;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.SettingMigrators;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.connectors.HttpConnector;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.exceptions.KernelException;
import org.neo4j.graphalgo.BaseProc;
import org.neo4j.graphalgo.catalog.GraphCreateProc;
import org.neo4j.graphalgo.catalog.GraphListProc;
import org.neo4j.graphalgo.pagerank.PageRankStreamProc;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.kernel.api.procedure.GlobalProcedures;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.SecurityProtocols;
import org.soulwing.snmp.*;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;

public class A3 {

    public static void main (String args[]) throws IOException, KernelException {
        final Path databaseDirectory = Files.createTempDirectory(DEFAULT_DATABASE_NAME);
        System.out.println(databaseDirectory);

        // This is an ugly hack to get around frustrations I have getting neo4j
        // to obey the settings I have to specify in a neo4j.conf file.
        final Path cfg = Files.createTempFile("neo4j", "conf");
        try (BufferedWriter writer = Files.newBufferedWriter(cfg)) {
            writer.write("dbms.connector.bolt.enabled=true");
            writer.newLine();
            writer.write("dbms.security.procedures.unrestricted=jwt.security.*,gds.*,apoc.*");
            writer.newLine();
            writer.write("dbms.security.procedures.whitelist=gds.*");
            writer.newLine();
        }

        Map<String, String> settings = new HashMap<>();
        settings.put(GraphDatabaseSettings.procedure_whitelist.name(), "*");
        settings.put("dbms.security.procedures.unrestricted", "gds.*");
        settings.put("dbms.security.procedures.whitelist", "gds.*");

        // Create the database
        final DatabaseManagementService managementService = new DatabaseManagementServiceBuilder(databaseDirectory).
                setConfig(BoltConnector.enabled, true).
                setConfigRaw(settings).
                build();
        // You will be able to connect to neo4j://localhost:7687/neo4j with blank username/blank password.
        // This is not mentioned at https://neo4j.com/docs/java-reference/current/java-embedded/bolt/#java-embedded-bolt.
        final GraphDatabaseService graphDb = managementService.database(DEFAULT_DATABASE_NAME);
        registerShutdownHook(managementService);

        GlobalProcedures proceduresRegistry = ((GraphDatabaseAPI) graphDb)
                .getDependencyResolver()
                .resolveDependency(GlobalProcedures.class, DependencyResolver.SelectionStrategy.SINGLE);
        List<Class<? extends BaseProc>> procsToRegister = List.of(
                GraphListProc.class,
                GraphCreateProc.class,
                PageRankStreamProc.class
        );
        for (Class<?> procedureClass : procsToRegister) {
            proceduresRegistry.registerProcedure(procedureClass);

        }

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
            while (row != null) {
                byte[] lsa = (byte[]) row.get("ospfLsdbAdvertisement").toObject();
                    //System.out.println(Lsa.getInstance(lsa));
                row = walker.next().get();
                i++;
            }
            System.out.println("Count: " + i);
        } catch (TimeoutException ex) {
            System.err.println("SNMP connection timed out");
        }

        System.out.print("Press any key to exit...");
        System.in.read();
        System.exit(0);
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
