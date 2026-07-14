package com.database.client.gui;

import com.database.core.KV;

import javax.swing.table.AbstractTableModel;
import java.util.*;

public class ResultTableModel extends AbstractTableModel {
    private List<KV> data = Collections.emptyList();
    private List<String> columns = List.of("key", "value", "version");

    public void setData(List<KV> kvs) {
        this.data = kvs != null ? kvs : Collections.emptyList();
        rebuildColumns();
        fireTableStructureChanged();
    }

    public void clear() {
        this.data = Collections.emptyList();
        this.columns = List.of("key", "value", "version");
        fireTableStructureChanged();
    }

    @SuppressWarnings("unchecked")
    private void rebuildColumns() {
        if (data.isEmpty()) {
            columns = List.of("key", "value", "version");
            return;
        }
        boolean allMap = true;
        for (KV kv : data) {
            if (!(kv.getValue() instanceof Map)) {
                allMap = false;
                break;
            }
        }
        if (allMap) {
            Set<String> keys = new LinkedHashSet<>();
            keys.add("key");
            for (KV kv : data) {
                Map<String, Object> map = (Map<String, Object>) kv.getValue();
                keys.addAll(map.keySet());
            }
            keys.add("version");
            columns = new ArrayList<>(keys);
        } else {
            columns = List.of("key", "value", "version");
        }
    }

    @Override
    public int getRowCount() {
        return data.size();
    }

    @Override
    public int getColumnCount() {
        return columns.size();
    }

    @Override
    public String getColumnName(int col) {
        return columns.get(col);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object getValueAt(int row, int col) {
        KV kv = data.get(row);
        String colName = columns.get(col);
        if ("key".equals(colName)) return kv.getKey();
        if ("version".equals(colName)) return kv.getVersion();
        if (kv.getValue() instanceof Map<?, ?> map) {
            Object val = ((Map<String, Object>) map).get(colName);
            return val != null ? val : "";
        }
        return kv.getValue();
    }
}
