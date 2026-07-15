@echo off
chcp 65001 >nul 2>&1
title MiniDB Client

set "BASE=%~dp0"
set "JAR_PATH=%BASE%target\mini-database-1.0.0.jar"

if not exist "%JAR_PATH%" (
    echo [ERROR] %JAR_PATH% not found. Run: mvn clean package
    pause
    exit /b 1
)

:menu
cls
echo ================= MiniDB Client =================
echo.
echo   1. Cluster mode (connect to 9527, show all nodes)
echo   2. Direct connect to 127.0.0.1:9527
echo   3. Direct connect to 127.0.0.1:9528
echo   4. Direct connect to 127.0.0.1:9529
echo   5. Exit
echo.
set /p choice="Enter choice (1-5): "

if "%choice%"=="1" (
    java -cp "%JAR_PATH%" com.database.client.ClientMain 127.0.0.1 9527 --cluster
    pause
    goto menu
)
if "%choice%"=="2" (
    java -cp "%JAR_PATH%" com.database.client.ClientMain 127.0.0.1 9527
    pause
    goto menu
)
if "%choice%"=="3" (
    java -cp "%JAR_PATH%" com.database.client.ClientMain 127.0.0.1 9528
    pause
    goto menu
)
if "%choice%"=="4" (
    java -cp "%JAR_PATH%" com.database.client.ClientMain 127.0.0.1 9529
    pause
    goto menu
)
if "%choice%"=="5" exit /b

echo Invalid option
pause
goto menu
