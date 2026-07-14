### bug1:我在使用数据库的时候，输入list databases的结果是没有的，但是我create database mimimidb的时候说mimimidb已存在
- 原因：
- 修复：
- 把cluster/ConsoleUI.java里的
```java
    private void handleQuit() {
         client.sendCommand(CommandType.QUIT);
         System.out.println("再见！");
     }
```
方法改为
```java
     private void handleQuit() {
         try {
             client.sendCommand(CommandType.QUIT);
         } catch (Exception ignored) {}
         System.out.println("再见！");
         client.disconnect();
     }
```
即可

### bug2:我在删除mimimidb后输入list db依旧会有mimimidb，但是list cols没有mimimitable，输入save也提示我没有选择库，任何mimimidb.db依旧在data包里
- 原因：
- 修复：
- 修改client/ConsoleUI.java的handleList方法
```java
     private void handleList(String[] args) {
         if (args.length == 0) {
             System.out.println("✗ 用法: LIST DATABASES|COLLECTIONS");
             return;
         }
         String type = args[0].toUpperCase();
         Response resp;
         if ("DATABASES".equals(type) || "DB".equals(type)) {
             resp = client.sendCommand(CommandType.LIST_DATABASES);
             printResponse(resp);
             if (resp.isSuccess() && resp.getData() instanceof Set<?> databases) {
                 if (databases.isEmpty()) {
                     System.out.println("  (没有数据库)");
                 } else {
                     System.out.println("  数据库列表:");
                     for (Object db : databases) {
                         System.out.println("    - " + db);
                     }
                 }
             }
         } else if ("COLLECTIONS".equals(type) || "COLS".equals(type)) {
             resp = client.sendCommand(CommandType.LIST_COLLECTIONS);
             printResponse(resp);
             if (resp.isSuccess() && resp.getData() instanceof Set<?> collections) {
                 if (collections.isEmpty()) {
                     System.out.println("  (没有集合)");
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
```

### bug3:use <database>的时候我输成了use db <database>
- 原因：健壮性不好
- 修复：写防御性代码，提高健壮性
- 修改client/ConsoleUI.java的handleUse方法
```java
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
         printResponse(resp);
     }
```
即可


### bug4:新启动一个服务器后，新启动一个客户端，在客户端先list db后create db mimidb后list db会查询不到mimidb，但是用新服务器和新客户端后在客户端先create db mimidb后list db能够查询得到mimidb
- 原因：Java 的 `ObjectOutputStream` 对同一个对象实例做引用跟踪。`ConcurrentHashMap.keySet()` 每次返回同一个 `KeySetView` 视图对象。第一次 `listDatabases()` 时该视图被完整序列化（此时 Map 为空），随后 `create database mimidb` 修改了底层 Map，但第二次 `listDatabases()` 返回的仍是同一个 `KeySetView` 对象，`ObjectOutputStream` 发现该对象已序列化过，只写入一个引用句柄，导致客户端反序列化得到的是第一次的空集合。
- 修复：将 `keySet()` 返回值包装为 `new HashSet<>()`，破坏对象引用同一性，确保每次返回独立副本。
- 修改 `core/Database.java` 的 `listDatabases()` 和 `listCollections()` 方法
```java
// 修改前
public Set<String> listDatabases() {
    return databases.keySet();
}

// 修改后
public Set<String> listDatabases() {
    return new HashSet<>(databases.keySet());
}
```

```java
// 修改前
public Set<String> listCollections() {
    ConcurrentHashMap<String, Collection_> db = getCurrentDB();
    if (db == null) return Collections.emptySet();
    return db.keySet();
}

// 修改后
public Set<String> listCollections() {
    ConcurrentHashMap<String, Collection_> db = getCurrentDB();
    if (db == null) return Collections.emptySet();
    return new HashSet<>(db.keySet());
}
```

