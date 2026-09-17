package easyshare.messenger;

import easyshare.common.Format;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** IP Messenger-style window: online users, message box, attachments, transfers. */
public final class MessengerGui implements MessengerService.Listener {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM HH:mm:ss");

    private final MessengerService service;
    private final JFrame frame = new JFrame("EasyShare Messenger");
    private final UserTableModel userModel = new UserTableModel();
    private final JTable userTable = new JTable(userModel);
    private final JTextArea messageBox = new JTextArea(5, 40);
    private final DefaultListModel<Path> attachments = new DefaultListModel<>();
    private final JList<Path> attachmentList = new JList<>(attachments);
    private final TransferTableModel transferModel = new TransferTableModel();
    private final JTable transferTable = new JTable(transferModel);
    private final JTextArea history = new JTextArea();
    private final JLabel statusBar = new JLabel(" ");
    private final JLabel onlineLabel = new JLabel();
    private TrayIcon trayIcon;

    private MessengerGui(MessengerService service) {
        this.service = service;
    }

    public static void launch(String[] args) throws Exception {
        Path settings = null;
        String name = null;
        Path receive = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--settings") && i + 1 < args.length) {
                settings = Path.of(args[++i]);
            } else if (args[i].equals("--name") && i + 1 < args.length) {
                name = args[++i];
            } else if (args[i].equals("--receive") && i + 1 < args.length) {
                receive = Path.of(args[++i]);
            } else {
                throw new IllegalArgumentException("Unknown messenger option: " + args[i]);
            }
        }
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // default look and feel
        }
        MessengerConfig config = MessengerConfig.load(settings);
        if (name != null) {
            config.name = name;
        }
        if (receive != null) {
            config.receiveDir = receive;
        }
        MessengerService service = new MessengerService(config);
        try {
            service.start();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null, "EasyShare Messenger could not start:\n" + e.getMessage(),
                    "EasyShare Messenger", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
        MessengerGui gui = new MessengerGui(service);
        service.addListener(gui);
        Runtime.getRuntime().addShutdownHook(new Thread(service::stop));
        SwingUtilities.invokeLater(gui::show);
    }

    // ---------------------------------------------------------------- layout

    private void show() {
        frame.setIconImage(icon(64));
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                service.stop();
                System.exit(0);
            }
        });

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        root.add(toolbar(), BorderLayout.NORTH);

        JSplitPane top = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, usersPanel(), composePanel());
        top.setResizeWeight(0.5);
        JSplitPane main = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottomTabs());
        main.setResizeWeight(0.62);
        root.add(main, BorderLayout.CENTER);
        statusBar.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
        root.add(statusBar, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setTransferHandler(new FileDropHandler());
        frame.setSize(1000, 680);
        frame.setMinimumSize(new Dimension(720, 480));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        setupTray();
        updateTitle();
        usersChanged();
        new Timer(500, e -> transferModel.fireTableRowsUpdated(0, Math.max(0, transferModel.getRowCount() - 1))).start();
        status("Online as " + service.config().name + ". Other PCs running EasyShare Messenger on this network appear in the list.");
    }

    private JComponent toolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> {
            service.refresh();
            status("Searching the network for other users...");
        });
        JButton addIp = new JButton("Add PC by IP...");
        addIp.addActionListener(e -> addIp());
        JButton received = new JButton("Open received folder");
        received.addActionListener(e -> openFolder(service.config().receiveDir));
        JButton settings = new JButton("Settings...");
        settings.addActionListener(e -> settings());
        bar.add(refresh);
        bar.add(addIp);
        bar.add(received);
        bar.add(settings);
        bar.add(Box.createHorizontalStrut(16));
        onlineLabel.setFont(onlineLabel.getFont().deriveFont(Font.BOLD));
        bar.add(onlineLabel);
        return bar;
    }

    private JComponent usersPanel() {
        userTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        userTable.setRowHeight(24);
        userTable.setFillsViewportHeight(true);
        userTable.getColumnModel().getColumn(0).setPreferredWidth(130);
        userTable.getColumnModel().getColumn(3).setPreferredWidth(110);
        userTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    messageBox.requestFocusInWindow();
                }
            }
        });
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.add(title("Users on the network  (select one or more; Ctrl+click for several)"), BorderLayout.NORTH);
        panel.add(new JScrollPane(userTable), BorderLayout.CENTER);
        return panel;
    }

    private JComponent composePanel() {
        messageBox.setLineWrap(true);
        messageBox.setWrapStyleWord(true);
        messageBox.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        messageBox.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "send");
        messageBox.getActionMap().put("send", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                send();
            }
        });
        messageBox.setTransferHandler(new FileDropHandler());

        attachmentList.setVisibleRowCount(4);
        attachmentList.setCellRenderer(new DefaultListCellRendererWithSize());
        attachmentList.setTransferHandler(new FileDropHandler());

        JButton addFiles = new JButton("Add files...");
        addFiles.addActionListener(e -> chooseAttachments(false));
        JButton addFolder = new JButton("Add folder...");
        addFolder.addActionListener(e -> chooseAttachments(true));
        JButton remove = new JButton("Remove");
        remove.addActionListener(e -> attachmentList.getSelectedValuesList().forEach(attachments::removeElement));
        JButton send = new JButton("Send");
        send.setFont(send.getFont().deriveFont(Font.BOLD, 14f));
        send.setPreferredSize(new Dimension(120, 36));
        send.addActionListener(e -> send());

        JPanel attachButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        attachButtons.add(addFiles);
        attachButtons.add(addFolder);
        attachButtons.add(remove);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 4, 0);
        c.gridy = 0;
        panel.add(title("Message"), c);
        c.gridy = 1;
        c.weighty = 0.6;
        panel.add(new JScrollPane(messageBox), c);
        c.gridy = 2;
        c.weighty = 0;
        panel.add(title("Attachments  (drag files or folders here)"), c);
        c.gridy = 3;
        c.weighty = 0.4;
        panel.add(new JScrollPane(attachmentList), c);
        c.gridy = 4;
        c.weighty = 0;
        JPanel row = new JPanel(new BorderLayout());
        row.add(attachButtons, BorderLayout.WEST);
        row.add(send, BorderLayout.EAST);
        panel.add(row, c);
        c.gridy = 5;
        JLabel hint = new JLabel("Ctrl+Enter = Send. Files are sent in SHA-1 verified pieces.");
        hint.setForeground(Color.GRAY);
        panel.add(hint, c);
        return panel;
    }

    private JComponent bottomTabs() {
        transferTable.setRowHeight(24);
        transferTable.getColumnModel().getColumn(4).setCellRenderer(new ProgressRenderer());
        transferTable.getColumnModel().getColumn(1).setPreferredWidth(200);
        transferTable.getColumnModel().getColumn(5).setPreferredWidth(280);
        transferTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openSelectedTransfer(false);
                }
            }
        });

        JButton open = new JButton("Open file");
        open.addActionListener(e -> openSelectedTransfer(false));
        JButton folder = new JButton("Open folder");
        folder.addActionListener(e -> openSelectedTransfer(true));
        JButton retry = new JButton("Retry");
        retry.addActionListener(e -> {
            Transfer t = selectedTransfer();
            if (t != null && t.canRetry()) {
                service.retry(t);
            }
        });
        JButton clear = new JButton("Clear finished");
        clear.addActionListener(e -> service.clearFinished());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttons.add(open);
        buttons.add(folder);
        buttons.add(retry);
        buttons.add(clear);

        JPanel transfersPanel = new JPanel(new BorderLayout());
        transfersPanel.add(new JScrollPane(transferTable), BorderLayout.CENTER);
        transfersPanel.add(buttons, BorderLayout.SOUTH);

        history.setEditable(false);
        history.setLineWrap(true);
        history.setWrapStyleWord(true);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Transfers", transfersPanel);
        tabs.addTab("Message history", new JScrollPane(history));
        return tabs;
    }

    private static JLabel title(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    // ---------------------------------------------------------------- actions

    private void send() {
        List<LanUser> selected = new ArrayList<>();
        for (int row : userTable.getSelectedRows()) {
            selected.add(userModel.users.get(userTable.convertRowIndexToModel(row)));
        }
        String text = messageBox.getText().trim();
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < attachments.size(); i++) {
            files.add(attachments.get(i));
        }
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Select at least one user in the list first.\n"
                    + "If nobody is listed, click Refresh or 'Add PC by IP...'.", "Send", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (text.isEmpty() && files.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Type a message or attach a file.", "Send", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        service.send(selected, text, files);
        StringBuilder names = new StringBuilder();
        selected.forEach(u -> names.append(names.length() == 0 ? "" : ", ").append(u.name()));
        appendHistory("To " + names + ": " + (text.isEmpty() ? "(no text)" : text) + attachmentsText(files));
        messageBox.setText("");
        attachments.clear();
        status("Sending to " + names + "...");
    }

    private static String attachmentsText(List<Path> files) {
        if (files.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n    Attached: ");
        for (int i = 0; i < files.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(files.get(i).getFileName());
        }
        return sb.toString();
    }

    private void chooseAttachments(boolean folders) {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(folders ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            for (File f : chooser.getSelectedFiles()) {
                addAttachment(f.toPath());
            }
        }
    }

    private void addAttachment(Path path) {
        if (!attachments.contains(path) && (Files.isRegularFile(path) || Files.isDirectory(path))) {
            attachments.addElement(path);
        }
    }

    private void addIp() {
        String ip = JOptionPane.showInputDialog(frame,
                "IP address of the other PC (use this if it does not appear automatically,\n"
                        + "for example when the PCs are on different subnets):", "Add PC by IP", JOptionPane.QUESTION_MESSAGE);
        if (ip != null && !ip.isBlank()) {
            try {
                service.addHost(ip);
                status("Contacting " + ip.trim() + "... it appears in the list if EasyShare Messenger is running there.");
            } catch (IOException e) {
                JOptionPane.showMessageDialog(frame, "Invalid address: " + ip, "Add PC by IP", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void settings() {
        MessengerConfig c = service.config();
        JTextField name = new JTextField(c.name, 20);
        JTextField group = new JTextField(c.group, 20);
        JTextField folder = new JTextField(c.receiveDir.toString(), 28);
        JButton browse = new JButton("Browse...");
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(folder.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                folder.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 4, 4, 4);
        g.anchor = GridBagConstraints.WEST;
        addRow(panel, g, 0, "Your name:", name, null);
        addRow(panel, g, 1, "Group:", group, null);
        addRow(panel, g, 2, "Save received files in:", folder, browse);
        if (JOptionPane.showConfirmDialog(frame, panel, "Settings", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                == JOptionPane.OK_OPTION && !name.getText().isBlank()) {
            c.name = name.getText().trim();
            c.group = group.getText().trim().isEmpty() ? "EasyShare" : group.getText().trim();
            c.receiveDir = Path.of(folder.getText().trim());
            try {
                c.save();
            } catch (IOException e) {
                status("Could not save settings: " + e.getMessage());
            }
            service.settingsChanged();
            updateTitle();
            status("Settings saved.");
        }
    }

    private static void addRow(JPanel panel, GridBagConstraints g, int row, String label, JComponent field, JComponent extra) {
        g.gridy = row;
        g.gridx = 0;
        panel.add(new JLabel(label), g);
        g.gridx = 1;
        panel.add(field, g);
        if (extra != null) {
            g.gridx = 2;
            panel.add(extra, g);
        }
    }

    private Transfer selectedTransfer() {
        int row = transferTable.getSelectedRow();
        return row < 0 ? null : transferModel.rows.get(transferTable.convertRowIndexToModel(row));
    }

    private void openSelectedTransfer(boolean folder) {
        Transfer t = selectedTransfer();
        if (t == null) {
            return;
        }
        if (t.result() == null) {
            if (folder && t.direction == Transfer.Direction.RECEIVE) {
                openFolder(service.config().receiveDir);
            }
            return;
        }
        try {
            if (folder) {
                new ProcessBuilder("explorer.exe", "/select,", t.result().toAbsolutePath().toString()).start();
            } else {
                Desktop.getDesktop().open(t.result().toFile());
            }
        } catch (IOException | RuntimeException e) {
            openFolder(t.result().toAbsolutePath().getParent());
        }
    }

    private void openFolder(Path folder) {
        try {
            Files.createDirectories(folder);
            Desktop.getDesktop().open(folder.toFile());
        } catch (IOException | RuntimeException e) {
            status("Folder: " + folder.toAbsolutePath());
        }
    }

    // ---------------------------------------------------------------- incoming message window

    private void showMessage(IncomingMessage m) {
        JDialog dialog = new JDialog(frame, "Message from " + m.fromName, false);
        dialog.setIconImage(icon(64));
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel from = new JLabel(m.fromName + "  (" + m.fromGroup + ")");
        from.setFont(from.getFont().deriveFont(Font.BOLD, 15f));
        JLabel detail = new JLabel(m.fromHost + "  " + m.fromAddress + "    " + m.time.format(TIME));
        detail.setForeground(Color.GRAY);
        header.add(from);
        header.add(detail);
        root.add(header, BorderLayout.NORTH);

        JTextArea text = new JTextArea(m.text.isEmpty() ? "(no text)" : m.text, 6, 36);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));

        JPanel center = new JPanel(new BorderLayout(4, 6));
        center.add(new JScrollPane(text), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        if (!m.offers.isEmpty()) {
            DefaultListModel<String> files = new DefaultListModel<>();
            long total = 0;
            for (IncomingMessage.Offer o : m.offers) {
                files.addElement(o.name() + "   (" + Format.bytes(o.size()) + ")");
                total += o.size();
            }
            JList<String> fileList = new JList<>(files);
            fileList.setVisibleRowCount(Math.min(5, files.size()));
            fileList.setSelectionInterval(0, files.size() - 1);
            JPanel attached = new JPanel(new BorderLayout(2, 2));
            attached.add(title(m.offers.size() + " attached file(s), " + Format.bytes(total) + "  (selected files will be saved)"), BorderLayout.NORTH);
            attached.add(new JScrollPane(fileList), BorderLayout.CENTER);
            center.add(attached, BorderLayout.SOUTH);

            JButton save = new JButton("Save files");
            save.setFont(save.getFont().deriveFont(Font.BOLD));
            JButton decline = new JButton("Decline");
            save.addActionListener(e -> {
                List<IncomingMessage.Offer> chosen = new ArrayList<>();
                for (int i : fileList.getSelectedIndices()) {
                    chosen.add(m.offers.get(i));
                }
                if (chosen.isEmpty()) {
                    return;
                }
                service.accept(m, chosen);
                save.setEnabled(false);
                decline.setEnabled(false);
                save.setText("Saving - see Transfers");
                status("Receiving " + chosen.size() + " file(s) from " + m.fromName + " into " + service.config().receiveDir);
            });
            decline.addActionListener(e -> {
                service.decline(m);
                save.setEnabled(false);
                decline.setEnabled(false);
            });
            buttons.add(save);
            buttons.add(decline);
        }
        JButton reply = new JButton("Reply");
        reply.addActionListener(e -> {
            selectUser(m.fromId);
            frame.toFront();
            messageBox.requestFocusInWindow();
            dialog.dispose();
        });
        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());
        buttons.add(reply);
        buttons.add(close);

        root.add(center, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
        dialog.toFront();
    }

    private void selectUser(String userId) {
        for (int i = 0; i < userModel.users.size(); i++) {
            if (userModel.users.get(i).id.equals(userId)) {
                int view = userTable.convertRowIndexToView(i);
                userTable.setRowSelectionInterval(view, view);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- service callbacks

    @Override
    public void usersChanged() {
        SwingUtilities.invokeLater(() -> {
            List<String> selected = new ArrayList<>();
            for (int row : userTable.getSelectedRows()) {
                selected.add(userModel.users.get(userTable.convertRowIndexToModel(row)).id);
            }
            userModel.users = service.users();
            userModel.fireTableDataChanged();
            for (int i = 0; i < userModel.users.size(); i++) {
                if (selected.contains(userModel.users.get(i).id)) {
                    int view = userTable.convertRowIndexToView(i);
                    userTable.addRowSelectionInterval(view, view);
                }
            }
            onlineLabel.setText(userModel.users.size() + " user(s) online");
        });
    }

    @Override
    public void messageReceived(IncomingMessage message) {
        SwingUtilities.invokeLater(() -> {
            appendHistory("From " + message.fromName + " (" + message.fromAddress + "): "
                    + (message.text.isEmpty() ? "(no text)" : message.text)
                    + (message.offers.isEmpty() ? "" : "\n    Attached: " + String.join(", ",
                    message.offers.stream().map(IncomingMessage.Offer::name).toList())));
            Toolkit.getDefaultToolkit().beep();
            if (trayIcon != null) {
                trayIcon.displayMessage("Message from " + message.fromName,
                        message.text.isEmpty() ? message.offers.size() + " file(s) attached" : message.text, TrayIcon.MessageType.INFO);
            }
            showMessage(message);
        });
    }

    @Override
    public void transfersChanged() {
        SwingUtilities.invokeLater(() -> {
            transferModel.rows = service.transfers();
            transferModel.fireTableDataChanged();
        });
    }

    @Override
    public void notice(String text) {
        SwingUtilities.invokeLater(() -> status(text));
    }

    private void status(String text) {
        statusBar.setText(text);
    }

    private void appendHistory(String text) {
        history.append("[" + java.time.LocalDateTime.now().format(TIME) + "] " + text + "\n\n");
        history.setCaretPosition(history.getDocument().getLength());
    }

    private void updateTitle() {
        frame.setTitle("EasyShare Messenger - " + service.config().name + " (" + MessengerConfig.computerName() + ")");
    }

    private void setupTray() {
        if (!SystemTray.isSupported()) {
            return;
        }
        try {
            trayIcon = new TrayIcon(icon(16), "EasyShare Messenger");
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> {
                frame.setVisible(true);
                frame.setState(JFrame.NORMAL);
                frame.toFront();
            });
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException | RuntimeException e) {
            trayIcon = null;
        }
    }

    private static BufferedImage icon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x2563eb));
        g.fillRoundRect(0, 0, size, size, size / 3, size / 3);
        g.setColor(Color.WHITE);
        int s = size / 5;
        g.fillOval(s, s, s * 2, s * 2);
        g.fillOval(size - s * 3, size - s * 3, s * 2, s * 2);
        g.dispose();
        return img;
    }

    // ---------------------------------------------------------------- table models and renderers

    private static final class UserTableModel extends AbstractTableModel {
        private final String[] columns = {"Name", "Group", "Computer", "IP address"};
        List<LanUser> users = new ArrayList<>();

        @Override
        public int getRowCount() {
            return users.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            LanUser u = users.get(row);
            return switch (column) {
                case 0 -> u.name();
                case 1 -> u.group();
                case 2 -> u.host();
                default -> u.address();
            };
        }
    }

    private final class TransferTableModel extends AbstractTableModel {
        private final String[] columns = {"", "File", "Size", "With", "Progress", "Status"};
        List<Transfer> rows = new ArrayList<>();

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            Transfer t = rows.get(row);
            return switch (column) {
                case 0 -> t.direction == Transfer.Direction.SEND ? "Sent" : "Received";
                case 1 -> t.fileName;
                case 2 -> Format.bytes(t.size);
                case 3 -> t.userName;
                case 4 -> service.progress(t);
                default -> t.status();
            };
        }
    }

    private static final class ProgressRenderer extends JProgressBar implements javax.swing.table.TableCellRenderer {
        ProgressRenderer() {
            super(0, 100);
            setStringPainted(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
            int v = value instanceof Integer i ? i : 0;
            setValue(v);
            setString(v + "%");
            return this;
        }
    }

    private static final class DefaultListCellRendererWithSize extends javax.swing.DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            Path p = (Path) value;
            String text;
            try {
                text = Files.isDirectory(p) ? p.getFileName() + "  (folder, sent as .zip)" : p.getFileName() + "  (" + Format.bytes(Files.size(p)) + ")";
            } catch (IOException e) {
                text = p.toString();
            }
            return super.getListCellRendererComponent(list, text, index, selected, focus);
        }
    }

    /** Lets the user drop files and folders from Explorer onto the window. */
    private final class FileDropHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                    || (support.getComponent() == messageBox && support.isDataFlavorSupported(DataFlavor.stringFlavor));
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport support) {
            try {
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    for (File f : (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor)) {
                        addAttachment(f.toPath());
                    }
                    status("Attached " + attachments.size() + " item(s). Select users and click Send.");
                    return true;
                }
                if (support.getComponent() == messageBox) {
                    messageBox.replaceSelection((String) support.getTransferable().getTransferData(DataFlavor.stringFlavor));
                    return true;
                }
            } catch (Exception e) {
                status("Drop failed: " + e.getMessage());
            }
            return false;
        }
    }
}
