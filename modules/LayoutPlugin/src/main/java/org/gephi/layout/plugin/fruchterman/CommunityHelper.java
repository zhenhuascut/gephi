package org.gephi.layout.plugin.fruchterman;

import org.gephi.graph.api.Edge;
import org.gephi.graph.api.Node;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * @author <a href="mailto:wu812730157@gmail.com">Junxian Wu</a>
 * @date 2020/9/4
 **/
public class CommunityHelper {
    public static Map<Object, Community> calculateCommunity(Node[] nodes, String communityKey) {
        Map<Object, Community> map = new HashMap<>();

        for (Node n : nodes) {
            Object communityId;
            try {
                communityId = n.getAttribute(communityKey);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Please Run Modularity First");
            }
            Community cc = map.containsKey(communityId) ? map.get(communityId) : new Community(communityId);
            cc.nodes.add(n);
            map.put(communityId, cc);
        }

        // Prevent unexpected modification
        return Collections.unmodifiableMap(map);
    }

    public static Map<Node, Map<Community, Float>> calculateNodeNeighborMap(Edge[] edges, String communityNameKey,
                                                                      Map<Object, Community> communityMap) {
        final Map<Node, Map<Community, Float>> map = new HashMap<>();

        for (final Edge edge : edges) {
            Node source = edge.getSource();
            Node target = edge.getTarget();
            Community sourceCommunity = communityMap.get(source.getAttribute(communityNameKey));
            Community targetCommunity = communityMap.get(target.getAttribute(communityNameKey));
            if (sourceCommunity == targetCommunity) {
                // Same community. Add edge to community's edges.
                sourceCommunity.edges.add(edge);
            } else {
                // Different community, sum the weight of the edges.
                addNodeNeighborCommunity(map, edge.getWeight(), source, targetCommunity);
                addNodeNeighborCommunity(map, edge.getWeight(), target, sourceCommunity);
            }
        }

        return Collections.unmodifiableMap(map);
    }

    private static void addNodeNeighborCommunity(Map<Node, Map<Community, Float>> nodeNeighborCommunity, double weight,
                                          Node node, Community neighborCommunity) {
        Map<Community, Float> sourceNeighbor;
        if (!nodeNeighborCommunity.containsKey(node)) {
            nodeNeighborCommunity.put(node, new HashMap<Community, Float>());
        }
        sourceNeighbor = nodeNeighborCommunity.get(node);

        double sum = sourceNeighbor.containsKey(neighborCommunity) ? sourceNeighbor.get(neighborCommunity) : 0;
        sourceNeighbor.put(neighborCommunity, (float) (weight + sum));
    }

    /**
     * Update Community Centroid and Radius concurrently.
     */
    public static void updateCentroid(Map<Object, Community> communityMap, ExecutorService pool, final double area, final int numNodes) {
        Set<Future<?>> threads = new HashSet<>(communityMap.size());
        for (final Community c : communityMap.values()) {
            Future<?> th = pool.submit(new Runnable() {
                @Override
                public void run() {
                    float x = 0;
                    float y = 0;
                    for (Node node : c.nodes) {
                        x += node.x();
                        y += node.y();
                    }
                    c.x = x / c.nodes.size();
                    c.y = y / c.nodes.size();
                    c.radius = (float) Math.sqrt(area / numNodes * c.nodes.size()) / 2;
                }
            });
            threads.add(th);
        }

        for (Future<?> thread : threads) {
            try {
                thread.get();
            } catch (Exception e) {
                throw new RuntimeException("Unable to update Centroid of community", e);
            }
        }
    }

}
