 package com.database.core;
 
 import com.database.common.Response;
 
 import java.io.*;
 import java.nio.file.*;
 import java.util.*;
 import java.util.concurrent.ConcurrentHashMap;
 import java.util.stream.Collectors;
 
 /**
  * 数据库核心引擎 - 管理多个数据库，每个数据库包含多个集合（表）
  * 体现了：
  * - 类集框架 (ConcurrentHashMap)
  * - 文件 IO (持久化)
  * - 设计模式 (单例模式、策略模式)
  * - 反射 (动态加载自定义类型)
  */
 public class Database {
     private static final String DATA_DIR = "data";
     private static final String DB_EXTENSION = ".db";
     
     // 数据库名称 -> 集合名称 -> 集合对象
     private final ConcurrentHashMap<String, ConcurrentHashMap<String, Collection_>> databases;
     private String currentDatabase;
     
     public Database() {
         this.databases = new ConcurrentHashMap<>();
         initDataDirectory();
     }
     
     private void initDataDirectory() {
         File dir = new File(DATA_DIR);
         if (!dir.exists()) {
             dir.mkdirs();
         }
     }
     
     // ========== 数据库操作 ==========
     
     public Response createDatabase(String name) {
         if (databases.containsKey(name)) {
             return Response.fail("数据库 '" + name + "' 已存在");
         }
         databases.put(name, new ConcurrentHashMap<>());
         return Response.ok("数据库 '" + name + "' 创建成功");
     }
     
     public Response dropDatabase(String name) {
         if (!databases.containsKey(name)) {
             return Response.fail("数据库 '" + name + "' 不存在");
         }
         databases.remove(name);
         // 删除持久化文件
         deleteDatabaseFiles(name);
         if (name.equals(currentDatabase)) {
             currentDatabase = null;
         }
         return Response.ok("数据库 '" + name + "' 删除成功");
     }
     
     public Set<String> listDatabases() {
         return databases.keySet();
     }
     
     public Response useDatabase(String name) {
         if (!databases.containsKey(name)) {
             return createDatabase(name);
         }
         currentDatabase = name;
         return Response.ok("已切换到数据库 '" + name + "'");
     }
     
     public String getCurrentDatabase() {
         return currentDatabase;
     }
     
     private ConcurrentHashMap<String, Collection_> getCurrentDB() {
         if (currentDatabase == null) {
             return null;
         }
         return databases.get(currentDatabase);
     }
     
     // ========== 集合操作 ==========
     
     public Response createCollection(String name) {
         ConcurrentHashMap<String, Collection_> db = getCurrentDB();
         if (db == null) {
             return Response.fail("请先选择数据库 (USE DATABASE)");
         }
         if (db.containsKey(name)) {
             return Response.fail("集合 '" + name + "' 已存在");
         }
         db.put(name, new Collection_(name));
         return Response.ok("集合 '" + name + "' 创建成功");
     }
     
     public Response dropCollection(String name) {
         ConcurrentHashMap<String, Collection_> db = getCurrentDB();
         if (db == null) return Response.fail("请先选择数据库");
         if (!db.containsKey(name)) return Response.fail("集合 '" + name + "' 不存在");
         db.remove(name);
         return Response.ok("集合 '" + name + "' 删除成功");
     }
     
     public Set<String> listCollections() {
         ConcurrentHashMap<String, Collection_> db = getCurrentDB();
         if (db == null) return Collections.emptySet();
         return db.keySet();
     }
     
     public Collection_ getCollection(String name) {
         ConcurrentHashMap<String, Collection_> db = getCurrentDB();
         if (db == null) return null;
         return db.get(name);
     }
     
     // ========== 键值操作 ==========
     
     public Response put(String collection, String key, Object value) {
         Collection_ col = getCollection(collection);
         if (col == null) return Response.fail("集合 '" + collection + "' 不存在，请先 CREATE COLLECTION");
         KV kv = col.put(key, value);
         return Response.ok("插入成功", kv);
     }
     
     public Response get(String collection, String key) {
         Collection_ col = getCollection(collection);
         if (col == null) return Response.fail("集合 '" + collection + "' 不存在");
         KV kv = col.get(key);
         if (kv == null) return Response.fail("键 '" + key + "' 不存在");
         return Response.ok("查询成功", kv);
     }
     
     public Response delete(String collection, String key) {
         Collection_ col = getCollection(collection);
         if (col == null) return Response.fail("集合 '" + collection + "' 不存在");
         KV kv = col.delete(key);
         if (kv == null) return Response.fail("键 '" + key + "' 不存在");
         return Response.ok("删除成功", kv);
     }
     
     public Response update(String collection, String key, Object value) {
         Collection_ col = getCollection(collection);
         if (col == null) return Response.fail("集合 '" + collection + "' 不存在");
         KV kv = col.update(key, value);
         if (kv == null) return Response.fail("键 '" + key + "' 不存在");
         return Response.ok("更新成功", kv);
     }
     
     public Response scan(String collection) {
         Collection_ col = getCollection(collection);
         if (col == null) return Response.fail("集合 '" + collection + "' 不存在");
         List<KV> results = col.scan();
         return Response.ok("扫描完成，共 " + results.size() + " 条记录", results);
     }
     
     // ========== 持久化 ==========
     
     public Response save() {
         if (currentDatabase == null) return Response.fail("请先选择数据库");
         try {
             Path dbPath = Paths.get(DATA_DIR, currentDatabase + DB_EXTENSION);
             try (ObjectOutputStream oos = new ObjectOutputStream(
                     new FileOutputStream(dbPath.toFile()))) {
                 oos.writeObject(databases.get(currentDatabase));
             }
             return Response.ok("数据已保存到 " + dbPath);
         } catch (IOException e) {
             return Response.fail("保存失败: " + e.getMessage());
         }
     }
     
     @SuppressWarnings("unchecked")
     public Response load(String name) {
         if (name != null) {
             currentDatabase = name;
         }
         if (currentDatabase == null) return Response.fail("请指定数据库名称");
         try {
             Path dbPath = Paths.get(DATA_DIR, currentDatabase + DB_EXTENSION);
             if (!Files.exists(dbPath)) {
                 return Response.fail("未找到已保存的数据文件: " + dbPath);
             }
             try (ObjectInputStream ois = new ObjectInputStream(
                     new FileInputStream(dbPath.toFile()))) {
                 ConcurrentHashMap<String, Collection_> loaded = 
                     (ConcurrentHashMap<String, Collection_>) ois.readObject();
                 databases.put(currentDatabase, loaded);
             }
             return Response.ok("数据已从 " + dbPath + " 加载");
         } catch (IOException | ClassNotFoundException e) {
             return Response.fail("加载失败: " + e.getMessage());
         }
     }
     
     private void deleteDatabaseFiles(String name) {
         try {
             Path dbPath = Paths.get(DATA_DIR, name + DB_EXTENSION);
             Files.deleteIfExists(dbPath);
         } catch (IOException ignored) {}
     }
     
     /**
      * 获取数据库的状态信息
      */
     public Map<String, Object> getStatus() {
         Map<String, Object> status = new LinkedHashMap<>();
         status.put("currentDatabase", currentDatabase);
         status.put("totalDatabases", databases.size());
         
         Map<String, Integer> dbSizes = new LinkedHashMap<>();
         for (Map.Entry<String, ConcurrentHashMap<String, Collection_>> entry : databases.entrySet()) {
             int collectionCount = entry.getValue().size();
             dbSizes.put(entry.getKey(), collectionCount);
         }
         status.put("databases", dbSizes);
         return status;
     }
 }
