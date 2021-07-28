package com.wjholden.ospf;

import org.neo4j.common.DependencyResolver;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.exceptions.KernelException;
import org.neo4j.graphalgo.BaseProc;
import org.neo4j.graphalgo.functions.AsNodeFunc;
import org.neo4j.graphalgo.functions.NodePropertyFunc;
import org.neo4j.graphalgo.functions.VersionFunc;
import org.neo4j.graphdb.*;
import org.neo4j.kernel.api.procedure.GlobalProcedures;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.reflections.Reflections;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.SecurityProtocols;
import org.soulwing.snmp.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;

public class OspfAnalyzer {

    private static final Label ROUTER = Label.label("ROUTER");
    private static final Label NETWORK = Label.label("NETWORK");
    private static final Label STUB = Label.label("STUB");
    private static final String[] CONSTRAINTS = {
            "CREATE CONSTRAINT IF NOT EXISTS ON (r:ROUTER) ASSERT r.name IS UNIQUE",
            "CREATE CONSTRAINT IF NOT EXISTS ON (n:NETWORK) ASSERT n.name IS UNIQUE",
            "CREATE CONSTRAINT IF NOT EXISTS ON (s:STUB) ASSERT s.name IS UNIQUE"
    };

    public static void main(String args[]) throws IOException, KernelException {
        // start the embedded Neo4j graph database. The database will write to an ephemeral temporary directory.
        GraphDatabaseService graphDb = startDb();

        System.out.println("An embedded Neo4j graph database is running at: neo4j://localhost:7687/neo4j");
        System.out.println("Connect to the database with Neo4j Desktop.");
        System.out.println("Username = (blank)");
        System.out.println("Password = (blank)");

        // register the GDS procedures and functions (gds.util.asNode, Betweenness, Closeness, etc)
        registerGds(graphDb);

        defineConstraints(graphDb);

        // Get the OSPFv2 LSAs out of a router- using SNMP.
        final List<Lsa> lsdb = walkOspfLsdbMib(args[0], args[1], args[2], args[3]);

        final List<RouterLsa> routers = lsdb.stream()
                .filter(l -> l instanceof RouterLsa)
                .map(l -> (RouterLsa) l)
                .collect(Collectors.toList());
        final List<NetworkLsa> networks = lsdb.stream().
                filter(l -> l instanceof NetworkLsa)
                .map(l -> (NetworkLsa) l)
                .collect(Collectors.toList());

        createRouters(graphDb, routers);
        createNetworks(graphDb, networks);
        connectRouters(graphDb, routers);
        connectNetworks(graphDb, networks);
        connectTransport(graphDb, routers, networks);
        connectStubs(graphDb, routers);

        System.out.print("Press any key to exit...");
        System.in.read();
        System.exit(0);
    }

    private static void defineConstraints(GraphDatabaseService graphDb) {
        // See https://neo4j.com/docs/java-reference/current/java-embedded/cypher-java/ for official documentation.
        try (Transaction tx = graphDb.beginTx()) {
            Arrays.asList(CONSTRAINTS).forEach(c -> tx.execute(c));
            tx.commit();
        }
    }

    private static void createRouters(GraphDatabaseService graphDb, Collection<RouterLsa> routers) {
        try (Transaction tx = graphDb.beginTx()) {
            routers.forEach(lsa -> {
                Node node = tx.createNode();
                node.addLabel(ROUTER);
                node.setProperty("name", lsa.routerId.getHostAddress());
            });
            tx.commit();
        }
    }

    private static void createNetworks(GraphDatabaseService graphDb,
                                       Collection<NetworkLsa> networks) {
        try (Transaction tx = graphDb.beginTx()) {
            networks.forEach(lsa -> {
                Node node = tx.createNode();
                node.addLabel(NETWORK);
                final String prefix = lsa.getPrefix();
                node.setProperty("name", prefix);
            });
            tx.commit();
        }
    }

    private static void connectRouters(GraphDatabaseService graphDb,
                                       Collection<RouterLsa> routers) {
        String queryString = "MATCH (src:ROUTER {name:$src})\n" +
                "MATCH (dst:ROUTER {name:$dst})\n" +
                "MERGE (src)-[:LINKED {cost:$cost}]->(dst)";
        try (Transaction tx = graphDb.beginTx()) {
            routers.forEach(src -> {
                src.getAdjacentRouters().forEach((dst, metric) -> {
                    Map<String, Object> parameters = new HashMap<>();
                    parameters.put("src", src.routerId.getHostAddress());
                    parameters.put("dst", dst.getHostAddress());
                    parameters.put("cost", metric);
                    tx.execute(queryString, parameters);
                });
            });
            tx.commit();
        }
    }

