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

### bug7:put功能有点神秘

#### support1:在mini-db前加一个
#### support2:把展示的集合collection和collections和cols换成table和tables和（？你取一个缩写）
#### support3:做一个cmd的记录功能，记录最后1000条指令，按上下来切换。





















