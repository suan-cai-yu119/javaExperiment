package com.database.client.gui;

import com.database.common.CommandType;
import com.database.common.Response;
import com.database.client.Client;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

public class DatabaseTree extends JTree {
    private final DefaultMutableTreeNode root;
    private final MainFrame mainFrame;
    private Client client;

    public DatabaseTree(MainFrame mainFrame) {
        this.mainFrame = mainFrame;
        this.root = new DefaultMutableTreeNode(new TreeNodeData(TreeNodeData.Type.ROOT, "数据库"));
        setModel(new DefaultTreeModel(root));
        setRootVisible(true);
        setShowsRootHandles(true);
        setCellRenderer(new NodeRenderer());

        addTreeExpansionListener(new TreeExpansionListener());
        addMouseListener(new TreeMouseListener());
    }

    public void setClient(Client client) {
        this.client = client;
    }

    public void clearAll() {
        runOnEDT(() -> {
            root.removeAllChildren();
            ((DefaultTreeModel) getModel()).nodeStructureChanged(root);
        });
    }

    @SuppressWarnings("unchecked")
    public Set<String> fetchDatabases() {
        if (client == null) return Collections.emptySet();
        try {
            Response resp = client.sendCommand(CommandType.LIST_DATABASES);
            if (resp.isSuccess() && resp.getData() instanceof Set<?> set) {
                return (Set<String>) set;
            }
        } catch (Exception ignored) {}
        return Collections.emptySet();
    }

    @SuppressWarnings("unchecked")
    public Set<String> fetchCollections(String dbName) {
        if (client == null) return Collections.emptySet();
        try {
            Response useResp = client.sendCommand(CommandType.USE_DATABASE, dbName);
            if (useResp.isSuccess()) {
                Response listResp = client.sendCommand(CommandType.LIST_COLLECTIONS);
                if (listResp.isSuccess() && listResp.getData() instanceof Set<?> set) {
                    return (Set<String>) set;
                }
            }
        } catch (Exception ignored) {}
        return Collections.emptySet();
    }

    public void applyDatabases(Set<String> dbs) {
        runOnEDT(() -> {
            root.removeAllChildren();
            for (String db : dbs) {
                DefaultMutableTreeNode dbNode = new DefaultMutableTreeNode(
                        new TreeNodeData(TreeNodeData.Type.DATABASE, db));
                dbNode.add(new DefaultMutableTreeNode("加载中..."));
                root.add(dbNode);
            }
            ((DefaultTreeModel) getModel()).nodeStructureChanged(root);
            expandPath(new TreePath(root.getPath()));
        });
    }

    public void loadDatabases() {
        Set<String> dbs = fetchDatabases();
        applyDatabases(dbs);
    }

    private void loadCollections(DefaultMutableTreeNode dbNode) {
        if (client == null || !(dbNode.getUserObject() instanceof TreeNodeData data)
                || data.type != TreeNodeData.Type.DATABASE) return;
        Set<String> cols = fetchCollections(data.name);
        applyCollections(dbNode, cols);
    }

    private void applyCollections(DefaultMutableTreeNode dbNode, Set<String> cols) {
        runOnEDT(() -> {
            dbNode.removeAllChildren();
            for (String col : cols) {
                dbNode.add(new DefaultMutableTreeNode(
                        new TreeNodeData(TreeNodeData.Type.COLLECTION, col)));
            }
            ((DefaultTreeModel) getModel()).nodeStructureChanged(dbNode);
        });
    }

    public String getSelectedCollection() {
        TreePath path = getSelectionPath();
        if (path == null || path.getPathCount() < 3) return null;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        if (node.getUserObject() instanceof TreeNodeData data
                && data.type == TreeNodeData.Type.COLLECTION) {
            return data.name;
        }
        return null;
    }

    public String getSelectedDatabase() {
        TreePath path = getSelectionPath();
        if (path == null) return null;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        if (node.getUserObject() instanceof TreeNodeData data) {
            if (data.type == TreeNodeData.Type.DATABASE) return data.name;
            if (data.type == TreeNodeData.Type.COLLECTION && node.getParent() != null) {
                DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
                if (parent.getUserObject() instanceof TreeNodeData pd) return pd.name;
            }
        }
        return null;
    }

    private void runOnEDT(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    private class TreeExpansionListener implements javax.swing.event.TreeExpansionListener {
        @Override
        public void treeExpanded(javax.swing.event.TreeExpansionEvent event) {
            TreePath path = event.getPath();
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            if (node.getUserObject() instanceof TreeNodeData data) {
                if (data.type == TreeNodeData.Type.DATABASE) {
                    loadCollections(node);
                }
            }
        }

        @Override
        public void treeCollapsed(javax.swing.event.TreeExpansionEvent event) {}
    }

    private class TreeMouseListener extends MouseAdapter {
        @Override
        public void mouseClicked(MouseEvent e) {
            TreePath path = getPathForLocation(e.getX(), e.getY());
            if (path == null) return;
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            if (!(node.getUserObject() instanceof TreeNodeData data)) return;

            if (e.getClickCount() == 2) {
                if (data.type == TreeNodeData.Type.COLLECTION) {
                    mainFrame.scanCollection(data.name);
                }
            }
            showPopup(e);
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            showPopup(e);
        }

        private void showPopup(MouseEvent e) {
            if (!e.isPopupTrigger()) return;
            TreePath path = getPathForLocation(e.getX(), e.getY());
            if (path == null) return;
            setSelectionPath(path);
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            if (!(node.getUserObject() instanceof TreeNodeData)) return;
            final TreeNodeData fData = (TreeNodeData) node.getUserObject();
            final DefaultMutableTreeNode fNode = node;

            JPopupMenu popup = new JPopupMenu();
            if (fData.type == TreeNodeData.Type.DATABASE) {
                JMenuItem createCol = new JMenuItem("创建集合");
                createCol.addActionListener(ev -> mainFrame.showCreateCollectionDialog(fData.name));
                popup.add(createCol);
                JMenuItem dropDb = new JMenuItem("删除数据库");
                dropDb.addActionListener(ev -> mainFrame.dropDatabase(fData.name));
                popup.add(dropDb);
                JMenuItem refresh = new JMenuItem("刷新");
                refresh.addActionListener(ev -> loadCollections(fNode));
                popup.add(refresh);
            } else if (fData.type == TreeNodeData.Type.COLLECTION) {
                JMenuItem scan = new JMenuItem("扫描");
                scan.addActionListener(ev -> mainFrame.scanCollection(fData.name));
                popup.add(scan);
                JMenuItem dropCol = new JMenuItem("删除集合");
                dropCol.addActionListener(ev -> mainFrame.dropCollection(fData.name));
                popup.add(dropCol);
            }
            popup.show(e.getComponent(), e.getX(), e.getY());
        }
    }

    static class TreeNodeData {
        enum Type { ROOT, DATABASE, COLLECTION }
        final Type type;
        final String name;

        TreeNodeData(Type type, String name) {
            this.type = type;
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static class NodeRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            if (value instanceof DefaultMutableTreeNode node
                    && node.getUserObject() instanceof TreeNodeData data) {
                switch (data.type) {
                    case ROOT -> setIcon(null);
                    case DATABASE -> setIcon(null);
                    case COLLECTION -> setIcon(null);
                }
            }
            return this;
        }
    }
}
