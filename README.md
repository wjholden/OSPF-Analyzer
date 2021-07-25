# OSPF-Analyzer

## Comments

- Cannot use `gds.list()`. Instead, call `CALL dbms.procedures()` or `CALL dbms.functions()`.
- Pathfinding algorithms are in `org.neo4j.gds.paths`.

## Queries

### Get only routers and transit networks

```
MATCH (n)
WHERE n:ROUTER OR n:NETWORK
return n
```

### Get unidirectional edges

This is not a common problem in OSPF, but it can happen.
A possible cause for this is a mismatched interface type, such as one interface configured as `ip ospf network point-to-point` and the other `ip ospf network broadcast`.

```
MATCH (u:ROUTER)-[]->(v:ROUTER)
WHERE NOT (v)-[]->(u)
RETURN u, v
```

### In-Memory Graph

```
call gds.graph.create('myGraph', 'ROUTER', 'LINKED', {
    relationshipProperties: 'cost'
})
```

### Centrality

```
CALL gds.alpha.closeness.stream('myGraph')
YIELD nodeId, centrality
RETURN gds.util.asNode(nodeId).name as name, centrality
ORDER BY centrality DESC
```

### PageRank

I can only get `relationshipWeightProperty` to work for weighted PageRank by first creating the in-memory graph.

```
CALL gds.pageRank.stream('myGraph', {
    maxIterations: 20,
    dampingFactor: 0.85,
    relationshipWeightProperty: 'cost'
})
YIELD nodeId, score
RETURN gds.util.asNode(nodeId).name as routerId, score
ORDER BY score DESC
```

### In-Memory Undirected Graph

```
call gds.graph.create(
    'myUndirectedGraph',
    ['ROUTER', 'NETWORK'],
    {
        LINKED: { orientation: 'UNDIRECTED' }
    },
    {
        relationshipProperties: 'cost'
    }
)
```

### Triangle Count

```
CALL gds.triangleCount.stream('myUndirectedGraph')
YIELD nodeId, triangleCount
RETURN gds.util.asNode(nodeId).name as name, triangleCount
ORDER BY triangleCount DESC
```

### Betweenness

```
CALL gds.betweenness.stream({
    nodeProjection: "ROUTER",
    relationshipProjection: "LINKED"
})
YIELD nodeId, score
RETURN gds.util.asNode(nodeId).name as Name, score as `Betweenness Score`
ORDER BY score DESC
```

### Yen

```
MATCH (u:ROUTER {name: "192.0.2.45"}),
    (v:ROUTER {name: "192.0.2.36"})
CALL gds.shortestPath.yens.stream({
    sourceNode: u,
    targetNode: v,
    nodeProjection: "*",
    relationshipProjection: {
        all: {
            type: "*",
            properties: "cost"
        }
    },
    relationshipWeightProperty: "cost",
    k: 5
})
YIELD index, nodeIds, totalCost
RETURN [nodeId IN nodeIds | gds.util.asNode(nodeId).name] AS names, totalCost
```

### Mismatched point-to-point costs

```
MATCH (u:ROUTER)-[e1:LINKED]->(v:ROUTER)
MATCH (v)-[e2:LINKED]->(u)
WHERE e1.cost <> e2.cost AND id(u)<id(v)
RETURN u.name, v.name, e1.cost, e2.cost
```

### Mismatched transit network costs

```
MATCH (n:NETWORK)<-[e:LINKED]-(:ROUTER)
WITH n.name as Network, collect(e) as Connections, stDev(e.cost) as s
WHERE s <> 0
return Network, Connections
```

### In-Memory Subgraph

Create a subgraph `g` that contains all nodes *except* for `192.0.2.23`.

```
call gds.graph.create.cypher(
    'g',
    "MATCH (r:ROUTER) WHERE r.name <> '192.0.2.23' RETURN id(r) AS id",
    "MATCH (u:ROUTER)-->(v:ROUTER) WHERE u.name <> '192.0.2.23' AND v.name <> '192.0.2.23' RETURN id(u) AS source, id(v) AS target"
)
```

### Parameterized In-Memory Subgraph

```
WITH "'192.0.2.23'" AS r
CALL gds.graph.create.cypher(
    'g',
    "MATCH (r) WHERE r.name <> " + r + " AND (r:ROUTER OR r:NETWORK) RETURN id(r) AS id",
    "MATCH (u)-->(v) WHERE (u.name <> " + r + " AND v.name <> " + r + ") AND (u:ROUTER OR u:NETWORK) AND (v:ROUTER OR v:NETWORK) RETURN id(u) as source, id(v) as target"
)
YIELD graphName,
  nodeCount,
  relationshipCount,
  createMillis
RETURN graphName,
  nodeCount,
  relationshipCount,
  createMillis
```

### Weakly-Connected Components

```
call gds.wcc.stream('g')
yield nodeId, componentId
```