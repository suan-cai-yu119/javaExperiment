package com.database.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class CacheManager {
    private static final int DEFAULT_MAX_SIZE = 500;
    private static final long DEFAULT_TTL_MS = 30_000;

    private final int maxSize;
    private final long ttlMs;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private static class CacheEntry {
        final Object value;
        final long created;

        CacheEntry(Object value) {
            this.value = value;
            this.created = System.currentTimeMillis();
        }

        boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - created > ttlMs;
        }
    }

    public CacheManager() {
        this(DEFAULT_MAX_SIZE, DEFAULT_TTL_MS);
    }

    public CacheManager(int maxSize, long ttlMs) {
        this.maxSize = maxSize;
        this.ttlMs = ttlMs;
    }

    public void put(String key, Object value) {
        if (value == null) return;
        lock.writeLock().lock();
        try {
            if (cache.size() >= maxSize) {
                evictOne();
            }
            cache.put(key, new CacheEntry(value));
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Object get(String key) {
        lock.readLock().lock();
        try {
            CacheEntry entry = cache.get(key);
            if (entry == null) return null;
            if (entry.isExpired(ttlMs)) {
                lock.readLock().unlock();
                lock.writeLock().lock();
                try {
                    cache.remove(key);
                    return null;
                } finally {
                    lock.writeLock().unlock();
                }
            }
            return entry.value;
        } finally {
            if (lock.getReadHoldCount() > 0) {
                lock.readLock().unlock();
            }
        }
    }

    public void remove(String key) {
        cache.remove(key);
    }

    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }

    private void evictOne() {
        String eldest = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, CacheEntry> e : cache.entrySet()) {
            if (e.getValue().created < oldest) {
                oldest = e.getValue().created;
                eldest = e.getKey();
            }
        }
        if (eldest != null) cache.remove(eldest);
    }
}