    private static void connectNetworks(GraphDatabaseService graphDb, Collection<NetworkLsa> networks) {
        String queryString = "MATCH (src:NETWORK {name:$src})\n" +
                "MATCH (dst:ROUTER {name:$dst})\n" +
                "MERGE (src)-[:LINKED {cost:$cost}]->(dst)";
        try (Transaction tx = graphDb.beginTx()) {
            networks.forEach(src -> {
                src.attachedRouters.forEach(dst -> {
                    Map<String, Object> parameters = new HashMap<>();
                    parameters.put("src", src.getPrefix());
                    parameters.put("dst", dst.getHostAddress());
                    parameters.put("cost", 0);
                    tx.execute(queryString, parameters);
                });
            });
            tx.commit();
        }
    }

    private static void connectTransport(GraphDatabaseService graphDb, Collection<RouterLsa> routers, Collection<NetworkLsa> networks) {
        String queryString = "MATCH (src:ROUTER {name:$src})\n" +
                "MATCH (dst:NETWORK {name:$dst})\n" +
                "MERGE (src)-[:LINKED {cost:$cost}]->(dst)";
        try (Transaction tx = graphDb.beginTx()) {
            routers.forEach(src -> {
                src.getAdjacentNetworks().forEach((dr, metric) -> {
                    // The router only knows the IP address of the designated router (DR).
                    // We have to search among our network LSA's for the correct network LSA that the DR creates.
                    // Only the DR generates the network LSA, and only the network LSA specifies the subnet mask.
                    // We'll do this with a linear search, knowing that a trie could do it faster for large networks.
                    // The linear search can fail under unusual circumstances where there is no type 2 LSA due to a
                    // network type mismatch or DR election problem.
                    String dst = null;
                    Iterator<NetworkLsa> iterator = networks.iterator();

                    while (iterator.hasNext() && dst == null) {
                        NetworkLsa networkLsa = iterator.next();
                        if (Arrays.equals(Lsa.getPrefixAddress(dr, networkLsa.mask).getAddress(), networkLsa.prefix.getAddress())) {
                            dst = networkLsa.getPrefix();
                        }
                    }

                    if (dst != null) {
                        Map<String, Object> parameters = new HashMap<>();
                        parameters.put("src", src.routerId.getHostAddress());
                        parameters.put("dst", dst);
                        parameters.put("cost", metric);
                        tx.execute(queryString, parameters);
                    } else {
                        System.err.println("Did not find a network LSA for " + dr.getHostAddress());
                    }
                });
            });


            networks.forEach(src -> {
                src.attachedRouters.forEach(dst -> {
                    Map<String, Object> parameters = new HashMap<>();
                    parameters.put("src", src.getPrefix());
                    parameters.put("dst", dst.getHostAddress());
                    parameters.put("cost", 0);
                    tx.execute(queryString, parameters);
                });
            });
            tx.commit();
        }
    }

    private static void connectStubs(GraphDatabaseService graphDb,
                                     Collection<RouterLsa> routers) {
        // The router definitely exists.
        // The stub may or may not exist.
        // So, one match and two merges.
        String queryString = "MATCH (src:ROUTER {name:$src})\n" +
                "MERGE (dst:STUB {name:$dst})\n" +
                "MERGE (src)-[:LINKED {cost:$cost}]->(dst)";
        try (Transaction tx = graphDb.beginTx()) {
            routers.forEach(src -> {
                src.getStubs().forEach((dst, metric) -> {
                    Map<String, Object> parameters = new HashMap<>();
                    parameters.put("src", src.routerId.getHostAddress());
                    parameters.put("dst", dst);
                    parameters.put("cost", metric);
                    tx.execute(queryString, parameters);
                });
            });
            tx.commit();
        }
    }

