@echo off
chcp 65001 >nul 2>&1
java -cp "target\mini-database-1.0.0.jar" com.database.client.ClientMain %*
