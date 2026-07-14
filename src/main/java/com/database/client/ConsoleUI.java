 package com.database.client;
 
 import com.database.common.*;
 import com.database.core.KV;

 import java.io.IOException;
 import java.util.*;
 
 /**
  * 控制台用户界面 - 提供交互式的命令行操作界面
  * 体现：常用类（Scanner）、字符串处理
  */
public class ConsoleUI {
    private final Client client;
    private final ConsoleReader consoleReader;
    private String currentDb;
    
    public ConsoleUI(Client client) {
        this.client = client;
        this.consoleReader = new ConsoleReader();
        this.currentDb = null;
    }
     
    /**
     * 启动交互式界面
     */
    public void start() {
        printBanner();
        
        while (client.isConnected()) {
            try {
                String prompt = buildPrompt();
                String input = consoleReader.readLine(prompt);
                if (input == null) {
                    System.out.println();
                    handleQuit();
                    break;
                }
                
                if (input.isEmpty()) continue;
                
                try {
                    processInput(input);
                } catch (Exception e) {
                    System.err.println("✗ 命令执行错误: " + e.getMessage());
                }
            } catch (IOException e) {
                System.err.println("✗ 读取输入失败: " + e.getMessage());
                break;
            }
        }
    }

    private String buildPrompt() {
        if (currentDb != null) {
            return "\n" + currentDb + " mini-db> ";
        }
        return "\nmini-db> ";
    }
     
