/*
 Copyright 2008-2010 Gephi
 Authors : Mathieu Jacomy
 Website : http://www.gephi.org

 This file is part of Gephi.

 DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.

 Copyright 2011 Gephi Consortium. All rights reserved.

 The contents of this file are subject to the terms of either the GNU
 General Public License Version 3 only ("GPL") or the Common
 Development and Distribution License("CDDL") (collectively, the
 "License"). You may not use this file except in compliance with the
 License. You can obtain a copy of the License at
 http://gephi.org/about/legal/license-notice/
 or /cddl-1.0.txt and /gpl-3.0.txt. See the License for the
 specific language governing permissions and limitations under the
 License.  When distributing the software, include this License Header
 Notice in each file and include the License files at
 /cddl-1.0.txt and /gpl-3.0.txt. If applicable, add the following below the
 License Header, with the fields enclosed by brackets [] replaced by
 your own identifying information:
 "Portions Copyrighted [year] [name of copyright owner]"

 If you wish your version of this file to be governed by only the CDDL
 or only the GPL Version 3, indicate your decision by adding
 "[Contributor] elects to include this software in this distribution
 under the [CDDL or GPL Version 3] license." If you do not indicate a
 single choice of license, a recipient has the option to distribute
 your version of this file under either the CDDL, the GPL Version 3 or
 to extend the choice of license to its licensees as provided above.
 However, if you add GPL Version 3 code and therefore, elected the GPL
 Version 3 license, then the option applies only if the new code is
 made subject to such option by the copyright holder.

 Contributor(s):

 Portions Copyrighted 2011 Gephi Consortium.
 */
package org.gephi.layout.plugin.fruchterman;

import org.gephi.graph.api.*;
import org.gephi.layout.plugin.AbstractLayout;
import org.gephi.layout.plugin.ForceVectorNodeLayoutData;
import org.gephi.layout.spi.Layout;
import org.gephi.layout.spi.LayoutBuilder;
import org.gephi.layout.spi.LayoutProperty;
import org.openide.util.Exceptions;
import org.openide.util.NbBundle;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * @author Mathieu Jacomy
 */
public class FruchtermanReingold extends AbstractLayout implements Layout {

    private static final float SPEED_DIVISOR = 800;
    private static final float AREA_MULTIPLICATOR = 10000;
    private static final String MODULARITY_CLASS = "modularity_class";
    private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors() - 1;

    //Graph
    protected Graph graph;
    //Properties
    private float area;
    private double gravity;
    private double speed;
    private float alpha;
    private boolean useCommunityDetection;
    private boolean approximate;
    private boolean communityAttraction;

    //Temporary Storage
    private ExecutorService pool;
    private Map<Integer, Community> communityMap;
    private Map<Node, Map<Community, Integer>> nodeNeighborCommunity;
    private Node[] nodes;
    private Edge[] edges;

    public FruchtermanReingold(LayoutBuilder layoutBuilder) {
        super(layoutBuilder);
    }

    @Override
    public void resetPropertiesValues() {
        speed = 1;
        area = 10000;
        gravity = 10;
        alpha = 0.1f;
        useCommunityDetection = false;
        communityAttraction = false;
        approximate = false;
    }

    @Override
    public void initAlgo() {
        pool = Executors.newFixedThreadPool(THREAD_COUNT);
        this.graph = graphModel.getGraphVisible();
        graph.readLock();
        try {
            nodes = graph.getNodes().toArray();
            edges = graph.getEdges().toArray();
            if (useCommunityDetection && approximate) {
                this.communityMap = initCommunity(nodes);
                this.nodeNeighborCommunity = initNodeNeighborCommunity(edges, communityMap);
            }
        } finally {
            graph.readUnlock();
        }

    }

