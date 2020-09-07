package org.gephi.layout.plugin.fruchterman;

import org.gephi.graph.api.Edge;
import org.gephi.graph.api.Node;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * @author <a href="mailto:wu812730157@gmail.com">Junxian Wu</a>
 * @date 20-9-3
 **/
public class Community {
    Object communityId;
    /**
     * centroid x
     */
    float x;
    /**
     * centroid y
     */
    float y;
    /**
     * nodes of this community
      */
    Set<Node> nodes;
    /**
     * edges between nodes of this community
     */
    Set<Edge> edges;
    /**
     * best radius of this community
     */
    float radius;

    public Community(Object communityId) {
        this.communityId = communityId;
        nodes = new HashSet<>();
        edges = new HashSet<>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        Community community = (Community) o;

        return communityId.equals(community.communityId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(communityId);
    }
}
