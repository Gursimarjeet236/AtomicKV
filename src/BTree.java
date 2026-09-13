import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class BTree {
    public static final int MAX_KEYS = 3;
    public static final int MAX_KEY_LEN = 64;
    private static final long ROOT_POINTER_OFFSET = 0;
    private static final long DATA_START_OFFSET = 8; // sizeof(size_t) which is 8 bytes for long in Java

    private RandomAccessFile idxFile;
    private RandomAccessFile datFile;
    private long rootOffset;

    public static class SearchResult {
        public boolean found;
        public long valueOffset;
        public long valueLength;
        public long version;

        public SearchResult(boolean found, long valueOffset, long valueLength, long version) {
            this.found = found;
            this.valueOffset = valueOffset;
            this.valueLength = valueLength;
            this.version = version;
        }
    }

    public static class Entry {
        public String key;
        public String value;
        public long version;

        public Entry(String key, String value, long version) {
            this.key = key;
            this.value = value;
            this.version = version;
        }
    }

    private static class BTreeNode {
        boolean isLeaf;
        int numKeys;
        byte[][] keys = new byte[MAX_KEYS][MAX_KEY_LEN];
        long[] valueOffsets = new long[MAX_KEYS];
        long[] valueLengths = new long[MAX_KEYS];
        boolean[] isDeleted = new boolean[MAX_KEYS];
        long[] versions = new long[MAX_KEYS];
        long[] childPointers = new long[MAX_KEYS + 1];

        // Total size calculation for node structure on disk
        // isLeaf (1 byte)
        // numKeys (4 bytes)
        // keys (MAX_KEYS * MAX_KEY_LEN = 3 * 64 = 192 bytes)
        // valueOffsets (MAX_KEYS * 8 = 24 bytes)
        // valueLengths (MAX_KEYS * 8 = 24 bytes)
        // isDeleted (MAX_KEYS * 1 = 3 bytes)
        // versions (MAX_KEYS * 8 = 24 bytes)
        // childPointers ((MAX_KEYS + 1) * 8 = 32 bytes)
        // Total = 1 + 4 + 192 + 24 + 24 + 3 + 24 + 32 = 304 bytes
        public static final int NODE_SIZE = 304;

        public void write(RandomAccessFile file, long offset) throws IOException {
            file.seek(offset);
            file.writeBoolean(isLeaf);
            file.writeInt(numKeys);
            for (int i = 0; i < MAX_KEYS; i++) {
                file.write(keys[i]);
            }
            for (int i = 0; i < MAX_KEYS; i++) {
                file.writeLong(valueOffsets[i]);
            }
            for (int i = 0; i < MAX_KEYS; i++) {
                file.writeLong(valueLengths[i]);
            }
            for (int i = 0; i < MAX_KEYS; i++) {
                file.writeBoolean(isDeleted[i]);
            }
            for (int i = 0; i < MAX_KEYS; i++) {
                file.writeLong(versions[i]);
            }
            for (int i = 0; i < MAX_KEYS + 1; i++) {
                file.writeLong(childPointers[i]);
            }
        }

        public void read(RandomAccessFile file, long offset) throws IOException {
            file.seek(offset);
            isLeaf = file.readBoolean();
            numKeys = file.readInt();
            for (int i = 0; i < MAX_KEYS; i++) {
                file.readFully(keys[i]);
            }
            for (int i = 0; i < MAX_KEYS; i++) {
                valueOffsets[i] = file.readLong();
            }
            for (int i = 0; i < MAX_KEYS; i++) {
                valueLengths[i] = file.readLong();
            }
            for (int i = 0; i < MAX_KEYS; i++) {
                isDeleted[i] = file.readBoolean();
            }
            for (int i = 0; i < MAX_KEYS; i++) {
                versions[i] = file.readLong();
            }
            for (int i = 0; i < MAX_KEYS + 1; i++) {
                childPointers[i] = file.readLong();
            }
        }
    }

    public BTree() throws IOException {
        this("");
    }

    public BTree(String nodePrefix) throws IOException {
        String idxFilename = nodePrefix.isEmpty() ? "btree.idx" : "node_" + nodePrefix + "_btree.idx";
        String datFilename = nodePrefix.isEmpty() ? "database.dat" : "node_" + nodePrefix + "_database.dat";

        File idx = new File(idxFilename);
        boolean idxExists = idx.exists();
        idxFile = new RandomAccessFile(idx, "rw");

        if (!idxExists || idxFile.length() < DATA_START_OFFSET) {
            rootOffset = DATA_START_OFFSET;
            idxFile.seek(ROOT_POINTER_OFFSET);
            idxFile.writeLong(rootOffset);

            BTreeNode root = new BTreeNode();
            root.isLeaf = true;
            root.numKeys = 0;
            root.write(idxFile, rootOffset);
        } else {
            idxFile.seek(ROOT_POINTER_OFFSET);
            rootOffset = idxFile.readLong();
        }

        File dat = new File(datFilename);
        datFile = new RandomAccessFile(dat, "rw");
    }

    public void close() throws IOException {
        if (idxFile != null)
            idxFile.close();
        if (datFile != null)
            datFile.close();
    }

    private long allocateNode() throws IOException {
        long offset = idxFile.length();
        if (offset < DATA_START_OFFSET)
            offset = DATA_START_OFFSET;

        BTreeNode dummy = new BTreeNode();
        dummy.write(idxFile, offset);

        return offset;
    }

    private String getKeyString(byte[] keyBytes) {
        int len = 0;
        while (len < keyBytes.length && keyBytes[len] != 0) {
            len++;
        }
        return new String(keyBytes, 0, len, StandardCharsets.UTF_8);
    }

    private void setKeyString(byte[] keyBytes, String key) {
        byte[] strBytes = key.getBytes(StandardCharsets.UTF_8);
        int len = Math.min(strBytes.length, MAX_KEY_LEN - 1); // keep space for null terminator conceptually
        System.arraycopy(strBytes, 0, keyBytes, 0, len);
        for (int i = len; i < MAX_KEY_LEN; i++) {
            keyBytes[i] = 0;
        }
    }

    private SearchResult searchInner(long nodeOffset, String key) throws IOException {
        BTreeNode node = new BTreeNode();
        node.read(idxFile, nodeOffset);

        int i = 0;
        while (i < node.numKeys && key.compareTo(getKeyString(node.keys[i])) > 0) {
            i++;
        }

        if (i < node.numKeys && key.equals(getKeyString(node.keys[i]))) {
            if (!node.isDeleted[i]) {
                return new SearchResult(true, node.valueOffsets[i], node.valueLengths[i], node.versions[i]);
            }
            return new SearchResult(false, 0, 0, 0);
        }

        if (node.isLeaf) {
            return new SearchResult(false, 0, 0, 0);
        }

        return searchInner(node.childPointers[i], key);
    }

    public String search(String key) throws IOException {
        long[] versionOut = new long[1];
        return search(key, versionOut);
    }

    public String search(String key, long[] outVersion) throws IOException {
        SearchResult res = searchInner(rootOffset, key);
        if (res.found) {
            datFile.seek(res.valueOffset);
            byte[] valueBytes = new byte[(int) res.valueLength];
            datFile.readFully(valueBytes);
            outVersion[0] = res.version;
            return new String(valueBytes, StandardCharsets.UTF_8);
        }
        outVersion[0] = 0;
        return "NULL";
    }

    private void splitChild(long parentOffset, BTreeNode parent, int i, long childOffset, BTreeNode child)
            throws IOException {
        long newChildOffset = allocateNode();
        BTreeNode newChild = new BTreeNode();
        newChild.isLeaf = child.isLeaf;

        int t = (MAX_KEYS + 1) / 2;
        newChild.numKeys = MAX_KEYS - t;

        for (int j = 0; j < newChild.numKeys; j++) {
            System.arraycopy(child.keys[j + t], 0, newChild.keys[j], 0, MAX_KEY_LEN);
            newChild.valueOffsets[j] = child.valueOffsets[j + t];
            newChild.valueLengths[j] = child.valueLengths[j + t];
            newChild.isDeleted[j] = child.isDeleted[j + t];
            newChild.versions[j] = child.versions[j + t];
        }

        if (!child.isLeaf) {
            for (int j = 0; j <= newChild.numKeys; j++) {
                newChild.childPointers[j] = child.childPointers[j + t];
            }
        }

        child.numKeys = t - 1;

        for (int j = parent.numKeys; j >= i + 1; j--) {
            parent.childPointers[j + 1] = parent.childPointers[j];
        }
        parent.childPointers[i + 1] = newChildOffset;

        for (int j = parent.numKeys - 1; j >= i; j--) {
            System.arraycopy(parent.keys[j], 0, parent.keys[j + 1], 0, MAX_KEY_LEN);
            parent.valueOffsets[j + 1] = parent.valueOffsets[j];
            parent.valueLengths[j + 1] = parent.valueLengths[j];
            parent.isDeleted[j + 1] = parent.isDeleted[j];
            parent.versions[j + 1] = parent.versions[j];
        }

        System.arraycopy(child.keys[t - 1], 0, parent.keys[i], 0, MAX_KEY_LEN);
        parent.valueOffsets[i] = child.valueOffsets[t - 1];
        parent.valueLengths[i] = child.valueLengths[t - 1];
        parent.isDeleted[i] = child.isDeleted[t - 1];
        parent.versions[i] = child.versions[t - 1];
        parent.numKeys++;

        child.write(idxFile, childOffset);
        newChild.write(idxFile, newChildOffset);
        parent.write(idxFile, parentOffset);
    }

    private void insertNonFull(long nodeOffset, BTreeNode node, String key, long valOffset, long valLen, long version)
            throws IOException {
        int i = node.numKeys - 1;

        for (int j = 0; j < node.numKeys; j++) {
            if (key.equals(getKeyString(node.keys[j]))) {
                if (version >= node.versions[j]) {
                    node.valueOffsets[j] = valOffset;
                    node.valueLengths[j] = valLen;
                    node.isDeleted[j] = false;
                    node.versions[j] = version;
                    node.write(idxFile, nodeOffset);
                }
                return;
            }
        }

        if (node.isLeaf) {
            while (i >= 0 && key.compareTo(getKeyString(node.keys[i])) < 0) {
                System.arraycopy(node.keys[i], 0, node.keys[i + 1], 0, MAX_KEY_LEN);
                node.valueOffsets[i + 1] = node.valueOffsets[i];
                node.valueLengths[i + 1] = node.valueLengths[i];
                node.isDeleted[i + 1] = node.isDeleted[i];
                node.versions[i + 1] = node.versions[i];
                i--;
            }

            setKeyString(node.keys[i + 1], key);
            node.valueOffsets[i + 1] = valOffset;
            node.valueLengths[i + 1] = valLen;
            node.isDeleted[i + 1] = false;
            node.versions[i + 1] = version;
            node.numKeys++;

            node.write(idxFile, nodeOffset);
        } else {
            while (i >= 0 && key.compareTo(getKeyString(node.keys[i])) < 0) {
                i--;
            }
            i++;

            BTreeNode child = new BTreeNode();
            child.read(idxFile, node.childPointers[i]);

            if (child.numKeys == MAX_KEYS) {
                splitChild(nodeOffset, node, i, node.childPointers[i], child);
                if (key.compareTo(getKeyString(node.keys[i])) > 0) {
                    i++;
                }
            }

            child.read(idxFile, node.childPointers[i]);
            insertNonFull(node.childPointers[i], child, key, valOffset, valLen, version);
        }
    }

    public void insert(String key, String value) throws IOException {
        insert(key, value, 0);
    }

    public void insert(String key, String value, long version) throws IOException {
        if (key.length() >= MAX_KEY_LEN) {
            System.err.println("Key too long!");
            return;
        }

        long valOffset = datFile.length();
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        long valLen = valueBytes.length;
        datFile.seek(valOffset);
        datFile.write(valueBytes);

        BTreeNode root = new BTreeNode();
        root.read(idxFile, rootOffset);

        if (root.numKeys == MAX_KEYS) {
            long newRootOffset = allocateNode();
            BTreeNode newRoot = new BTreeNode();
            newRoot.isLeaf = false;
            newRoot.numKeys = 0;
            newRoot.childPointers[0] = rootOffset;

            splitChild(newRootOffset, newRoot, 0, rootOffset, root);

            int i = 0;
            if (getKeyString(newRoot.keys[0]).compareTo(key) < 0) {
                i++;
            }

            BTreeNode child = new BTreeNode();
            child.read(idxFile, newRoot.childPointers[i]);
            insertNonFull(newRoot.childPointers[i], child, key, valOffset, valLen, version);

            rootOffset = newRootOffset;
            idxFile.seek(ROOT_POINTER_OFFSET);
            idxFile.writeLong(rootOffset);
        } else {
            insertNonFull(rootOffset, root, key, valOffset, valLen, version);
        }
    }

    public boolean remove(String key) throws IOException {
        long currOffset = rootOffset;
        while (true) {
            BTreeNode node = new BTreeNode();
            node.read(idxFile, currOffset);

            int i = 0;
            while (i < node.numKeys && key.compareTo(getKeyString(node.keys[i])) > 0) {
                i++;
            }

            if (i < node.numKeys && key.equals(getKeyString(node.keys[i]))) {
                if (!node.isDeleted[i]) {
                    node.isDeleted[i] = true;
                    node.write(idxFile, currOffset);
                    return true;
                }
                return false;
            }

            if (node.isLeaf) {
                return false;
            }

            currOffset = node.childPointers[i];
        }
    }

    private void collectAll(long nodeOffset, List<Entry> results) throws IOException {
        BTreeNode node = new BTreeNode();
        node.read(idxFile, nodeOffset);

        for (int i = 0; i < node.numKeys; i++) {
            if (!node.isLeaf) {
                collectAll(node.childPointers[i], results);
            }

            if (!node.isDeleted[i]) {
                datFile.seek(node.valueOffsets[i]);
                byte[] valueBytes = new byte[(int) node.valueLengths[i]];
                datFile.readFully(valueBytes);
                String value = new String(valueBytes, StandardCharsets.UTF_8);
                results.add(new Entry(getKeyString(node.keys[i]), value, node.versions[i]));
            }
        }

        if (!node.isLeaf) {
            collectAll(node.childPointers[node.numKeys], results);
        }
    }

    public List<Entry> getAllEntries() throws IOException {
        List<Entry> results = new ArrayList<>();
        collectAll(rootOffset, results);
        return results;
    }
}
