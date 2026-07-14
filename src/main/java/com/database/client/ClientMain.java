package com.database.client;

import com.database.common.Protocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class ClientMain {
    public static void main(String[] args) {
        if (args.length >= 2 && "--file".equals(args[0])) {
            runBatch(args[1], args.length > 2 ? args[2] : "127.0.0.1",
                     args.length > 3 ? Integer.parseInt(args[3]) : Protocol.DEFAULT_PORT);
            return;
        }

        String host = "127.0.0.1";
        int port = Protocol.DEFAULT_PORT;
        if (args.length > 0) host = args[0];
        if (args.length > 1) {
            try { port = Integer.parseInt(args[1]); }
            catch (NumberFormatException ignored) {}
        }
        startInteractive(host, port);
    }

    private static void startInteractive(String host, int port) {
        Client client = new Client(host, port);
        if (client.connect()) {
            ConsoleUI ui = new ConsoleUI(client);
            ui.start();
        } else {
            System.err.println("无法连接到服务器 " + host + ":" + port);
            System.exit(1);
        }
    }

    private static void runBatch(String scriptPath, String host, int port) {
        Path path = Paths.get(scriptPath);
        if (!Files.exists(path)) {
            System.err.println("? 脚本文件不存在: " + scriptPath);
            System.exit(1);
            return;
        }
        Client client = new Client(host, port);
        if (!client.connect()) {
            System.err.println("? 无法连接到服务器 " + host + ":" + port);
            System.exit(1);
            return;
        }
        try {
            ConsoleUI ui = new ConsoleUI(client);
            List<String> lines = Files.readAllLines(path);
            System.out.println("? 批量执行脚本: " + scriptPath + " (" + lines.size() + " 行)");
            int success = 0, fail = 0;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
                System.out.print("? [" + (i + 1) + "] " + line + "  ->  ");
                try {
                    ui.processInput(line);
                    success++;
                } catch (Exception e) {
                    System.out.println("? 错误: " + e.getMessage());
                    fail++;
                }
            }
            System.out.println("? 批处理完成: 成功 " + success + " 条, 失败 " + fail + " 条");
        } catch (IOException e) {
            System.err.println("? 读取脚本文件失败: " + e.getMessage());
        } finally {
            client.disconnect();
        }
    }
}
