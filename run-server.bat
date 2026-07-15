@echo off
chcp 65001 >nul 2>&1
echo Starting standalone server (non-cluster) ...
java -jar "target\mini-database-1.0.0.jar" %*