    private static void registerShutdownHook(final DatabaseManagementService managementService) {
        // Registers a shutdown hook for the Neo4j instance so that it
        // shuts down nicely when the VM exits (even if you "Ctrl-C" the
        // running application).
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                managementService.shutdown();
            }
        });
    }

    private enum RelTypes implements RelationshipType {
        LINKED
    }

    private static List<Lsa> walkOspfLsdbMib(String address, String username, String authPassword,
                                             String privPassword) throws IOException {
        final Mib mib = MibFactory.getInstance().newMib();
        mib.load("SNMPv2-MIB");
        mib.load("OSPF-MIB");

        SecurityProtocols.getInstance().addAuthenticationProtocol(new AuthSHA());
        final SimpleSnmpV3Target target = new SimpleSnmpV3Target();
        target.setAddress(address);
        target.setSecurityName(username);
        target.setAuthType(SnmpV3Target.AuthType.SHA);
        target.setPrivType(SnmpV3Target.PrivType.AES128);
        target.setAuthPassphrase(System.getProperty("tnm4j.agent.auth.password", authPassword));
        target.setPrivPassphrase(System.getProperty("tnm4j.agent.priv.password", privPassword));

        final List<Lsa> lsdb = new ArrayList<>();
        try (SnmpContext context = SnmpFactory.getInstance().newContext(target, mib)) {
            final SnmpWalker<VarbindCollection> walker = context.walk(1, "sysName",
                    "ospfLsdbAdvertisement");
            VarbindCollection row = walker.next().get();
            while (row != null) {
                final byte[] lsa = (byte[]) row.get("ospfLsdbAdvertisement").toObject();
                lsdb.add(Lsa.getInstance(lsa));
                row = walker.next().get();
            }
        }
        return lsdb;
    }

    public static GraphDatabaseService startDb() throws IOException {
        final Path databaseDirectory = Files.createTempDirectory(DEFAULT_DATABASE_NAME);

        Map<String, String> settings = new HashMap<>();
        settings.put("dbms.security.procedures.unrestricted", "jwt.security.*,gds.*,apoc.*");
        settings.put("dbms.security.procedures.whitelist", "gds.*");
        settings.put("dbms.connector.bolt.enabled", "true");
        settings.put("dbms.connector.http.enabled", "true");

        final DatabaseManagementService managementService = new DatabaseManagementServiceBuilder(
                databaseDirectory).
                setConfigRaw(settings).
                build();
        // You will be able to connect to neo4j://localhost:7687/neo4j with blank username/blank password.
        // This is not mentioned at https://neo4j.com/docs/java-reference/current/java-embedded/bolt/#java-embedded-bolt.
        final GraphDatabaseService graphDb = managementService.database(DEFAULT_DATABASE_NAME);
        registerShutdownHook(managementService);

        return graphDb;
    }

    public static GraphDatabaseService registerGds(GraphDatabaseService graphDb)
            throws KernelException {
        // See https://github.com/neo4j/graph-data-science/issues/91,
        // "How to install Graph Data Science (GDS) library embedded in Java applications".
        // The instructions from Mats-SX are extremely helpful.
        final GlobalProcedures proceduresRegistry = ((GraphDatabaseAPI) graphDb)
                .getDependencyResolver().resolveDependency(GlobalProcedures.class,
                        DependencyResolver.SelectionStrategy.SINGLE);

        // Get a list of all procedures (classes that extend BaseProc) and register them in our graph database.
        // This is stuff like gds.betweenness.stream and gds.pageRank.write.
        final Set<Class<? extends BaseProc>> procedures = new Reflections("org.neo4j.graphalgo").
                getSubTypesOf(BaseProc.class);
        procedures.addAll(new Reflections("org.neo4j.gds.embeddings").getSubTypesOf(BaseProc.class));
        procedures.addAll(new Reflections("org.neo4j.gds.paths").getSubTypesOf(BaseProc.class));

        // Cannot use forEach with checked exceptions.
        for (Class<? extends BaseProc> procedureClass : procedures) {
            proceduresRegistry.registerProcedure(procedureClass);
        }

        //System.out.println("Loaded the following GDS procedures: " + procedures);

        // Functions are handled differently from procedures. This is for the gds.util.asNode function.
        // This function will give the following error if not unrestricted:
        //
        // gds.util.asNode is unavailable because it is sandboxed and has dependencies outside of the sandbox.
        // Sandboxing is controlled by the dbms.security.procedures.unrestricted setting.
        // Only unrestrict procedures you can trust with access to database internals.
        final Class[] functionsToRegister = {
                AsNodeFunc.class,
                NodePropertyFunc.class,
                VersionFunc.class};

        for (Class f : functionsToRegister) {
            proceduresRegistry.registerFunction(f);
        }

        return graphDb;
    }
}