### bug5:scan指令遇到name是null的时候会触发空指针异常导致程序终端
- 原因：scan指令遇到name是null的时候会触发空指针异常导致程序终端
- 修复：if添加空值判断
- 修改 
```java
// 修改后
public Collection_ getCollection(String name) {
    if (name == null) {
        return null;
    }
    ConcurrentHashMap<String, Collection_> db = getCurrentDB();
    if (db == null) return null;
    return db.get(name);
}

public Response put(String collection, String key, Object value) {
    if (collection == null || key == null) {
        return Response.fail("集合名称和键不能为空");
    }
    Collection_ col = getCollection(collection);
    if (col == null) return Response.fail("集合 '" + collection + "' 不存在，请先 CREATE COLLECTION");
    KV kv = col.put(key, value);
    return Response.ok("插入成功", kv);
}

public Response get(String collection, String key) {
    if (collection == null || key == null) {
        return Response.fail("集合名称和键不能为空");
    }
    Collection_ col = getCollection(collection);
    if (col == null) return Response.fail("集合 '" + collection + "' 不存在");
    KV kv = col.get(key);
    if (kv == null) return Response.fail("键 '" + key + "' 不存在");
    return Response.ok("查询成功", kv);
}

public Response delete(String collection, String key) {
    if (collection == null || key == null) {
        return Response.fail("集合名称和键不能为空");
    }
    Collection_ col = getCollection(collection);
    if (col == null) return Response.fail("集合 '" + collection + "' 不存在");
    KV kv = col.delete(key);
    if (kv == null) return Response.fail("键 '" + key + "' 不存在");
    return Response.ok("删除成功", kv);
}

public Response update(String collection, String key, Object value) {
    if (collection == null || key == null) {
        return Response.fail("集合名称和键不能为空");
    }
    Collection_ col = getCollection(collection);
    if (col == null) return Response.fail("集合 '" + collection + "' 不存在");
    KV kv = col.update(key, value);
    if (kv == null) return Response.fail("键 '" + key + "' 不存在");
    return Response.ok("更新成功", kv);
}

public Response scan(String collection) {
    if (collection == null) {
        return Response.fail("集合名称不能为空");
    }
    Collection_ col = getCollection(collection);
    if (col == null) return Response.fail("集合 '" + collection + "' 不存在");
    List<KV> results = col.scan();
    return Response.ok("扫描完成，共 " + results.size() + " 条记录", results);
}
```

### bug6: scan任何值都会显示集合名称不能为空
- 原因：database.scan(request.getCollectionName());返回 null，因为客户端没有设置这个字段
- 修复：把request.getCollectionName()改为request.getArgs().length > 0 ? request.getArgs()[0] : null
- put、get、delete、update、scan都要改掉
- 修改 server/ClientHandler.java的processRequest方法
```
        // 键值操作
             case PUT:
                 // 优先使用字段，如果没有则从 args 获取
                 String putCol = request.getCollectionName() != null ?
                         request.getCollectionName() :
                         (request.getArgs().length > 0 ? request.getArgs()[0] : null);
                 String putKey = request.getKey() != null ?
                         request.getKey() :
                         (request.getArgs().length > 1 ? request.getArgs()[1] : null);
                 Object putVal = request.getValue();
                 return database.put(putCol, putKey, putVal);

             case GET:
                 String getCol = request.getCollectionName() != null ?
                         request.getCollectionName() :
                         (request.getArgs().length > 0 ? request.getArgs()[0] : null);
                 String getKey = request.getKey() != null ?
                         request.getKey() :
                         (request.getArgs().length > 1 ? request.getArgs()[1] : null);
                 return database.get(getCol, getKey);

             case DELETE:
                 String delCol = request.getCollectionName() != null ?
                         request.getCollectionName() :
                         (request.getArgs().length > 0 ? request.getArgs()[0] : null);
                 String delKey = request.getKey() != null ?
                         request.getKey() :
                         (request.getArgs().length > 1 ? request.getArgs()[1] : null);
                 return database.delete(delCol, delKey);

             case UPDATE:
                 String updCol = request.getCollectionName() != null ?
                         request.getCollectionName() :
                         (request.getArgs().length > 0 ? request.getArgs()[0] : null);
                 String updKey = request.getKey() != null ?
                         request.getKey() :
                         (request.getArgs().length > 1 ? request.getArgs()[1] : null);
                 Object updVal = request.getValue();
                 return database.update(updCol, updKey, updVal);

             case SCAN:
                 String scanCol = request.getCollectionName() != null ?
                         request.getCollectionName() :
                         (request.getArgs().length > 0 ? request.getArgs()[0] : null);
                 return database.scan(scanCol);

             case LIST_KEYS:
                 String listCol = request.getCollectionName() != null ?
                         request.getCollectionName() :
                         (request.getArgs().length > 0 ? request.getArgs()[0] : null);
                 return database.scan(listCol);
```

