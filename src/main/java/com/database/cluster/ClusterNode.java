 package com.database.cluster;
 
 import java.io.Serial;
 import java.io.Serializable;
 import java.net.InetAddress;
 import java.net.UnknownHostException;
 import java.util.Objects;
 
 /**
  * 集群节点 - 代表集群中的一个节点
  * 体现：序列化、网络编程
  */
 public class ClusterNode implements Serializable, Comparable<ClusterNode> {
     @Serial
     private static final long serialVersionUID = 1L;
     
     private final String nodeId;
     private final String host;
     private final int port;
     private ClusterRole role;
     private long lastHeartbeat;
     private boolean alive;
     private long dataVersion;
     
     public ClusterNode(String nodeId, String host, int port, ClusterRole role) {
         this.nodeId = nodeId;
         this.host = host;
         this.port = port;
         this.role = role;
         this.alive = true;
         this.lastHeartbeat = System.currentTimeMillis();
     }
     
     public String getNodeId() { return nodeId; }
     public String getHost() { return host; }
     public int getPort() { return port; }
     public ClusterRole getRole() { return role; }
     public void setRole(ClusterRole role) { this.role = role; }
     public long getLastHeartbeat() { return lastHeartbeat; }
     public boolean isAlive() { return alive; }
     public void setAlive(boolean alive) { this.alive = alive; }
     public long getDataVersion() { return dataVersion; }
     public void setDataVersion(long dataVersion) { this.dataVersion = dataVersion; }
     
     public void updateHeartbeat() {
         this.lastHeartbeat = System.currentTimeMillis();
     }
     
     @Override
     public boolean equals(Object o) {
         if (this == o) return true;
         if (!(o instanceof ClusterNode that)) return false;
         return Objects.equals(nodeId, that.nodeId);
     }
     
     @Override
     public int hashCode() {
         return Objects.hash(nodeId);
     }
     
     @Override
     public int compareTo(ClusterNode other) {
         return this.nodeId.compareTo(other.nodeId);
     }
     
     @Override
     public String toString() {
         return "Node{" + nodeId + ", " + host + ":" + port + ", " + role + 
                ", alive=" + alive + ", version=" + dataVersion + "}";
     }
 }
