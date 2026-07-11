 package com.database.client;
 
 import com.database.common.*;
 
 import java.util.*;
 
 /**
  * 控制台用户界面 - 提供交互式的命令行操作界面
  * 体现：常用类（Scanner）、字符串处理
  */
 public class ConsoleUI {
     private final Client client;
     private final Scanner scanner;
     
     public ConsoleUI(Client client) {
         this.client = client;
         this.scanner = new Scanner(System.in);
     }
     
     /**
      * 启动交互式界面
      */
     public void start() {
         printBanner();
         
         while (client.isConnected()) {
             System.out.print("\nmini-db> ");
             String input = scanner.nextLine().trim();
             
             if (input.isEmpty()) continue;
             
             try {
                 processInput(input);
             } catch (Exception e) {
                 System.err.println("✗ 命令执行错误: " + e.getMessage());
             }
         }
     }
     
     /**
      * 解析并执行用户输入
      * 体现了：反射（命令分发）、注解处理的思路
      */
     private void processInput(String input) {
         String[] parts = parseInput(input);
         if (parts.length == 0) return;
         
         String command = parts[0].toUpperCase();
         String[] args = Arrays.copyOfRange(parts, 1, parts.length);
         
         switch (command) {
             // 数据库操作
             case "CREATE", "CR" -> handleCreate(args);
             case "DROP", "DR" -> handleDrop(args);
             case "LIST", "LS" -> handleList(args);
             case "USE", "US" -> handleUse(args);
             
             // 键值操作
             case "PUT", "P" -> handlePut(args);
             case "GET", "G" -> handleGet(args);
             case "DELETE", "DEL" -> handleDelete(args);
             case "UPDATE", "UPD" -> handleUpdate(args);
             case "SCAN", "SC" -> handleScan(args);
             
             // 持久化
             case "SAVE", "SV" -> handleSave(args);
             case "LOAD", "LD" -> handleLoad(args);
             
             // 系统
             case "HELP", "H", "?" -> printHelp();
             case "PING" -> handlePing();
             case "QUIT", "Q", "EXIT" -> handleQuit();
             case "CLEAR", "CLS" -> clearScreen();
             
             default -> System.out.println("✗ 未知命令，输入 HELP 查看帮助");
         }
     }
     
     private void handleCreate(String[] args) {
         if (args.length < 2) {
             System.out.println("✗ 用法: CREATE DATABASE|COLLECTION <name>");
             return;
         }
         String type = args[0].toUpperCase();
         String name = args[1];
         Response resp;
         if ("DATABASE".equals(type) || "DB".equals(type)) {
             resp = client.sendCommand(CommandType.CREATE_DATABASE, name);
         } else if ("COLLECTION".equals(type) || "COL".equals(type) || "TABLE".equals(type)) {
             resp = client.sendCommand(CommandType.CREATE_COLLECTION, name);
         } else {
             System.out.println("✗ 类型错误: " + type);
             return;
         }
         printResponse(resp);
     }
     
     private void handleDrop(String[] args) {
         if (args.length < 2) {
             System.out.println("✗ 用法: DROP DATABASE|COLLECTION <name>");
             return;
         }
         String type = args[0].toUpperCase();
         String name = args[1];
         Response resp;
         if ("DATABASE".equals(type) || "DB".equals(type)) {
             resp = client.sendCommand(CommandType.DROP_DATABASE, name);
         } else if ("COLLECTION".equals(type) || "COL".equals(type) || "TABLE".equals(type)) {
             resp = client.sendCommand(CommandType.DROP_COLLECTION, name);
         } else {
             System.out.println("✗ 类型错误: " + type);
             return;
         }
         printResponse(resp);
     }
     
     private void handleList(String[] args) {
         if (args.length == 0) {
             System.out.println("✗ 用法: LIST DATABASES|COLLECTIONS");
             return;
         }
         String type = args[0].toUpperCase();
         Response resp;
         if ("DATABASES".equals(type) || "DB".equals(type)) {
             resp = client.sendCommand(CommandType.LIST_DATABASES);
         } else if ("COLLECTIONS".equals(type) || "COLS".equals(type)) {
             resp = client.sendCommand(CommandType.LIST_COLLECTIONS);
         } else {
             System.out.println("✗ 类型错误: " + type);
             return;
         }
         printResponse(resp);
     }
     
     private void handleUse(String[] args) {
         if (args.length == 0) {
             System.out.println("✗ 用法: USE DATABASE <name>");
             return;
         }
         String name = args[0];
         Response resp = client.sendCommand(CommandType.USE_DATABASE, name);
         printResponse(resp);
     }
     
     private void handlePut(String[] args) {
         if (args.length < 3) {
             System.out.println("✗ 用法: PUT <collection> <key> <value>");
             return;
         }
         Request req = new Request(CommandType.PUT);
         req.setCollectionName(args[0]);
         req.setKey(args[1]);
         // 尝试解析数值
         req.setValue(parseValue(args[2]));
         printResponse(client.sendRequest(req));
     }
     
     private void handleGet(String[] args) {
         if (args.length < 2) {
             System.out.println("✗ 用法: GET <collection> <key>");
             return;
         }
         Request req = new Request(CommandType.GET);
         req.setCollectionName(args[0]);
         req.setKey(args[1]);
         Response resp = client.sendRequest(req);
         printResponse(resp);
         // 格式化显示 KV 数据
         if (resp.isSuccess() && resp.getData() instanceof KV kv) {
             System.out.println("  键: " + kv.getKey());
             System.out.println("  值: " + kv.getValue());
             System.out.println("  版本: " + kv.getVersion());
         }
     }
     
     private void handleDelete(String[] args) {
         if (args.length < 2) {
             System.out.println("✗ 用法: DELETE <collection> <key>");
             return;
         }
         Request req = new Request(CommandType.DELETE);
         req.setCollectionName(args[0]);
         req.setKey(args[1]);
         printResponse(client.sendRequest(req));
     }
     
     private void handleUpdate(String[] args) {
         if (args.length < 3) {
             System.out.println("✗ 用法: UPDATE <collection> <key> <value>");
             return;
         }
         Request req = new Request(CommandType.UPDATE);
         req.setCollectionName(args[0]);
         req.setKey(args[1]);
         req.setValue(parseValue(args[2]));
         printResponse(client.sendRequest(req));
     }
     
     private void handleScan(String[] args) {
         if (args.length == 0) {
             System.out.println("✗ 用法: SCAN <collection>");
             return;
         }
         Response resp = client.sendCommand(CommandType.SCAN, args[0]);
         printResponse(resp);
         if (resp.isSuccess() && resp.getData() instanceof List<?> list) {
             if (list.isEmpty()) {
                 System.out.println("  (集合为空)");
             } else {
                 System.out.println("  ┌──────┬────────────────┬──────────────────────────────┐");
                 System.out.println("  │ 序号 │ 键             │ 值                            │");
                 System.out.println("  ├──────┼────────────────┼──────────────────────────────┤");
                 int i = 1;
                 for (Object item : list) {
                     if (item instanceof KV kv) {
                         String keyStr = truncate(kv.getKey(), 14);
                         String valStr = truncate(kv.getValueAsString(), 28);
                         System.out.printf("  │ %4d │ %-14s │ %-28s │%n", i++, keyStr, valStr);
                     }
                 }
                 System.out.println("  └──────┴────────────────┴──────────────────────────────┘");
             }
         }
     }
     
     private void handleSave(String[] args) {
         printResponse(client.sendCommand(CommandType.SAVE));
     }
     
     private void handleLoad(String[] args) {
         printResponse(client.sendCommand(CommandType.LOAD, args));
     }
     
     private void handlePing() {
         long start = System.nanoTime();
         Response resp = client.sendCommand(CommandType.PING);
         long time = (System.nanoTime() - start) / 1_000_000;
         if (resp.isSuccess()) {
             System.out.println("✓ " + resp.getMessage() + " (延迟: " + time + "ms)");
         }
     }
     
     private void handleQuit() {
         client.sendCommand(CommandType.QUIT);
         System.out.println("再见！");
     }
     
     private void clearScreen() {
         System.out.print("\033[H\033[2J");
         System.out.flush();
     }
     
     private void printHelp() {
         System.out.println("""
                 ╔══════════════════════════════════════════════════════╗
                 ║              迷你数据库系统 - 命令帮助              ║
                 ╠══════════════════════════════════════════════════════╣
                 ║  数据库操作:                                        ║
                 ║    CREATE DATABASE <name>    创建数据库              ║
                 ║    DROP DATABASE <name>      删除数据库              ║
                 ║    LIST DATABASES            列出所有数据库          ║
                 ║    USE DATABASE <name>       切换数据库              ║
                 ║                                                    ║
                 ║  集合操作:                                          ║
                 ║    CREATE COLLECTION <name>  创建集合               ║
                 ║    DROP COLLECTION <name>    删除集合               ║
                 ║    LIST COLLECTIONS          列出所有集合           ║
                 ║                                                    ║
                 ║  键值操作:                                          ║
                 ║    PUT <col> <key> <value>   插入键值对             ║
                 ║    GET <col> <key>           获取值                 ║
                 ║    DELETE <col> <key>        删除键值对             ║
                 ║    UPDATE <col> <key> <val>  更新值                 ║
                 ║    SCAN <col>                扫描所有键值对         ║
                 ║                                                    ║
                 ║  持久化:                                            ║
                 ║    SAVE                      保存数据               ║
                 ║    LOAD [dbname]             加载数据               ║
                 ║                                                    ║
                 ║  系统:                                              ║
                 ║    HELP                      显示帮助               ║
                 ║    PING                      心跳检测               ║
                 ║    QUIT                      退出                   ║
                 ╚══════════════════════════════════════════════════════╝
                 """);
     }
     
     // ========== 工具方法 ==========
     
     private void printResponse(Response resp) {
         if (resp == null) return;
         if (resp.isSuccess()) {
             System.out.println("✓ " + resp.getMessage());
         } else {
             System.out.println("✗ " + resp.getMessage());
         }
     }
     
     private String[] parseInput(String input) {
         List<String> tokens = new ArrayList<>();
         StringBuilder current = new StringBuilder();
         boolean inQuote = false;
         
         for (char c : input.toCharArray()) {
             if (c == '"') {
                 inQuote = !inQuote;
             } else if (c == ' ' && !inQuote) {
                 if (!current.isEmpty()) {
                     tokens.add(current.toString());
                     current = new StringBuilder();
                 }
             } else {
                 current.append(c);
             }
         }
         if (!current.isEmpty()) {
             tokens.add(current.toString());
         }
         return tokens.toArray(new String[0]);
     }
     
     private Object parseValue(String s) {
         // 尝试解析为整数
         try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
         // 尝试解析为浮点数
         try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
         // 默认为字符串
         return s;
     }
     
     private String truncate(String s, int maxLen) {
         if (s == null) return "null";
         return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
     }
     
     private void printBanner() {
         System.out.println("""
                 ╔═══════════════════════════════════════════╗
                 ║        迷你数据库系统 - 客户端             ║
                 ║           输入 HELP 获取帮助               ║
                 ╚═══════════════════════════════════════════╝
                 """);
     }
 }
