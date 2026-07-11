 package com.database.client;
 
 import com.database.common.Protocol;
 
 /**
  * 客户端启动入口
  */
 public class ClientMain {
     public static void main(String[] args) {
         String host = "127.0.0.1";
         int port = Protocol.DEFAULT_PORT;
         
         if (args.length > 0) host = args[0];
         if (args.length > 1) {
             try { port = Integer.parseInt(args[1]); } 
             catch (NumberFormatException ignored) {}
         }
         
         Client client = new Client(host, port);
         if (client.connect()) {
             ConsoleUI ui = new ConsoleUI(client);
             ui.start();
         } else {
             System.err.println("无法连接到服务器 " + host + ":" + port);
             System.exit(1);
         }
     }
 }
