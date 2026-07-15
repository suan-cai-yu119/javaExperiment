package com.database.client;

import com.database.common.Protocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class ClientMain {

    private static final Scanner SCANNER = new Scanner(System.in);

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

        if (hasClusterArg(args)) {
            connectWithClusterSelection(host, port);
        } else {
            startInteractive(host, port);
        }
    }

    private static boolean hasClusterArg(String[] args) {
        for (String a : args) {
            if ("--cluster".equals(a)) return true;
        }
        return false;
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

    @SuppressWarnings("unchecked")
    private static void connectWithClusterSelection(String host, int port) {
        System.out.println("╔═══════════════════════════════════════════╗");
        System.out.println("║     迷你数据库系统 - 集群节点选择        ║");
        System.out.println("╚═══════════════════════════════════════════╝");

        Map<String, Object> clusterStatus = Client.fetchClusterStatus(host, port);
        if (clusterStatus == null) {
            System.err.println("[x] 无法获取集群状态，请确保集群已启动");
            System.exit(1);
        }

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) clusterStatus.get("nodes");
        if (nodes == null || nodes.isEmpty()) {
            System.err.println("[x] 集群中没有可用节点");
            System.exit(1);
        }

        System.out.println("\n  当前集群节点列表:");
        System.out.println("  ┌──────┬──────────────┬──────────┬──────────┬────────────┐");
        System.out.println("  │ 序号  │ 节点地址       │ 角色     │ 状态      │ 延迟(ms)    │");
        System.out.println("  ├──────┼──────────────┼──────────┼──────────┼────────────┤");

        List<NodeInfo> nodeInfos = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> node : nodes) {
            boolean alive = (Boolean) node.get("alive");
            if (!alive) {
                index++;
                continue;
            }

            String nodeHost = (String) node.get("host");
            int nodePort = ((Number) node.get("port")).intValue();
            String role = (String) node.get("role");
            long latency = Client.measureLatency(nodeHost, nodePort);
            long latencyMs = latency < 0 ? -1 : latency / 1_000_000;
            String latencyStr = latencyMs < 0 ? "超时" : latencyMs + "ms";

            String roleDisplay = switch (role) {
                case "MASTER" -> "★ 主节点";
                case "SLAVE" -> "○ 从节点";
                case "CANDIDATE" -> "△ 候选";
                default -> role;
            };

            System.out.printf("  │ %4d │ %-12s │ %-8s │ %-6s │ %-10s │%n",
                index, nodeHost + ":" + nodePort, roleDisplay, "在线", latencyStr);

            nodeInfos.add(new NodeInfo(index, nodeHost, nodePort, role, latencyMs));
            index++;
        }

        System.out.println("  └──────┴──────────────┴──────────┴──────────┴────────────┘");

        if (nodeInfos.isEmpty()) {
            System.err.println("[x] 没有在线的节点可供选择");
            System.exit(1);
        }

        System.out.print("\n请选择要连接的节点序号 (1-" + nodeInfos.size() + "): ");
        try {
            int choice = Integer.parseInt(SCANNER.nextLine().trim());
            NodeInfo selected = null;
            for (NodeInfo ni : nodeInfos) {
                if (ni.index == choice) {
                    selected = ni;
                    break;
                }
            }
            if (selected == null) {
                System.err.println("[x] 无效的选择");
                System.exit(1);
            }

            System.out.println("\n→ 正在连接到 " + selected.host + ":" + selected.port + " ...");
            String roleInfo = switch (selected.role) {
                case "MASTER" -> "★ 主节点 (延迟: " + (selected.latency < 0 ? "N/A" : selected.latency + "ms") + ")";
                case "SLAVE" -> "○ 从节点 (延迟: " + (selected.latency < 0 ? "N/A" : selected.latency + "ms") + ")";
                case "CANDIDATE" -> "△ 候选 (延迟: " + (selected.latency < 0 ? "N/A" : selected.latency + "ms") + ")";
                default -> selected.role + " (延迟: " + (selected.latency < 0 ? "N/A" : selected.latency + "ms") + ")";
            };
            System.out.println("  节点角色: " + roleInfo);
            startInteractive(selected.host, selected.port);
        } catch (NumberFormatException e) {
            System.err.println("[x] 请输入有效的序号");
            System.exit(1);
        }
    }

    private static class NodeInfo {
        final int index;
        final String host;
        final int port;
        final String role;
        final long latency;

        NodeInfo(int index, String host, int port, String role, long latency) {
            this.index = index;
            this.host = host;
            this.port = port;
            this.role = role;
            this.latency = latency;
        }
    }

    private static void runBatch(String scriptPath, String host, int port) {
        Path path = Paths.get(scriptPath);
        if (!Files.exists(path)) {
            System.err.println("[x] 脚本文件不存在: " + scriptPath);
            System.exit(1);
            return;
        }
        Client client = new Client(host, port);
        if (!client.connect()) {
            System.err.println("[x] 无法连接到服务器 " + host + ":" + port);
            System.exit(1);
            return;
        }
        try {
            ConsoleUI ui = new ConsoleUI(client);
            List<String> lines = Files.readAllLines(path);
            System.out.println("→ 批量执行脚本: " + scriptPath + " (" + lines.size() + " 行)");
            int success = 0, fail = 0;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
                System.out.print("  [" + (i + 1) + "] " + line + "  ->  ");
                try {
                    ui.processInput(line);
                    success++;
                } catch (Exception e) {
                    System.out.println("  [x] 错误: " + e.getMessage());
                    fail++;
                }
            }
            System.out.println("→ 批处理完成: 成功 " + success + " 条, 失败 " + fail + " 条");
        } catch (IOException e) {
            System.err.println("[x] 读取脚本文件失败: " + e.getMessage());
        } finally {
            client.disconnect();
        }
    }
}
