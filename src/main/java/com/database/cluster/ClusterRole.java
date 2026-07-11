 package com.database.cluster;
 
 /**
  * 集群节点角色
  */
 public enum ClusterRole {
     MASTER("主节点 - 可读写"),
     SLAVE("从节点 - 只读备份"),
     CANDIDATE("候选节点 - 可参与选举");
     
     private final String description;
     
     ClusterRole(String description) {
         this.description = description;
     }
     
     public String getDescription() { return description; }
 }
