package com.database.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class Collection_ implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private static final int FLUSH_THRESHOLD = 5000;

    private final String name;
    private final ConcurrentSkipListMap<String, KV> data;
    private long nextVersion;
    private final long createdTime;
    private IndexManager indexManager;
    private transient CacheManager cacheManager;
    private transient List<Path> sstablePaths = new ArrayList<>();
    private transient int nextSeq;
    private transient Set<String> tombstones = ConcurrentHashMap.newKeySet();

    public Collection_(String name) {
        this.name = name;
        this.data = new ConcurrentSkipListMap<>();
        this.nextVersion = 1;
        this.createdTime = System.currentTimeMillis();
        this.indexManager = new IndexManager();
        this.cacheManager = new CacheManager();
        this.tombstones = ConcurrentHashMap.newKeySet();
    }

    public String getName() { return name; }
    public long getCreatedTime() { return createdTime; }

    public int size() {
        return data.size() + sstablePaths.size();
    }

    public boolean isEmpty() {
        return data.isEmpty() && sstablePaths.isEmpty();
    }

    public KV put(String key, Object value) {
        KV old = data.get(key);
        KV kv = new KV(key, value, nextVersion++);
        data.put(key, kv);
        if (old != null) {
            indexManager.onUpdate(key, old.getValue(), value);
        } else {
            indexManager.onPut(key, value);
        }
        cacheManager.remove(key);
        return kv;
    }

    public KV get(String key) {
        if (tombstones.contains(key)) return null;
        Object cached = cacheManager.get(key);
        if (cached instanceof KV kv) return kv;
        KV kv = data.get(key);
        if (kv != null) {
            cacheManager.put(key, kv);
            return kv;
        }
        for (int i = sstablePaths.size() - 1; i >= 0; i--) {
            try {
                kv = SstableUtil.get(sstablePaths.get(i), key);
                if (kv != null) {
                    cacheManager.put(key, kv);
                    return kv;
                }
            } catch (IOException ignored) {}
        }
        return null;
    }

    public KV delete(String key) {
        KV kv = get(key);
        if (kv != null) {
            data.remove(key);
            indexManager.onDelete(key, kv.getValue());
            cacheManager.remove(key);
        }
        tombstones.add(key);
        return kv;
    }

    public KV update(String key, Object newValue) {
        KV kv = get(key);
        if (kv != null) {
            Object existing = kv.getValue();
            Object finalValue;
            if (existing instanceof Map && newValue instanceof Map) {
                Map<String, Object> merged = new LinkedHashMap<>();
                merged.putAll((Map<String, Object>) existing);
                merged.putAll((Map<String, Object>) newValue);
                finalValue = merged;
            } else {
                finalValue = newValue;
            }
            KV newKv = new KV(key, finalValue, nextVersion++);
            data.put(key, newKv);
            indexManager.onUpdate(key, existing, finalValue);
            cacheManager.remove(key);
            return newKv;
        }
        return null;
    }

    public boolean containsKey(String key) {
        if (data.containsKey(key)) return true;
        for (int i = sstablePaths.size() - 1; i >= 0; i--) {
            try {
                if (SstableUtil.get(sstablePaths.get(i), key) != null) return true;
            } catch (IOException ignored) {}
        }
        return false;
    }

    public List<KV> scan() {
        Map<String, KV> merged = new TreeMap<>(data);
        for (Path p : sstablePaths) {
            try {
                for (KV kv : SstableUtil.scanAll(p)) {
                    if (!tombstones.contains(kv.getKey())) {
                        merged.putIfAbsent(kv.getKey(), kv);
                    }
                }
            } catch (IOException ignored) {}
        }
        return new ArrayList<>(merged.values());
    }

    public List<KV> scanByPrefix(String prefix) {
        List<KV> result = new ArrayList<>();
        for (Map.Entry<String, KV> entry : data.tailMap(prefix).entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.add(entry.getValue());
            } else {
                break;
            }
        }
        Map<String, KV> sstMap = new TreeMap<>();
        for (Path p : sstablePaths) {
            try {
                for (KV kv : SstableUtil.scanAll(p)) {
                    if (kv.getKey().startsWith(prefix) && !data.containsKey(kv.getKey())) {
                        sstMap.putIfAbsent(kv.getKey(), kv);
                    }
                }
            } catch (IOException ignored) {}
        }
        result.addAll(sstMap.values());
        return result;
    }

    public Set<String> keySet() {
        Set<String> keys = new HashSet<>(data.keySet());
        for (Path p : sstablePaths) {
            try {
                for (KV kv : SstableUtil.scanAll(p)) {
                    keys.add(kv.getKey());
                }
            } catch (IOException ignored) {}
        }
        return keys;
    }

    public void clear() {
        data.clear();
        sstablePaths.clear();
        tombstones.clear();
        indexManager.clear();
        cacheManager.clear();
    }

    // ========== LSM Flush ==========

    public boolean needFlush() {
        return data.size() >= FLUSH_THRESHOLD;
    }

    public void flush(String dbDir) throws IOException {
        if (data.isEmpty()) return;
        int seq = nextSeq++;
        SstableUtil.flush(dbDir, name, data, seq);
        Path p = SstableUtil.sstPath(dbDir, name, seq);
        sstablePaths.add(p);
        data.clear();
    }

    public void assignSstables(List<Path> paths) {
        this.sstablePaths = new ArrayList<>(paths);
        paths.stream()
            .map(p -> SstableUtil.parseSeq(p.getFileName().toString()))
            .max(Integer::compare)
            .ifPresent(max -> nextSeq = max + 1);
    }

    public int sstableCount() { return sstablePaths.size(); }

    // ========== 索引 ==========

    public boolean createIndex(String field) {
        boolean ok = indexManager.addIndex(field);
        if (ok) indexManager.rebuild(data);
        return ok;
    }

    public boolean dropIndex(String field) {
        return indexManager.removeIndex(field);
    }

    public Set<String> listIndexes() {
        return indexManager.listIndexes();
    }

    public boolean hasIndex(String field) {
        return indexManager.hasIndex(field);
    }

    public Set<String> lookupIndex(String field, Object value) {
        return indexManager.lookup(field, value);
    }

    // ========== 缓存 ==========

    public void clearCache() { cacheManager.clear(); }
    public int cacheSize() { return cacheManager.size(); }

    // ========== 序列化 ==========

    @Serial
    private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
        ois.defaultReadObject();
        this.cacheManager = new CacheManager();
        this.sstablePaths = new ArrayList<>();
        this.nextSeq = 0;
        this.tombstones = ConcurrentHashMap.newKeySet();
    }

    public void saveToFile(String filePath) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(filePath))) {
            oos.writeObject(this);
        }
    }

    public static Collection_ loadFromFile(String filePath) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(filePath))) {
            return (Collection_) ois.readObject();
        }
    }
}
