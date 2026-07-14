package com.database.core;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;

public class CompressionService {
    private static final long ROTATE_THRESHOLD = 10 * 1024 * 1024; // 10MB
    private final ExecutorService executor;

    public CompressionService(int poolSize) {
        this.executor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "compress-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean needRotate(Path filePath) {
        try {
            return Files.exists(filePath) && Files.size(filePath) >= ROTATE_THRESHOLD;
        } catch (IOException e) {
            return false;
        }
    }

    public Path rotate(Path original, int generation) throws IOException {
        Path parent = original.getParent();
        if (parent == null) {
            parent = Paths.get(".");
        }
        String name = original.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = (dot == -1) ? name : name.substring(0, dot);
        String ext = (dot == -1) ? "" : name.substring(dot);
        Path rotated = parent.resolve(base + "_" + generation + ext);
        Files.move(original, rotated, StandardCopyOption.ATOMIC_MOVE);
        return rotated;
    }

    public CompletableFuture<Void> compressAsync(Path filePath) {
        Path gzPath = filePath.resolveSibling(filePath.getFileName() + ".gz");
        return CompletableFuture.runAsync(() -> {
            try {
                try (FileInputStream fis = new FileInputStream(filePath.toFile());
                     BufferedInputStream bis = new BufferedInputStream(fis);
                     FileOutputStream fos = new FileOutputStream(gzPath.toFile());
                     GZIPOutputStream gzos = new GZIPOutputStream(new BufferedOutputStream(fos))) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = bis.read(buf)) > 0) {
                        gzos.write(buf, 0, len);
                    }
                }
                Files.deleteIfExists(filePath);
                System.out.println("Compressed: " + gzPath.getFileName());
            } catch (IOException e) {
                System.err.println("Compress error: " + e.getMessage());
            }
        }, executor);
    }

    public void shutdown() {
        executor.shutdown();
    }

    public static long getRotateThreshold() {
        return ROTATE_THRESHOLD;
    }
}
