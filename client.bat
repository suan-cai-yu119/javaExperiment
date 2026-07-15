@echo off
chcp 65001 >nul
title 迷你数据库客户端

:menu
cls
echo === 迷你数据库客户端 ===
echo.
echo   1. 集群选择模式（连接到 9527，展示所有节点）
echo   2. 直连节点 127.0.0.1:9527
echo   3. 直连节点 127.0.0.1:9528
echo   4. 直连节点 127.0.0.1:9529
echo   5. 退出
echo.
set /p choice="请输入选项 (1/2/3/4/5): "

if "%choice%"=="1" (
    java -cp "target\mini-database-1.0.0.jar" com.database.client.ClientMain 127.0.0.1 9527 --cluster
    pause
    goto menu
)
if "%choice%"=="2" (
    java -cp "target\mini-database-1.0.0.jar" com.database.client.ClientMain 127.0.0.1 9527
    pause
    goto menu
)
if "%choice%"=="3" (
    java -cp "target\mini-database-1.0.0.jar" com.database.client.ClientMain 127.0.0.1 9528
    pause
    goto menu
)
if "%choice%"=="4" (
    java -cp "target\mini-database-1.0.0.jar" com.database.client.ClientMain 127.0.0.1 9529
    pause
    goto menu
)
if "%choice%"=="5" exit /b

echo 无效选项
pause
goto menu
