 package com.database.server;
 
 import com.database.common.*;
 import com.database.core.Database;
 
 import java.io.*;
 import java.net.Socket;
 import java.util.Set;
 import java.util.logging.Level;
 import java.util.logging.Logger;
 
 /**
  * 客户端处理器 - 每个客户端连接对应一个线程
  * 体现多线程编程
  */
 public class ClientHandler implements Runnable {
     private static final Logger LOGGER = Logger.getLogger(ClientHandler.class.getName());
     
     private final Socket socket;
     private final Database database;
     private final int clientId;
     private volatile boolean running;
     
     public ClientHandler(Socket socket, Database database, int clientId) {
         this.socket = socket;
         this.database = database;
         this.clientId = clientId;
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
     
     /**
      * 处理客户端请求 - 使用反射和策略模式思想
      */
     private Response processRequest(Request request) {
         switch (request.getCommand()) {
             // 数据库操作
             case CREATE_DATABASE:
                 Response createResp = database.createDatabase(request.getArgs()[0]);
                 return createResp;
             case DROP_DATABASE:
                 return database.dropDatabase(request.getArgs()[0]);
             case LIST_DATABASES:
                 Set<String> dbs = database.listDatabases();
                 return Response.ok("数据库列表", dbs);
             case USE_DATABASE:
                 return database.useDatabase(request.getArgs()[0]);
                 
             // 集合操作
             case CREATE_COLLECTION:
                 return database.createCollection(request.getArgs()[0]);
             case DROP_COLLECTION:
                 return database.dropCollection(request.getArgs()[0]);
             case LIST_COLLECTIONS:
                 return Response.ok("集合列表", database.listCollections());

             // 键值操作
             case PUT:
                 // 优先使用字段，如果没有则从 args 获取
                 String putCol = request.getCollectionName() != null ?
                         request.getCollectionName() :
                         (request.getArgs().length > 0 ? request.getArgs()[0] : null);
                 String putKey = request.getKey() != null ?
                         request.getKey() :
                         (request.getArgs().length > 1 ? request.getArgs()[1] : null);
                 Object putVal = request.getValue();
                 return database.put(putCol, putKey, putVal);

             case GET:
                 String getCol = request.getCollectionName() != null ?
                         request.getCollectionName() :
                         (request.getArgs().length > 0 ? request.getArgs()[0] : null);
                 String getKey = request.getKey() != null ?
                         request.getKey() :
                         (request.getArgs().length > 1 ? request.getArgs()[1] : null);
                 return database.get(getCol, getKey);

             case DELETE:
                 String delCol = request.getCollectionName() != null ?
                         request.getCollectionName() :
                         (request.getArgs().length > 0 ? request.getArgs()[0] : null);
                 String delKey = request.getKey() != null ?
                         request.getKey() :
                         (request.getArgs().length > 1 ? request.getArgs()[1] : null);
                 return database.delete(delCol, delKey);

             case UPDATE:
                 String updCol = request.getCollectionName() != null ?
                         request.getCollectionName() :
                         (request.getArgs().length > 0 ? request.getArgs()[0] : null);
                 String updKey = request.getKey() != null ?
                         request.getKey() :
                         (request.getArgs().length > 1 ? request.getArgs()[1] : null);
                 Object updVal = request.getValue();
                 return database.update(updCol, updKey, updVal);

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

             // 持久化
             case SAVE:
                 return database.save();
             case LOAD:
                 return database.load(request.getArgs().length > 0 ? request.getArgs()[0] : null);
                 
             // 系统
             case PING:
                 return Response.ok("PONG", System.currentTimeMillis());
             case QUIT:
                 return Response.ok("再见！");
             case HELP:
                 return getHelp();
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
