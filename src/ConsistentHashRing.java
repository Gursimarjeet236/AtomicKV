
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ConsistentHashRing {
    private final TreeMap<Long, ClusterNode> ring;
    private final int vnodesPerNode;
    private final ReentrantReadWriteLock ringMutex;
    private final AtomicLong ringVersion;

    public ConsistentHashRing() {
        this(100);
    }

    public ConsistentHashRing(int vnodes) {
        this.ring = new TreeMap<>();
        this.vnodesPerNode = vnodes;
        this.ringMutex = new ReentrantReadWriteLock();
        this.ringVersion = new AtomicLong(0);
    }

    private long hashFunc(String key) {
        // Simple hash function mapping string to long
        long hash = 5381;
        for (int i = 0; i < key.length(); i++) {
            hash = ((hash << 5) + hash) + key.charAt(i); // hash * 33 + c
        }
        return hash;
    }

    public void addNode(String ip, int port) {
        ringMutex.writeLock().lock();
        try {
            String nodeId = ip + ":" + port;

            for (ClusterNode node : ring.values()) {
                if (node.id.equals(nodeId)) return;
            }

            ClusterNode node = new ClusterNode(ip, port, nodeId);

            for (int i = 0; i < vnodesPerNode; ++i) {
                String vnodeKey = nodeId + "#" + i;
                long hashVal = hashFunc(vnodeKey);
                ring.put(hashVal, node);
            }

            ringVersion.incrementAndGet();
        } finally {
            ringMutex.writeLock().unlock();
        }
    }

    public void removeNode(String ip, int port) {
        ringMutex.writeLock().lock();
        try {
            String nodeId = ip + ":" + port;

            for (int i = 0; i < vnodesPerNode; ++i) {
                String vnodeKey = nodeId + "#" + i;
                long hashVal = hashFunc(vnodeKey);
                ring.remove(hashVal);
            }

            ringVersion.incrementAndGet();
        } finally {
            ringMutex.writeLock().unlock();
        }
    }

    public ClusterNode getNodeForKey(String key) {
        ringMutex.readLock().lock();
        try {
            if (ring.isEmpty()) {
                return new ClusterNode("", 0, "");
            }

            long hashVal = hashFunc(key);
            Map.Entry<Long, ClusterNode> entry = ring.ceilingEntry(hashVal);

            if (entry == null) {
                entry = ring.firstEntry();
            }

            return entry.getValue();
        } finally {
            ringMutex.readLock().unlock();
        }
    }

    public List<ClusterNode> getReplicaNodes(String key, int count) {
        ringMutex.readLock().lock();
        try {
            List<ClusterNode> replicas = new ArrayList<>();
            if (ring.isEmpty()) return replicas;

            long hashVal = hashFunc(key);
            SortedMap<Long, ClusterNode> tailMap = ring.tailMap(hashVal);
            
            Long firstKey = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();
            
            Set<String> seenNodes = new HashSet<>();
            seenNodes.add(ring.get(firstKey).id);
            
            Long currKey = firstKey;
            while (replicas.size() < count) {
                currKey = ring.higherKey(currKey);
                if (currKey == null) {
                    currKey = ring.firstKey();
                }
                
                if (currKey.equals(firstKey)) break;
                
                ClusterNode node = ring.get(currKey);
                if (!seenNodes.contains(node.id)) {
                    seenNodes.add(node.id);
                    replicas.add(node);
                }
            }

            return replicas;
        } finally {
            ringMutex.readLock().unlock();
        }
    }

    public boolean isEmpty() {
        ringMutex.readLock().lock();
        try {
            return ring.isEmpty();
        } finally {
            ringMutex.readLock().unlock();
        }
    }

    public long getRingVersion() {
        return ringVersion.get();
    }
}
