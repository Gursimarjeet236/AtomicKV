# AtomicKV (Java Version)

## Motivation
This is the complete port of AtomicKV from C++ to Java. It preserves all the architectural decisions made in the original project, just utilizing the standard Java equivalents.

## Architecture

### 1. The Core Engine: `epoll` -> Java NIO `Selector`
The Java version replaces Linux's `epoll` with Java's `java.nio.channels.Selector`. A single main thread monitors thousands of non-blocking `SocketChannel`s simultaneously, eliminating context-switching overhead exactly like the C++ version.

### 2. The Storage Engine: LRU Cache & Custom B-Tree
- **RAM Layer**: The LRU Cache is implemented using a custom combination of `HashMap` and `LinkedList` to achieve O(1) time complexity. It uses `ReentrantReadWriteLock` for concurrency. A probabilistic Bloom Filter (`atomickv.BloomFilter`) minimizes disk reads on cache misses.
- **Disk Layer**: An Order-3 B-Tree is implemented completely from scratch (`atomickv.BTree`) using Java's `RandomAccessFile`. It maintains the same binary layout as the C++ structs for compatibility.

### 3. Scaling Out: Distributed System
- **Consistent Hashing**: A `TreeMap` manages the Hash Ring with virtual nodes.
- **Asynchronous Replication**: Background threads push `INTERNAL_SET` commands to replicas over sockets.
- **Gossip Protocol**: A background daemon thread gossips with a randomly selected node every 2 seconds, maintaining cluster state and heartbeats. Nodes failing to heartbeat for 10 seconds are considered dead and automatically removed from the hash ring.

### 4. Conflict Resolution
Uses event-driven read repair, anti-entropy daemon threads (scanning every 30 seconds), and Lamport clock versioning via `AtomicLong` to manage consistency in a highly available system.

## Project Structure (Java)

* `src/AtomicKVServer.java`: Main NIO loop and TCP handling.
* `src/KVStore.java`: Thread-safe Key-Value store and tiered storage logic.
* `src/BTree.java`: Disk-persistence engine using `RandomAccessFile`.
* `src/ConsistentHashRing.java`: Virtual node routing.
* `src/GossipManager.java`: Heartbeat and cluster state.
* `benchmark.py`: Multithreaded Python script for stress-testing.

## Usage

### Prerequisites
* Java JDK 8 or higher

### Build
```bash
# On Linux / Mac
./build.sh

# On Windows
build.bat
```

### Run a Cluster
Start multiple nodes to test distributed features. Open separate terminals:

```bash
# Node 1
java -cp out AtomicKVServer 8081

# Node 2 (Joins via Node 1)
java -cp out AtomicKVServer 8082 127.0.0.1:8081

# Node 3 (Joins via Node 1)
java -cp out AtomicKVServer 8083 127.0.0.1:8081
```

### Client Connection
Connect using `nc` or `telnet`:

```bash
nc localhost 8081
```

**Commands:**
* `SET <key> <value>`
* `SET <key> <value> <seconds>` (with TTL)
* `GET <key>`
* `DEL <key>`

## Deployment

### Docker
```bash
docker build -f Dockerfile.java -t atomickv-java .
docker run -d -p 8081:8081 atomickv-java
```
