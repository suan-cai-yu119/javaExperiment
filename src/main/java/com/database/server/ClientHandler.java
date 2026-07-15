 package com.database.server;
 
import com.database.cluster.ClusterManager;
import com.database.cluster.ClusterReplicator;
import com.database.common.*;
import com.database.core.Database;

import java.io.*;
import java.net.Socket;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.database.cluster.ClusterReplicator.WalEntry;
 
 /**
  * 客户端处理器 - 每个客户端连接对应一个线程
  * 体现多线程编程
  */
 public class ClientHandler implements Runnable {
     private static final Logger LOGGER = Logger.getLogger(ClientHandler.class.getName());
     
      private final Socket socket;
      private final Database database;
      private final int clientId;
      private final ClusterManager clusterManager;
      private volatile boolean running;
      
      public ClientHandler(Socket socket, Database database, int clientId, ClusterManager clusterManager) {
          this.socket = socket;
          this.database = database;
          this.clientId = clientId;
          this.clusterManager = clusterManager;
          this.running = true;
      }

     @Override
     public void run() {
         LOGGER.info("客户端 #" + clientId + " 已连接: " + socket.getRemoteSocketAddress());

         try (ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
              ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream())) {

             // 发送欢迎信息
             oos.writeObject(Response.ok("欢迎使用迷你数据库系统 v" + Protocol.VERSION +
                     " | 客户端 #" + clientId));
             oos.flush();

             while (running) {
                 try {
                     Request request = (Request) ois.readObject();
                     if (request == null) break;

                     Response response = processRequest(request);
                     oos.writeObject(response);
                     oos.flush();

                     if (request.getCommand() == CommandType.QUIT) {
                         break;
                     }
                 } catch (EOFException e) {
                     LOGGER.info("客户端 #" + clientId + " 正常断开连接");
                     break;
                 } catch (ClassNotFoundException e) {
                     LOGGER.warning("客户端 #" + clientId + " 发送了无效的请求格式");
                     oos.writeObject(Response.fail("无效的请求格式"));
                 } catch (java.net.SocketException e) {
                     if ("Connection reset".equals(e.getMessage())) {
                         LOGGER.warning("客户端 #" + clientId + " 强制断开连接（Connection reset）");
                     } else {
                         LOGGER.log(Level.WARNING, "客户端 #" + clientId + " 网络异常", e);
                     }
                     break;
                 }
             }
         } catch (IOException e) {
             LOGGER.log(Level.WARNING, "客户端 #" + clientId + " 连接异常", e);
         } finally {
             close();
         }

         LOGGER.info("客户端 #" + clientId + " 已断开连接");
     }
     
       private Response checkWriteAllowed() {
           if (clusterManager != null && clusterManager.isClusterEnabled()) {
               if (!clusterManager.isMaster()) {
                   return Response.fail("当前节点不是主节点（只读），无法执行写操作");
               }
           }
           return null;
       }

      private void broadcastWrite(String op, String collection, String key, Object value) {
          if (clusterManager != null && clusterManager.isClusterEnabled()) {
              clusterManager.broadcastWal(new WalEntry(op, collection, key, value));
          }
      }

      /**
       * 处理客户端请求 - 使用反射和策略模式思想
       */
      private Response processRequest(Request request) {
          switch (request.getCommand()) {
              // 数据库操作
               case CREATE_DATABASE: {
                   Response deny = checkWriteAllowed();
                   if (deny != null) return deny;
                   return database.createDatabase(request.getArgs()[0]);
               }
               case DROP_DATABASE: {
                   Response deny = checkWriteAllowed();
                   if (deny != null) return deny;
                   return database.dropDatabase(request.getArgs()[0]);
               }
              case LIST_DATABASES:
                  Set<String> dbs = database.listDatabases();
                  return Response.ok("数据库列表", dbs);
              case USE_DATABASE:
                  return database.useDatabase(request.getArgs()[0]);
                  
              // 集合操作
               case CREATE_COLLECTION: {
                   Response deny = checkWriteAllowed();
                   if (deny != null) return deny;
                   return database.createCollection(request.getArgs()[0]);
               }
               case DROP_COLLECTION: {
                   Response deny = checkWriteAllowed();
                   if (deny != null) return deny;
                   return database.dropCollection(request.getArgs()[0]);
               }
              case LIST_COLLECTIONS:
                  return Response.ok("集合列表", database.listCollections());

              // 键值操作（写操作需检查 SLAVE 权限并广播 WAL）
              case PUT: {
                  Response deny = checkWriteAllowed();
                  if (deny != null) return deny;
                  String putCol = request.getCollectionName() != null ?
                          request.getCollectionName() :
                          (request.getArgs().length > 0 ? request.getArgs()[0] : null);
                  String putKey = request.getKey() != null ?
                          request.getKey() :
                          (request.getArgs().length > 1 ? request.getArgs()[1] : null);
                  Object putVal = request.getValue();
                  Response putResp = database.put(putCol, putKey, putVal);
                  if (putResp.isSuccess()) broadcastWrite("PUT", putCol, putKey, putVal);
                  return putResp;
              }

              case GET:
                  String getCol = request.getCollectionName() != null ?
                          request.getCollectionName() :
                          (request.getArgs().length > 0 ? request.getArgs()[0] : null);
                  String getKey = request.getKey() != null ?
                          request.getKey() :
                          (request.getArgs().length > 1 ? request.getArgs()[1] : null);
                  return database.get(getCol, getKey);

               case DELETE: {
                   Response deny = checkWriteAllowed();
                   if (deny != null) return deny;
                   String delCol = request.getCollectionName() != null ?
                           request.getCollectionName() :
                           (request.getArgs().length > 0 ? request.getArgs()[0] : null);
                   if (request.getFilterField() != null) {
                       Response delResp = database.deleteWhere(delCol, request.getFilterField(), request.getFilterValue());
                       if (delResp.isSuccess()) broadcastWrite("DEL_WHERE", delCol, request.getFilterField(), request.getFilterValue());
                       return delResp;
                   }
                   String delKey = request.getKey() != null ?
                           request.getKey() :
                           (request.getArgs().length > 1 ? request.getArgs()[1] : null);
                   Response delResp = database.delete(delCol, delKey);
                   if (delResp.isSuccess()) broadcastWrite("DEL", delCol, delKey, null);
                   return delResp;
               }

               case UPDATE: {
                   Response deny = checkWriteAllowed();
                   if (deny != null) return deny;
                   String updCol = request.getCollectionName() != null ?
                           request.getCollectionName() :
                           (request.getArgs().length > 0 ? request.getArgs()[0] : null);
                   if (request.getFilterField() != null) {
                       Response updResp = database.updateWhere(updCol, request.getFilterField(),
                               request.getFilterValue(), request.getValue());
                       if (updResp.isSuccess()) broadcastWrite("UPD_WHERE", updCol, request.getFilterField(), request.getFilterValue());
                       return updResp;
                   }
                   String updKey = request.getKey() != null ?
                           request.getKey() :
                           (request.getArgs().length > 1 ? request.getArgs()[1] : null);
                   Object updVal = request.getValue();
                   Response updResp = database.update(updCol, updKey, updVal);
                   if (updResp.isSuccess()) broadcastWrite("UPD", updCol, updKey, updVal);
                   return updResp;
               }

               case BATCH_PUT: {
                   Response deny = checkWriteAllowed();
                   if (deny != null) return deny;
                   String bpCol = request.getCollectionName() != null ?
                           request.getCollectionName() :
                           (request.getArgs().length > 0 ? request.getArgs()[0] : null);
                   Response bpResp = database.batchPut(bpCol, request.getBatchData());
                   if (bpResp.isSuccess()) broadcastWrite("BATCH_PUT", bpCol, null, request.getBatchData());
                   return bpResp;
               }

               case BATCH_UPDATE: {
                   Response deny = checkWriteAllowed();
                   if (deny != null) return deny;
                   String buCol = request.getCollectionName() != null ?
                           request.getCollectionName() :
                           (request.getArgs().length > 0 ? request.getArgs()[0] : null);
                   Response buResp = database.batchUpdate(buCol, request.getBatchData());
                   if (buResp.isSuccess()) broadcastWrite("BATCH_UPD", buCol, null, request.getBatchData());
                   return buResp;
               }

               case SCAN:
                  String scanCol = request.getCollectionName() != null ?
                          request.getCollectionName() :
                          (request.getArgs().length > 0 ? request.getArgs()[0] : null);
                  return database.scan(scanCol);

              case LIST_KEYS:
                  String listCol = request.getCollectionName() != null ?
                          request.getCollectionName() :
                          (request.getArgs().length > 0 ? request.getArgs()[0] : null);
                  return database.scan(listCol);

               // 索引
               case CREATE_INDEX: {
                   Response deny = checkWriteAllowed();
                   if (deny != null) return deny;
                   String ciCol = request.getCollectionName() != null ?
                           request.getCollectionName() :
                           (request.getArgs().length > 0 ? request.getArgs()[0] : null);
                   String ciField = request.getKey() != null ? request.getKey() :
                           (request.getArgs().length > 1 ? request.getArgs()[1] : null);
                   return database.createIndex(ciCol, ciField);
               }
               case DROP_INDEX: {
                   Response deny = checkWriteAllowed();
                   if (deny != null) return deny;
                   String diCol = request.getCollectionName() != null ?
                           request.getCollectionName() :
                           (request.getArgs().length > 0 ? request.getArgs()[0] : null);
                   String diField = request.getKey() != null ? request.getKey() :
                           (request.getArgs().length > 1 ? request.getArgs()[1] : null);
                   return database.dropIndex(diCol, diField);
               }
               case LIST_INDEXES: {
                   String liCol = request.getCollectionName() != null ?
                           request.getCollectionName() :
                           (request.getArgs().length > 0 ? request.getArgs()[0] : null);
                   return database.listIndexes(liCol);
               }

               // 持久化
                case SAVE: {
                    Response deny = checkWriteAllowed();
                    if (deny != null) return deny;
                    return database.save();
                }
               case LOAD:
                   return database.load(request.getArgs().length > 0 ? request.getArgs()[0] : null);
                  
              // 系统
              case PING:
                  return Response.ok("PONG", System.currentTimeMillis());
              case QUIT:
                  return Response.ok("再见！");
               case HELP:
                   return getHelp();

               // 集群命令（未启用集群时给提示）
               case CLUSTER_STATUS:
                   if (clusterManager == null || !clusterManager.isClusterEnabled()) {
                       return Response.fail("集群模式未启用，请使用 --cluster 参数启动服务器");
                   }
                   return Response.ok("集群状态", clusterManager.getClusterStatus());
               case CLUSTER_JOIN:
                   if (clusterManager == null || !clusterManager.isClusterEnabled()) {
                       return Response.fail("集群模式未启用，请使用 --cluster 参数启动服务器");
                   }
                   String coordHost = request.getArgs().length > 0 ? request.getArgs()[0] : "127.0.0.1";
                   int coordPort = request.getArgs().length > 1 ?
                           Integer.parseInt(request.getArgs()[1]) : Protocol.DEFAULT_PORT;
                   clusterManager.joinCluster(coordHost, coordPort);
                   return Response.ok("已加入集群");
               case CLUSTER_LEAVE:
                   if (clusterManager == null || !clusterManager.isClusterEnabled()) {
                       return Response.fail("集群模式未启用，请使用 --cluster 参数启动服务器");
                   }
                   clusterManager.leaveCluster();
                   return Response.ok("已离开集群");
               default:
                   return Response.fail("未知命令");
          }
      }
     
     private Response getHelp() {
         String help = """
                 ========== 迷你数据库系统 命令帮助 ==========
                 
                 -- 数据库操作 --
                 CREATE DATABASE <name>   创建数据库
                 DROP DATABASE <name>     删除数据库
                 LIST DATABASES           列出所有数据库
                 USE DATABASE <name>      切换/创建数据库
                 
                 -- 集合操作 --
                 CREATE COLLECTION <name> 创建集合
                 DROP COLLECTION <name>   删除集合
                 LIST COLLECTIONS         列出所有集合
                 
                 -- 键值操作 --
                 PUT <col> <key> <value>   插入键值对
                 GET <col> <key>           获取值
                 DELETE <col> <key>        删除键值对
                 UPDATE <col> <key> <val>  更新值
                 SCAN <col>                扫描所有键值对
                 
                  -- 索引 --
                  CREATE INDEX <col> <f>   为字段创建索引
                  DROP INDEX <col> <f>     删除索引
                  LIST INDEXES <col>       查看索引列表

                  -- 缓存 --
                  CLEAR CACHE <col>        清除查询缓存

                  -- 持久化 --
                  SAVE                     保存数据
                  LOAD [dbname]            加载数据
                 
                 -- 系统 --
                 HELP                     显示帮助
                 PING                     心跳检测
                 QUIT                     断开连接
                 ========================================
                 """;
         return Response.ok(help);
     }
     
     public void close() {
         running = false;
         try {
             if (socket != null && !socket.isClosed()) {
                 socket.close();
             }
         } catch (IOException e) {
             LOGGER.log(Level.FINE, "关闭客户端连接异常", e);
         }
     }
     
     public int getClientId() { return clientId; }
 }
