
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class AtomicKVServer {
    private static KVStore db;
    private static ConsistentHashRing ring = new ConsistentHashRing();
    private static String myIp = "127.0.0.1";
    private static int myPort = 8081;
    private static String myNodeId;

    private static GossipManager gossipMgr = new GossipManager();
    private static AtomicInteger myHeartbeat = new AtomicInteger(0);

    private static class ReplicationTask {
        String command;
        String targetIp;
        int targetPort;

        ReplicationTask(String cmd, String ip, int port) {
            this.command = cmd;
            this.targetIp = ip;
            this.targetPort = port;
        }
    }

    private static BlockingQueue<ReplicationTask> replicationQueue = new LinkedBlockingQueue<>();

    private static class ReadRepairTask {
        String key;
        String localValue;
        long localVersion;

        ReadRepairTask(String key, String localValue, long localVersion) {
            this.key = key;
            this.localValue = localValue;
            this.localVersion = localVersion;
        }
    }

    private static BlockingQueue<ReadRepairTask> readRepairQueue = new LinkedBlockingQueue<>();

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: java atomickv.AtomicKVServer <port> [node1_ip:port] [node2_ip:port] ...");
            System.exit(1);
        }

        myPort = Integer.parseInt(args[0]);
        myNodeId = myIp + ":" + myPort;

        db = new KVStore(5, String.valueOf(myPort));

        for (int i = 1; i < args.length; ++i) {
            String[] parts = args[i].split(":");
            if (parts.length == 2) {
                String ip = parts[0];
                int port = Integer.parseInt(parts[1]);
                ring.addNode(ip, port);
                gossipMgr.updateNode(ip, port, 0);
            }
        }

        if (ring.isEmpty()) {
            ring.addNode(myIp, myPort);
        }

        startBackgroundWorkers();

        Selector selector = Selector.open();
        ServerSocketChannel serverSocket = ServerSocketChannel.open();
        serverSocket.bind(new InetSocketAddress(myPort));
        serverSocket.configureBlocking(false);
        serverSocket.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("Server listening on port " + myPort + " using NIO Selector...");
        System.out.println("Node ID: " + myNodeId);
        System.out.println("Background threads: replication, gossip, migration, read-repair, anti-entropy");

        ByteBuffer buffer = ByteBuffer.allocate(4096);

        while (true) {
            selector.select();
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> iter = selectedKeys.iterator();

            while (iter.hasNext()) {
                SelectionKey key = iter.next();

                if (key.isAcceptable()) {
                    SocketChannel client = serverSocket.accept();
                    client.configureBlocking(false);
                    client.register(selector, SelectionKey.OP_READ);
                } else if (key.isReadable()) {
                    SocketChannel client = (SocketChannel) key.channel();
                    buffer.clear();
                    int bytesRead = -1;
                    try {
                        bytesRead = client.read(buffer);
                    } catch (IOException e) {
                        // Connection reset by peer
                    }

                    if (bytesRead <= 0) {
                        client.close();
                        key.cancel();
                    } else {
                        buffer.flip();
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        String request = new String(bytes, StandardCharsets.UTF_8);
                        processClientRequest(client, request);
                    }
                }
                iter.remove();
            }
        }
    }

    private static void startBackgroundWorkers() {
        Thread repThread = new Thread(() -> {
            while (true) {
                try {
                    ReplicationTask task = replicationQueue.take();
                    try (Socket sock = new Socket()) {
                        sock.connect(new InetSocketAddress(task.targetIp, task.targetPort), 2000);
                        sock.setSoTimeout(2000);
                        OutputStream out = sock.getOutputStream();
                        out.write(task.command.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        
                        InputStream in = sock.getInputStream();
                        byte[] buf = new byte[1024];
                        in.read(buf);
                    } catch (Exception e) {
                        // Ignore connection failures
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        repThread.setDaemon(true);
        repThread.start();

        Thread migThread = new Thread(() -> {
            long lastRingVersion = ring.getRingVersion();
            while (true) {
                try {
                    Thread.sleep(1000);
                    long currentRingVersion = ring.getRingVersion();
                    if (currentRingVersion == lastRingVersion) continue;

                    lastRingVersion = currentRingVersion;
                    System.out.println("[MIGRATE] Ring changed (v" + currentRingVersion + "). Scanning keys for migration...");

                    List<BTree.Entry> entries = db.getAllEntries();
                    int migrated = 0;

                    for (BTree.Entry entry : entries) {
                        ClusterNode owner = ring.getNodeForKey(entry.key);
                        if (owner.id.equals(myNodeId) || owner.id.isEmpty()) continue;

                        String migrateCmd = "MIGRATE " + entry.key + " " + entry.value + " " + entry.version + "\n";
                        replicationQueue.put(new ReplicationTask(migrateCmd, owner.ip, owner.port));
                        migrated++;
                    }

                    if (migrated > 0) {
                        System.out.println("[MIGRATE] Migration complete. Pushed " + migrated + " keys to new owners.");
                    }
                } catch (Exception e) {
                    // Ignore
                }
            }
        });
        migThread.setDaemon(true);
        migThread.start();

        Thread rrThread = new Thread(() -> {
            while (true) {
                try {
                    ReadRepairTask task = readRepairQueue.take();
                    List<ClusterNode> replicas = ring.getReplicaNodes(task.key, 2);

                    for (ClusterNode replica : replicas) {
                        if (replica.id.equals(myNodeId)) continue;

                        try (Socket sock = new Socket()) {
                            sock.connect(new InetSocketAddress(replica.ip, replica.port), 1000);
                            sock.setSoTimeout(1000);
                            
                            OutputStream out = sock.getOutputStream();
                            String cmd = "INTERNAL_GET " + task.key + "\n";
                            out.write(cmd.getBytes(StandardCharsets.UTF_8));
                            out.flush();

                            InputStream in = sock.getInputStream();
                            byte[] buf = new byte[4096];
                            int valRead = in.read(buf);
                            
                            if (valRead > 0) {
                                String response = new String(buf, 0, valRead, StandardCharsets.UTF_8).trim();
                                String[] parts = response.split("\\s+");
                                if (parts.length >= 2) {
                                    String replicaValue = parts[0];
                                    long replicaVersion = Long.parseLong(parts[1]);

                                    if (!"NULL".equals(replicaValue)) {
                                        if (replicaVersion > task.localVersion) {
                                            db.setVersioned(task.key, replicaValue, replicaVersion, 0);
                                            task.localValue = replicaValue;
                                            task.localVersion = replicaVersion;
                                            System.out.println("[READ_REPAIR] Updated local key \"" + task.key 
                                                + "\" from " + replica.id + " (v" + replicaVersion + ")");
                                        } else if (replicaVersion < task.localVersion) {
                                            String fixCmd = "INTERNAL_SET " + task.key + " " 
                                                + task.localValue + " " + task.localVersion + "\n";
                                            replicationQueue.put(new ReplicationTask(fixCmd, replica.ip, replica.port));
                                            System.out.println("[READ_REPAIR] Fixed stale key \"" + task.key 
                                                + "\" on " + replica.id + " (v" + replicaVersion + " -> v" + task.localVersion + ")");
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            // Ignore connection failures
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        rrThread.setDaemon(true);
        rrThread.start();

        Thread aeThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(30000);
                    List<BTree.Entry> entries = db.getAllEntries();
                    int repaired = 0;

                    for (BTree.Entry entry : entries) {
                        ClusterNode owner = ring.getNodeForKey(entry.key);
                        if (!owner.id.equals(myNodeId)) continue;

                        List<ClusterNode> replicas = ring.getReplicaNodes(entry.key, 2);

                        for (ClusterNode replica : replicas) {
                            if (replica.id.equals(myNodeId)) continue;

                            try (Socket sock = new Socket()) {
                                sock.connect(new InetSocketAddress(replica.ip, replica.port), 1000);
                                sock.setSoTimeout(1000);
                                
                                OutputStream out = sock.getOutputStream();
                                String cmd = "INTERNAL_GET " + entry.key + "\n";
                                out.write(cmd.getBytes(StandardCharsets.UTF_8));
                                out.flush();

                                InputStream in = sock.getInputStream();
                                byte[] buf = new byte[4096];
                                int valRead = in.read(buf);

                                if (valRead > 0) {
                                    String response = new String(buf, 0, valRead, StandardCharsets.UTF_8).trim();
                                    String[] parts = response.split("\\s+");
                                    if (parts.length >= 2) {
                                        String replicaValue = parts[0];
                                        long replicaVersion = Long.parseLong(parts[1]);

                                        if (replicaVersion < entry.version) {
                                            String fixCmd = "INTERNAL_SET " + entry.key + " " + entry.value + " " + entry.version + "\n";
                                            replicationQueue.put(new ReplicationTask(fixCmd, replica.ip, replica.port));
                                            repaired++;
                                        } else if (replicaVersion > entry.version) {
                                            if (!"NULL".equals(replicaValue)) {
                                                db.setVersioned(entry.key, replicaValue, replicaVersion, 0);
                                                repaired++;
                                            }
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                // Ignore connection failures
                            }
                        }
                    }
                    if (repaired > 0) {
                        System.out.println("[ANTI-ENTROPY] Scanned " + entries.size() + " keys, repaired " + repaired + " stale replicas.");
                    }
                } catch (Exception e) {
                    // Ignore
                }
            }
        });
        aeThread.setDaemon(true);
        aeThread.start();

        Thread gossipThread = new Thread(() -> {
            Random random = new Random();
            while (true) {
                try {
                    Thread.sleep(2000);
                    int currentHb = myHeartbeat.incrementAndGet();

                    List<GossipNode> deadNodes = gossipMgr.getDeadNodes(10);
                    for (GossipNode node : deadNodes) {
                        System.out.println("[GOSSIP] Node " + node.ip + ":" + node.port + " is DEAD. Removing from Ring.");
                        ring.removeNode(node.ip, node.port);
                    }

                    List<GossipNode> aliveNodes = gossipMgr.getAliveNodes();
                    if (aliveNodes.isEmpty()) continue;

                    GossipNode target = aliveNodes.get(random.nextInt(aliveNodes.size()));

                    try (Socket sock = new Socket()) {
                        sock.connect(new InetSocketAddress(target.ip, target.port), 1000);
                        sock.setSoTimeout(1000);
                        
                        OutputStream out = sock.getOutputStream();
                        String gossipMsg = "GOSSIP " + myIp + " " + myPort + " " + currentHb + "\n";
                        out.write(gossipMsg.getBytes(StandardCharsets.UTF_8));
                        out.flush();

                        InputStream in = sock.getInputStream();
                        byte[] buf = new byte[1024];
                        in.read(buf);
                    } catch (Exception e) {
                        // Ignore connection failures
                    }

                } catch (Exception e) {
                    // Ignore
                }
            }
        });
        gossipThread.setDaemon(true);
        gossipThread.start();
    }

    private static void processClientRequest(SocketChannel clientSocket, String requestStr) throws IOException {
        String result = "ERROR: Unknown Command\n";
        
        Scanner scanner = new Scanner(requestStr);
        if (!scanner.hasNext()) {
            return;
        }

        String command = scanner.next();
        String key = scanner.hasNext() ? scanner.next() : "";

        if (command.equals("GOSSIP") || command.equals("GOSSIP_ACK")) {
            String targetIp = key;
            int targetPort = scanner.hasNextInt() ? scanner.nextInt() : 0;
            int targetHb = scanner.hasNextInt() ? scanner.nextInt() : 0;

            gossipMgr.updateNode(targetIp, targetPort, targetHb);
            ring.addNode(targetIp, targetPort);

            if (command.equals("GOSSIP")) {
                result = "GOSSIP_ACK " + myIp + " " + myPort + " " + myHeartbeat.get() + "\n";
                clientSocket.write(ByteBuffer.wrap(result.getBytes(StandardCharsets.UTF_8)));
            }
            return;
        }

        boolean needsRedirect = false;
        ClusterNode owner = null;

        if (!key.isEmpty() && !ring.isEmpty()) {
            owner = ring.getNodeForKey(key);
            if (!owner.id.equals(myNodeId) && !command.equals("INTERNAL_SET") && !command.equals("INTERNAL_DEL") 
                && !command.equals("MIGRATE") && !command.equals("INTERNAL_GET")) {
                needsRedirect = true;
            }
        }

        if (needsRedirect) {
            try (Socket proxySock = new Socket()) {
                proxySock.connect(new InetSocketAddress(owner.ip, owner.port), 2000);
                proxySock.setSoTimeout(2000);
                
                OutputStream out = proxySock.getOutputStream();
                out.write(requestStr.getBytes(StandardCharsets.UTF_8));
                out.flush();

                InputStream in = proxySock.getInputStream();
                byte[] buf = new byte[4096];
                int valRead = in.read(buf);

                if (valRead > 0) {
                    clientSocket.write(ByteBuffer.wrap(buf, 0, valRead));
                } else {
                    result = "ERROR: Owner node did not respond\n";
                    clientSocket.write(ByteBuffer.wrap(result.getBytes(StandardCharsets.UTF_8)));
                }
            } catch (Exception e) {
                result = "ERROR: Could not connect to owner node\n";
                clientSocket.write(ByteBuffer.wrap(result.getBytes(StandardCharsets.UTF_8)));
            }
            return;
        }

        if (command.equals("SET")) {
            String value = "";
            if (scanner.hasNextLine()) {
                value = scanner.nextLine().trim();
            }
            
            Scanner valScanner = new Scanner(value);
            String actualValue = valScanner.hasNext() ? valScanner.next() : "";
            int ttl = valScanner.hasNextInt() ? valScanner.nextInt() : 0;

            long version = db.set(key, actualValue, ttl);
            result = "OK\n";

            List<ClusterNode> replicas = ring.getReplicaNodes(key, 2);
            String internalCmd = "INTERNAL_SET " + key + " " + actualValue + " " + version + "\n";
            for (ClusterNode rep : replicas) {
                try {
                    replicationQueue.put(new ReplicationTask(internalCmd, rep.ip, rep.port));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } else if (command.equals("INTERNAL_SET") || command.equals("MIGRATE")) {
            String value = "";
            if (scanner.hasNextLine()) {
                value = scanner.nextLine().trim();
            }

            Scanner valScanner = new Scanner(value);
            String actualValue = valScanner.hasNext() ? valScanner.next() : "";
            long version = valScanner.hasNextLong() ? valScanner.nextLong() : 0;

            db.setVersioned(key, actualValue, version, 0);
            result = "OK\n";
        } else if (command.equals("GET")) {
            long[] versionArr = new long[1];
            String val = db.getWithVersion(key, versionArr);
            result = val + "\n";

            if (!"NULL".equals(val)) {
                try {
                    readRepairQueue.put(new ReadRepairTask(key, val, versionArr[0]));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } else if (command.equals("INTERNAL_GET")) {
            long[] versionArr = new long[1];
            String val = db.getWithVersion(key, versionArr);
            result = val + " " + versionArr[0] + "\n";
        } else if (command.equals("DEL") || command.equals("INTERNAL_DEL")) {
            boolean deleted = db.del(key);
            result = deleted ? "DELETED\n" : "NOT FOUND\n";

            if (command.equals("DEL") && deleted) {
                List<ClusterNode> replicas = ring.getReplicaNodes(key, 2);
                String internalCmd = "INTERNAL_DEL " + key + "\n";
                for (ClusterNode rep : replicas) {
                    try {
                        replicationQueue.put(new ReplicationTask(internalCmd, rep.ip, rep.port));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        clientSocket.write(ByteBuffer.wrap(result.getBytes(StandardCharsets.UTF_8)));
    }
}
