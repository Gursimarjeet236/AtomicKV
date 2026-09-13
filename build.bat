@echo off
echo Building AtomicKV Java Version...

mkdir out 2>nul
javac -d out src\*.java

if %ERRORLEVEL% NEQ 0 (
    echo Build failed!
    exit /b %ERRORLEVEL%
)

echo Build successful!
echo.
echo To run the server:
echo java -cp out AtomicKVServer 8081
echo.
echo To run the B-Tree test:
echo java -cp out TestBTree