     /**
      * 解析并执行用户输入
      * 体现了：反射（命令分发）、注解处理的思路
      */
      private void processInput(String input) {
          String[] parts = parseInput(input);
          if (parts.length == 0) return;
          
          String first = parts[0];
          if (first.startsWith("!")) {
              handleBang(first);
              return;
          }
          
          String command = first.toUpperCase();
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
             
              // 批量操作
              case "BATCH", "B" -> handleBatch(args);

              // 持久化
              case "SAVE", "SV" -> handleSave(args);
              case "LOAD", "LD" -> handleLoad(args);
             
              // 系统
              case "HELP", "H", "?" -> printHelp();
              case "HISTORY" -> handleHistory();
              case "PING" -> handlePing();
              case "QUIT", "Q", "EXIT" -> handleQuit();
              case "CLEAR", "CLS" -> clearScreen();
             
             default -> System.out.println("✗ 未知命令，输入 HELP 查看帮助");
         }
     }
     
      private void handleCreate(String[] args) {
          if (args.length < 2) {
              System.out.println("✗ 用法: CREATE DATABASE|COLLECTION|TABLE <name>");
              return;
          }
          String type = args[0].toUpperCase();
          String name = args[1];
          Response resp;
          if ("DATABASE".equals(type) || "DB".equals(type)) {
              resp = client.sendCommand(CommandType.CREATE_DATABASE, name);
          } else if ("COLLECTION".equals(type) || "COL".equals(type) || "TABLE".equals(type) || "TBL".equals(type)) {
              resp = client.sendCommand(CommandType.CREATE_COLLECTION, name);
          } else {
              System.out.println("✗ 类型错误: " + type);
              return;
          }
          printResponse(resp);
      }
     
      private void handleDrop(String[] args) {
          if (args.length < 2) {
              System.out.println("✗ 用法: DROP DATABASE|COLLECTION|TABLE <name>");
              return;
          }
          String type = args[0].toUpperCase();
          String name = args[1];
          Response resp;
          if ("DATABASE".equals(type) || "DB".equals(type)) {
              resp = client.sendCommand(CommandType.DROP_DATABASE, name);
          } else if ("COLLECTION".equals(type) || "COL".equals(type) || "TABLE".equals(type) || "TBL".equals(type)) {
              resp = client.sendCommand(CommandType.DROP_COLLECTION, name);
          } else {
              System.out.println("✗ 类型错误: " + type);
              return;
          }
          printResponse(resp);
      }

      private void handleList(String[] args) {
          if (args.length == 0) {
              System.out.println("✗ 用法: LIST DATABASES|COLLECTIONS|TABLES");
              return;
          }
          String type = args[0].toUpperCase();
          Response resp;
          if ("DATABASES".equals(type) || "DB".equals(type)) {
              resp = client.sendCommand(CommandType.LIST_DATABASES);
              printResponse(resp);
              if (resp.isSuccess() && resp.getData() instanceof Set<?> databases) {
                  if (databases.isEmpty()) {
                      System.out.println("  (当前没有数据库，请使用 CREATE DATABASE 创建)");
                  } else {
                      System.out.println("  数据库列表:");
                      for (Object db : databases) {
                          System.out.println("    - " + db);
                      }
                  }
              }
          } else if ("COLLECTIONS".equals(type) || "COLS".equals(type) || "TABLES".equals(type) || "TBL".equals(type)) {
              resp = client.sendCommand(CommandType.LIST_COLLECTIONS);
              printResponse(resp);
              if (resp.isSuccess() && resp.getData() instanceof Set<?> collections) {
                  if (collections.isEmpty()) {
                      System.out.println("  (当前数据库中没有集合，请使用 CREATE COLLECTION 创建)");
                  } else {
                      System.out.println("  集合列表:");
                      for (Object col : collections) {
                          System.out.println("    - " + col);
                      }
                  }
              }
          } else {
              System.out.println("✗ 类型错误: " + type);
          }
      }

     private void handleUse(String[] args) {
         if (args.length == 0) {
             System.out.println("✗ 用法: USE DATABASE <name>");
             return;
         }

         String dbName;
         String firstArg = args[0].toUpperCase();

         // 智能解析数据库名称
         if ("DATABASE".equals(firstArg) || "DB".equals(firstArg)) {
             // 格式1: USE DATABASE <name> 或 USE DB <name>
             if (args.length < 2) {
                 System.out.println("✗ 用法: USE DATABASE <name>");
                 return;
             }
             dbName = args[1];
         } else {
             // 格式2: USE <name> (简写形式)
             dbName = args[0];
         }

          Response resp = client.sendCommand(CommandType.USE_DATABASE, dbName);
          if (resp.isSuccess()) {
              currentDb = dbName;
          }
          printResponse(resp);
      }
     
      private void handlePut(String[] args) {
          if (args.length < 3) {
              System.out.println("✗ 用法: PUT <collection> <key> <field:value> ...");
              return;
          }
          Request req = new Request(CommandType.PUT);
          req.setCollectionName(args[0]);
          req.setKey(args[1]);
          if (args.length == 3 && !args[2].contains(":")) {
              req.setValue(parseValue(args[2]));
          } else {
              Map<String, Object> doc = new LinkedHashMap<>();
              for (int i = 2; i < args.length; i++) {
                  int colon = args[i].indexOf(':');
                  if (colon > 0) {
                      String field = args[i].substring(0, colon);
                      String val = args[i].substring(colon + 1);
                      if (!field.isEmpty()) {
                          doc.put(field, parseValue(val));
                      }
                  }
              }
              req.setValue(doc);
          }
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
          if (resp.isSuccess() && resp.getData() instanceof KV kv) {
              System.out.println("  键: " + kv.getKey());
              System.out.println("  版本: " + kv.getVersion());
              if (kv.getValue() instanceof Map<?, ?> map) {
                  for (Map.Entry<?, ?> e : map.entrySet()) {
                      System.out.println("    " + e.getKey() + ": " + e.getValue());
                  }
              } else {
                  System.out.println("  值: " + kv.getValue());
              }
          }
      }
     
      private void handleDelete(String[] args) {
          if (args.length < 2) {
              System.out.println("✗ 用法: DELETE <collection> [<key>] 或 DELETE <collection> WHERE <field> = <value>");
              return;
          }
          Request req = new Request(CommandType.DELETE);
          req.setCollectionName(args[0]);

          if (args.length >= 3 && "WHERE".equalsIgnoreCase(args[1])) {
              if (args.length >= 5 && "=".equals(args[3])) {
                  req.setFilterField(args[2]);
                  req.setFilterValue(parseValue(args[4]));
              } else if (args.length >= 4 && args[3].contains("=")) {
                  int eq = args[3].indexOf('=');
                  req.setFilterField(args[3].substring(0, eq));
                  req.setFilterValue(parseValue(args[3].substring(eq + 1)));
              } else if (args.length >= 3 && args[2].contains("=")) {
                  int eq = args[2].indexOf('=');
                  req.setFilterField(args[2].substring(0, eq));
                  req.setFilterValue(parseValue(args[2].substring(eq + 1)));
              } else {
                  System.out.println("✗ 用法: DELETE <collection> WHERE <field> = <value>");
                  return;
              }
              printResponse(client.sendRequest(req));
              return;
          }

          req.setKey(args[1]);
          printResponse(client.sendRequest(req));
      }
     
      private void handleUpdate(String[] args) {
          if (args.length < 3) {
              System.out.println("✗ 用法: UPDATE <collection> <key> <f:v> ... 或 UPDATE <collection> WHERE <f>=<v> <sf:sv> ...");
              return;
          }
          Request req = new Request(CommandType.UPDATE);
          req.setCollectionName(args[0]);

          // UPDATE <col> WHERE <field> = <value> <sf:sv> ...  (批量条件更新)
          if (args.length >= 3 && "WHERE".equalsIgnoreCase(args[1])) {
              int setStart;
              if (args.length >= 5 && "=".equals(args[3])) {
                  req.setFilterField(args[2]);
                  req.setFilterValue(parseValue(args[4]));
                  setStart = 5;
              } else if (args.length >= 4 && args[3].contains("=")) {
                  int eq = args[3].indexOf('=');
                  req.setFilterField(args[3].substring(0, eq));
                  req.setFilterValue(parseValue(args[3].substring(eq + 1)));
                  setStart = 4;
              } else if (args.length >= 3 && args[2].contains("=")) {
                  int eq = args[2].indexOf('=');
                  req.setFilterField(args[2].substring(0, eq));
                  req.setFilterValue(parseValue(args[2].substring(eq + 1)));
                  setStart = 3;
              } else {
                  System.out.println("✗ 用法: UPDATE <collection> WHERE <field> = <value> <field:value> ...");
                  return;
              }
              Map<String, Object> setData = new LinkedHashMap<>();
              for (int i = setStart; i < args.length; i++) {
                  int colon = args[i].indexOf(':');
                  if (colon > 0) {
                      String field = args[i].substring(0, colon);
                      String val = args[i].substring(colon + 1);
                      if (!field.isEmpty()) {
                          setData.put(field, parseValue(val));
                      }
                  }
              }
              if (setData.isEmpty()) {
                  System.out.println("✗ 请指定要更新的字段");
                  return;
              }
              req.setValue(setData);
              printResponse(client.sendRequest(req));
              return;
          }

          // UPDATE <col> <key> <f:v> ...  (单文档)
          req.setKey(args[1]);
          if (args.length == 3 && !args[2].contains(":")) {
              req.setValue(parseValue(args[2]));
          } else {
              Map<String, Object> doc = new LinkedHashMap<>();
              for (int i = 2; i < args.length; i++) {
                  int colon = args[i].indexOf(':');
                  if (colon > 0) {
                      String field = args[i].substring(0, colon);
                      String val = args[i].substring(colon + 1);
                      if (!field.isEmpty()) {
                          doc.put(field, parseValue(val));
                      }
                  }
              }
              req.setValue(doc);
          }
          printResponse(client.sendRequest(req));
      }

      private void handleBatch(String[] args) {
          if (args.length < 2) {
              System.out.println("✗ 用法: BATCH PUT|UPDATE <collection> <k1> <f:v> ... || <k2> <f:v> ...");
              return;
          }
          String subCmd = args[0].toUpperCase();
          String[] batchArgs = Arrays.copyOfRange(args, 1, args.length);
          switch (subCmd) {
              case "PUT", "P" -> handleBatchPut(batchArgs);
              case "UPDATE", "UPD" -> handleBatchUpdate(batchArgs);
              default -> System.out.println("✗ 未知子命令: " + subCmd + "，可用: PUT, UPDATE");
          }
      }

      private void handleBatchPut(String[] batchArgs) {
          if (batchArgs.length < 2) {
              System.out.println("✗ 用法: BATCH PUT <collection> <k1> <f:v> ... || <k2> <f:v> ...");
              return;
          }
          Request req = new Request(CommandType.BATCH_PUT);
          req.setCollectionName(batchArgs[0]);
          Map<String, Object> entries = parseBatchEntries(
                  Arrays.copyOfRange(batchArgs, 1, batchArgs.length));
          if (entries.isEmpty()) {
              System.out.println("✗ 没有有效的条目");
              return;
          }
          req.setBatchData(entries);
          printResponse(client.sendRequest(req));
      }

      private void handleBatchUpdate(String[] batchArgs) {
          if (batchArgs.length < 2) {
              System.out.println("✗ 用法: BATCH UPDATE <collection> <k1> <f:v> ... || <k2> <f:v> ...");
              return;
          }
          Request req = new Request(CommandType.BATCH_UPDATE);
          req.setCollectionName(batchArgs[0]);
          Map<String, Object> entries = parseBatchEntries(
                  Arrays.copyOfRange(batchArgs, 1, batchArgs.length));
          if (entries.isEmpty()) {
              System.out.println("✗ 没有有效的条目");
              return;
          }
          req.setBatchData(entries);
          printResponse(client.sendRequest(req));
      }

      private Map<String, Object> parseBatchEntries(String[] tokens) {
          Map<String, Object> entries = new LinkedHashMap<>();
          String currentKey = null;
          Map<String, Object> currentDoc = null;
          for (String token : tokens) {
              if ("||".equals(token)) {
                  if (currentKey != null) {
                      entries.put(currentKey, currentDoc);
                      currentKey = null;
                      currentDoc = null;
                  }
              } else if (token.contains(":")) {
                  if (currentKey == null) continue;
                  int colon = token.indexOf(':');
                  String field = token.substring(0, colon);
                  String val = token.substring(colon + 1);
                  if (!field.isEmpty()) {
                      currentDoc.put(field, parseValue(val));
                  }
              } else {
                  if (currentKey != null) {
                      entries.put(currentKey, currentDoc);
                  }
                  currentKey = token;
                  currentDoc = new LinkedHashMap<>();
              }
          }
          if (currentKey != null) {
              entries.put(currentKey, currentDoc);
          }
          return entries;
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
                   List<KV> kvs = new ArrayList<>();
                   for (Object item : list) {
                       if (item instanceof KV kv) kvs.add(kv);
                   }
                   boolean allMap = !kvs.isEmpty();
                   for (KV kv : kvs) {
                       if (!(kv.getValue() instanceof Map)) { allMap = false; break; }
                   }
                   if (allMap) {
                       printScanTable(kvs);
                   } else {
                       printScanList(kvs);
                   }
               }
           }
       }

         private void printScanTable(List<KV> kvs) {
             Set<String> fieldSet = new LinkedHashSet<>();
             for (KV kv : kvs) {
                 fieldSet.addAll(((Map<String, Object>) kv.getValue()).keySet());
             }
             List<String> fields = new ArrayList<>(fieldSet);
             int keyW = displayWidth("键");
             for (KV kv : kvs) keyW = Math.max(keyW, displayWidth(kv.getKey()));
             Map<String, Integer> colW = new HashMap<>();
             for (String f : fields) colW.put(f, displayWidth(f));
             for (KV kv : kvs) {
                 Map<String, Object> m = (Map<String, Object>) kv.getValue();
                 for (String f : fields) {
                     Object v = m.get(f);
                     colW.put(f, Math.max(colW.get(f), displayWidth(v != null ? v.toString() : "null")));
                 }
             }
             System.out.println(tableLine("┌", "┬", "┐", keyW, fields, colW));
             StringBuilder hdr = new StringBuilder("  │ ").append(padRight("键", keyW)).append(" ");
             for (String f : fields) hdr.append("│ ").append(padRight(f, colW.get(f))).append(" ");
             hdr.append("│");
             System.out.println(hdr);
             System.out.println(tableLine("├", "┼", "┤", keyW, fields, colW));
             for (KV kv : kvs) {
                 Map<String, Object> m = (Map<String, Object>) kv.getValue();
                 StringBuilder row = new StringBuilder("  │ ").append(padRight(kv.getKey(), keyW)).append(" ");
                 for (String f : fields) {
                     Object v = m.get(f);
                     row.append("│ ").append(padRight(v != null ? v.toString() : "null", colW.get(f))).append(" ");
                 }
                 row.append("│");
                 System.out.println(row);
             }
             System.out.println(tableLine("└", "┴", "┘", keyW, fields, colW));
         }

        private String tableLine(String l, String m, String r, int keyW, List<String> fields, Map<String, Integer> colW) {
            StringBuilder sb = new StringBuilder("  ").append(l);
            sb.append(rep(keyW + 2)).append(m);
            for (String f : fields) { sb.append(rep(colW.get(f) + 2)).append(m); }
            sb.setCharAt(sb.length() - 1, r.charAt(0));
            return sb.toString();
        }

        private String rep(int n) { return "─".repeat(Math.max(0, n)); }

       private void printScanList(List<KV> kvs) {
           System.out.println("  ┌──────┬────────────────────────────────────────────────┐");
           System.out.println("  │ 序号 │ 数据                                            │");
           System.out.println("  ├──────┼────────────────────────────────────────────────┤");
           int i = 1;
           for (KV kv : kvs) {
               String keyStr = kv.getKey();
               Object val = kv.getValue();
               System.out.printf("  │ %4d │ 键: %-44s │%n", i, truncate(keyStr, 44));
               if (val instanceof Map<?, ?> map) {
                   for (Map.Entry<?, ?> e : map.entrySet()) {
                       System.out.printf("  │      │   %s: %-39s │%n",
                           truncate(String.valueOf(e.getKey()), 10),
                           truncate(String.valueOf(e.getValue()), 28));
                   }
               } else {
                   System.out.printf("  │      │ 值: %-44s │%n",
                       truncate(val != null ? val.toString() : "null", 44));
               }
               if (i < kvs.size()) {
                   System.out.println("  ├──────┼────────────────────────────────────────────────┤");
               }
               i++;
           }
           System.out.println("  └──────┴────────────────────────────────────────────────┘");
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
          try {
              client.sendCommand(CommandType.QUIT);
          } catch (Exception ignored) {}
          System.out.println("再见！");
          client.disconnect();
      }

      private void handleBang(String cmd) {
          String numStr = cmd.substring(1);
          try {
              int n = Integer.parseInt(numStr) - 1;
              List<String> history = consoleReader.getHistory();
              if (n >= 0 && n < history.size()) {
                  String prevCmd = history.get(n);
                  System.out.println("  " + prevCmd);
                  processInput(prevCmd);
              } else {
                  System.out.println("✗ 历史索引超出范围，共 " + history.size() + " 条");
              }
          } catch (NumberFormatException e) {
              System.out.println("✗ 用法: !<序号>  例: !3 执行第 3 条历史命令");
          }
      }

      private void handleHistory() {
          List<String> history = consoleReader.getHistory();
          if (history.isEmpty()) {
              System.out.println("  (暂无历史命令)");
          } else {
              System.out.println("  最近 " + history.size() + " 条命令:");
              int start = Math.max(0, history.size() - 50);
              for (int i = start; i < history.size(); i++) {
                  System.out.printf("  %4d  %s%n", i + 1, history.get(i));
              }
          }
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
                  ║  集合/表操作:                                        ║
                  ║    CREATE COLLECTION|TABLE <n>  创建集合            ║
                  ║    DROP COLLECTION|TABLE <n>    删除集合            ║
                  ║    LIST COLLECTIONS|TABLES      列出所有集合        ║
                  ║                                                    ║
                  ║  键值操作（NoSQL 文档模式）:                          ║
                  ║    PUT <col> <key> <f:v> ...  替换整个文档           ║
                  ║    GET <col> <key>             获取文档              ║
                  ║    UPDATE <col> <key> <f:v>... 更新指定字段（$set）  ║
                  ║    DELETE <col> <key>          删除文档              ║
                  ║    DELETE <col> WHERE f=v     按条件删除             ║
                  ║  SCAN <col>                 扫描所有文档           ║
                  ║                                                    ║
                  ║  批量操作:                                          ║
                  ║    BATCH PUT <c> <k1> f:v... \|\| <k2>...  批量插入 ║
                  ║    BATCH UPDATE <c> <k1> f:v... \|\| <k2>... 批量更新║
                  ║    UPDATE <c> WHERE f=v sf:sv...   条件批量更新    ║
                  ║                                                    ║
                  ║  持久化:                                            ║
                 ║    SAVE                      保存数据               ║
                 ║    LOAD [dbname]             加载数据               ║
                 ║                                                    ║
                 ║  系统:                                              ║
                 ║    HELP                      显示帮助               ║
                 ║    PING                      心跳检测               ║
                  ║    QUIT                      退出                   ║
                  ║    HISTORY                   显示历史命令            ║
                  ║    !<n>                      执行第 n 条历史命令     ║
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

      private int displayWidth(String s) {
          if (s == null) return 0;
          int w = 0;
          for (char c : s.toCharArray()) {
              if (c >= '\u4e00' && c <= '\u9fff' ||
                  c >= '\u3000' && c <= '\u303f' ||
                  c >= '\uff00' && c <= '\uffef') {
                  w += 2;
              } else {
                  w += 1;
              }
          }
          return w;
      }

      private String padRight(String s, int len) {
          if (s == null) s = "null";
          int dl = displayWidth(s);
          return dl < len ? s + " ".repeat(len - dl) : s;
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
