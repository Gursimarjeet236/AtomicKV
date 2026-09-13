
public class TestBTree {
    public static void main(String[] args) {
        try {
            BTree tree = new BTree();
            System.out.println("Tree created");

            System.out.println("Inserting 0");
            tree.insert("key0", "value0");
            System.out.println("Inserted 0");

            System.out.println("Inserting 1");
            tree.insert("key1", "value1");
            System.out.println("Inserted 1");

            System.out.println("Inserting 2");
            tree.insert("key2", "value2");
            System.out.println("Inserted 2");

            System.out.println("Inserting 3");
            tree.insert("key3", "value3");
            System.out.println("Inserted 3");

            tree.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
