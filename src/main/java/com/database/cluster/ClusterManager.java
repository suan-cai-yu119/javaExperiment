package com.database.cluster;

import com.database.common.Protocol;
import com.database.core.Database;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class ClusterManager {
    private static final Logger LOGGER = Logger.getLogger(ClusterManager.class.getName());

    private final Database database;
    private final ConcurrentHashMap<String, ClusterNode> nodes;
    private final String currentNodeId;
    private final String currentHost;
    private int currentPort;
    private ClusterRole currentRole;
    private volatile boolean running;
    private ScheduledExecutorService syncExecutor;
    private ClusterReplicator replicator;
    private boolean clusterEnabled;
    private String clusterPort;
    private int clientPort;
    private List<String> peerAddresses;

    public ClusterManager(Database database) {
        this.database = database;
        this.nodes = new ConcurrentHashMap<>();
        this.currentNodeId = "node-" + UUID.randomUUID().toString().substring(0, 8);
        this.currentHost = "127.0.0.1";
        this.currentRole = ClusterRole.CANDIDATE;
        this.clusterEnabled = false;
        this.peerAddresses = new ArrayList<>();
    }

    public void enableCluster(int clientPort, String[] args) {
        this.clusterEnabled = true;
        this.clientPort = clientPort;
        int clusterPortVal = clientPort + Protocol.CLUSTER_PORT_OFFSET;
        this.clusterPort = String.valueOf(clusterPortVal);
        this.replicator = new ClusterReplicator(database, this, clusterPortVal);

        String peersStr = getArg(args, "--peers", "");
        if (!peersStr.isEmpty()) {
            this.peerAddresses = Arrays.asList(peersStr.split(","));
        }
        String selfAddr = currentHost + ":" + clientPort;
        if (!this.peerAddresses.contains(selfAddr)) {
            this.peerAddresses.add(selfAddr);
        }

        ClusterNode self = new ClusterNode(currentNodeId, currentHost, clientPort, ClusterRole.CANDIDATE);
        nodes.put(currentNodeId, self);

        System.out.println("→ 集群模式已启用，对等节点: " + String.join(", ", peerAddresses));

        discoverPeers();
        electMaster();
        startRole();
        startSyncThread();
    }

    private void discoverPeers() {
        for (String addr : peerAddresses) {
            String[] parts = addr.split(":");
            if (parts.length != 2) continue;
            String peerHost = parts[0];
            int peerPort = Integer.parseInt(parts[1]);
            if (peerHost.equals(currentHost) && peerPort == clientPort) continue;

            int peerClusterPort = peerPort + Protocol.CLUSTER_PORT_OFFSET;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(peerHost, peerClusterPort), Protocol.CLUSTER_TIMEOUT);
                s.setSoTimeout(Protocol.CLUSTER_TIMEOUT);
                ClusterReplicator.exchangeNodes(s, this, currentNodeId, currentHost, clientPort);
                System.out.println("[OK] 发现对等节点: " + addr);
            } catch (Exception e) {
                LOGGER.fine("对等节点 " + addr + " 暂不可达: " + e.getMessage());
            }
        }
    }

    private void startRole() {
        if (currentRole == ClusterRole.MASTER) {
            replicator.startMaster();
            System.out.println("★ 本节点被选举为主节点，集群端口: " + clusterPort);
            ClusterNode self = nodes.get(currentNodeId);
            if (self != null) self.setRole(ClusterRole.MASTER);
        } else {
            ClusterNode master = findMaster();
            if (master != null) {
                int masterClusterPort = master.getPort() + Protocol.CLUSTER_PORT_OFFSET;
                replicator.startSlave(master.getHost(), masterClusterPort);
                System.out.println("○ 本节点为从节点，主节点: " + master.toAddress());
            }
        }
    }

    private String getArg(String[] args, String key, String defaultVal) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(key)) return args[i + 1];
        }
        return defaultVal;
    }

    public ClusterReplicator getReplicator() { return replicator; }
    public boolean isClusterEnabled() { return clusterEnabled; }

    public boolean isMaster() {
        return currentRole == ClusterRole.MASTER;
    }

    public synchronized boolean joinCluster(String coordinatorHost, int coordinatorPort) {
        startSyncThread();
        System.out.println("[OK] 节点 " + currentNodeId + " 已加入集群");
        return true;
    }

    public synchronized void leaveCluster() {
        running = false;
        if (syncExecutor != null) syncExecutor.shutdown();
        nodes.clear();
        System.out.println("[OK] 已离开集群");
    }

    private void startSyncThread() {
        running = true;
        syncExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cluster-sync");
            t.setDaemon(true);
            return t;
        });

        syncExecutor.scheduleAtFixedRate(() -> {
            if (!running) return;
            ClusterNode self = nodes.get(currentNodeId);
            if (self != null) {
                self.updateHeartbeat();
            }

            boolean changed = false;
            long now = System.currentTimeMillis();
            for (ClusterNode node : nodes.values()) {
                if (!node.getNodeId().equals(currentNodeId) && node.isAlive()) {
                    if (now - node.getLastHeartbeat() > Protocol.NODE_TIMEOUT) {
                        node.setAlive(false);
                        changed = true;
                        System.out.println("⚠ 节点 " + node.getNodeId() + " (" + node.toAddress() + ") 已超时");
                    }
                }
            }
            if (changed) {
                electMaster();
                if (currentRole == ClusterRole.MASTER && !replicator.isRunning()) {
                    replicator.startMaster();
                }
            }
        }, Protocol.HEARTBEAT_INTERVAL, Protocol.HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
    }

    public ClusterRole electMaster() {
        Optional<ClusterNode> elected = nodes.values().stream()
                .filter(ClusterNode::isAlive)
                .min(Comparator.naturalOrder());

        if (elected.isPresent()) {
            ClusterNode master = elected.get();
            master.setRole(ClusterRole.MASTER);

            nodes.values().stream()
                    .filter(n -> !n.getNodeId().equals(master.getNodeId()) && n.isAlive())
                    .forEach(n -> n.setRole(ClusterRole.SLAVE));

            boolean wasMaster = currentRole == ClusterRole.MASTER;
            if (master.getNodeId().equals(currentNodeId)) {
                currentRole = ClusterRole.MASTER;
                if (!wasMaster) {
                    System.out.println("→ 本节点被选举为主节点");
                }
            } else {
                if (currentRole != ClusterRole.SLAVE) {
                    System.out.println("→ 本节点为从节点，主节点: " + master.getNodeId() + " (" + master.toAddress() + ")");
                }
                currentRole = ClusterRole.SLAVE;
            }
        }
        return currentRole;
    }

    private ClusterNode findMaster() {
        return nodes.values().stream()
                .filter(n -> n.isAlive() && n.getRole() == ClusterRole.MASTER)
                .findFirst().orElse(null);
    }

    public ClusterNode findOrElectMaster() {
        ClusterNode master = findMaster();
        if (master == null) {
            electMaster();
            master = findMaster();
        }
        return master;
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

    public List<ClusterNode> getAllNodes() {
        return new ArrayList<>(nodes.values());
    }

    public Map<String, Object> getClusterStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("clusterEnabled", clusterEnabled);
        status.put("currentNode", currentNodeId);
        status.put("currentRole", currentRole);
        status.put("totalNodes", nodes.size());

        List<Map<String, Object>> nodeList = new ArrayList<>();
        List<ClusterNode> sorted = new ArrayList<>(nodes.values());
        sorted.sort(Comparator.naturalOrder());
        for (ClusterNode node : sorted) {
            Map<String, Object> ni = new LinkedHashMap<>();
            ni.put("id", node.getNodeId());
            ni.put("host", node.getHost());
            ni.put("port", node.getPort());
            ni.put("role", node.getRole().name());
            ni.put("alive", node.isAlive());
            ni.put("lastHeartbeat", node.getLastHeartbeat());
            ni.put("latencyMs", node.getLatencyMs());
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
    public int getCurrentPort() { return clientPort; }

    public void stop() {
        running = false;
        if (syncExecutor != null) syncExecutor.shutdown();
        if (replicator != null) replicator.stop();
        nodes.clear();
        System.out.println("[OK] 集群已关闭");
    }
}
