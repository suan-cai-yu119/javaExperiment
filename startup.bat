@echo off
chcp 65001 >nul 2>&1
title MiniDB Node Launcher

set "CLUSTER_PEERS=127.0.0.1:9527,127.0.0.1:9528,127.0.0.1:9529"
set "JAR_PATH=target\mini-database-1.0.0.jar"

if not exist "%JAR_PATH%" (
    echo [ERROR] %JAR_PATH% not found. Run: mvn clean package
    pause
    exit /b 1
)

:start
cls
echo ===================== MiniDB Node Launcher =====================
echo.
echo This tool starts one cluster node at a time.
echo Run it multiple times to start multiple nodes.
echo Cluster peers: %CLUSTER_PEERS%
echo.

set /p PORT="Enter port [9527]: "
if "%PORT%"=="" set PORT=9527

start "Node-%PORT%" cmd /c "java -jar "%JAR_PATH%" %PORT% --cluster --peers %CLUSTER_PEERS% ^& pause"

echo.
echo [OK] Node %PORT% started in a new window.
echo.
echo Choose action:
echo   1. Start another node
echo   2. Exit
echo.
set /p choice="Enter 1/2: "

if "%choice%"=="1" goto start
exit /b
