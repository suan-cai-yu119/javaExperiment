 package com.database.core;
 
 import com.database.common.Response;
 
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
 
 /**
  * 数据库核心引擎 - 管理多个数据库，每个数据库包含多个集合（表）
  * 体现了：
  * - 类集框架 (ConcurrentHashMap)
  * - 文件 IO (持久化)
  * - 设计模式 (单例模式、策略模式)
  * - 反射 (动态加载自定义类型)
  */
public class Database {
    private static final Logger LOG = Logger.getLogger(Database.class.getName());
    private static final String DATA_DIR = "data";
    private static final String DB_EXTENSION = ".db";
    private static final long AUTO_SAVE_INTERVAL_MS = 60_000;

    // 数据库名称 -> 集合名称 -> 集合对象
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Collection_>> databases;
    private String currentDatabase;
    private final CompressionService compressionService;
    private final Map<String, WalWriter> walWriters = new ConcurrentHashMap<>();
    private final Map<String, Integer> generationCounters = new ConcurrentHashMap<>();
    private final Set<String> dirtyDatabases = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "auto-save");
        t.setDaemon(true);
        return t;
    });

    public Database() {
        this.databases = new ConcurrentHashMap<>();
        this.compressionService = new CompressionService(
            Math.max(2, Runtime.getRuntime().availableProcessors())
        );
        initDataDirectory();
        scheduler.scheduleWithFixedDelay(this::autoSave,
                AUTO_SAVE_INTERVAL_MS, AUTO_SAVE_INTERVAL_MS, TimeUnit.MILLISECONDS);
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

    private void markDirty() {
        if (currentDatabase != null) dirtyDatabases.add(currentDatabase);
    }

    private void autoSave() {
        for (String db : dirtyDatabases) {
            try {
                if (databases.containsKey(db)) {
                    saveDatabase(db);
                    LOG.info("自动保存数据库 '" + db + "' 成功");
                }
            } catch (Exception e) {
                LOG.warning("自动保存数据库 '" + db + "' 失败: " + e.getMessage());
            }
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
        try {
            WalWriter wal = getWal();
            if (wal != null) wal.logPut(collection, key, value);
            KV kv = col.put(key, value);
            markDirty();
            return Response.ok("插入成功", kv);
        } catch (IOException e) {
            return Response.fail("WAL 写入失败，操作已取消: " + e.getMessage());
        }
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
        try {
            WalWriter wal = getWal();
            if (wal != null) wal.logDelete(collection, key);
            col.delete(key);
            markDirty();
            return Response.ok("删除成功", kv);
        } catch (IOException e) {
            return Response.fail("WAL 写入失败，操作已取消: " + e.getMessage());
        }
    }

      public Response deleteWhere(String collection, String field, Object value) {
          if (collection == null || field == null || value == null) {
              return Response.fail("参数不能为空");
          }
          Collection_ col = getCollection(collection);
          if (col == null) return Response.fail("集合 '" + collection + "' 不存在");
          List<String> toDelete = new ArrayList<>();
          Set<String> indexed = col.hasIndex(field) ? col.lookupIndex(field, value) : null;
          if (indexed != null) {
              toDelete.addAll(indexed);
          } else {
              for (KV kv : col.scan()) {
                  Object val = kv.getValue();
                  if (val instanceof Map<?, ?> map) {
                      Object fieldVal = map.get(field);
                      if (Objects.equals(fieldVal, value)) {
                          toDelete.add(kv.getKey());
                      }
                  }
              }
          }
          if (toDelete.isEmpty()) {
              return Response.fail("没有匹配的记录");
          }
          try {
              WalWriter wal = getWal();
              for (String key : toDelete) {
                  if (wal != null) wal.logDelete(collection, key);
                  col.delete(key);
              }
              markDirty();
              return Response.ok("删除成功，共删除 " + toDelete.size() + " 条记录");
          } catch (IOException e) {
              return Response.fail("WAL 写入失败，操作已取消 (部分删除可能已执行): " + e.getMessage());
          }
      }

      public Response update(String collection, String key, Object value) {
          if (collection == null || key == null) {
              return Response.fail("集合名称和键不能为空");
          }
          Collection_ col = getCollection(collection);
          if (col == null) return Response.fail("集合 '" + collection + "' 不存在");
          KV kv = col.get(key);
          if (kv == null) return Response.fail("键 '" + key + "' 不存在");
          try {
              WalWriter wal = getWal();
              if (wal != null) wal.logUpdate(collection, key, value);
              col.update(key, value);
              markDirty();
              return Response.ok("更新成功", col.get(key));
          } catch (IOException e) {
              return Response.fail("WAL 写入失败，操作已取消: " + e.getMessage());
          }
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
          Collection_ col = getCollection(collection);
          if (col == null) return Response.fail("集合 '" + collection + "' 不存在");
          try {
              WalWriter wal = getWal();
              int count = 0;
              for (Map.Entry<String, Object> entry : entries.entrySet()) {
                  if (wal != null) wal.logPut(collection, entry.getKey(), entry.getValue());
                  col.put(entry.getKey(), entry.getValue());
                  count++;
              }
              markDirty();
              return Response.ok("批量插入成功，共插入 " + count + " 条记录");
          } catch (IOException e) {
              return Response.fail("WAL 写入失败，批量操作已取消: " + e.getMessage());
          }
      }

      public Response batchUpdate(String collection, Map<String, Object> entries) {
          if (collection == null || entries == null || entries.isEmpty()) {
              return Response.fail("参数不能为空");
          }
          Collection_ col = getCollection(collection);
          if (col == null) return Response.fail("集合 '" + collection + "' 不存在");
          try {
              WalWriter wal = getWal();
              int count = 0;
              for (Map.Entry<String, Object> entry : entries.entrySet()) {
                  if (wal != null) wal.logUpdate(collection, entry.getKey(), entry.getValue());
                  KV kv = col.update(entry.getKey(), entry.getValue());
                  if (kv != null) count++;
              }
              markDirty();
              return Response.ok("批量更新成功，共更新 " + count + " 条记录");
          } catch (IOException e) {
              return Response.fail("WAL 写入失败，批量操作已取消: " + e.getMessage());
          }
      }

      public Response updateWhere(String collection, String field, Object value, Object updateData) {
          if (collection == null || field == null || value == null || updateData == null) {
              return Response.fail("参数不能为空");
          }
          Collection_ col = getCollection(collection);
          if (col == null) return Response.fail("集合 '" + collection + "' 不存在");
          Set<String> keysToUpdate;
          Set<String> indexed = col.hasIndex(field) ? col.lookupIndex(field, value) : null;
          if (indexed != null) {
              keysToUpdate = indexed;
          } else {
              keysToUpdate = new LinkedHashSet<>();
              for (KV kv : col.scan()) {
                  Object val = kv.getValue();
                  if (val instanceof Map<?, ?> map) {
                      Object fieldVal = map.get(field);
                      if (Objects.equals(fieldVal, value)) {
                          keysToUpdate.add(kv.getKey());
                      }
                  }
              }
          }
          if (keysToUpdate.isEmpty()) {
              return Response.fail("没有匹配的记录");
          }
          try {
              WalWriter wal = getWal();
              for (String key : keysToUpdate) {
                  if (wal != null) wal.logUpdate(collection, key, updateData);
                  col.update(key, updateData);
              }
              markDirty();
              return Response.ok("批量更新成功，共更新 " + keysToUpdate.size() + " 条记录");
          } catch (IOException e) {
              return Response.fail("WAL 写入失败，批量操作已取消: " + e.getMessage());
          }
      }

      public Response createIndex(String collection, String field) {
          if (collection == null || field == null) return Response.fail("参数不能为空");
          Collection_ col = getCollection(collection);
          if (col == null) return Response.fail("集合 '" + collection + "' 不存在");
          boolean ok = col.createIndex(field);
          if (!ok) return Response.fail("字段 '" + field + "' 已有索引");
          return Response.ok("在集合 '" + collection + "' 的字段 '" + field + "' 上创建索引成功");
      }

      public Response dropIndex(String collection, String field) {
          if (collection == null || field == null) return Response.fail("参数不能为空");
          Collection_ col = getCollection(collection);
          if (col == null) return Response.fail("集合 '" + collection + "' 不存在");
          boolean ok = col.dropIndex(field);
          if (!ok) return Response.fail("字段 '" + field + "' 上没有索引");
          return Response.ok("删除索引成功");
      }

      public Response listIndexes(String collection) {
          if (collection == null) return Response.fail("参数不能为空");
          Collection_ col = getCollection(collection);
          if (col == null) return Response.fail("集合 '" + collection + "' 不存在");
          Set<String> indexes = col.listIndexes();
          return Response.ok("索引列表: " + indexes, indexes);
      }
     
    // ========== 持久化 ==========

    public Response save() {
        if (currentDatabase == null) return Response.fail("请先选择数据库");
        return saveDatabase(currentDatabase);
    }

    private Response saveDatabase(String dbName) {
        try {
            String dbDir = Paths.get(DATA_DIR, dbName).toString();
            ConcurrentHashMap<String, Collection_> db = databases.get(dbName);
            if (db == null) return Response.fail("数据库 '" + dbName + "' 不存在");
            int flushed = 0;
            for (Collection_ col : db.values()) {
                if (!col.isEmpty()) {
                    col.flush(dbDir);
                    flushed++;
                }
            }
            WalWriter wal = walWriters.get(dbName);
            if (wal != null) {
                wal.checkpoint();
                wal.truncate();
            }
            dirtyDatabases.remove(dbName);
            return Response.ok("数据已保存 (flush " + flushed + " 个集合到 SSTable)");
        } catch (IOException e) {
            return Response.fail("保存失败: " + e.getMessage());
        }
    }

    public Set<String> listDirtyDatabases() {
        return new HashSet<>(dirtyDatabases);
    }

    public Response load(String name) {
        if (name != null) currentDatabase = name;
        if (currentDatabase == null) return Response.fail("请指定数据库名称");
        try {
            String dbDir = Paths.get(DATA_DIR, currentDatabase).toString();
            Path sstDir = Paths.get(dbDir, "sst");
            ConcurrentHashMap<String, Collection_> db = databases.get(currentDatabase);
            if (db == null) {
                db = new ConcurrentHashMap<>();
                databases.put(currentDatabase, db);
            }
            if (Files.exists(sstDir)) {
                Map<String, List<Path>> colFiles = new HashMap<>();
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(sstDir, "*" + ".sst")) {
                    for (Path p : ds) {
                        String fname = p.getFileName().toString();
                        String cname = SstableUtil.parseCollection(fname);
                        colFiles.computeIfAbsent(cname, k -> new ArrayList<>()).add(p);
                    }
                }
                for (Map.Entry<String, List<Path>> e : colFiles.entrySet()) {
                    List<Path> paths = e.getValue();
                    paths.sort(Comparator.comparingInt(p -> SstableUtil.parseSeq(p.getFileName().toString())));
                    Collection_ col = db.get(e.getKey());
                    if (col == null) {
                        col = new Collection_(e.getKey());
                        db.put(e.getKey(), col);
                    }
                    col.assignSstables(paths);
                }
            }
            WalWriter wal = getWal();
            if (wal != null) replayWal(currentDatabase);
            dirtyDatabases.remove(currentDatabase);
            return Response.ok("数据已从 " + sstDir + " 加载 (SSTable)，含WAL回放");
        } catch (IOException e) {
            return Response.fail("加载失败: " + e.getMessage());
        }
    }

    public Response autoLoadDatabases() {
        int loaded = 0;
        int recovered = 0;
        File dataDir = new File(DATA_DIR);
        File[] dbDirs = dataDir.listFiles(File::isDirectory);
        if (dbDirs == null) return Response.ok("未发现已保存的数据库");
        for (File dir : dbDirs) {
            Path sstDir = dir.toPath().resolve("sst");
            if (!Files.exists(sstDir)) continue;
            String dbName = dir.getName();
            databases.putIfAbsent(dbName, new ConcurrentHashMap<>());
            String prev = currentDatabase;
            currentDatabase = dbName;
            Response resp = load(dbName);
            if (resp.isSuccess()) {
                loaded++;
                if (resp.getMessage() != null && resp.getMessage().contains("WAL回放")) {
                    recovered++;
                }
            } else {
                LOG.warning("加载数据库 '" + dbName + "' 失败: " + resp.getMessage());
            }
            currentDatabase = prev;
        }
        return Response.ok("自动恢复完成: 加载 " + loaded + " 个数据库，WAL回放 " + recovered + " 个");
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
            Path sstDir = Paths.get(DATA_DIR, name, "sst");
            if (Files.exists(sstDir)) {
                try (var files = Files.walk(sstDir)) {
                    files.sorted(Comparator.reverseOrder())
                         .map(java.nio.file.Path::toFile)
                         .forEach(File::delete);
                }
            }
            Path dbDir = Paths.get(DATA_DIR, name);
            if (Files.exists(dbDir)) {
                try (var files = Files.walk(dbDir)) {
                    files.sorted(Comparator.reverseOrder())
                         .map(java.nio.file.Path::toFile)
                         .forEach(File::delete);
                }
            }
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

        Map<String, Object> dbInfo = new LinkedHashMap<>();
        for (Map.Entry<String, ConcurrentHashMap<String, Collection_>> entry : databases.entrySet()) {
            Map<String, Object> cols = new LinkedHashMap<>();
            for (Collection_ col : entry.getValue().values()) {
                Map<String, Object> ci = new LinkedHashMap<>();
                ci.put("memtable", col.size());
                ci.put("sstables", col.sstableCount());
                cols.put(col.getName(), ci);
            }
            dbInfo.put(entry.getKey(), cols);
        }
        status.put("databases", dbInfo);
        status.put("storage", "LSM (MemTable + SSTable)");
        status.put("dirtyDatabases", new HashSet<>(dirtyDatabases));
        status.put("autoSaveIntervalMs", AUTO_SAVE_INTERVAL_MS);
        status.put("rotateThreshold", CompressionService.getRotateThreshold() + " bytes");
        status.put("compressionPoolSize", compressionService.toString());
        return status;
    }

    public void shutdown() {
        LOG.info("开始关闭数据库引擎...");
        // 保存脏数据
        for (String db : dirtyDatabases) {
            LOG.info("自动保存脏数据: " + db);
            saveDatabase(db);
        }
        dirtyDatabases.clear();
        // 关闭定时器
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // 关闭 WAL
        for (WalWriter ww : walWriters.values()) {
            ww.close();
        }
        walWriters.clear();
        compressionService.shutdown();
        LOG.info("数据库引擎已安全关闭");
    }
}
