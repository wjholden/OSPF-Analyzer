# OSPF-Analyzer

## Queries

### In-Memory Graph

```
call gds.graph.create('myGraph', 'ROUTER', 'LINKED', {
    relationshipProperties: 'cost'
})
```

### Yen

```
match (u:ROUTER {routerId: "10.25.26.26"}),
    (v:ROUTER {routerId: "10.16.18.18"})
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
YIELD index, nodeIds, costs
RETURN nodeIds, costs
```
