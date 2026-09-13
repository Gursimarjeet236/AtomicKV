
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class KVStore {
    private final int capacity;
    private final LinkedList<String> lruList;
    private final Map<String, CacheEntry> store;
    private final ReentrantReadWriteLock rwLock;
    private final BTree diskDb;
    private final AtomicLong versionCounter;
    private final BloomFilter bloomFilter;

    public static class EntryData {
        public String value;
        public long expiryTime;
        public long version;

        public EntryData(String value, long expiryTime, long version) {
            this.value = value;
            this.expiryTime = expiryTime;
            this.version = version;
        }
    }

    private static class CacheEntry {
        EntryData data;

        CacheEntry(EntryData data) {
            this.data = data;
        }
    }

    public KVStore(int cap, String nodePrefix) throws IOException {
        this.capacity = cap;
        this.lruList = new LinkedList<>();
        this.store = new HashMap<>();
        this.rwLock = new ReentrantReadWriteLock();
        this.diskDb = new BTree(nodePrefix);
        this.versionCounter = new AtomicLong(0);
        this.bloomFilter = new BloomFilter();

        List<BTree.Entry> existingEntries = diskDb.getAllEntries();
        for (BTree.Entry entry : existingEntries) {
            bloomFilter.add(entry.key);
        }
    }

    public void close() throws IOException {
        diskDb.close();
    }

    public long set(String key, String value, int ttlSeconds) throws IOException {
        rwLock.writeLock().lock();
        try {
            long version = versionCounter.incrementAndGet();

            diskDb.insert(key, value, version);
            bloomFilter.add(key);

            long expiry = 0;
            if (ttlSeconds > 0) {
                expiry = (System.currentTimeMillis() / 1000) + ttlSeconds;
            }

            if (store.containsKey(key)) {
                store.get(key).data = new EntryData(value, expiry, version);
                lruList.remove(key);
                lruList.addFirst(key);
            } else {
                if (store.size() >= capacity) {
                    String lruKey = lruList.removeLast();
                    store.remove(lruKey);
                }

                lruList.addFirst(key);
                store.put(key, new CacheEntry(new EntryData(value, expiry, version)));
            }

            return version;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void setVersioned(String key, String value, long version, int ttlSeconds) throws IOException {
        rwLock.writeLock().lock();
        try {
            long current = versionCounter.get();
            while (version > current) {
                if (versionCounter.compareAndSet(current, version)) {
                    break;
                }
                current = versionCounter.get();
            }

            diskDb.insert(key, value, version);
            bloomFilter.add(key);

            long expiry = 0;
            if (ttlSeconds > 0) {
                expiry = (System.currentTimeMillis() / 1000) + ttlSeconds;
            }

            if (store.containsKey(key)) {
                if (version > store.get(key).data.version) {
                    store.get(key).data = new EntryData(value, expiry, version);
                    lruList.remove(key);
                    lruList.addFirst(key);
                }
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public String get(String key) throws IOException {
        long[] dummy = new long[1];
        return getWithVersion(key, dummy);
    }

    public String getWithVersion(String key, long[] outVersion) throws IOException {
        rwLock.writeLock().lock();
        try {
            if (!store.containsKey(key)) {
                if (!bloomFilter.possiblyContains(key)) {
                    outVersion[0] = 0;
                    return "NULL";
                }

                String diskVal = diskDb.search(key, outVersion);
                if (!"NULL".equals(diskVal)) {
                    if (store.size() >= capacity) {
                        String lruKey = lruList.removeLast();
                        store.remove(lruKey);
                    }
                    lruList.addFirst(key);
                    store.put(key, new CacheEntry(new EntryData(diskVal, 0, outVersion[0])));
                    return diskVal;
                }
                outVersion[0] = 0;
                return "NULL";
            }

            EntryData entry = store.get(key).data;
            if (entry.expiryTime != 0 && (System.currentTimeMillis() / 1000) > entry.expiryTime) {
                lruList.remove(key);
                store.remove(key);
                diskDb.remove(key);
                outVersion[0] = 0;
                return "NULL";
            }

            lruList.remove(key);
            lruList.addFirst(key);

            outVersion[0] = entry.version;
            return entry.value;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public boolean del(String key) throws IOException {
        rwLock.writeLock().lock();
        try {
            boolean deletedFromDisk = diskDb.remove(key);

            if (store.containsKey(key)) {
                lruList.remove(key);
                store.remove(key);
                return true;
            }
            return deletedFromDisk;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public List<BTree.Entry> getAllEntries() throws IOException {
        rwLock.writeLock().lock();
        try {
            return diskDb.getAllEntries();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void advanceClock(long receivedVersion) {
        long current = versionCounter.get();
        while (receivedVersion > current) {
            if (versionCounter.compareAndSet(current, receivedVersion)) {
                break;
            }
            current = versionCounter.get();
        }
    }
}
