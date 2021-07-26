package com.wjholden.ospf;

import org.neo4j.common.DependencyResolver;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.exceptions.KernelException;
import org.neo4j.graphalgo.BaseProc;
import org.neo4j.graphalgo.functions.*;
import org.neo4j.graphdb.*;
import org.neo4j.gds.paths.*;
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

import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;

public class OspfAnalyzer {

    private static final Label ROUTER = Label.label("ROUTER");
    private static final Label NETWORK = Label.label("NETWORK");
    private static final Label STUB = Label.label("STUB");
    private static final String[] CONSTRAINTS = {
            "CREATE CONSTRAINT IF NOT EXISTS ON (r:ROUTER) ASSERT r.name IS UNIQUE"
    };

    public static void main (String args[]) throws IOException, KernelException {
        // start the embedded Neo4j graph database. The database will write to an ephemeral temporary directory.
        GraphDatabaseService graphDb = startDb();

        System.out.println("An embedded Neo4j graph database is running at: neo4j://localhost:7687/neo4j");
        System.out.println("Connect to the database with Neo4j Desktop.");
        System.out.println("Username = (blank)");
        System.out.println("Password = (blank)");

        // register the GDS procedures and functions (gds.util.asNode, Betweenness, Closeness, etc)
        registerGds(graphDb);

        // Define one constraint using a raw Cypher query.
        // See https://neo4j.com/docs/java-reference/current/java-embedded/cypher-java/ for official documentation.
        try (Transaction tx = graphDb.beginTx()) {
            for (String constraint : CONSTRAINTS) {
                Result result = tx.execute(constraint);
                // CREATE CONSTRAINT should not return any output.
                while (result.hasNext()) {
                    Map<String, Object> row = result.next();
                    for (Map.Entry<String, Object> column : row.entrySet()) {
                        System.out.println(column);
                    }
                }
            }
            tx.commit();
        }

        // Get the OSPFv2 LSAs out of a router using SNMP.
        List<Lsa> lsdb = walkOspfLsdbMib();
        //lsdb.forEach(System.out::println);

        Map<String, RouterLsa> routerIds = new HashMap<>();
        Map<String, NetworkLsa> networks = new HashMap<>();
        Map<Lsa, Node> nodes = new HashMap<>();
        // Now we are ready to commit the LSAs to the database.
        try (Transaction tx = graphDb.beginTx()) {
            // First, create the nodes in the graph.
            for (Lsa lsa : lsdb) {
                Node node = tx.createNode();
                if (lsa instanceof RouterLsa) {
                    RouterLsa routerLsa = (RouterLsa) lsa;
                    node.addLabel(ROUTER);
                    node.setProperty("name", routerLsa.routerId.getHostAddress());
                    routerIds.put(routerLsa.routerId.getHostAddress(), routerLsa);
                } else if (lsa instanceof NetworkLsa) {
                    NetworkLsa networkLsa = (NetworkLsa) lsa;
                    node.addLabel(NETWORK);
                    final String prefix = networkLsa.prefix.getHostAddress() + "/" + networkLsa.prefixLength;
                    node.setProperty("name", prefix);
                    networks.put(prefix, networkLsa);
                } else {
                    throw new RuntimeException("Found an LSA of unexpected type: " + lsa);
                }
                nodes.put(lsa, node);
            }

            // Second, create the adjacencies between these nodes.
            for (Lsa lsa: lsdb) {
                if (lsa instanceof RouterLsa) {
                    RouterLsa routerLsa = (RouterLsa) lsa;

                    // Router-to-router (LSA Type 1/Type 1)
                    routerLsa.getAdjacentRouters().forEach((addr, metric) -> {
                        //System.out.println(routerLsa.routerId.getHostAddress() + " -> " + addr.getHostAddress());
                        Relationship e = nodes.get(lsa).createRelationshipTo(
                                nodes.get(routerIds.get(addr.getHostAddress())), RelTypes.LINKED
                        );
                        e.setProperty("cost", metric);
                    });

                    // Router-to-transit network (LSA Type 1/Type 2).
                    // We have to get back from addresses to subnets on this.
                    // The router LSA tells us our address and the DR address, but it does not tell us the prefix/mask.
                    // So, we have to go find the prefix/mask of the network LSA associated with this transit network.
                    routerLsa.getAdjacentNetworks().forEach((addr, metric) -> {
                        //System.out.println(((RouterLsa) lsa).routerId.getHostAddress() + " -> " + addr);

                        // We could get this perfect in a production environment, but in practice a linear search will
                        // basically always be good enough. OSPF doesn't scale up to millions of routers, anyways.
                        NetworkLsa peer = null;
                        for (NetworkLsa networkLsa : networks.values()) {
                            if (Arrays.equals(Lsa.getPrefixAddress(addr, networkLsa.mask).getAddress(), networkLsa.prefix.getAddress())) {
                                //System.out.println("Found our subnet, it's " + networkLsa.getPrefix());
                                peer = networkLsa;
                                break;
                            }
                        }

                        // If the network types are mismatched, then the router that thinks it is in a broadcast network
                        // will (incorrectly) assume that the other router (the P2P router) is the DR.
                        // The broadcast router will therefore assume that the P2P router is originating a type 2 (network)
                        // LSA. The above search will fail, and "peer" will be null.
                        if (peer != null) {
                            Relationship e = nodes.get(lsa).createRelationshipTo(
                                    nodes.get(peer), RelTypes.LINKED
                            );
                            e.setProperty("cost", metric);
                        } else {
                            // Searching for the network LSA failed, which could mean there is a P2P/broadcast mismatch.
                            //System.err.println("There may be a P2P/broadcast mismatch at " + ((RouterLsa) lsa).routerId.getHostAddress());
                        }
                    });

                    // Finally, the stub networks attached to routers.
                    // It is possible for two different routers to originate the same stub route.
                    // This will appear in the graph database as two different vertices with the same name.
                    routerLsa.getStubs().forEach((name, metric) -> {
                        Node stub = tx.createNode(STUB);
                        stub.setProperty("name", name);
                        Relationship e1 = nodes.get(lsa).createRelationshipTo(stub, RelTypes.LINKED);
                        e1.setProperty("cost", metric);
                    });
                }
            }

            for (Lsa lsa : lsdb) {
                if (lsa instanceof NetworkLsa) {
                    NetworkLsa networkLsa = (NetworkLsa) lsa;
                    //System.out.println(networkLsa.attachedRouters);
                    networkLsa.attachedRouters.forEach(addr -> {
                        //System.out.println(networkLsa.getPrefix() + " -> " + addr.getHostAddress());
                        Node r = nodes.get(routerIds.get(addr.getHostAddress()));
                        Relationship e1 = nodes.get(lsa).createRelationshipTo(r, RelTypes.LINKED);
                        e1.setProperty("cost", 0);
                    });
                }
            }

            tx.commit();
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

    private static List<Lsa> walkOspfLsdbMib() throws IOException {
        final Mib mib = MibFactory.getInstance().newMib();
        mib.load("SNMPv2-MIB");
        mib.load("OSPF-MIB");

        SecurityProtocols.getInstance().addAuthenticationProtocol(new AuthSHA());
        final SimpleSnmpV3Target target = new SimpleSnmpV3Target();
        target.setAddress("192.168.0.1");
        target.setSecurityName("admin");
        target.setAuthType(SnmpV3Target.AuthType.SHA);
        target.setPrivType(SnmpV3Target.PrivType.AES128);
        target.setAuthPassphrase(System.getProperty("tnm4j.agent.auth.password", "cisco123"));
        target.setPrivPassphrase(System.getProperty("tnm4j.agent.priv.password", "cisco123"));

        final List<Lsa> lsdb = new ArrayList<>();
        try (SnmpContext context = SnmpFactory.getInstance().newContext(target, mib)) {
            final SnmpWalker<VarbindCollection> walker = context.walk(1, "sysName", "ospfLsdbAdvertisement");

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

        final DatabaseManagementService managementService = new DatabaseManagementServiceBuilder(databaseDirectory).
                setConfigRaw(settings).
                build();
        // You will be able to connect to neo4j://localhost:7687/neo4j with blank username/blank password.
        // This is not mentioned at https://neo4j.com/docs/java-reference/current/java-embedded/bolt/#java-embedded-bolt.
        final GraphDatabaseService graphDb = managementService.database(DEFAULT_DATABASE_NAME);
        registerShutdownHook(managementService);

        return graphDb;
    }

    public static GraphDatabaseService registerGds(GraphDatabaseService graphDb) throws KernelException {
        // See https://github.com/neo4j/graph-data-science/issues/91,
        // "How to install Graph Data Science (GDS) library embedded in Java applications".
        // The instructions from Mats-SX are extremely helpful.
        GlobalProcedures proceduresRegistry = ((GraphDatabaseAPI) graphDb)
                .getDependencyResolver()
                .resolveDependency(GlobalProcedures.class, DependencyResolver.SelectionStrategy.SINGLE);

        // Get a list of all procedures (classes that extend BaseProc) and register them in our graph database.
        // This is stuff like gds.betweenness.stream and gds.pageRank.write.
        Set<Class<? extends BaseProc>> procedures = new Reflections("org.neo4j.graphalgo").getSubTypesOf(BaseProc.class);
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
        Class[] functionsToRegister = { AsNodeFunc.class, NodePropertyFunc.class, VersionFunc.class };

        for (Class f : functionsToRegister) {
            proceduresRegistry.registerFunction(f);
        }

        return graphDb;
    }
}
