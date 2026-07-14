@echo off
chcp 65001 >nul
java -jar target\mini-database-1.0.0.jar --cluster --role master
