@echo off
chcp 65001 >nul 2>&1
echo 启动客户端...
echo 提示：如果连接集群，请在命令后加 --cluster 参数
java -cp "target\mini-database-1.0.0.jar" com.database.client.ClientMain %*
