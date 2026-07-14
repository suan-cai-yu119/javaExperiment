@echo off
chcp 65001 >nul
REM 从节点启动脚本
REM 默认连接到主节点 127.0.0.1:9527，从节点自身监听 9528
java -jar target\mini-database-1.0.0.jar 9528 --cluster --role slave --master-host 127.0.0.1 --master-port 9527
