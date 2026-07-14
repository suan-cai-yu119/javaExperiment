package com.database.core;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class IndexManager implements Serializable {
    private final Set<String> indexedFields = ConcurrentHashMap.newKeySet();
    private ConcurrentHashMap<String, ConcurrentSkipListMap<Object, Set<String>>> indexes;

    public IndexManager() {
        this.indexes = new ConcurrentHashMap<>();
    }

    private ConcurrentSkipListMap<Object, Set<String>> getOrCreate(String field) {
        return indexes.computeIfAbsent(field, k -> new ConcurrentSkipListMap<>());
    }

    public boolean addIndex(String field) {
        if (indexedFields.contains(field)) return false;
        indexedFields.add(field);
        indexes.put(field, new ConcurrentSkipListMap<>());
        return true;
    }

    public boolean removeIndex(String field) {
        indexedFields.remove(field);
        return indexes.remove(field) != null;
    }

    public Set<String> listIndexes() {
        return new HashSet<>(indexedFields);
    }

    public boolean hasIndex(String field) {
        return indexedFields.contains(field);
    }

    public void onPut(String key, Object value) {
        if (value instanceof Map<?, ?> map) {
            for (String field : indexedFields) {
                Object fv = map.get(field);
                if (fv != null) {
                    getOrCreate(field)
                        .computeIfAbsent(fv, k -> ConcurrentHashMap.newKeySet())
                        .add(key);
                }
            }
        }
    }

    public void onDelete(String key, Object oldValue) {
        if (oldValue instanceof Map<?, ?> map) {
            for (String field : indexedFields) {
                Object fv = map.get(field);
                if (fv != null) {
                    ConcurrentSkipListMap<Object, Set<String>> idx = indexes.get(field);
                    if (idx != null) {
                        Set<String> keys = idx.get(fv);
                        if (keys != null) {
                            keys.remove(key);
                            if (keys.isEmpty()) idx.remove(fv);
                        }
                    }
                }
            }
        }
    }

    public void onUpdate(String key, Object oldValue, Object newValue) {
        onDelete(key, oldValue);
        onPut(key, newValue);
    }

    public Set<String> lookup(String field, Object value) {
        if (!indexedFields.contains(field)) return null;
        ConcurrentSkipListMap<Object, Set<String>> idx = indexes.get(field);
        if (idx == null) return null;
        Set<String> keys = idx.get(value);
        return keys == null ? Collections.emptySet() : keys;
    }

    public void clear() {
        indexes.clear();
    }

    public void rebuild(Map<String, KV> allData) {
        indexes.clear();
        for (String field : indexedFields) {
            indexes.put(field, new ConcurrentSkipListMap<>());
        }
        for (Map.Entry<String, KV> e : allData.entrySet()) {
            onPut(e.getKey(), e.getValue().getValue());
        }
    }
}
