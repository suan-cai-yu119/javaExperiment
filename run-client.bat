@echo off
chcp 65001 >nul
title 迷你数据库客户端
java -cp "target\mini-database-1.0.0.jar" com.database.client.ClientMain 127.0.0.1 9527
pause