### bug7:put 的 key 重复时覆盖旧数据
- 原因：原实现中 `Collection_.put()` 直接覆盖相同 key 的值，不符合用户期望的"相同 key 追加"行为
- 修复：修改 `Collection_.put()` —— 若 key 已存在且 value 是 List 则追加，若不是 List 则包装为 List 再追加
- 修改 `core/Collection_.java` 的 `put()` 方法
```java
// 修改后
public KV put(String key, Object value) {
    KV existing = data.get(key);
    if (existing != null) {
        Object oldVal = existing.getValue();
        List<Object> list;
        if (oldVal instanceof List<?> oldList) {
            list = new ArrayList<>(oldList);
        } else {
            list = new ArrayList<>();
            list.add(oldVal);
        }
        list.add(value);
        KV kv = new KV(key, list, nextVersion++);
        data.put(key, kv);
        return kv;
    }
    KV kv = new KV(key, value, nextVersion++);
    data.put(key, kv);
    return kv;
}
```

### bug8:重启程序后，前一个程序删除的内容全回来了
- 原因：墓碑标记在程序中，而SST和WAL持久化在磁盘中，重启程序后，磁盘中的数据会恢复
- 修复：
- 修改：
```java
//修改前

// 修改后

```

### support1:在 mini-db 提示符前显示当前数据库名
- 说明：使用 `USE DATABASE <name>` 切换到某个数据库后，提示符会变为 `<name> mini-db>`，提示当前所在数据库。
- 实现：`ConsoleUI` 增加 `currentDb` 字段记录当前数据库，`handleUse()` 在 `USE` 成功后更新该字段，`buildPrompt()` 据此生成提示符。
- 修改 `client/ConsoleUI.java`

### support2:支持 TABLE / TABLES / TBL 作为 COLLECTION 的别名
- 说明：`CREATE TABLE`、`DROP TABLE`、`LIST TABLES`、`CREATE TBL`、`LIST TBL` 等均等同于 COLLECTION 操作。缩写的 `TBL` 与 `TABLE` 功能一致。
- 修改 `client/ConsoleUI.java` 的 `handleCreate()`、`handleDrop()`、`handleList()`、`printHelp()`

### support3:命令历史记录功能（HISTORY / !n）
- 说明：记录最近 1000 条命令，通过 `HISTORY` 查看、`!<n>` 快捷执行。IDEA Run 控制台不支持 ↑↓ 翻历史（伪终端限制），改用命令替代。
- 实现：`ConsoleReader` 使用 `BufferedReader` 读取输入，`ArrayList` 维护历史。支持中文输入。
- 修改 `client/ConsoleReader.java`、`client/ConsoleUI.java`（添加 `handleHistory`、`handleBang`）

### support4:在put的时候有key:value，然后where条件有key=value，有些混乱，可以让where的也是用:

### support5:历史记录倒叙，最新的命令在最前面

### doc-put:PUT 改为文档式（兼容 MySQL 行概念）
- 说明：`PUT students s001 name:小明 age:18` 将多个字段作为 Map 存入，同 key 再次 PUT 时合并字段（新增/覆盖）。SCAN 显示为表格。
- 改动：
  - `core/Collection_.java` — `put()` 方法：旧值和新值均为 Map 时执行 merge，否则直接覆盖
  - `client/ConsoleUI.java` — `handlePut()`：解析 `field:value` 语法；`handleScan()`：全部为 Map 值时以表格展示；`handleGet()`：Map 值分行展示
