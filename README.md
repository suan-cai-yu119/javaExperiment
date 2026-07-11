 # 迷你数据库系统 (Mini Database System)
 
 ## 项目简介
 
 Java高级编程及应用课程设计项目。基于 C/S 架构的迷你数据库系统，支持多线程并发访问、
 键值存储、集合管理、数据持久化以及集群扩展等核心功能。
 
 ## 技术栈
 
 - **语言**: Java 17+
 - **构建工具**: Maven
 - **通信**: TCP Socket (对象序列化)
 - **存储**: 内存 + 文件持久化 (Java 序列化)
 - **并发**: 线程池 (ThreadPoolExecutor)
 
 ## 项目结构
 
 ```
 mini-database/
 ├── pom.xml                          # Maven 构建配置
 ├── README.md                        # 项目说明
 ├── .gitignore                       # Git 忽略规则
 └── src/
     ├── main/java/com/database/
     │   ├── common/                  # 公共模块
     │   │   ├── CommandType.java     # 命令类型枚举
     │   │   ├── Request.java         # 请求对象
     │   │   ├── Response.java        # 响应对象
     │   │   ├── Protocol.java        # 通信协议常量
     │   │   └── ValueType.java       # 值类型枚举
     │   ├── core/                    # 核心引擎
     │   │   ├── KV.java             # 键值对
     │   │   ├── Collection_.java     # 集合（表）
     │   │   └── Database.java        # 数据库引擎
     │   ├── server/                  # 服务器
     │   │   ├── Server.java          # 主服务器（线程池）
     │   │   └── ClientHandler.java   # 客户端处理器
     │   ├── client/                  # 客户端
     │   │   ├── Client.java          # 网络客户端
     │   │   ├── ClientMain.java      # 客户端启动入口
     │   │   └── ConsoleUI.java       # 交互式控制台 UI
     │   ├── cluster/                 # 集群模块（拓展）
     │   │   ├── ClusterRole.java     # 节点角色枚举
     │   │   ├── ClusterNode.java     # 集群节点
     │   │   └── ClusterManager.java  # 集群管理器
     │   └── test/                    # 测试
     │       └── TestRunner.java      # 功能测试
     └── test/java/com/database/
         └── DatabaseTests.java       # JUnit 测试
 ```
 
 ## 快速开始
 
 ### 环境要求
 
 - JDK 17+
 - Maven 3.6+
 - Git
 
 ### 编译构建
 
 ```bash
 # 编译项目
 mvn clean compile
 
 # 打包
 mvn clean package
 
 # 运行功能测试
 mvn exec:java -Dexec.mainClass="com.database.test.TestRunner"
 ```
 
 ### 启动服务器
 
 ```bash
 # 启动（默认端口 9527）
 mvn exec:java -Dexec.mainClass="com.database.server.Server"
 
 # 或直接运行 jar
 java -jar target/mini-database-1.0.0.jar [port]
 ```
 
 ### 启动客户端
 
 ```bash
 # 启动客户端（连接默认端口）
 mvn exec:java -Dexec.mainClass="com.database.client.ClientMain"
 
 # 指定服务器地址和端口
 mvn exec:java -Dexec.mainClass="com.database.client.ClientMain" -Dexec.args="127.0.0.1 9527"
 ```
 
 ## 命令示例
 
 ```
 # 数据库操作
 CREATE DATABASE school         # 创建数据库
 USE DATABASE school             # 切换数据库
 LIST DATABASES                  # 列出所有数据库
 
 # 集合操作
 CREATE COLLECTION students      # 创建集合
 LIST COLLECTIONS                # 列出所有集合
 
 # 键值操作（支持数值、字符串、集合等类型）
 PUT students s001 小明         # 插入键值对
 GET students s001               # 获取值
 UPDATE students s001 大明      # 更新值
 DELETE students s001            # 删除值
 SCAN students                   # 扫描所有键值对
 
 # 持久化
 SAVE                            # 保存数据
 LOAD school                     # 加载数据
 ```
 
 ## 架构设计
 
 ### C/S 架构
 
 - **Server**: 主服务器使用 `ServerSocket` 监听连接，通过 `ThreadPoolExecutor` 管理客户端线程
 - **Client**: 客户端使用 `Socket` 连接服务器，通过 `ObjectInputStream/OutputStream` 传输序列化对象
 - **协议**: 基于 Java 对象序列化的自定义协议（Request/Response）
 
 ### 多线程支持
 
 - 服务端使用线程池管理客户端连接
 - 每个客户端连接由独立的 `ClientHandler` 线程处理
 - 核心数据结构使用 `ConcurrentHashMap` 和 `ConcurrentSkipListMap` 保证线程安全
 
 ### 集群模式（拓展）
 
 - 支持主从架构
 - 节点健康检测（心跳机制）
 - 动态主节点选举
 
 ## 设计模式应用
 
 - **单例模式**: 数据库引擎单例
 - **工厂模式**: 线程工厂 (ThreadFactory)
 - **策略模式**: 命令分发处理
 - **观察者模式**: 集群心跳检测
 
 ## 课程设计考核要求对应
 
 | 序号 | 要求 | 实现 |
 |------|------|------|
 | 1 | 使用 Git | 本仓库完整 Git 记录 |
 | 2 | C/S 结构 | ServerSocket + Socket |
 | 3 | 多线程 | ThreadPoolExecutor + ClientHandler |
 | 4 | KV 增删改查 | PUT/GET/DELETE/UPDATE/SCAN |
 | 5 | 多种值类型 | 数值、字符串、Set、Map、List |
 | 6 | 面向对象 | 类、继承、多态、设计模式 |
 | 7 | 类集框架 | ConcurrentHashMap, ConcurrentSkipListMap |
 | 8 | 注解、反射 | 序列化/反序列化 |
 | 9 | 文件 IO | 数据持久化 (ObjectOutputStream) |
 | 10 | 网络 IO | TCP Socket 通信 |
 | 11 | 多线程 | 线程池、并发集合 |
 | 12 | Collection 概念 | 集合/表管理 (拓展) |
 | 13 | 集群支持 | 主从架构、心跳检测 (拓展) |
 
 ## 许可
 
 本课程设计仅供学习参考
