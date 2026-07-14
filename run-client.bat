@echo off
chcp 65001 >nul
java -cp target\mini-database-1.0.0.jar com.database.client.ClientMain %*
