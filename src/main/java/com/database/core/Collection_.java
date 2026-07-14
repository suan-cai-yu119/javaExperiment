package com.database.core;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

public class Collection_ implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String name;
    private final ConcurrentSkipListMap<String, KV> data;
    private long nextVersion;
    private final long createdTime;
    private IndexManager indexManager;
    private transient CacheManager cacheManager;

    public Collection_(String name) {
        this.name = name;
        this.data = new ConcurrentSkipListMap<>();
        this.nextVersion = 1;
        this.createdTime = System.currentTimeMillis();
        this.indexManager = new IndexManager();
        this.cacheManager = new CacheManager();
    }

    public String getName() { return name; }
    public int size() { return data.size(); }
    public boolean isEmpty() { return data.isEmpty(); }
    public long getCreatedTime() { return createdTime; }

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
        Object cached = cacheManager.get(key);
        if (cached instanceof KV kv) {
            return kv;
        }
        KV kv = data.get(key);
        if (kv != null) {
            cacheManager.put(key, kv);
        }
        return kv;
    }

    public KV delete(String key) {
        KV kv = data.remove(key);
        if (kv != null) {
            indexManager.onDelete(key, kv.getValue());
            cacheManager.remove(key);
        }
        return kv;
    }

    public KV update(String key, Object newValue) {
        KV kv = data.get(key);
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
        return data.containsKey(key);
    }

    public List<KV> scan() {
        return new ArrayList<>(data.values());
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
        return result;
    }

    public Set<String> keySet() {
        return data.keySet();
    }

    public void clear() {
        data.clear();
        indexManager.clear();
        cacheManager.clear();
    }

    // ========== 索引 ==========

    public boolean createIndex(String field) {
        boolean ok = indexManager.addIndex(field);
        if (ok) {
            indexManager.rebuild(data);
        }
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

    public void clearCache() {
        cacheManager.clear();
    }

    public int cacheSize() {
        return cacheManager.size();
    }

    // ========== 序列化 ==========

    @Serial
    private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
        ois.defaultReadObject();
        this.cacheManager = new CacheManager();
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
