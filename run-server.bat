@echo off
chcp 65001 >nul 2>&1
echo 启动单机服务器（非集群模式）...
java -jar "target\mini-database-1.0.0.jar" %*
