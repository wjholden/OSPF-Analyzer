import com.wjholden.ospf.Lsa;
import org.neo4j.common.DependencyResolver;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.exceptions.KernelException;
import org.neo4j.graphalgo.betweenness.BetweennessCentralityStreamProc;
import org.neo4j.graphalgo.centrality.ClosenessCentralityProc;
import org.neo4j.graphalgo.functions.AsNodeFunc;
import org.neo4j.graphalgo.pagerank.PageRankStreamProc;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.kernel.api.procedure.GlobalProcedures;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.SecurityProtocols;
import org.soulwing.snmp.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;

public class A3 {

    public static void main (String args[]) throws IOException, KernelException {
        final Path databaseDirectory = Files.createTempDirectory(DEFAULT_DATABASE_NAME);
        System.out.println(databaseDirectory);

        Map<String, String> settings = new HashMap<>();
        settings.put("dbms.security.procedures.unrestricted", "jwt.security.*,gds.*,apoc.*");
        settings.put("dbms.security.procedures.whitelist", "gds.*");
        settings.put("dbms.connector.bolt.enabled", "true");
        settings.put("dbms.connector.http.enabled", "true");

        // Create the database
        final DatabaseManagementService managementService = new DatabaseManagementServiceBuilder(databaseDirectory).
                setConfigRaw(settings).
                build();
        // You will be able to connect to neo4j://localhost:7687/neo4j with blank username/blank password.
        // This is not mentioned at https://neo4j.com/docs/java-reference/current/java-embedded/bolt/#java-embedded-bolt.
        final GraphDatabaseService graphDb = managementService.database(DEFAULT_DATABASE_NAME);
        registerShutdownHook(managementService);

        System.out.println("An embedded Neo4j graph database is running at: neo4j://localhost:7687/neo4j");
        System.out.println("Connect to the database with Neo4j Desktop.");
        System.out.println("Username = (blank)");
        System.out.println("Password = (blank)");

        // See https://github.com/neo4j/graph-data-science/issues/91,
        // "How to install Graph Data Science (GDS) library embedded in Java applications".
        // The instructions from Mats-SX are extremely helpful.
        GlobalProcedures proceduresRegistry = ((GraphDatabaseAPI) graphDb)
                .getDependencyResolver()
                .resolveDependency(GlobalProcedures.class, DependencyResolver.SelectionStrategy.SINGLE);
        Class[] procsToRegister = {
                PageRankStreamProc.class,
                ClosenessCentralityProc.class,
                BetweennessCentralityStreamProc.class
        };

        // Cannot use forEach with checked exceptions.
        for (Class<?> procedureClass : procsToRegister) {
            proceduresRegistry.registerProcedure(procedureClass);
        }

        // Functions are handled differently from procedures. This is for the gds.util.asNode function.
        // This function will give the following error if not unrestricted:
        //
        // gds.util.asNode is unavailable because it is sandboxed and has dependencies outside of the sandbox.
        // Sandboxing is controlled by the dbms.security.procedures.unrestricted setting.
        // Only unrestrict procedures you can trust with access to database internals.
        Class[] functionsToRegister = { AsNodeFunc.class };
        for (Class f : functionsToRegister) {
            proceduresRegistry.registerFunction(f);
        }

        List<Lsa> lsdb = snmpwalk();
        lsdb.forEach(System.out::println);

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

    private static List<Lsa> snmpwalk() throws IOException {
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


        List<Lsa> lsdb = new ArrayList<>();
        try (SnmpContext context = SnmpFactory.getInstance().newContext(target, mib)) {
            SnmpWalker<VarbindCollection> walker = context.walk(1, "sysName", "ospfLsdbAdvertisement");

            VarbindCollection row = walker.next().get();
            while (row != null) {
                byte[] lsa = (byte[]) row.get("ospfLsdbAdvertisement").toObject();
                lsdb.add(Lsa.getInstance(lsa));
                //System.out.println(Lsa.getInstance(lsa));
                row = walker.next().get();
            }
        }
        return lsdb;
    }
}
