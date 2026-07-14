package com.database.client.gui;

import com.database.client.Client;
import com.database.common.CommandType;
import com.database.common.Protocol;
import com.database.common.Request;
import com.database.common.Response;
import com.database.core.KV;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainFrame extends JFrame {
    private Client client;
    private String currentDb;

    // 连接面板
    private final JTextField hostField = new JTextField("127.0.0.1", 12);
    private final JTextField portField = new JTextField(String.valueOf(Protocol.DEFAULT_PORT), 5);
    private final JButton connectBtn = new JButton("连接");
    private final JButton disconnectBtn = new JButton("断开");
    private final JLabel statusLabel = new JLabel("○ 未连接");
    private final JLabel clusterLabel = new JLabel("");

    // 导航树
    private final DatabaseTree databaseTree;

    // 操作按钮
    private final JButton putBtn = new JButton("PUT");
    private final JButton getBtn = new JButton("GET");
    private final JButton delBtn = new JButton("DEL");
    private final JButton scanBtn = new JButton("SCAN");
    private final JButton saveBtn = new JButton("SAVE");
    private final JButton loadBtn = new JButton("LOAD");

    // 数据表格
    private final ResultTableModel tableModel = new ResultTableModel();
    private final JTable dataTable = new JTable(tableModel);

    // 底部
    private final JTextField cmdField = new JTextField();
    private final JTextArea logArea = new JTextArea(8, 40);

    public MainFrame(String host, int port) {
        super("迷你数据库系统 - GUI 客户端");
        hostField.setText(host);
        portField.setText(String.valueOf(port));
        databaseTree = new DatabaseTree(this);

        initUI();
        initListeners();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 700);
        setLocationRelativeTo(null);
    }

    // ==================== UI 布局 ====================

    private void initUI() {
        setLayout(new BorderLayout(3, 3));

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);

        setConnectedState(false);
    }

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(new JLabel("主机:"));
        bar.add(hostField);
        bar.add(Box.createHorizontalStrut(4));
        bar.add(new JLabel("端口:"));
        bar.add(portField);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(connectBtn);
        bar.add(disconnectBtn);
        bar.addSeparator();
        bar.add(statusLabel);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(clusterLabel);
        return bar;
    }

    private JSplitPane buildCenter() {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setLeftComponent(new JScrollPane(databaseTree));
        split.setRightComponent(buildRightPanel());
        split.setDividerLocation(230);
        split.setResizeWeight(0);
        return split;
    }

    private JPanel buildRightPanel() {
        JPanel p = new JPanel(new BorderLayout(3, 3));
        p.add(buildButtonPanel(), BorderLayout.NORTH);

        JTable tbl = dataTable;
        tbl.setFillsViewportHeight(true);
        tbl.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        tbl.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v,
                    boolean s, boolean f, int r, int c) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, s, f, r, c);
                if (v != null) l.setText(v.toString());
                return l;
            }
        });
        p.add(new JScrollPane(tbl), BorderLayout.CENTER);

        JLabel info = new JLabel("当前数据库: (无)");
        info.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        info.setName("infoLabel");
        p.add(info, BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildButtonPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 3));
        p.setBorder(BorderFactory.createTitledBorder("操作"));
        p.add(putBtn);
        p.add(getBtn);
        p.add(delBtn);
        p.add(scanBtn);
        p.add(new JSeparator(SwingConstants.VERTICAL));
        p.add(saveBtn);
        p.add(loadBtn);
        return p;
    }

    private JPanel buildBottom() {
        JPanel p = new JPanel(new BorderLayout(3, 3));
        JPanel cmdPanel = new JPanel(new BorderLayout(3, 3));
        cmdPanel.setBorder(BorderFactory.createTitledBorder("命令输入"));
        JButton execBtn = new JButton("执行");
        cmdPanel.add(cmdField, BorderLayout.CENTER);
        cmdPanel.add(execBtn, BorderLayout.EAST);
        execBtn.addActionListener(e -> executeCommand());
        cmdField.addActionListener(e -> executeCommand());
        p.add(cmdPanel, BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        p.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return p;
    }

    // ==================== 事件绑定 ====================

    private void initListeners() {
        connectBtn.addActionListener(e -> connect());
        disconnectBtn.addActionListener(e -> disconnect());
        putBtn.addActionListener(e -> showPutDialog());
        getBtn.addActionListener(e -> showGetDialog());
        delBtn.addActionListener(e -> showDeleteDialog());
        scanBtn.addActionListener(e -> {
            String col = databaseTree.getSelectedCollection();
            if (col != null) scanCollection(col);
            else log("? 请先在左侧树中选择一个集合");
        });
        saveBtn.addActionListener(e -> save());
        loadBtn.addActionListener(e -> load());
    }

    // ==================== 连接管理 ====================

    private void connect() {
        final String host = hostField.getText().trim();
        final String portText = portField.getText().trim();
        final int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            log("? 端口号格式错误");
            return;
        }
        setConnectedState(false);
        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() {
                publish("正在连接 " + host + ":" + port + " ...");
                client = new Client(host, port);
                if (client.connect()) {
                    publish("? 连接成功");
                    databaseTree.setClient(client);
                    databaseTree.loadDatabases();
                    publish("? 数据库列表已加载");
                } else {
                    client = null;
                    publish("? 连接失败");
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String s : chunks) log(s);
            }

            @Override
            protected void done() {
                boolean ok = client != null && client.isConnected();
                setConnectedState(ok);
                if (ok) {
                    statusLabel.setText("● 已连接");
                    queryClusterStatus();
                } else {
                    statusLabel.setText("○ 连接失败");
                }
            }
        }.execute();
    }

    private void queryClusterStatus() {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                try {
                    Response resp = client.sendCommand(CommandType.CLUSTER_STATUS);
                    if (resp.isSuccess() && resp.getData() instanceof Map<?, ?> status) {
                        Object role = status.get("currentRole");
                        Object enabled = status.get("clusterEnabled");
                        if (Boolean.TRUE.equals(enabled) && role != null) {
                            final String text = "? 集群: " + role;
                            SwingUtilities.invokeLater(() -> clusterLabel.setText(text));
                        }
                    }
                } catch (Exception ignored) {}
                return null;
            }
        }.execute();
    }

    private void disconnect() {
        if (client != null) {
            client.disconnect();
            client = null;
        }
        databaseTree.clearAll();
        tableModel.clear();
        currentDb = null;
        clusterLabel.setText("");
        setConnectedState(false);
        statusLabel.setText("○ 未连接");
        log("? 已断开连接");
    }

    // ==================== 数据操作 ====================

    void selectDatabase(String name) {
        if (client == null) return;
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                Response resp = client.sendCommand(CommandType.USE_DATABASE, name);
                if (resp.isSuccess()) currentDb = name;
                return null;
            }
            @Override
            protected void done() {
                updateInfoLabel();
            }
        }.execute();
    }

    void selectCollection(String col) {
        if (client == null) return;
        log("? 扫描集合: " + col);
        new SwingWorker<Void, Void>() {
            private Response resp;
            @Override
            protected Void doInBackground() {
                resp = client.sendCommand(CommandType.SCAN, col);
                return null;
            }
            @Override
            protected void done() {
                if (resp != null && resp.isSuccess()) {
                    if (resp.getData() instanceof List<?> list) {
                        List<KV> kvs = list.stream()
                                .filter(x -> x instanceof KV).map(x -> (KV) x).toList();
                        tableModel.setData(kvs);
                        log("? " + resp.getMessage());
                    }
                } else {
                    log("? " + (resp != null ? resp.getMessage() : "无响应"));
                }
            }
        }.execute();
    }

    void scanCollection(final String col) {
        if (client == null || col == null) return;
        log("? 扫描集合: " + col);
        new SwingWorker<Void, String>() {
            private Response resp;

            @Override
            protected Void doInBackground() {
                resp = client.sendCommand(CommandType.SCAN, col);
                return null;
            }

            @Override
            protected void done() {
                if (resp != null && resp.isSuccess()) {
                    if (resp.getData() instanceof List<?> list) {
                        List<KV> kvs = list.stream()
                                .filter(x -> x instanceof KV).map(x -> (KV) x).toList();
                        tableModel.setData(kvs);
                        log("? " + resp.getMessage());
                    }
                } else {
                    log("? " + (resp != null ? resp.getMessage() : "无响应"));
                }
            }
        }.execute();
    }

    private void showPutDialog() {
        String col = databaseTree.getSelectedCollection();
        if (col == null) { log("? 请先在左侧树中选择一个集合"); return; }
        String key = JOptionPane.showInputDialog(this, "输入键(key):", "PUT", JOptionPane.PLAIN_MESSAGE);
        if (key == null || key.isBlank()) return;
        String value = JOptionPane.showInputDialog(this, "输入值(value):", "PUT", JOptionPane.PLAIN_MESSAGE);
        if (value == null) return;
        executeOnWorker("PUT " + col + " " + key + " " + value);
    }

    private void showGetDialog() {
        String col = databaseTree.getSelectedCollection();
        if (col == null) { log("? 请先在左侧树中选择一个集合"); return; }
        String key = JOptionPane.showInputDialog(this, "输入键(key):", "GET", JOptionPane.PLAIN_MESSAGE);
        if (key == null || key.isBlank()) return;
        executeOnWorker("GET " + col + " " + key);
    }

    private void showDeleteDialog() {
        String col = databaseTree.getSelectedCollection();
        if (col == null) { log("? 请先在左侧树中选择一个集合"); return; }
        String key = JOptionPane.showInputDialog(this, "输入键(key):", "DELETE", JOptionPane.PLAIN_MESSAGE);
        if (key == null || key.isBlank()) return;
        executeOnWorker("DELETE " + col + " " + key);
    }

    void showCreateCollectionDialog(String dbName) {
        String name = JOptionPane.showInputDialog(this, "集合名称:", "创建集合", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;
        executeOnWorker("CREATE COLLECTION " + name);
    }

    void dropDatabase(String name) {
        int opt = JOptionPane.showConfirmDialog(this, "确定删除数据库 '" + name + "'?", "确认删除",
                JOptionPane.YES_NO_OPTION);
        if (opt == JOptionPane.YES_OPTION) executeOnWorker("DROP DATABASE " + name);
    }

    void dropCollection(String name) {
        int opt = JOptionPane.showConfirmDialog(this, "确定删除集合 '" + name + "'?", "确认删除",
                JOptionPane.YES_NO_OPTION);
        if (opt == JOptionPane.YES_OPTION) executeOnWorker("DROP COLLECTION " + name);
    }

    private void save() {
        executeOnWorker("SAVE");
    }

    private void load() {
        String name = JOptionPane.showInputDialog(this, "数据库名称(留空为当前):", "LOAD", JOptionPane.PLAIN_MESSAGE);
        executeOnWorker(name != null && !name.isBlank() ? "LOAD " + name : "LOAD");
    }

    // ==================== 命令执行 ====================

    private void executeCommand() {
        String cmd = cmdField.getText().trim();
        if (cmd.isEmpty()) return;
        cmdField.setText("");
        log("> " + cmd);
        // 解析命令并发送到 client
        if (client == null || !client.isConnected()) {
            log("? 未连接到服务器");
            return;
        }
        executeOnWorker(cmd);
    }

    private void executeOnWorker(final String cmdLine) {
        new SwingWorker<Void, GuiMessage>() {
            private Response workerResp;
            private String workerCmd;

            @Override
            protected Void doInBackground() {
                try {
                    String[] parts = parseCommand(cmdLine);
                    if (parts.length == 0) return null;
                    workerCmd = parts[0].toUpperCase();
                    String[] args = parts.length > 1
                            ? cmdLine.substring(parts[0].length()).trim().split("\\s+")
                            : new String[0];

                    workerResp = switch (workerCmd) {
                        case "CREATE" -> handleCreate(args);
                        case "DROP" -> handleDrop(args);
                        case "USE" -> handleUse(args);
                        case "PUT" -> handlePut(args);
                        case "GET" -> handleGet(args);
                        case "UPDATE" -> handleUpdate(args);
                        case "DELETE" -> handleDelete(args);
                        case "SCAN" -> client.sendCommand(CommandType.SCAN, args);
                        case "SAVE" -> client.sendCommand(CommandType.SAVE);
                        case "LOAD" -> client.sendCommand(CommandType.LOAD, args);
                        case "LIST" -> handleList(args);
                        case "PING" -> client.sendCommand(CommandType.PING);
                        default -> Response.fail("未知命令: " + workerCmd);
                    };

                    if (workerResp != null) {
                        publish(new GuiMessage("MSG", workerResp.isSuccess()
                                ? "? " + workerResp.getMessage()
                                : "? " + workerResp.getMessage()));
                        if (workerResp.isSuccess() && "SCAN".equals(workerCmd)
                                && workerResp.getData() instanceof List<?> list) {
                            publish(new GuiMessage("SCAN_DATA", list));
                        }
                        if (workerResp.isSuccess() && ("USE".equals(workerCmd)
                                || "CREATE".equals(workerCmd)
                                || "DROP".equals(workerCmd)
                                || "LOAD".equals(workerCmd))) {
                            Set<String> dbs = databaseTree.fetchDatabases();
                            publish(new GuiMessage("TREE_DBS", dbs));
                        }
                        if (workerResp.isSuccess() && "DROP".equals(workerCmd)) {
                            publish(new GuiMessage("CLEAR_TABLE", null));
                        }
                    }
                } catch (Exception e) {
                    publish(new GuiMessage("MSG", "? 错误: " + e.getMessage()));
                }
                return null;
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void process(List<GuiMessage> chunks) {
                for (GuiMessage msg : chunks) {
                    switch (msg.type) {
                        case "MSG" -> log((String) msg.data);
                        case "SCAN_DATA" -> {
                            if (msg.data instanceof List<?> list) {
                                List<KV> kvs = list.stream()
                                        .filter(x -> x instanceof KV).map(x -> (KV) x).toList();
                                tableModel.setData(kvs);
                            }
                        }
                        case "TREE_DBS" -> {
                            if (msg.data instanceof Set<?> set) {
                                databaseTree.applyDatabases((Set<String>) set);
                            }
                        }
                        case "CLEAR_TABLE" -> tableModel.clear();
                    }
                }
            }

            @Override
            protected void done() {
                updateInfoLabel();
            }
        }.execute();
    }

    // ==================== 命令解析与分发 ====================

    private String[] parseCommand(String input) {
        java.util.List<String> tokens = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (char c : input.toCharArray()) {
            if (c == '"') inQuote = !inQuote;
            else if (c == ' ' && !inQuote) {
                if (!cur.isEmpty()) { tokens.add(cur.toString()); cur = new StringBuilder(); }
            } else cur.append(c);
        }
        if (!cur.isEmpty()) tokens.add(cur.toString());
        return tokens.toArray(new String[0]);
    }

    private Response handleCreate(String[] args) {
        if (args.length < 2) return Response.fail("用法: CREATE DATABASE|COLLECTION <name>");
        String type = args[0].toUpperCase();
        String name = args[1];
        if ("DATABASE".equals(type) || "DB".equals(type))
            return client.sendCommand(CommandType.CREATE_DATABASE, name);
        if ("COLLECTION".equals(type) || "COL".equals(type) || "TABLE".equals(type) || "TBL".equals(type))
            return client.sendCommand(CommandType.CREATE_COLLECTION, name);
        return Response.fail("未知类型: " + type);
    }

    private Response handleDrop(String[] args) {
        if (args.length < 2) return Response.fail("用法: DROP DATABASE|COLLECTION <name>");
        String type = args[0].toUpperCase();
        String name = args[1];
        if ("DATABASE".equals(type) || "DB".equals(type))
            return client.sendCommand(CommandType.DROP_DATABASE, name);
        if ("COLLECTION".equals(type) || "COL".equals(type) || "TABLE".equals(type) || "TBL".equals(type))
            return client.sendCommand(CommandType.DROP_COLLECTION, name);
        return Response.fail("未知类型: " + type);
    }

    private Response handleUse(String[] args) {
        String dbName;
        if (args.length == 0) return Response.fail("用法: USE DATABASE <name>");
        String first = args[0].toUpperCase();
        if (("DATABASE".equals(first) || "DB".equals(first)) && args.length > 1) dbName = args[1];
        else dbName = args[0];
        Response resp = client.sendCommand(CommandType.USE_DATABASE, dbName);
        if (resp.isSuccess()) currentDb = dbName;
        return resp;
    }

    private Response handlePut(String[] args) {
        if (args.length < 2) return Response.fail("用法: PUT <collection> <key> [<f:v> ...]");
        Request req = new Request(CommandType.PUT);
        req.setCollectionName(args[0]);
        req.setKey(args[1]);
        if (args.length == 2) {
            req.setValue("");
        } else if (args.length == 3 && !args[2].contains(":")) {
            req.setValue(args[2]);
        } else {
            java.util.Map<String, Object> doc = new java.util.LinkedHashMap<>();
            for (int i = 2; i < args.length; i++) {
                int colon = args[i].indexOf(':');
                if (colon > 0) {
                    doc.put(args[i].substring(0, colon), args[i].substring(colon + 1));
                }
            }
            req.setValue(doc);
        }
        return client.sendRequest(req);
    }

    private Response handleGet(String[] args) {
        if (args.length < 2) return Response.fail("用法: GET <collection> <key>");
        Request req = new Request(CommandType.GET);
        req.setCollectionName(args[0]);
        req.setKey(args[1]);
        return client.sendRequest(req);
    }

    private Response handleUpdate(String[] args) {
        if (args.length < 3) return Response.fail("用法: UPDATE <collection> <key> <f:v> ...");
        Request req = new Request(CommandType.UPDATE);
        req.setCollectionName(args[0]);
        req.setKey(args[1]);
        java.util.Map<String, Object> doc = new java.util.LinkedHashMap<>();
        for (int i = 2; i < args.length; i++) {
            int colon = args[i].indexOf(':');
            if (colon > 0) doc.put(args[i].substring(0, colon), args[i].substring(colon + 1));
        }
        if (!doc.isEmpty()) req.setValue(doc);
        return client.sendRequest(req);
    }

    private Response handleDelete(String[] args) {
        if (args.length < 2) return Response.fail("用法: DELETE <collection> <key>");
        Request req = new Request(CommandType.DELETE);
        req.setCollectionName(args[0]);
        if (args.length >= 3 && "WHERE".equalsIgnoreCase(args[1])) {
            req.setFilterField(args[2]);
            if (args.length >= 4) req.setFilterValue(args[3]);
        } else {
            req.setKey(args[1]);
        }
        return client.sendRequest(req);
    }

    private Response handleList(String[] args) {
        String type = args.length > 0 ? args[0].toUpperCase() : "";
        return switch (type) {
            case "DATABASES", "DB" -> client.sendCommand(CommandType.LIST_DATABASES);
            case "COLLECTIONS", "COLS", "TABLES", "TBL" -> client.sendCommand(CommandType.LIST_COLLECTIONS);
            default -> Response.fail("用法: LIST DATABASES|COLLECTIONS");
        };
    }

    // ==================== 工具 ====================

    private void setConnectedState(boolean connected) {
        connectBtn.setEnabled(!connected);
        disconnectBtn.setEnabled(connected);
        putBtn.setEnabled(connected);
        getBtn.setEnabled(connected);
        delBtn.setEnabled(connected);
        scanBtn.setEnabled(connected);
        saveBtn.setEnabled(connected);
        loadBtn.setEnabled(connected);
        cmdField.setEnabled(connected);
    }

    void log(String msg) {
        Runnable r = () -> {
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        };
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    void updateInfoLabel() {
        String text = "当前数据库: " + (currentDb != null ? currentDb : "(无)");
        updateLabelInContainer(getContentPane(), text);
    }

    private void updateLabelInContainer(Container container, String text) {
        for (Component c : container.getComponents()) {
            if ("infoLabel".equals(c.getName()) && c instanceof JLabel label) {
                label.setText(text);
                return;
            }
            if (c instanceof Container sub) updateLabelInContainer(sub, text);
        }
    }

    private static class GuiMessage {
        final String type;
        final Object data;
        GuiMessage(String type, Object data) { this.type = type; this.data = data; }
    }
}