    @Override
    public void goAlgo() {
        graph.readLock();
        try {
            // Update Community Centroid
            if (useCommunityDetection && approximate) {
                updateCentroid(communityMap);
            }

            final float maxDisplace = (float) (Math.sqrt(AREA_MULTIPLICATOR * area) / 10f);                    // Déplacement limite : on peut le calibrer...
            final float k = (float) Math.sqrt((AREA_MULTIPLICATOR * area) / (1f + nodes.length));        // La variable k, l'idée principale du layout.

            // Repulsive force
            HashSet<Future<?>> threads = new HashSet<>(nodes.length);
            if (useCommunityDetection && approximate) {
                for (final Node n : nodes) {
                    threads.add(pool.submit(new Runnable() {
                        @Override
                        public void run() {
                            ForceVectorNodeLayoutData layoutData = new ForceVectorNodeLayoutData();

                            int communityId = (Integer) n.getAttribute(MODULARITY_CLASS);
                            Community nodeCommunity = communityMap.get(communityId);

                            // Repulsive force by other community
                            for (Community neighbor : communityMap.values()) {
                                if (nodeCommunity != neighbor) {
                                    updateRepulsiveDisplacement(k, neighbor.nodes.size(), n.x() - neighbor.x, n.y() - neighbor.y, layoutData);
                                }
                            }

                            // Repulsive force by nodes inside same community
                            for (Node node : nodeCommunity.nodes) {
                                if (node != n) {
                                    updateRepulsiveDisplacement(k, 1, n.x() - node.x(), n.y() - node.y(), layoutData);
                                }
                            }
                            n.setLayoutData(layoutData);
                        }
                    }));
                }
            } else {
                for (final Node n : nodes) {
                    threads.add(pool.submit(new Runnable() {
                        @Override
                        public void run() {
                            ForceVectorNodeLayoutData layoutData = new ForceVectorNodeLayoutData();
                            for (Node N2 : nodes) {    // On fait toutes les paires de noeuds
                                if (n != N2) {
                                    updateRepulsiveDisplacement(k, 1, n.x() - N2.x(), n.y() - N2.y(), layoutData);
                                }
                            }
                            n.setLayoutData(layoutData);
                        }
                    }));
                }
            }

            for (Future<?> thread : threads) {
                try {
                    thread.get();
                } catch (Exception e) {
                    throw new RuntimeException("Unable to calculate repulsive force", e);
                }
            }

            // Attractive force
            if (useCommunityDetection && approximate) {
                // Use community to simplify calculation.
                threads = new HashSet<>(nodeNeighborCommunity.size());
                for (final Map.Entry<Node, Map<Community, Integer>> entry : nodeNeighborCommunity.entrySet()) {
                    threads.add(pool.submit(new Runnable() {
                        @Override
                        public void run() {
                            Node n = entry.getKey();
                            Map<Community, Integer> neighbors = entry.getValue();
                            Community community = communityMap.get((Integer) n.getAttribute(MODULARITY_CLASS));
                            ForceVectorNodeLayoutData layoutData = n.getLayoutData();
                            // Attractive force by other communities.
                            for (Map.Entry<Community, Integer> entry : neighbors.entrySet()) {
                                // number of edges connected to the community
                                int weight = entry.getValue();
                                Community neighbor = entry.getKey();
                                if (community == neighbor) {
                                    continue;
                                }

                                float xDist = n.x() - neighbor.x;
                                float yDist = n.y() - neighbor.y;
                                float dist = (float) Math.sqrt(xDist * xDist + yDist * yDist);
                                float attractiveF = weight * alpha * dist * dist / k;

                                if (dist > 0) {
                                    layoutData.dx -= xDist / dist * attractiveF;
                                    layoutData.dy -= yDist / dist * attractiveF;
                                }
                            }

                            if (communityAttraction) {
                                // Centroid of community drag nodes together.
                                float xDist = n.x() - community.x;
                                float yDist = n.y() - community.y;
                                float dist = (float) Math.sqrt(xDist * xDist + yDist * yDist);
                                if (dist > community.radius) {
                                    // Only those outside the circle of radius community.radius is dragged by this force.
                                    float weight = community.nodes.size() * (dist / community.radius - 1);
                                    float attractiveF = weight * dist * dist / k;
                                    if (dist > 0) {
                                        float dx = xDist / dist * attractiveF;
                                        float dy = yDist / dist * attractiveF;
                                        layoutData.dx -= dx;
                                        layoutData.dy -= dy;
                                    }
                                }
                            }
                        }
                    }));
                }
                for (Future<?> thread : threads) {
                    try {
                        thread.get();
                    } catch (Exception e) {
                        throw new RuntimeException("Unable to calculate attractive force", e);
                    }
                }

                // Attractive force between nodes in same community
                threads = new HashSet<>(edges.length);
                Collection<Community> communities = communityMap.values();
                for (Community community : communities) {
                    for (final Edge edge : community.edges) {
                        threads.add(pool.submit(new Runnable() {
                            @Override
                            public void run() {
                                Node source = edge.getSource();
                                Node target = edge.getTarget();
                                updateAttractiveDisplacement(k, 1.0f, source, target);
                            }
                        }));
                    }
                }

                for (Future<?> thread : threads) {
                    try {
                        thread.get();
                    } catch (Exception e) {
                        throw new RuntimeException("Unable to calculate attractive force", e);
                    }
                }

            } else {
                threads = new HashSet<>(edges.length);
                for (final Edge E : edges) {
                    threads.add(pool.submit(new Runnable() {
                        @Override
                        public void run() {
                            // Idem, pour tous les noeuds on applique la force d'attraction
                            Node Nf = E.getSource();
                            Node Nt = E.getTarget();

                            // Weight is 1 if same community/label, alpha if different community/label.
                            float weight = Nf.getLabel().equals(Nt.getLabel()) ? 1.0f : alpha;
                            try {
                                if (useCommunityDetection) {
                                    weight = Nf.getAttribute(MODULARITY_CLASS).equals(Nt.getAttribute(MODULARITY_CLASS)) ? 1.0f : alpha;
                                }
                            } catch (IllegalArgumentException e) {
                                // No modularity class
                            }
                            updateAttractiveDisplacement(k, weight, Nf, Nt);
                        }
                    }));
                }
                for (Future<?> thread : threads) {
                    try {
                        thread.get();
                    } catch (Exception e) {
                        throw new RuntimeException("Unable to calculate attractive force", e);
                    }
                }
            }

            threads = new HashSet<>(nodes.length);
            for (final Node n : nodes) {
                threads.add(pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        ForceVectorNodeLayoutData layoutData = n.getLayoutData();

                        // gravity
                        float d = (float) Math.sqrt(n.x() * n.x() + n.y() * n.y());
                        float gf = 0.01f * k * (float) gravity * d;
                        layoutData.dx -= gf * n.x() / d;
                        layoutData.dy -= gf * n.y() / d;

                        // speed
                        layoutData.dx *= speed / SPEED_DIVISOR;
                        layoutData.dy *= speed / SPEED_DIVISOR;

                        // Maintenant on applique le déplacement calculé sur les noeuds.
                        // nb : le déplacement à chaque passe "instantanné" correspond à la force : c'est une sorte d'accélération.
                        float xDist = layoutData.dx;
                        float yDist = layoutData.dy;
                        float dist = (float) Math.sqrt(layoutData.dx * layoutData.dx + layoutData.dy * layoutData.dy);
                        if (dist > 0 && !n.isFixed()) {
                            float limitedDist = Math.min(maxDisplace * ((float) speed / SPEED_DIVISOR), dist);
                            n.setX(n.x() + xDist / dist * limitedDist);
                            n.setY(n.y() + yDist / dist * limitedDist);
                        }
                    }

                }));
            }

            for (Future<?> thread : threads) {
                try {
                    thread.get();
                } catch (Exception e) {
                    throw new RuntimeException("Unable to calculate position", e);
                }
            }

        } finally {
            graph.readUnlockAll();
        }
    }

    @Override
    public void endAlgo() {
        graph.readLock();
        try {
            for (Node n : graph.getNodes()) {
                n.setLayoutData(null);
            }
            pool.shutdown();
        } finally {
            graph.readUnlockAll();
        }
    }

    @Override
    public boolean canAlgo() {
        return true;
    }

    @Override
    public LayoutProperty[] getProperties() {
        List<LayoutProperty> properties = new ArrayList<>();
        final String FRUCHTERMAN_REINGOLD = "Fruchterman Reingold";
        final String COMMUNITY = "Community";
        final String LARGE_GRAPH_OPTIMIZATION = "Large graph optimization";

        try {
            properties.add(LayoutProperty.createProperty(
                    this, Float.class,
                    NbBundle.getMessage(FruchtermanReingold.class, "fruchtermanReingold.area.name"),
                    FRUCHTERMAN_REINGOLD,
                    "fruchtermanReingold.area.name",
                    NbBundle.getMessage(FruchtermanReingold.class, "fruchtermanReingold.area.desc"),
                    "getArea", "setArea"));
            properties.add(LayoutProperty.createProperty(
                    this, Double.class,
                    NbBundle.getMessage(FruchtermanReingold.class, "fruchtermanReingold.gravity.name"),
                    FRUCHTERMAN_REINGOLD,
                    "fruchtermanReingold.gravity.name",
                    NbBundle.getMessage(FruchtermanReingold.class, "fruchtermanReingold.gravity.desc"),
                    "getGravity", "setGravity"));
            properties.add(LayoutProperty.createProperty(
                    this, Double.class,
                    NbBundle.getMessage(FruchtermanReingold.class, "fruchtermanReingold.speed.name"),
                    FRUCHTERMAN_REINGOLD,
                    "fruchtermanReingold.speed.name",
                    NbBundle.getMessage(FruchtermanReingold.class, "fruchtermanReingold.speed.desc"),
                    "getSpeed", "setSpeed"));
            // 忽略国际化
            properties.add(LayoutProperty.createProperty(
                    this, Float.class,
                    "Alpha",
                    COMMUNITY,
                    "fruchtermanReingold.alpha.name",
                    "The weight of attractive force between nodes of different community.",
                    "getAlpha", "setAlpha"));
            properties.add(LayoutProperty.createProperty(
                    this, Boolean.class,
                    "Community Detection",
                    COMMUNITY,
                    "fruchtermanReingold.communityDetection.name",
                    "Use Modularity BEFORE enabling this option. Use modularity_class to separate nodes",
                    "getUseCommunityDetection", "setUseCommunityDetection"));
            properties.add(LayoutProperty.createProperty(
                    this, Boolean.class,
                    "Approximate",
                    LARGE_GRAPH_OPTIMIZATION,
                    "fruchtermanReingold.approximate.name",
                    "Use the centroid of communities to simplify the calculation.",
                    "getApproximate", "setApproximate"));
            properties.add(LayoutProperty.createProperty(
                    this, Boolean.class,
                    "Community Attraction",
                    LARGE_GRAPH_OPTIMIZATION,
                    "fruchtermanReingold.communityAttraction.name",
                    "Set nodes can be attracted to the centroid of its community. Makes communities more compact.",
                    "getCommunityAttraction", "setCommunityAttraction"));
        } catch (Exception e) {
            Exceptions.printStackTrace(e);
        }

        return properties.toArray(new LayoutProperty[0]);
    }

    private static class Community {
        int communityId;
        // centroid x
        float x;
        // centroid y
        float y;
        // nodes of this community
        Set<Node> nodes;
        // edges between nodes of this community
        Set<Edge> edges;
        // best radius of this community
        float radius;

        public Community(int communityId) {
            this.communityId = communityId;
            nodes = new HashSet<>();
            edges = new HashSet<>();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Community community = (Community) o;
            return communityId == community.communityId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(communityId);
        }
    }

    private Map<Integer, Community> initCommunity(Node[] nodes) {
        Map<Integer, Community> map = new HashMap<>();

        for (Node n : nodes) {
            int communityId;
            try {
                communityId = (int) n.getAttribute(MODULARITY_CLASS);
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

    private Map<Node, Map<Community, Integer>> initNodeNeighborCommunity(Edge[] edges, final Map<Integer, Community> communityMap) {
        final Map<Node, Map<Community, Integer>> map = new ConcurrentHashMap<>();

        for (final Edge edge : edges) {
            Node source = edge.getSource();
            Node target = edge.getTarget();
            Community community = communityMap.get((Integer) source.getAttribute(MODULARITY_CLASS));
            Community neighbor = communityMap.get((Integer) target.getAttribute(MODULARITY_CLASS));
            if (community == neighbor) {
                // Same community. Add edge to community's edges.
                community.edges.add(edge);
            } else {
                // Different community, sum the weight of the edges.
                Map<Community, Integer> sourceNeighbor = map.containsKey(source) ? map.get(source) : new HashMap<Community, Integer>();
                int weight = sourceNeighbor.containsKey(neighbor) ? sourceNeighbor.get(neighbor) : 0;
                sourceNeighbor.put(neighbor, ++weight);
                map.put(source, sourceNeighbor);
            }
        }

        // Prevent unexpected modification
        return Collections.unmodifiableMap(map);
    }

    /**
     * Update Centroid Parallel.
     *
     * @param map Community Map
     */
    private void updateCentroid(Map<Integer, Community> map) {
        Set<Future<?>> threads = new HashSet<>(map.size());
        for (final Community c : map.values()) {
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
                    c.radius = (float) Math.sqrt(AREA_MULTIPLICATOR * area / nodes.length * c.nodes.size()) / 2;
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

    /**
     * Update displacement due to repulsive force.
     *
     * @param k          k
     * @param weight     The weight of node/community of the repulsive force.
     * @param xDist      x distance
     * @param yDist      y distance
     * @param layoutData layoutData, store displacement.
     */
    private void updateRepulsiveDisplacement(float k, int weight, float xDist, float yDist, ForceVectorNodeLayoutData layoutData) {
        float dist = (float) Math.sqrt(xDist * xDist + yDist * yDist);

        if (dist > 0) {
            float repulsiveF = weight * k * k / dist;
            layoutData.dx += xDist / dist * repulsiveF;
            layoutData.dy += yDist / dist * repulsiveF;
        }
    }

    /**
     * Update displacement due to attractive force.
     *
     * @param k           k
     * @param forceWeight the weight of attractive force, smaller between nodes of different community
     *                    than those of same community.
     * @param source      edge source node
     * @param target      edge target node
     */
    private void updateAttractiveDisplacement(float k, float forceWeight, Node source, Node target) {
        float xDist = source.x() - target.x();
        float yDist = source.y() - target.y();
        float dist = (float) Math.sqrt(xDist * xDist + yDist * yDist);
        float attractiveF = forceWeight * dist * dist / k;
        if (dist > 0) {
            ForceVectorNodeLayoutData sourceLayoutData = source.getLayoutData();
            ForceVectorNodeLayoutData targetLayoutData = target.getLayoutData();
            // Because we use parallelism, there's a chance when two edges use the same node. We need to synchronize.
            synchronized (source) {
                sourceLayoutData.dx -= xDist / dist * attractiveF;
                sourceLayoutData.dy -= yDist / dist * attractiveF;
            }
            synchronized (target) {
                targetLayoutData.dx += xDist / dist * attractiveF;
                targetLayoutData.dy += yDist / dist * attractiveF;
            }
        }
    }

    /**
     * @return community attraction
     */
    public Boolean getCommunityAttraction() {
        return communityAttraction;
    }

    /**
     * @param communityAttraction toggle community attraction
     */
    public void setCommunityAttraction(Boolean communityAttraction) {
        this.communityAttraction = communityAttraction;
    }

    /**
     * @return approximate
     */
    public Boolean getApproximate() {
        return approximate;
    }

    /**
     * @param approximate toggle approximate
     */
    public void setApproximate(Boolean approximate) {
        this.approximate = approximate;
        // Prevent NPE
        if (approximate && this.edges != null && this.nodes != null) {
            this.communityMap = initCommunity(nodes);
            this.nodeNeighborCommunity = initNodeNeighborCommunity(edges, this.communityMap);
        }
    }

    /**
     * @return whether to use modularity_class
     */
    public Boolean getUseCommunityDetection() {
        return useCommunityDetection;
    }

    /**
     * @param useCommunityDetection toggle use modularity_class
     */
    public void setUseCommunityDetection(Boolean useCommunityDetection) {
        this.useCommunityDetection = useCommunityDetection;
    }

    /**
     * @return attractive force weight
     */
    public Float getAlpha() {
        return alpha;
    }

    /**
     * @param alpha attractive force weight
     */
    public void setAlpha(Float alpha) {
        this.alpha = alpha;
    }

    public Float getArea() {
        return area;
    }

    public void setArea(Float area) {
        this.area = area;
    }

    /**
     * @return the gravity
     */
    public Double getGravity() {
        return gravity;
    }

    /**
     * @param gravity the gravity to set
     */
    public void setGravity(Double gravity) {
        this.gravity = gravity;
    }

    /**
     * @return the speed
     */
    public Double getSpeed() {
        return speed;
    }

    /**
     * @param speed the speed to set
     */
    public void setSpeed(Double speed) {
        this.speed = speed;
    }
}
