 package com.database.core;
 
 import com.database.common.Response;
 
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
 
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
    private final CompressionService compressionService;
    private final Map<String, WalWriter> walWriters = new ConcurrentHashMap<>();
    private final Map<String, Integer> generationCounters = new ConcurrentHashMap<>();

    public Database() {
        this.databases = new ConcurrentHashMap<>();
        this.compressionService = new CompressionService(
            Math.max(2, Runtime.getRuntime().availableProcessors())
        );
        initDataDirectory();
    }

    private void initDataDirectory() {
        File dir = new File(DATA_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private WalWriter getWal() {
        if (currentDatabase == null) return null;
        return walWriters.computeIfAbsent(currentDatabase, WalWriter::new);
    }

    private int nextGeneration(String dbName) {
        return generationCounters.merge(dbName, 1, Integer::sum);
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
        // 关闭并删除 WAL
        WalWriter ww = walWriters.remove(name);
        if (ww != null) ww.close();
        deleteWalFiles(name);
        if (name.equals(currentDatabase)) {
            currentDatabase = null;
        }
        return Response.ok("数据库 '" + name + "' 删除成功");
    }
     
     public Set<String> listDatabases() {
         return new HashSet<>(databases.keySet());
     }

     public Response useDatabase(String name) {
         if (!databases.containsKey(name)) {
             return Response.fail("数据库 '" + name + "' 不存在，请先使用 CREATE DATABASE 创建");
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
         return new HashSet<>(db.keySet());
     }

     public Collection_ getCollection(String name) {
         if (name == null) {
             return null;
         }
         ConcurrentHashMap<String, Collection_> db = getCurrentDB();
         if (db == null) return null;
         return db.get(name);
     }

     // ========== 键值操作 ==========

    public Response put(String collection, String key, Object value) {
        if (collection == null || key == null) {
            return Response.fail("集合名称和键不能为空");
        }
        Collection_ col = getCollection(collection);
        if (col == null) return Response.fail("集合 '" + collection + "' 不存在，请先 CREATE COLLECTION");
        WalWriter wal = getWal();
        if (wal != null) wal.logPut(collection, key, value);
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
        KV kv = col.get(key);
        if (kv == null) return Response.fail("键 '" + key + "' 不存在");
        WalWriter wal = getWal();
        if (wal != null) wal.logDelete(collection, key);
        col.delete(key);
        return Response.ok("删除成功", kv);
    }

      public Response deleteWhere(String collection, String field, Object value) {
          if (collection == null || field == null || value == null) {
              return Response.fail("参数不能为空");
          }
          Collection_ col = getCollection(collection);
          if (col == null) return Response.fail("集合 '" + collection + "' 不存在");
          List<String> toDelete = new ArrayList<>();
          for (KV kv : col.scan()) {
              Object val = kv.getValue();
              if (val instanceof Map<?, ?> map) {
                  Object fieldVal = map.get(field);
                  if (Objects.equals(fieldVal, value)) {
                      toDelete.add(kv.getKey());
                  }
              }
          }
          if (toDelete.isEmpty()) {
              return Response.fail("没有匹配的记录");
          }
          WalWriter wal = getWal();
          for (String key : toDelete) {
              if (wal != null) wal.logDelete(collection, key);
              col.delete(key);
          }
          return Response.ok("删除成功，共删除 " + toDelete.size() + " 条记录");
      }

      public Response update(String collection, String key, Object value) {
          if (collection == null || key == null) {
              return Response.fail("集合名称和键不能为空");
          }
          Collection_ col = getCollection(collection);
          if (col == null) return Response.fail("集合 '" + collection + "' 不存在");
          KV kv = col.get(key);
          if (kv == null) return Response.fail("键 '" + key + "' 不存在");
          WalWriter wal = getWal();
          if (wal != null) wal.logUpdate(collection, key, value);
          col.update(key, value);
          return Response.ok("更新成功", col.get(key));
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

      public Response batchPut(String collection, Map<String, Object> entries) {
          if (collection == null || entries == null || entries.isEmpty()) {
              return Response.fail("参数不能为空");
          }
          WalWriter wal = getWal();
          Collection_ col = getCollection(collection);
          if (col == null) return Response.fail("集合 '" + collection + "' 不存在");
          int count = 0;
          for (Map.Entry<String, Object> entry : entries.entrySet()) {
              if (wal != null) wal.logPut(collection, entry.getKey(), entry.getValue());
              col.put(entry.getKey(), entry.getValue());
              count++;
          }
          return Response.ok("批量插入成功，共插入 " + count + " 条记录");
      }

      public Response batchUpdate(String collection, Map<String, Object> entries) {
          if (collection == null || entries == null || entries.isEmpty()) {
              return Response.fail("参数不能为空");
          }
          WalWriter wal = getWal();
          Collection_ col = getCollection(collection);
          if (col == null) return Response.fail("集合 '" + collection + "' 不存在");
          int count = 0;
          for (Map.Entry<String, Object> entry : entries.entrySet()) {
              if (wal != null) wal.logUpdate(collection, entry.getKey(), entry.getValue());
              KV kv = col.update(entry.getKey(), entry.getValue());
              if (kv != null) count++;
          }
          return Response.ok("批量更新成功，共更新 " + count + " 条记录");
      }

      public Response updateWhere(String collection, String field, Object value, Object updateData) {
          if (collection == null || field == null || value == null || updateData == null) {
              return Response.fail("参数不能为空");
          }
          WalWriter wal = getWal();
          Collection_ col = getCollection(collection);
          if (col == null) return Response.fail("集合 '" + collection + "' 不存在");
          int count = 0;
          for (KV kv : col.scan()) {
              Object val = kv.getValue();
              if (val instanceof Map<?, ?> map) {
                  Object fieldVal = map.get(field);
                  if (Objects.equals(fieldVal, value)) {
                      if (wal != null) wal.logUpdate(collection, kv.getKey(), updateData);
                      col.update(kv.getKey(), updateData);
                      count++;
                  }
              }
          }
          if (count == 0) {
              return Response.fail("没有匹配的记录");
          }
          return Response.ok("批量更新成功，共更新 " + count + " 条记录");
      }
     
    // ========== 持久化 ==========

    public Response save() {
        if (currentDatabase == null) return Response.fail("请先选择数据库");
        try {
            Path dbPath = Paths.get(DATA_DIR, currentDatabase + DB_EXTENSION);
            // 写入快照
            try (ObjectOutputStream oos = new ObjectOutputStream(
                    new FileOutputStream(dbPath.toFile()))) {
                oos.writeObject(databases.get(currentDatabase));
            }
            // WAL checkpoint + truncate
            WalWriter wal = getWal();
            if (wal != null) {
                wal.checkpoint();
                wal.truncate();
            }
            // Rotate 检测
            if (compressionService.needRotate(dbPath)) {
                int gen = nextGeneration(currentDatabase);
                Path rotated = compressionService.rotate(dbPath, gen);
                compressionService.compressAsync(rotated);
                return Response.ok("数据已保存，文件已旋转 (gen=" + gen + ")，后台压缩中");
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
            // WAL recovery: 回放WAL中的操作，恢复到崩溃前状态
            WalWriter wal = getWal();
            if (wal != null) {
                replayWal(currentDatabase);
            }
            return Response.ok("数据已从 " + dbPath + " 加载 (含WAL回放)");
        } catch (IOException | ClassNotFoundException e) {
            return Response.fail("加载失败: " + e.getMessage());
        }
    }

    private void replayWal(String dbName) {
        WalWriter wal = walWriters.get(dbName);
        if (wal == null) return;
        ConcurrentHashMap<String, Collection_> db = databases.get(dbName);
        if (db == null) return;
        List<WalWriter.WalEntry> entries = wal.readAll();
        // 找到最后一个 CHECKPOINT 的位置
        int lastCk = -1;
        for (int i = 0; i < entries.size(); i++) {
            if ("CHECKPOINT".equals(entries.get(i).op)) {
                lastCk = i;
            }
        }
        // 从最后一个 CHECKPOINT 之后开始回放
        for (int i = lastCk + 1; i < entries.size(); i++) {
            WalWriter.WalEntry entry = entries.get(i);
            Collection_ col = db.get(entry.collection);
            if (col == null) {
                col = new Collection_(entry.collection);
                db.put(entry.collection, col);
            }
            switch (entry.op) {
                case "PUT" -> col.put(entry.key, entry.value);
                case "DEL" -> col.delete(entry.key);
                case "UPD" -> col.update(entry.key, entry.value);
            }
        }
    }

    private void deleteDatabaseFiles(String name) {
        try {
            Path dbPath = Paths.get(DATA_DIR, name + DB_EXTENSION);
            Files.deleteIfExists(dbPath);
        } catch (IOException ignored) {}
    }

    private void deleteWalFiles(String name) {
        try {
            Path walDir = Paths.get(DATA_DIR, name, "wal");
            if (Files.exists(walDir)) {
                try (var files = Files.walk(walDir)) {
                    files.sorted(Comparator.reverseOrder())
                         .map(java.nio.file.Path::toFile)
                         .forEach(File::delete);
                }
            }
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
        status.put("rotateThreshold", CompressionService.getRotateThreshold() + " bytes");
        status.put("compressionPoolSize", compressionService.toString());
        return status;
    }

    public void shutdown() {
        for (WalWriter ww : walWriters.values()) {
            ww.close();
        }
        walWriters.clear();
        compressionService.shutdown();
    }
}
