@echo off
chcp 65001 >nul 2>&1
title MiniDB Cluster

echo ===================== MiniDB Cluster =====================
echo Starting 3 nodes equally - cluster will auto-elect master.
echo.

set "CLUSTER_PEERS=127.0.0.1:9527,127.0.0.1:9528,127.0.0.1:9529"
set "JAR_PATH=target\mini-database-1.0.0.jar"

if not exist "%JAR_PATH%" (
    echo [ERROR] %JAR_PATH% not found. Run: mvn clean package
    pause
    exit /b 1
)

echo [1/3] Starting node 9527 ...
start "Node-9527" cmd /c "java -jar "%JAR_PATH%" 9527 --cluster --peers %CLUSTER_PEERS% ^& pause"

timeout /t 2 /nobreak >nul

echo [2/3] Starting node 9528 ...
start "Node-9528" cmd /c "java -jar "%JAR_PATH%" 9528 --cluster --peers %CLUSTER_PEERS% ^& pause"

timeout /t 2 /nobreak >nul

echo [3/3] Starting node 9529 ...
start "Node-9529" cmd /c "java -jar "%JAR_PATH%" 9529 --cluster --peers %CLUSTER_PEERS% ^& pause"

echo.
echo ============================================================
echo All 3 nodes started in separate windows.
echo Cluster is electing master/slave automatically.
echo.

:menu
echo Choose action:
echo   1. Start client (cluster selection mode)
echo   2. Direct connection to node 9527
echo   3. Exit
echo.
set /p choice="Enter 1/2/3: "

if "%choice%"=="1" (
    start "Client" cmd /c "java -cp "%JAR_PATH%" com.database.client.ClientMain 127.0.0.1 9527 --cluster ^& pause"
    goto menu
)
if "%choice%"=="2" (
    start "Client" cmd /c "java -cp "%JAR_PATH%" com.database.client.ClientMain 127.0.0.1 9527 ^& pause"
    goto menu
)
if "%choice%"=="3" exit /b

echo Invalid option
goto menu
