package com.database.cluster;

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

    public void startSlave(String masterHost, int masterPort) {
        running = true;
        new Thread(() -> {
            ScheduledExecutorService heartbeater = null;
            while (running) {
                try {
                    Socket s = new Socket(masterHost, masterPort);
                    ObjectOutputStream oos = new ObjectOutputStream(s.getOutputStream());
                    ObjectInputStream ois = new ObjectInputStream(s.getInputStream());

                    ClusterNode self = clusterManager.getSelf();
                    oos.writeObject(new ClusterMessage(ClusterMessage.Type.REGISTER, self, self.getNodeId()));
                    oos.flush();

                    Object resp = ois.readObject();
                    if (resp instanceof ClusterMessage msg && msg.getType() == ClusterMessage.Type.REGISTERED) {
                        LOG.info("已注册到主节点 " + masterHost + ":" + masterPort);
                    }

                    // 维持心跳，防止空闲断开
                    ClusterMessage heartbeatMsg = new ClusterMessage(ClusterMessage.Type.HEARTBEAT,
                            self, self.getNodeId());
                    heartbeater = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "heartbeat-to-master");
                        t.setDaemon(true);
                        return t;
                    });
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
                        LOG.warning("与主节点连接断开，5秒后重试: " + e.getMessage());
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
                clusterManager.getSelf().updateHeartbeat();
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

        void send(ClusterMessage msg) {
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
            ScheduledExecutorService heartbeater = null;
            try {
                ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
                ClusterMessage reg = (ClusterMessage) ois.readObject();
                if (reg.getType() == ClusterMessage.Type.REGISTER) {
                    ClusterNode slaveNode = reg.getDataAs();
                    clusterManager.addNode(slaveNode);
                    oos = new ObjectOutputStream(socket.getOutputStream());
                    oos.writeObject(new ClusterMessage(ClusterMessage.Type.REGISTERED,
                            clusterManager.getSelf(), clusterManager.getCurrentNodeId()));
                    oos.flush();
                    LOG.info("从节点已注册: " + slaveNode.getNodeId());
                }
                // 维持心跳，防止空闲断开
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
                LOG.warning("从节点连接异常: " + e.getMessage());
            } finally {
                active = false;
                slaves.remove(this);
                if (heartbeater != null) heartbeater.shutdownNow();
                close();
                LOG.info("从节点已断开: " + socket.getRemoteSocketAddress());
            }
        }
    }
}
