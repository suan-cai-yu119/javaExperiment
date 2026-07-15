@echo off
chcp 65001 >nul 2>&1
title MiniDB Client
java -cp "target\mini-database-1.0.0.jar" com.database.client.ClientMain 127.0.0.1 9527
pause
