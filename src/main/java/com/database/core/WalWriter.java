package com.database.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32;

public class WalWriter {
    private static final String WAL_DIR = "data";
    private static final String WAL_FILE = "wal.log";

    private final String dbName;
    private final Path walPath;
    private DataOutputStream dos;
    private final ReentrantLock lock = new ReentrantLock();

    public WalWriter(String dbName) {
        this.dbName = dbName;
        this.walPath = Paths.get(WAL_DIR, dbName, "wal", WAL_FILE);
        init();
    }

    private void init() {
        try {
            Files.createDirectories(walPath.getParent());
            dos = new DataOutputStream(new BufferedOutputStream(
                    new FileOutputStream(walPath.toFile(), true)));
        } catch (IOException e) {
            System.err.println("WAL init error: " + e.getMessage());
        }
    }

    public void logPut(String collection, String key, Object value) throws IOException {
        write(new WalEntry("PUT", collection, key, value, System.currentTimeMillis()));
    }

    public void logDelete(String collection, String key) throws IOException {
        write(new WalEntry("DEL", collection, key, null, System.currentTimeMillis()));
    }

    public void logUpdate(String collection, String key, Object value) throws IOException {
        write(new WalEntry("UPD", collection, key, value, System.currentTimeMillis()));
    }

    private void write(WalEntry entry) throws IOException {
        byte[] data = serialize(entry);
        long crc = crc32(data);
        lock.lock();
        try {
            dos.writeInt(data.length);
            dos.write(data);
            dos.writeLong(crc);
            dos.flush();
        } finally {
            lock.unlock();
        }
    }

    public List<WalEntry> readAll() {
        List<WalEntry> entries = new ArrayList<>();
        if (!Files.exists(walPath) || walPath.toFile().length() == 0) {
            return entries;
        }
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(walPath.toFile())))) {
            while (true) {
                try {
                    int len = dis.readInt();
                    if (len <= 0 || len > 10 * 1024 * 1024) {
                        System.err.println("WAL 跳过无效条目长度: " + len);
                        break;
                    }
                    byte[] data = new byte[len];
                    dis.readFully(data);
                    long storedCrc = dis.readLong();
                    long computedCrc = crc32(data);
                    if (storedCrc != computedCrc) {
                        System.err.println("WAL CRC 校验失败，跳过该条目");
                        continue;
                    }
                    WalEntry entry = deserialize(data);
                    if (entry != null) {
                        entries.add(entry);
                    }
                } catch (EOFException e) {
                    break;
                } catch (IOException e) {
                    System.err.println("WAL 读取错误，跳过: " + e.getMessage());
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("WAL 读取失败: " + e.getMessage());
        }
        return entries;
    }

    public void checkpoint() {
        try {
            write(new WalEntry("CHECKPOINT", "", "", null, System.currentTimeMillis()));
        } catch (IOException e) {
            System.err.println("WAL checkpoint error: " + e.getMessage());
        }
    }

    public void truncate() {
        lock.lock();
        try {
            if (dos != null) dos.close();
            Files.writeString(walPath, "");
            dos = new DataOutputStream(new BufferedOutputStream(
                    new FileOutputStream(walPath.toFile())));
        } catch (IOException e) {
            System.err.println("WAL truncate error: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    public void close() {
        try {
            if (dos != null) dos.close();
        } catch (IOException ignored) {}
    }

    public String getDbName() { return dbName; }

    private byte[] serialize(WalEntry entry) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(entry);
            oos.flush();
        }
        return baos.toByteArray();
    }

    private WalEntry deserialize(byte[] data) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            ObjectInputStream ois = new ObjectInputStream(bais);
            Object obj = ois.readObject();
            if (obj instanceof WalEntry entry) {
                if (!entry.verifyChecksum()) {
                    System.err.println("WAL 条目数据校验失败");
                    return null;
                }
                return entry;
            }
        } catch (Exception e) {
            System.err.println("WAL 反序列化失败: " + e.getMessage());
        }
        return null;
    }

    private long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    public static class WalEntry implements Serializable {
        private static final long serialVersionUID = 2L;
        public final String op;
        public final String collection;
        public final String key;
        public final Object value;
        public final long timestamp;
        private final long checksum;

        public WalEntry(String op, String collection, String key, Object value, long timestamp) {
            this.op = op;
            this.collection = collection;
            this.key = key;
            this.value = value;
            this.timestamp = timestamp;
            this.checksum = computeChecksum();
        }

        private long computeChecksum() {
            CRC32 crc = new CRC32();
            if (op != null) crc.update(op.getBytes());
            if (collection != null) crc.update(collection.getBytes());
            if (key != null) crc.update(key.getBytes());
            crc.update((int)(timestamp & 0xFF));
            crc.update((int)((timestamp >> 8) & 0xFF));
            return crc.getValue();
        }

        public boolean verifyChecksum() {
            return checksum == computeChecksum();
        }

        @Serial
        private Object readResolve() {
            return this;
        }
    }
}
