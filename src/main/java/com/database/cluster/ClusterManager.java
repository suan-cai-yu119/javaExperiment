 package com.database.cluster;
 
 import com.database.common.Protocol;
 import com.database.core.Database;
 
 import java.util.*;
 import java.util.concurrent.*;
 import java.util.logging.Logger;
 
 /**
  * 集群管理器 - 管理集群节点
  * 体现：多线程、设计模式（观察者模式）、网络IO
  */
 public class ClusterManager {
     private static final Logger LOGGER = Logger.getLogger(ClusterManager.class.getName());
     
     private final Database database;
     private final ConcurrentHashMap<String, ClusterNode> nodes;
     private final String currentNodeId;
     private ClusterRole currentRole;
     private volatile boolean running;
     private ScheduledExecutorService heartbeatExecutor;
     
     public ClusterManager(Database database) {
         this.database = database;
         this.nodes = new ConcurrentHashMap<>();
         this.currentNodeId = "node-" + UUID.randomUUID().toString().substring(0, 8);
         this.currentRole = ClusterRole.MASTER;
     }
     
     /**
      * 加入集群
      */
     public synchronized boolean joinCluster(String coordinatorHost, int coordinatorPort) {
         // 添加自身
         ClusterNode self = new ClusterNode(currentNodeId, "127.0.0.1", 
             Protocol.DEFAULT_PORT, currentRole);
         nodes.put(currentNodeId, self);
         
         startHeartbeat();
         System.out.println("✓ 节点 " + currentNodeId + " 已加入集群");
         return true;
     }
     
     /**
      * 离开集群
      */
     public synchronized void leaveCluster() {
         running = false;
         if (heartbeatExecutor != null) {
             heartbeatExecutor.shutdown();
         }
         nodes.clear();
         System.out.println("✓ 已离开集群");
     }
     
     /**
      * 启动心跳检测
      */
     private void startHeartbeat() {
         running = true;
         heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
             Thread t = new Thread(r, "cluster-heartbeat");
             t.setDaemon(true);
             return t;
         });
         
         heartbeatExecutor.scheduleAtFixedRate(() -> {
             if (!running) return;
             // 更新自身心跳
             ClusterNode self = nodes.get(currentNodeId);
             if (self != null) {
                 self.updateHeartbeat();
             }
             // 检测其他节点
             long now = System.currentTimeMillis();
             for (ClusterNode node : nodes.values()) {
                 if (!node.getNodeId().equals(currentNodeId)) {
                     if (now - node.getLastHeartbeat() > Protocol.NODE_TIMEOUT) {
                         node.setAlive(false);
                         System.out.println("⚠ 节点 " + node.getNodeId() + " 已超时");
                     }
                 }
             }
         }, 0, Protocol.HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
     }
     
     /**
      * 选举主节点（动态配置方案）
      */
     public ClusterRole electMaster() {
         // 简单的选举：选择 nodeId 最小的存活节点作为主节点
         Optional<ClusterNode> elected = nodes.values().stream()
             .filter(ClusterNode::isAlive)
             .min(Comparator.comparing(ClusterNode::getNodeId));
         
         if (elected.isPresent()) {
             ClusterNode master = elected.get();
             master.setRole(ClusterRole.MASTER);
             
             // 其他节点设为从节点
             nodes.values().stream()
                 .filter(n -> !n.getNodeId().equals(master.getNodeId()) && n.isAlive())
                 .forEach(n -> n.setRole(ClusterRole.SLAVE));
             
             if (master.getNodeId().equals(currentNodeId)) {
                 currentRole = ClusterRole.MASTER;
             } else {
                 currentRole = ClusterRole.SLAVE;
             }
             
             System.out.println("→ 选举完成，主节点: " + master.getNodeId());
         }
         return currentRole;
     }
     
     public Map<String, Object> getClusterStatus() {
         Map<String, Object> status = new LinkedHashMap<>();
         status.put("currentNode", currentNodeId);
         status.put("currentRole", currentRole);
         status.put("totalNodes", nodes.size());
         
         List<Map<String, Object>> nodeList = new ArrayList<>();
         for (ClusterNode node : nodes.values()) {
             Map<String, Object> nodeInfo = new LinkedHashMap<>();
             nodeInfo.put("id", node.getNodeId());
             nodeInfo.put("host", node.getHost() + ":" + node.getPort());
             nodeInfo.put("role", node.getRole());
             nodeInfo.put("alive", node.isAlive());
             nodeList.add(nodeInfo);
         }
         status.put("nodes", nodeList);
         return status;
     }
     
     public ClusterRole getCurrentRole() { return currentRole; }
     public String getCurrentNodeId() { return currentNodeId; }
 }
