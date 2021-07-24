# OSPF-Analyzer

## Queries

### In-Memory Graph

```
call gds.graph.create('myGraph', 'ROUTER', 'LINKED', {
    relationshipProperties: 'cost'
})
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
MATCH (u:ROUTER {name: "10.25.26.26"}),
    (v:ROUTER {name: "10.16.18.18"})
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
