package com.database.cluster;

import com.database.common.Protocol;
import com.database.core.Database;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class ClusterManager {
    private static final Logger LOGGER = Logger.getLogger(ClusterManager.class.getName());

    private final Database database;
    private final ConcurrentHashMap<String, ClusterNode> nodes;
    private final String currentNodeId;
    private ClusterRole currentRole;
    private volatile boolean running;
    private ScheduledExecutorService heartbeatExecutor;
    private ClusterReplicator replicator;
    private boolean clusterEnabled;

    public ClusterManager(Database database) {
        this.database = database;
        this.nodes = new ConcurrentHashMap<>();
        this.currentNodeId = "node-" + UUID.randomUUID().toString().substring(0, 8);
        this.currentRole = ClusterRole.MASTER;
        this.clusterEnabled = false;
    }

    public void enableCluster(int clientPort, String[] args) {
        this.clusterEnabled = true;
        int clusterPort = clientPort + Protocol.CLUSTER_PORT_OFFSET;
        this.replicator = new ClusterReplicator(database, this, clusterPort);

        String roleStr = getArg(args, "--role", "master");
        this.currentRole = "slave".equalsIgnoreCase(roleStr) ? ClusterRole.SLAVE : ClusterRole.MASTER;

        ClusterNode self = new ClusterNode(currentNodeId, "127.0.0.1", clientPort, currentRole);
        nodes.put(currentNodeId, self);

        if (currentRole == ClusterRole.MASTER) {
            replicator.startMaster();
            System.out.println("✓ 集群主节点模式，集群端口: " + clusterPort);
            startHeartbeat();
            electMaster();
        } else {
            String masterHost = getArg(args, "--master-host", "127.0.0.1");
            int masterPort = Integer.parseInt(getArg(args, "--master-port",
                    String.valueOf(Protocol.DEFAULT_PORT)));
            int masterClusterPort = masterPort + Protocol.CLUSTER_PORT_OFFSET;
            replicator.startSlave(masterHost, masterClusterPort);
            System.out.println("✓ 集群从节点模式，连接到主节点 " + masterHost + ":" + masterClusterPort);
            startHeartbeat();
        }
    }

    private String getArg(String[] args, String key, String defaultVal) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(key)) return args[i + 1];
        }
        return defaultVal;
    }

    public ClusterReplicator getReplicator() {
        return replicator;
    }

    public boolean isClusterEnabled() {
        return clusterEnabled;
    }

    public boolean isMaster() {
        return currentRole == ClusterRole.MASTER;
    }

    public synchronized boolean joinCluster(String coordinatorHost, int coordinatorPort) {
        ClusterNode self = new ClusterNode(currentNodeId, "127.0.0.1",
                Protocol.DEFAULT_PORT, currentRole);
        nodes.put(currentNodeId, self);
        startHeartbeat();
        System.out.println("? 节点 " + currentNodeId + " 已加入集群");
        return true;
    }

    public synchronized void leaveCluster() {
        running = false;
        if (heartbeatExecutor != null) heartbeatExecutor.shutdown();
        nodes.clear();
        System.out.println("? 已离开集群");
    }

    private void startHeartbeat() {
        running = true;
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cluster-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!running) return;
            ClusterNode self = nodes.get(currentNodeId);
            if (self != null) self.updateHeartbeat();
            long now = System.currentTimeMillis();
            boolean changed = false;
            for (ClusterNode node : nodes.values()) {
                if (!node.getNodeId().equals(currentNodeId)) {
                    if (now - node.getLastHeartbeat() > Protocol.NODE_TIMEOUT) {
                        if (node.isAlive()) {
                            node.setAlive(false);
                            changed = true;
                            System.out.println("? 节点 " + node.getNodeId() + " 已超时");
                        }
                    }
                }
            }
            if (changed) electMaster();
        }, 0, Protocol.HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
    }

    public ClusterRole electMaster() {
        Optional<ClusterNode> elected = nodes.values().stream()
                .filter(ClusterNode::isAlive)
                .min(Comparator.comparing(ClusterNode::getNodeId));

        if (elected.isPresent()) {
            ClusterNode master = elected.get();
            master.setRole(ClusterRole.MASTER);
            nodes.values().stream()
                    .filter(n -> !n.getNodeId().equals(master.getNodeId()) && n.isAlive())
                    .forEach(n -> n.setRole(ClusterRole.SLAVE));
            if (master.getNodeId().equals(currentNodeId)) {
                currentRole = ClusterRole.MASTER;
            } else {
                currentRole = ClusterRole.SLAVE;
            }
            System.out.println("? 选举完成，主节点: " + master.getNodeId());
        }
        return currentRole;
    }

    public void addNode(ClusterNode node) {
        nodes.put(node.getNodeId(), node);
        electMaster();
    }

    public ClusterNode getSelf() {
        return nodes.get(currentNodeId);
    }

    public ClusterNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    public Map<String, Object> getClusterStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("clusterEnabled", clusterEnabled);
        status.put("currentNode", currentNodeId);
        status.put("currentRole", currentRole);
        status.put("totalNodes", nodes.size());

        List<Map<String, Object>> nodeList = new ArrayList<>();
        for (ClusterNode node : nodes.values()) {
            Map<String, Object> ni = new LinkedHashMap<>();
            ni.put("id", node.getNodeId());
            ni.put("host", node.getHost() + ":" + node.getPort());
            ni.put("role", node.getRole());
            ni.put("alive", node.isAlive());
            ni.put("lastHeartbeat", node.getLastHeartbeat());
            nodeList.add(ni);
        }
        status.put("nodes", nodeList);
        return status;
    }

    public void broadcastWal(ClusterReplicator.WalEntry entry) {
        if (replicator != null && isMaster()) {
            replicator.broadcastWal(entry);
        }
    }

    public ClusterRole getCurrentRole() { return currentRole; }
    public String getCurrentNodeId() { return currentNodeId; }

    public void stop() {
        running = false;
        if (heartbeatExecutor != null) heartbeatExecutor.shutdown();
        if (replicator != null) replicator.stop();
        nodes.clear();
    }
}
