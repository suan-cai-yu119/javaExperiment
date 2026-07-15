@echo off
chcp 65001 >nul 2>&1
title MiniDB Client

setlocal enabledelayedexpansion

set "BASE=%~dp0"
set "JAR_PATH=%BASE%target\mini-database-1.0.0.jar"

if not exist "%JAR_PATH%" (
    echo [ERROR] JAR not found. Run: mvn clean package
    pause
    exit /b 1
)

:menu
cls
echo ================= MiniDB Client =================
echo.
echo   1. Cluster mode (connect to a node, show all nodes)
echo   2. Direct connect (enter address manually)
echo   3. Exit
echo.
set /p choice="Enter choice (1-3): "

if "%choice%"=="1" (
    set "ADDR="
    set /p ADDR="Enter node address [127.0.0.1:9527]: "
    if not defined ADDR set ADDR=127.0.0.1:9527
    for /f "tokens=1,2 delims=:" %%a in ("!ADDR!") do (
        set "HOST=%%a"
        set "PORT=%%b"
    )
    if not defined PORT set "PORT=!HOST!" & set "HOST=127.0.0.1"
    java -cp "%JAR_PATH%" com.database.client.ClientMain !HOST! !PORT! --cluster
    pause
    goto menu
)
if "%choice%"=="2" (
    set "ADDR="
    set /p ADDR="Enter node address [127.0.0.1:9527]: "
    if not defined ADDR set ADDR=127.0.0.1:9527
    for /f "tokens=1,2 delims=:" %%a in ("!ADDR!") do (
        set "HOST=%%a"
        set "PORT=%%b"
    )
    if not defined PORT set "PORT=!HOST!" & set "HOST=127.0.0.1"
    java -cp "%JAR_PATH%" com.database.client.ClientMain !HOST! !PORT!
    pause
    goto menu
)
if "%choice%"=="3" exit /b

echo Invalid option
pause
goto menu