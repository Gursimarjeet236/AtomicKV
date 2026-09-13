#!/bin/bash
echo "Building AtomicKV Java Version..."

mkdir -p out
javac -d out src/*.java

if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

echo "Build successful!"
echo ""
echo "To run the server:"
echo "java -cp out AtomicKVServer 8081"
echo ""
echo "To run the B-Tree test:"
echo "java -cp out TestBTree"
