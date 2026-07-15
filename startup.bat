@echo off
chcp 65001 >nul 2>&1
title MiniDB Node Launcher

set "BASE=%~dp0"
set "JAR_PATH=%BASE%target\mini-database-1.0.0.jar"

if not exist "%JAR_PATH%" (
    echo [ERROR] JAR not found. Run: mvn clean package
    pause
    exit /b 1
)

:start
cls
echo ===================== MiniDB Node Launcher =====================
echo.

set /p PORT="Enter port [9527]: "
if "%PORT%"=="" set PORT=9527

set "DEFAULT_PEERS=127.0.0.1:%PORT%"
set /p PEERS="Enter peers (comma-separated host:port) [%DEFAULT_PEERS%]: "
if "%PEERS%"=="" set PEERS=%DEFAULT_PEERS%

start "Node-%PORT%" cmd /c "java -jar ""%JAR_PATH%"" %PORT% --cluster --peers %PEERS% ^& pause"

echo.
echo [OK] Node %PORT% started. Peers: %PEERS%
echo.
echo   1. Start another node
echo   2. Exit
echo.
set /p choice="Enter 1/2: "

if "%choice%"=="1" goto start
exit /b
