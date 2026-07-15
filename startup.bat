@echo off
chcp 65001 >nul
title 迷你数据库系统 - 集群启动

echo === 迷你数据库系统 - 集群节点启动脚本 ===
echo 所有节点平等启动，集群内部自动选举主从节点
echo.
echo 注意：请先运行 mvn clean package 编译打包
echo.

set "CLUSTER_PEERS=127.0.0.1:9527,127.0.0.1:9528,127.0.0.1:9529"
set "JAR_PATH=target\mini-database-1.0.0.jar"

if not exist "%JAR_PATH%" (
    echo [错误] 未找到 %JAR_PATH%
    echo 请先运行: mvn clean package
    pause
    exit /b 1
)

echo 启动节点 1 (端口 9527) ...
start "Node-9527" cmd /c "java -jar "%JAR_PATH%" 9527 --cluster --peers %CLUSTER_PEERS% ^& pause"

timeout /t 2 /nobreak >nul

echo 启动节点 2 (端口 9528) ...
start "Node-9528" cmd /c "java -jar "%JAR_PATH%" 9528 --cluster --peers %CLUSTER_PEERS% ^& pause"

timeout /t 2 /nobreak >nul

echo 启动节点 3 (端口 9529) ...
start "Node-9529" cmd /c "java -jar "%JAR_PATH%" 9529 --cluster --peers %CLUSTER_PEERS% ^& pause"

echo.
echo ==========================================
echo 所有节点已启动！
echo 每个节点运行在独立的窗口中。
echo 集群将自动选举主节点和从节点。
echo.
echo 启动客户端(集群选择模式):
echo   java -cp target/mini-database-1.0.0.jar com.database.client.ClientMain 127.0.0.1 9527 --cluster
echo.
echo 启动客户端(直连模式):
echo   java -cp target/mini-database-1.0.0.jar com.database.client.ClientMain 127.0.0.1 9527
echo.
pause
