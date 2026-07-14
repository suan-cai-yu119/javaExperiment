package com.database.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

public class SstableUtil {
    private static final int INDEX_INTERVAL = 128;
    private static final String SST_DIR = "sst";
    private static final String EXT = ".sst";

    public static Path sstPath(String dbDir, String collection, int seq) {
        return Paths.get(dbDir, SST_DIR, collection + "_" + seq + EXT);
    }

    public static String parseCollection(String filename) {
        int u = filename.lastIndexOf('_');
        return u < 0 ? filename.replace(EXT, "") : filename.substring(0, u);
    }

    public static int parseSeq(String filename) {
        int u = filename.lastIndexOf('_');
        int d = filename.indexOf('.', u);
        return u < 0 || d < 0 ? 0 : Integer.parseInt(filename.substring(u + 1, d));
    }

    public static int flush(String dbDir, String collection, ConcurrentSkipListMap<String, KV> data, int seq) throws IOException {
        Path dir = Paths.get(dbDir, SST_DIR);
        Files.createDirectories(dir);
        Path path = dir.resolve(collection + "_" + seq + EXT);
        List<Map.Entry<String, KV>> entries = new ArrayList<>(data.entrySet());
        if (entries.isEmpty()) return seq;
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(path.toFile())))) {
            long dataStart = dos.size();
            for (Map.Entry<String, KV> e : entries) {
                writeKv(dos, e.getKey(), e.getValue());
            }
            long indexStart = dos.size();
            int entryCount = entries.size();
            int indexCount = (entryCount + INDEX_INTERVAL - 1) / INDEX_INTERVAL;
            dos.writeInt(indexCount);
            long runningOff = dataStart;
            int idxIdx = 0;
            for (int i = 0; i < entryCount; i++) {
                if (i % INDEX_INTERVAL == 0) {
                    writeIndexEntry(dos, entries.get(i).getKey(), runningOff);
                    idxIdx++;
                }
                runningOff += sizeOf(entries.get(i).getKey(), entries.get(i).getValue());
            }
            dos.writeLong(indexStart);
            dos.writeInt(entryCount);
        }
        return seq;
    }

    public static KV get(Path sstPath, String key) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(sstPath.toFile(), "r")) {
            long len = raf.length();
            if (len < 12) return null;
            raf.seek(len - 12);
            long indexOffset = raf.readLong();
            int entryCount = raf.readInt();
            if (indexOffset < 0 || entryCount <= 0) return null;
            raf.seek(indexOffset);
            int indexCount = raf.readInt();
            long[] offsets = new long[indexCount];
            String[] idxKeys = new String[indexCount];
            for (int i = 0; i < indexCount; i++) {
                short kl = raf.readShort();
                byte[] kb = new byte[kl];
                raf.readFully(kb);
                idxKeys[i] = new String(kb);
                offsets[i] = raf.readLong();
            }
            int idx = binarySearch(idxKeys, key);
            if (idx < 0) return null;
            long startOff = offsets[idx];
            long endOff = (idx + 1 < offsets.length) ? offsets[idx + 1] : indexOffset;
            raf.seek(startOff);
            while (raf.getFilePointer() < endOff) {
                KV kv = readKv(raf);
                if (kv.getKey().equals(key)) return kv;
            }
        }
        return null;
    }

    public static List<KV> scanAll(Path sstPath) throws IOException {
        List<KV> result = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(sstPath.toFile(), "r")) {
            long len = raf.length();
            if (len < 12) return result;
            raf.seek(len - 12);
            long indexOffset = raf.readLong();
            int entryCount = raf.readInt();
            if (indexOffset < 0 || entryCount <= 0) return result;
            raf.seek(0);
            while (raf.getFilePointer() < indexOffset) {
                KV kv = readKv(raf);
                if (kv != null) result.add(kv);
            }
        }
        return result;
    }

    private static void writeKv(DataOutputStream dos, String key, KV kv) throws IOException {
        byte[] kb = key.getBytes();
        byte[] vb = serializeValue(kv.getValue());
        dos.writeShort(kb.length);
        dos.write(kb);
        dos.writeInt(vb.length);
        dos.write(vb);
    }

    private static void writeIndexEntry(DataOutputStream dos, String key, long offset) throws IOException {
        byte[] kb = key.getBytes();
        dos.writeShort(kb.length);
        dos.write(kb);
        dos.writeLong(offset);
    }

    private static KV readKv(RandomAccessFile raf) throws IOException {
        short kl = raf.readShort();
        byte[] kb = new byte[kl];
        raf.readFully(kb);
        String key = new String(kb);
        int vl = raf.readInt();
        byte[] vb = new byte[vl];
        raf.readFully(vb);
        Object val = deserializeValue(vb);
        return new KV(key, val);
    }

    private static int sizeOf(String key, KV kv) {
        try {
            return 2 + key.getBytes().length + 4 + serializeValue(kv.getValue()).length;
        } catch (IOException e) {
            return 0;
        }
    }

    private static byte[] serializeValue(Object val) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(val);
        }
        return baos.toByteArray();
    }

    private static Object deserializeValue(byte[] data) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            ObjectInputStream ois = new ObjectInputStream(bais);
            return ois.readObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static int binarySearch(String[] keys, String target) {
        int lo = 0, hi = keys.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (keys[mid].compareTo(target) <= 0) lo = mid;
            else hi = mid - 1;
        }
        return (keys[lo].compareTo(target) <= 0) ? lo : -1;
    }
}
