package com.database.client.gui;

import com.database.common.Protocol;

import javax.swing.*;

public class GuiMain {
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        final String fHost = args.length > 0 ? args[0] : "127.0.0.1";
        int tmpPort = Protocol.DEFAULT_PORT;
        if (args.length > 1) {
            try { tmpPort = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
        }
        final int fPort = tmpPort;

        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame(fHost, fPort);
            frame.setVisible(true);
        });
    }
}
