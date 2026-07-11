 package com.database.client;
 
 import com.database.common.*;
 
 import java.io.*;
 import java.net.Socket;
 import java.util.logging.Logger;
 
 /**
  * 数据库客户端 - 连接到服务器的终端客户端
  * 体现：网络 IO、对象序列化
  */
 public class Client {
     private static final Logger LOGGER = Logger.getLogger(Client.class.getName());
     
     private final String host;
     private final int port;
     private Socket socket;
     private ObjectOutputStream oos;
     private ObjectInputStream ois;
     private boolean connected;
     
     public Client(String host, int port) {
         this.host = host;
         this.port = port;
     }
     
     /**
      * 连接到服务器
      */
     public boolean connect() {
         try {
             socket = new Socket(host, port);
             socket.setSoTimeout(Protocol.TIMEOUT);
             
             // 注意：必须先获取 OOS，再获取 OIS（避免死锁）
             oos = new ObjectOutputStream(socket.getOutputStream());
             ois = new ObjectInputStream(socket.getInputStream());
             
             // 读取欢迎信息
             Response welcome = (Response) ois.readObject();
             System.out.println("✓ " + welcome.getMessage());
             
             connected = true;
             return true;
         } catch (IOException | ClassNotFoundException e) {
             System.err.println("✗ 连接服务器失败: " + e.getMessage());
             return false;
         }
     }
     
     /**
      * 发送请求并接收响应
      */
     public Response sendRequest(Request request) {
         if (!connected) {
             return Response.fail("未连接到服务器");
         }
         
         try {
             oos.writeObject(request);
             oos.flush();
             return (Response) ois.readObject();
         } catch (IOException | ClassNotFoundException e) {
             connected = false;
             return Response.fail("连接断开: " + e.getMessage());
         }
     }
     
     /**
      * 便捷方法：发送简单命令
      */
     public Response sendCommand(CommandType command, String... args) {
         return sendRequest(new Request(command, args));
     }
     
     /**
      * 断开连接
      */
     public void disconnect() {
         try {
             if (connected) {
                 sendCommand(CommandType.QUIT);
             }
         } catch (Exception ignored) {}
         
         try {
             if (oos != null) oos.close();
             if (ois != null) ois.close();
             if (socket != null) socket.close();
         } catch (IOException ignored) {}
         connected = false;
     }
     
     public boolean isConnected() { return connected; }
 }
