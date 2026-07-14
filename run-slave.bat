@echo off
chcp 65001 >nul 2>&1
java -jar "target\mini-database-1.0.0.jar" 9528 --cluster --role slave --master-host 127.0.0.1 --master-port 9527
