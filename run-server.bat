@echo off
chcp 65001 >nul
REM 迷你数据库服务器启动脚本
REM 集群模式用法:
REM   run-server.bat --cluster --role master
REM   run-server.bat --cluster --role slave --master-host 127.0.0.1 --master-port 9527
java -jar target\mini-database-1.0.0.jar %*
