 package com.database.common;
 
 /**
  * 命令类型枚举 - 定义所有支持的数据库操作命令
  */
 public enum CommandType {
     // 数据库操作
     CREATE_DATABASE,
     DROP_DATABASE,
     LIST_DATABASES,
     USE_DATABASE,
     
     // 集合（表）操作
     CREATE_COLLECTION,
     DROP_COLLECTION,
     LIST_COLLECTIONS,
     
      // 键值操作
      PUT,
      GET,
      DELETE,
      UPDATE,
      SCAN,
      LIST_KEYS,
      BATCH_PUT,
      BATCH_UPDATE,
     
     // 持久化
     SAVE,
     LOAD,
     
     // 集群管理
     CLUSTER_JOIN,
     CLUSTER_LEAVE,
     CLUSTER_STATUS,
     CLUSTER_SYNC,
     
     // 系统
     PING,
     QUIT,
     HELP,
     UNKNOWN
 }
