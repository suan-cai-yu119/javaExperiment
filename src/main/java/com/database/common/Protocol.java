 package com.database.common;
 
 /**
  * 通信协议常量
  */
 public final class Protocol {
     private Protocol() {}
     
     /** 默认端口 */
     public static final int DEFAULT_PORT = 9527;
     
     /** 缓冲区大小 */
     public static final int BUFFER_SIZE = 65536;
     
     /** 超时时间（毫秒） */
     public static final int TIMEOUT = 5000;
     
     /** 最大连接数 */
     public static final int MAX_CONNECTIONS = 50;
     
     /** 协议版本 */
     public static final String VERSION = "1.0.0";
     
     /** 消息分隔符 */
     public static final String DELIMITER = "\r\n";
     
      /** 集群心跳间隔（毫秒） */
      public static final long HEARTBEAT_INTERVAL = 3000;
      
      /** 节点超时时间（毫秒） */
      public static final long NODE_TIMEOUT = 10000;

      /** HTTP REST API 端口 */
      public static final int HTTP_PORT = 8080;

      /** 集群内部通信端口偏移量（clientPort + OFFSET） */
      public static final int CLUSTER_PORT_OFFSET = 10000;
  }
