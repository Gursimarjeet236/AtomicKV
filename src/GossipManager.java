
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GossipManager {
    private final Map<String, GossipNode> clusterState;

    public GossipManager() {
        this.clusterState = new ConcurrentHashMap<>();
    }

    public void updateNode(String ip, int port, int heartbeat) {
        String nodeId = ip + ":" + port;
        
        clusterState.compute(nodeId, (key, existingNode) -> {
            long now = System.currentTimeMillis() / 1000;
            if (existingNode == null) {
                return new GossipNode(ip, port, heartbeat, now, NodeStatus.ALIVE);
            } else {
                if (heartbeat > existingNode.heartbeat) {
                    existingNode.heartbeat = heartbeat;
                    existingNode.lastUpdated = now;
                    existingNode.status = NodeStatus.ALIVE;
                }
                return existingNode;
            }
        });
    }

    public List<GossipNode> getDeadNodes(int timeoutSeconds) {
        List<GossipNode> deadNodes = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000;

        for (GossipNode node : clusterState.values()) {
            if (node.status == NodeStatus.ALIVE && (now - node.lastUpdated) > timeoutSeconds) {
                node.status = NodeStatus.DEAD;
                deadNodes.add(node);
            }
        }
        return deadNodes;
    }

    public List<GossipNode> getAliveNodes() {
        List<GossipNode> aliveNodes = new ArrayList<>();
        for (GossipNode node : clusterState.values()) {
            if (node.status == NodeStatus.ALIVE) {
                aliveNodes.add(node);
            }
        }
        return aliveNodes;
    }

    public void markDead(String nodeId) {
        GossipNode node = clusterState.get(nodeId);
        if (node != null) {
            node.status = NodeStatus.DEAD;
        }
    }
}
