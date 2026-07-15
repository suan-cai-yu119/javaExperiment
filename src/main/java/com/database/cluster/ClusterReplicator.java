package com.database.cluster;

import com.database.common.Protocol;
import com.database.core.Database;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class ClusterReplicator {
    private static final Logger LOG = Logger.getLogger(ClusterReplicator.class.getName());

    public static class WalEntry implements Serializable {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        public final String op;
        public final String collection;
        public final String key;
        public final Object value;
        public final long seq;

        public WalEntry(String op, String collection, String key, Object value) {
            this.op = op;
            this.collection = collection;
            this.key = key;
            this.value = value;
            this.seq = System.currentTimeMillis();
        }
    }

    private final Database database;
    private final ClusterManager clusterManager;
    private final int clusterPort;
    private final List<SlaveConnection> slaves = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private volatile String slaveTargetHost;
    private volatile int slaveTargetPort;

    public ClusterReplicator(Database database, ClusterManager clusterManager, int clusterPort) {
        this.database = database;
        this.clusterManager = clusterManager;
        this.clusterPort = clusterPort;
    }

    public void startMaster() {
        running = true;
        new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(clusterPort)) {
                LOG.info("集群服务器已启动，监听端口: " + clusterPort);
                while (running) {
                    try {
                        Socket s = ss.accept();
                        SlaveConnection sc = new SlaveConnection(s);
                        slaves.add(sc);
                        new Thread(sc, "slave-" + s.getPort()).start();
                    } catch (IOException e) {
                        if (running) LOG.warning("接受从节点连接失败: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                LOG.severe("集群服务器启动失败: " + e.getMessage());
            }
        }, "cluster-server").start();
    }

    public boolean matchesMaster(String host, int port) {
        return running && host.equals(slaveTargetHost) && port == slaveTargetPort;
    }

    public void startSlave(String masterHost, int masterPort) {
        running = true;
        slaveTargetHost = masterHost;
        slaveTargetPort = masterPort;
        new Thread(() -> {
            LOG.info("从节点线程已启动，正在连接主节点 " + masterHost + ":" + masterPort);
            ScheduledExecutorService heartbeater = null;
            while (running) {
                try {
                    LOG.info("=== 从节点启动诊断 ===");
                    LOG.info("目标主节点: " + masterHost + ":" + masterPort);
                    LOG.info("当前节点ID: " + clusterManager.getCurrentNodeId());
                    LOG.info("当前角色: " + clusterManager.getCurrentRole());

                    LOG.info("尝试连接主节点 " + masterHost + ":" + masterPort);
                    Socket s = new Socket(masterHost, masterPort);
                    LOG.info("TCP 连接成功，本地端口: " + s.getLocalPort() + ", 远程端口: " + s.getPort());

                    ClusterNode self = clusterManager.getSelf();
                    LOG.info("准备发送注册信息...");

                    ObjectOutputStream oos = new ObjectOutputStream(s.getOutputStream());
                    oos.flush();

                    ObjectInputStream ois = new ObjectInputStream(s.getInputStream());
                    LOG.info("ObjectStream 创建完成，立即发送 REGISTER...");

                    ClusterMessage regMsg = new ClusterMessage(ClusterMessage.Type.REGISTER, self, self.getNodeId());
                    LOG.info("注册信息: ID=" + self.getNodeId() + ", Host=" + self.getHost() + ":" + self.getPort() + ", Role=" + self.getRole());

                    oos.writeObject(regMsg);
                    oos.flush();
                    LOG.info("[OK] REGISTER 消息已发送");

                    Object resp = ois.readObject();
                    if (resp instanceof ClusterMessage msg && msg.getType() == ClusterMessage.Type.REGISTERED) {
                        LOG.info("[OK] 已收到主节点的 REGISTERED 响应");
                        Object rawData = msg.getData();
                        LOG.info("REGISTERED data type=" + rawData.getClass().getName()
                                + ", isList=" + (rawData instanceof List)
                                + ", isClusterNode=" + (rawData instanceof ClusterNode));

                        int beforeCount = clusterManager.getAllNodes().size();
                        LOG.info("同步前节点数: " + beforeCount);

                        if (rawData instanceof List<?> nodeList) {
                            LOG.info("[OK] 收到完整节点列表，数量: " + nodeList.size());
                            for (Object n : nodeList) {
                                if (n instanceof ClusterNode cn) {
                                    LOG.info("  添加节点: " + cn.getNodeId() + " (" + cn.getHost() + ":" + cn.getPort() + ") [" + cn.getRole() + "]");
                                    clusterManager.addNode(cn);
                                }
                            }
                        } else if (rawData instanceof ClusterNode cn) {
                            LOG.warning("⚠ 主节点返回的是单节点(旧代码)，只添加一个节点: " + cn.getNodeId());
                            clusterManager.addNode(cn);
                        }

                        int afterCount = clusterManager.getAllNodes().size();
                        LOG.info("[OK] 同步后节点数: " + afterCount + " (增加了 " + (afterCount - beforeCount) + " 个)");

                        LOG.info("当前已知的所有节点:");
                        for (ClusterNode node : clusterManager.getAllNodes()) {
                            LOG.info("  - " + node.getNodeId() + " @ " + node.getHost() + ":" + node.getPort()
                                    + " [角色: " + node.getRole() + ", 存活: " + node.isAlive() + "]");
                        }
                    } else {
                        LOG.warning("⚠ 注册响应异常: " + (resp != null ? resp.getClass().getName() : "null"));
                    }

                    heartbeater = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "heartbeat-to-master");
                        t.setDaemon(true);
                        return t;
                    });
                    ClusterMessage heartbeatMsg = new ClusterMessage(ClusterMessage.Type.HEARTBEAT,
                            self, self.getNodeId());
                    heartbeater.scheduleAtFixedRate(() -> {
                        try {
                            oos.writeObject(heartbeatMsg);
                            oos.flush();
                        } catch (IOException ignored) {}
                    }, 0, Protocol.HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);

                    while (running) {
                        Object obj = ois.readObject();
                        if (obj instanceof ClusterMessage msg) {
                            handleMasterMessage(msg);
                        }
                    }
                } catch (Exception e) {
                    if (running) {
                        LOG.warning("与主节点连接断开，5秒后重试: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        if (heartbeater != null) {
                            heartbeater.shutdownNow();
                            heartbeater = null;
                        }
                        try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                    }
                }
            }
        }, "cluster-slave").start();
    }

    private void handleMasterMessage(ClusterMessage msg) {
        switch (msg.getType()) {
            case WAL_ENTRY -> {
                WalEntry entry = msg.getDataAs();
                replay(entry);
            }
            case HEARTBEAT -> {
                ClusterNode sender = clusterManager.getNode(msg.getSenderId());
                if (sender != null) sender.updateHeartbeat();
            }
            case NODE_LIST -> {
                Object rawData = msg.getData();
                LOG.info("收到 NODE_LIST: type=" + rawData.getClass().getName()
                        + ", isList=" + (rawData instanceof List));
                if (rawData instanceof List<?> nodeList) {
                    LOG.info("NODE_LIST size=" + nodeList.size());
                    for (Object n : nodeList) {
                        if (n instanceof ClusterNode cn) {
                            clusterManager.addNode(cn);
                        }
                    }
                }
                LOG.info("同步后本地节点数: " + clusterManager.getAllNodes().size());
            }
        }
    }

    public void broadcastWal(WalEntry entry) {
        ClusterMessage msg = new ClusterMessage(ClusterMessage.Type.WAL_ENTRY, entry,
                clusterManager.getCurrentNodeId());
        for (SlaveConnection sc : slaves) {
            sc.send(msg);
        }
    }

    private void replay(WalEntry entry) {
        try {
            switch (entry.op) {
                case "PUT" -> {
                    database.getCollection(entry.collection);
                    var col = database.getCollection(entry.collection);
                    if (col != null) col.put(entry.key, entry.value);
                }
                case "DEL" -> {
                    var col = database.getCollection(entry.collection);
                    if (col != null) col.delete(entry.key);
                }
                case "UPD" -> {
                    var col = database.getCollection(entry.collection);
                    if (col != null) col.update(entry.key, entry.value);
                }
            }
            LOG.fine("已回放 " + entry.op + " " + entry.collection + "/" + entry.key);
        } catch (Exception e) {
            LOG.warning("回放失败: " + e.getMessage());
        }
    }

    public boolean isRunning() {
        return running;
    }

    public static void exchangeNodes(Socket socket, ClusterManager clusterManager,
                                     String selfId, String selfHost, int selfPort) throws Exception {
        ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
        oos.flush();
        ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());

        ClusterNode self = clusterManager.getSelf();
        oos.writeObject(new ClusterMessage(ClusterMessage.Type.REGISTER,
                new ClusterNode(selfId, selfHost, selfPort), selfId));
        oos.flush();

        Object resp = ois.readObject();
        if (resp instanceof ClusterMessage msg
                && msg.getType() == ClusterMessage.Type.REGISTERED) {
            Object rawData = msg.getData();
            if (rawData instanceof List<?> nodeList) {
                for (Object n : nodeList) {
                    if (n instanceof ClusterNode cn) {
                        clusterManager.addNode(cn);
                    }
                }
            }
        }
    }

    public void stop() {
        running = false;
        for (SlaveConnection sc : slaves) sc.close();
        slaves.clear();
    }

    private class SlaveConnection implements Runnable {
        private final Socket socket;
        private ObjectOutputStream oos;
        private volatile boolean active = true;

        SlaveConnection(Socket socket) { this.socket = socket; }

        synchronized void send(ClusterMessage msg) {
            if (!active) return;
            try {
                if (oos == null) oos = new ObjectOutputStream(socket.getOutputStream());
                oos.writeObject(msg);
                oos.flush();
            } catch (IOException e) {
                active = false;
                slaves.remove(this);
                LOG.warning("从节点断开: " + socket.getRemoteSocketAddress());
            }
        }

        void close() {
            active = false;
            try { socket.close(); } catch (IOException ignored) {}
        }

        @Override
        public void run() {
            LOG.info("=== 主节点处理新连接 ===");
            LOG.info("从节点 TCP 连接已建立: " + socket.getRemoteSocketAddress());
            ScheduledExecutorService heartbeater = null;
            try {
                LOG.info("创建 ObjectOutputStream（先发送序列化头）...");
                oos = new ObjectOutputStream(socket.getOutputStream());
                oos.flush();

                LOG.info("创建 ObjectInputStream（等待从节点数据）...");
                ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());

                LOG.info("等待接收 REGISTER 消息...");
                ClusterMessage reg = (ClusterMessage) ois.readObject();
                LOG.info("[OK] 收到消息，类型: " + reg.getType());

                if (reg.getType() == ClusterMessage.Type.REGISTER) {
                    ClusterNode slaveNode = reg.getDataAs();
                    LOG.info("=== 主节点处理从节点注册 ===");
                    LOG.info("收到从节点注册请求:");
                    LOG.info("  节点ID: " + slaveNode.getNodeId());
                    LOG.info("  地址: " + slaveNode.getHost() + ":" + slaveNode.getPort());
                    LOG.info("  角色: " + slaveNode.getRole());

                    clusterManager.addNode(slaveNode);
                    LOG.info("[OK] 从节点已添加到集群管理器");
                    LOG.info("[OK] 当前集群节点总数: " + clusterManager.getAllNodes().size());

                    List<ClusterNode> allNodes = clusterManager.getAllNodes();
                    LOG.info("准备发送节点列表给新从节点:");
                    LOG.info("  节点总数: " + allNodes.size());
                    for (ClusterNode node : allNodes) {
                        LOG.info("    - " + node.getNodeId() + " (" + node.getHost() + ":" + node.getPort() + ") [" + node.getRole() + "]");
                    }

                    ClusterMessage registeredMsg = new ClusterMessage(ClusterMessage.Type.REGISTERED,
                            allNodes, clusterManager.getCurrentNodeId());
                    LOG.info("发送 REGISTERED 消息...");
                    oos.writeObject(registeredMsg);
                    oos.flush();
                    LOG.info("[OK] 已发送 REGISTERED 消息（包含 " + allNodes.size() + " 个节点）");

                    List<ClusterNode> broadcastNodes = clusterManager.getAllNodes();
                    LOG.info("准备广播 NODE_LIST 给其他从节点:");
                    LOG.info("  广播节点数: " + broadcastNodes.size());
                    LOG.info("  当前从节点连接数: " + slaves.size());

                    ClusterMessage nodeListMsg = new ClusterMessage(ClusterMessage.Type.NODE_LIST,
                            broadcastNodes, clusterManager.getCurrentNodeId());
                    int sentCount = 0;
                    for (SlaveConnection sc : slaves) {
                        if (sc != this) {
                            sc.send(nodeListMsg);
                            sentCount++;
                        }
                    }
                    LOG.info("[OK] NODE_LIST 广播完成 (发送给 " + sentCount + " 个从节点)");

                    System.out.println("[OK] 从节点已加入集群: " + slaveNode.getNodeId());
                } else {
                    LOG.warning("⚠ 收到的消息类型不是 REGISTER: " + reg.getType());
                }

                ClusterMessage heartbeatMsg = new ClusterMessage(ClusterMessage.Type.HEARTBEAT,
                        clusterManager.getSelf(), clusterManager.getCurrentNodeId());
                heartbeater = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "heartbeat-to-slave-" + socket.getPort());
                    t.setDaemon(true);
                    return t;
                });
                heartbeater.scheduleAtFixedRate(() -> send(heartbeatMsg),
                        0, Protocol.HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
                while (active) {
                    try {
                        Object obj = ois.readObject();
                        if (obj instanceof ClusterMessage msg) {
                            if (msg.getType() == ClusterMessage.Type.HEARTBEAT) {
                                ClusterNode slaveNode = clusterManager.getNode(msg.getSenderId());
                                if (slaveNode != null) slaveNode.updateHeartbeat();
                            }
                        }
                    } catch (EOFException e) {
                        LOG.warning("从节点连接已关闭: " + socket.getRemoteSocketAddress());
                        break;
                    }
                }
            } catch (Exception e) {
                if (active) {
                    LOG.warning("从节点连接异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            } finally {
                active = false;
                slaves.remove(this);
                if (heartbeater != null) heartbeater.shutdownNow();
                close();
                LOG.info("从节点已断开: " + socket.getRemoteSocketAddress());
                List<ClusterNode> remaining = clusterManager.getAllNodes();
                LOG.info("断开广播 NODE_LIST: size=" + remaining.size()
                        + ", nodes=" + remaining.stream().map(ClusterNode::getNodeId).toList());
                ClusterMessage nodeListMsg = new ClusterMessage(ClusterMessage.Type.NODE_LIST,
                        remaining, clusterManager.getCurrentNodeId());
                for (SlaveConnection sc : slaves) {
                    sc.send(nodeListMsg);
                }
            }
        }
    }
}
