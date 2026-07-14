package com.database.client;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 控制台读取器 - 读取用户输入并记录历史
 * 支持 HISTORY 命令和 !<n> 快捷执行
 */
public class ConsoleReader implements Closeable {
    private final BufferedReader reader;
    private final List<String> cmdHistory;

    public ConsoleReader() {
        this.reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        this.cmdHistory = new ArrayList<>();
    }

    public String readLine(String prompt) throws IOException {
        System.out.print(prompt);
        System.out.flush();
        String line = reader.readLine();
        if (line != null) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.matches("^!\\d+$")) {
                cmdHistory.add(trimmed);
                if (cmdHistory.size() > 1000) cmdHistory.remove(0);
            }
        }
        return line;
    }

    public List<String> getHistory() {
        return new ArrayList<>(cmdHistory);
    }

    @Override
    public void close() {}
}
