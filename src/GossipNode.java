
public class GossipNode {
    public String ip;
    public int port;
    public int heartbeat;
    public long lastUpdated;
    public NodeStatus status;

    public GossipNode(String ip, int port, int heartbeat, long lastUpdated, NodeStatus status) {
        this.ip = ip;
        this.port = port;
        this.heartbeat = heartbeat;
        this.lastUpdated = lastUpdated;
        this.status = status;
    }
}
