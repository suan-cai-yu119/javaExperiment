package com.database.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class WalWriter {
    private static final String WAL_DIR = "data";
    private static final String WAL_FILE = "wal.log";

    private final String dbName;
    private final Path walPath;
    private ObjectOutputStream oos;
    private final ReentrantLock lock = new ReentrantLock();

    public WalWriter(String dbName) {
        this.dbName = dbName;
        this.walPath = Paths.get(WAL_DIR, dbName, "wal", WAL_FILE);
        init();
    }

    private void init() {
        try {
            Files.createDirectories(walPath.getParent());
            if (Files.size(walPath) > 0) {
                oos = new ObjectOutputStream(new FileOutputStream(walPath.toFile(), true)) {
                    protected void writeStreamHeader() {}
                };
            } else {
                oos = new ObjectOutputStream(new FileOutputStream(walPath.toFile()));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void logPut(String collection, String key, Object value) {
        write(new WalEntry("PUT", collection, key, value, System.currentTimeMillis()));
    }

    public void logDelete(String collection, String key) {
        write(new WalEntry("DEL", collection, key, null, System.currentTimeMillis()));
    }

    public void logUpdate(String collection, String key, Object value) {
        write(new WalEntry("UPD", collection, key, value, System.currentTimeMillis()));
    }

    private void write(WalEntry entry) {
        lock.lock();
        try {
            oos.writeObject(entry);
            oos.flush();
            oos.reset();
        } catch (IOException e) {
            System.err.println("WAL write error: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    public List<WalEntry> readAll() {
        List<WalEntry> entries = new ArrayList<>();
        if (!Files.exists(walPath) || walPath.toFile().length() == 0) {
            return entries;
        }
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(walPath.toFile()))) {
            while (true) {
                try {
                    Object obj = ois.readObject();
                    if (obj instanceof WalEntry entry) {
                        entries.add(entry);
                    }
                } catch (EOFException e) {
                    break;
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            if (!(e instanceof EOFException)) {
                System.err.println("WAL read error: " + e.getMessage());
            }
        }
        return entries;
    }

    public void checkpoint() {
        write(new WalEntry("CHECKPOINT", "", "", null, System.currentTimeMillis()));
    }

    public void truncate() {
        lock.lock();
        try {
            if (oos != null) oos.close();
            Files.writeString(walPath, "");
            oos = new ObjectOutputStream(new FileOutputStream(walPath.toFile()));
        } catch (IOException e) {
            System.err.println("WAL truncate error: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    public void close() {
        try {
            if (oos != null) oos.close();
        } catch (IOException ignored) {}
    }

    public String getDbName() { return dbName; }

    public static class WalEntry implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String op;
        public final String collection;
        public final String key;
        public final Object value;
        public final long timestamp;

        public WalEntry(String op, String collection, String key, Object value, long timestamp) {
            this.op = op;
            this.collection = collection;
            this.key = key;
            this.value = value;
            this.timestamp = timestamp;
        }

        @Serial
        private Object readResolve() { return this; }
    }
}
