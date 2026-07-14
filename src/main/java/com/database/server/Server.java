 package com.database.server;
 
import com.database.cluster.ClusterManager;
import com.database.common.Protocol;
import com.database.common.Response;
import com.database.core.Database;

import java.io.IOException;
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
          }
          
           try (ServerSocket serverSocket = new ServerSocket(port)) {
               System.out.println("✓ 服务器已启动，监听端口: " + port);
               if (clusterMode) System.out.println("✓ 集群模式已启用");
               System.out.println("✓ 支持最大客户端连接数: " + Protocol.MAX_CONNECTIONS);
               
               // 自动恢复数据（扫描 data/ 目录，加载所有数据库 + WAL回放）
                System.out.println("✓ 正在自动恢复持久化数据...");
                Response recoveryResp = database.autoLoadDatabases();
                System.out.println("  " + recoveryResp.getMessage());
                
                // 启动 HTTP RESTful API 服务器
                try {
                    HttpApiServer httpApi = new HttpApiServer(database, clusterManager);
                    httpApi.start();
                } catch (IOException e) {
                    System.err.println("? HTTP API 服务器启动失败: " + e.getMessage());
                }
                
                System.out.println("✓ 等待客户端连接...\n");
               
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
                          System.err.println("✗ 接受客户端连接失败: " + e.getMessage());
                      }
                  }
              }
          } catch (IOException e) {
              System.err.println("✗ 服务器启动失败: " + e.getMessage());
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
           System.out.println("\n✓ 服务器已安全关闭");
       }
      
      public static void main(String[] args) {
          int port = Protocol.DEFAULT_PORT;
          if (args.length > 0) {
              try {
                  port = Integer.parseInt(args[0]);
              } catch (NumberFormatException e) {
                  System.err.println("端口号格式错误，使用默认端口: " + Protocol.DEFAULT_PORT);
              }
          }
          new Server(port).start(args);
      }
 }
