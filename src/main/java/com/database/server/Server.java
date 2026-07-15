 package com.database.server;
 
import com.database.cluster.ClusterManager;
import com.database.common.*;
import com.database.core.Database;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * 数据库服务器 - 主入口
 * 采用 C/S 架构，支持多线程（一个 Server 支持多个 Client 连接）
 * 使用线程池管理客户端连接
 */
public class Server {
    private static final Logger LOGGER = Logger.getLogger(Server.class.getName());
    
    private final int port;
    private final Database database;
    private final ExecutorService threadPool;
    private final AtomicInteger clientCounter;
    private volatile boolean running;
    private ClusterManager clusterManager;
    
    public Server(int port) {
        this.port = port;
        this.database = new Database();
        // 使用线程池管理客户端连接，体现多线程编程
        this.threadPool = new ThreadPoolExecutor(
            Protocol.MAX_CONNECTIONS / 2,
            Protocol.MAX_CONNECTIONS,
            60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "client-handler-" + counter.getAndIncrement());
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        this.clientCounter = new AtomicInteger(1);
        this.running = true;
    }
     
      /**
       * 启动服务器
       */
       public void start(String[] args) {
           System.out.println("╔═══════════════════════════════════════════╗");
           System.out.println("║    迷你数据库系统 v" + Protocol.VERSION + "                    ║");
           System.out.println("║    正在启动服务器...                      ║");
           System.out.println("╚═══════════════════════════════════════════╝");

           boolean clusterMode = false;
           for (String a : args) {
               if ("--cluster".equals(a)) { clusterMode = true; break; }
           }
           if (clusterMode) {
               clusterManager = new ClusterManager(database);
               clusterManager.enableCluster(port, args);
               System.out.println("[OK] 集群模式已启用（内部自动选举主从）");
           }
          
           try (ServerSocket serverSocket = new ServerSocket(port)) {
               System.out.println("[OK] 服务器已启动，监听端口: " + port);
               if (clusterMode) System.out.println("[OK] 集群模式已启用");
               System.out.println("[OK] 支持最大客户端连接数: " + Protocol.MAX_CONNECTIONS);
               
               // 自动恢复数据（扫描 data/ 目录，加载所有数据库 + WAL回放）
                System.out.println("[OK] 正在自动恢复持久化数据...");
                Response recoveryResp = database.autoLoadDatabases();
                System.out.println("  " + recoveryResp.getMessage());
                
               // 启动 HTTP RESTful API 服务器
                try {
                    int httpPort = port + Protocol.HTTP_PORT_OFFSET;
                    HttpApiServer httpApi = new HttpApiServer(database, clusterManager, httpPort);
                    httpApi.start();
                } catch (IOException e) {
                    System.err.println("⚠ HTTP API 服务器启动失败: " + e.getMessage());
                    System.err.println("  提示：端口 " + (port + Protocol.HTTP_PORT_OFFSET) + " 可能被占用");
                }

                // 启动探针服务器（匿名查询，不打印日志、不分配编号）
                int probePort = port + Protocol.PROBE_PORT_OFFSET;
                new Thread(() -> {
                    try (ServerSocket probeSocket = new ServerSocket(probePort)) {
                        while (running) {
                            try (Socket s = probeSocket.accept()) {
                                handleProbe(s);
                            } catch (IOException ignored) {}
                        }
                    } catch (IOException e) {
                        System.err.println("⚠ 探针服务器启动失败（端口 " + probePort + "）: " + e.getMessage());
                    }
                }, "probe-server").start();
                
                System.out.println("[OK] 等待客户端连接...\n");
               
               // 关闭钩子，确保优雅关闭
               Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
              
              while (running) {
                  try {
                      Socket clientSocket = serverSocket.accept();
                      int clientId = clientCounter.getAndIncrement();
                      
                      // 每个客户端由单独的线程处理（多线程核心体现）
                      ClientHandler handler = new ClientHandler(clientSocket, database, clientId, clusterManager);
                      threadPool.execute(handler);
                      
                      System.out.println("→ 客户端 #" + clientId + " 已连接 (" + 
                              clientSocket.getInetAddress().getHostAddress() + ":" + 
                              clientSocket.getPort() + ")");
                      
                  } catch (IOException e) {
                      if (running) {
                          System.err.println("[x] 接受客户端连接失败: " + e.getMessage());
                      }
                  }
              }
          } catch (IOException e) {
              System.err.println("[x] 服务器启动失败: " + e.getMessage());
          } finally {
              stop();
          }
      }
     
     /**
      * 停止服务器
      */
       public void stop() {
           running = false;
           if (clusterManager != null) clusterManager.stop();
           threadPool.shutdown();
           try {
               if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                   threadPool.shutdownNow();
               }
           } catch (InterruptedException e) {
               threadPool.shutdownNow();
               Thread.currentThread().interrupt();
           }
           database.shutdown();
           System.out.println("\n[OK] 服务器已安全关闭");
       }
      
       private void handleProbe(Socket s) {
           try (ObjectInputStream ois = new ObjectInputStream(s.getInputStream());
                ObjectOutputStream oos = new ObjectOutputStream(s.getOutputStream())) {
               Request req = (Request) ois.readObject();
               switch (req.getCommand()) {
                   case PING -> oos.writeObject(Response.ok("PONG", System.currentTimeMillis()));
                   case CLUSTER_STATUS -> {
                       if (clusterManager != null && clusterManager.isClusterEnabled()) {
                           oos.writeObject(Response.ok("集群状态", clusterManager.getClusterStatus()));
                       } else {
                           oos.writeObject(Response.fail("集群模式未启用"));
                       }
                   }
                   default -> oos.writeObject(Response.fail("探针端口仅支持 PING 和 CLUSTER_STATUS"));
               }
               oos.flush();
           } catch (Exception ignored) {}
       }

       public static void main(String[] args) {
          int port = Protocol.DEFAULT_PORT;
           if (args.length > 0 && !args[0].startsWith("--")) {
              try {
                  port = Integer.parseInt(args[0]);
              } catch (NumberFormatException e) {
                  System.err.println("端口号格式错误，使用默认端口: " + Protocol.DEFAULT_PORT);
              }
          }
          new Server(port).start(args);
      }
 }
