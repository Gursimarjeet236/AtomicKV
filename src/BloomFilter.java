
public class BloomFilter {
    private final boolean[] bits;
    private final int numHashes;

    public BloomFilter() {
        this(1000000, 3); // Default config: 1,000,000 bits and 3 hash functions
    }

    public BloomFilter(int size, int hashes) {
        this.bits = new boolean[size];
        this.numHashes = hashes;
    }

    private int hash1(String key) {
        return key.hashCode();
    }

    // FNV-1a hash
    private int hash2(String key) {
        long hash = 2166136261L;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 16777619L;
        }
        return (int) hash;
    }

    // DJB2 hash
    private int hash3(String key) {
        long hash = 5381;
        for (int i = 0; i < key.length(); i++) {
            hash = ((hash << 5) + hash) + key.charAt(i); // hash * 33 + c
        }
        return (int) hash;
    }

    public void add(String key) {
        if (bits.length == 0) return;
        
        int h1 = Math.abs(hash1(key) % bits.length);
        bits[h1] = true;
        
        if (numHashes > 1) {
            int h2 = Math.abs(hash2(key) % bits.length);
            bits[h2] = true;
        }
        if (numHashes > 2) {
            int h3 = Math.abs(hash3(key) % bits.length);
            bits[h3] = true;
        }
    }

    public boolean possiblyContains(String key) {
        if (bits.length == 0) return false;
        
        int h1 = Math.abs(hash1(key) % bits.length);
        if (!bits[h1]) return false;
        
        if (numHashes > 1) {
            int h2 = Math.abs(hash2(key) % bits.length);
            if (!bits[h2]) return false;
        }
        
        if (numHashes > 2) {
            int h3 = Math.abs(hash3(key) % bits.length);
            if (!bits[h3]) return false;
        }
        
        return true;
    }
}
