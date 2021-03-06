package featurecat.lizzie;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.jcabi.manifests.Manifests;
import com.toomasr.sgf4j.Sgf;
import com.toomasr.sgf4j.parser.Game;
import com.toomasr.sgf4j.parser.GameNode;
import com.toomasr.sgf4j.parser.Util;
import featurecat.lizzie.analysis.GnuGoScoreEstimator;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.ScoreEstimator;
import featurecat.lizzie.analysis.ZenScoreEstimator;
import featurecat.lizzie.gui.*;
import featurecat.lizzie.rules.*;
import featurecat.lizzie.util.ThreadPoolUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jfree.graphics2d.svg.SVGGraphics2D;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.UIManager.LookAndFeelInfo;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

/**
 * Main class.
 */
public class Lizzie {
    static {
        // Make java.util.logging work with log4j
        System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
    }

    private static final Logger logger = LogManager.getLogger(Lizzie.class);
    private static final ResourceBundle resourceBundle = ResourceBundle.getBundle("featurecat.lizzie.i18n.GuiBundle");

    public static final String SETTING_FILE = "mylizzie.json";
    public static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public static final CountDownLatch exitLatch = new CountDownLatch(1);
    private static int lizzieExitCode = 0;

    public static GtpConsoleDialog gtpConsole;
    public static LizzieFrame frame;
    public static JDialog analysisDialog;
    public static AnalysisFrame analysisFrame;
    public static Leelaz leelaz;
    public static Board board;
    public static OptionDialog optionDialog;
    public static OptionSetting optionSetting;
    public static WinrateHistogramDialog winrateHistogramDialog;
    public static ScheduledExecutorService miscExecutor = Executors.newSingleThreadScheduledExecutor();
    public static ScoreEstimator scoreEstimator = null;
    public static GameStatusManager gameStatusManager = new GameStatusManager();
    public static LiveStatus liveStatus = new LiveStatus();

    static {
        readSettingFile();

        migrateSettings();

        // Sometimes gson will fail to parse the file
        if (optionSetting == null
                || optionSetting.getBoardColor() == null
                || StringUtils.isEmpty(optionSetting.getLeelazCommandLine())) {
            optionSetting = new OptionSetting();
        }
    }

    private static void migrateSettings() {
        if (isMigrationNeeded()) {
            List<String> engineProfileList = optionSetting.getEngineProfileList();
            String currentEngineProfile = optionSetting.getLeelazCommandLine();

            optionSetting.setEngineProfileList(engineProfileList.stream()
                    .map(Lizzie::migrateEngineProfile)
                    .collect(Collectors.toCollection(ArrayList::new)));
            optionSetting.setLeelazCommandLine(migrateEngineProfile(currentEngineProfile));
        }
    }

    private static String migrateEngineProfile(String oldProfile) {
        if (StringUtils.isEmpty(oldProfile)) {
            return oldProfile;
        } else {
            return "./leelaz " + oldProfile;
        }
    }

    private static boolean isMigrationNeeded() {
        try (Reader reader = new FileReader(SETTING_FILE)) {
            Type mapType = new TypeToken<LinkedHashMap<String, Object>>() {
            }.getType();
            LinkedHashMap<String, Object> rawSettings = gson.fromJson(reader, mapType);
            Integer version = (Integer) rawSettings.get("version");
            return version == null || version < new OptionSetting().getVersion();
        } catch (Exception e) {
            return false;
        }
    }

    public static void exitLizzie(int exitCode) {
        leelaz.setThinking(false);

        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            // Do nothing
        }

        if (Lizzie.board.getHistory().getInitialNode().getNext() != null) {
            Lizzie.storeGameByFile(Paths.get("restore.sgf"));
        }

        Lizzie.leelaz.close();

        ThreadPoolUtil.shutdownAndAwaitTermination(Lizzie.miscExecutor);
        if (scoreEstimator != null) {
            try {
                scoreEstimator.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        System.exit(exitCode);
    }

    public static void notifyExitLizzie(int exitCode) {
        lizzieExitCode = exitCode;

        // Main thread will exit
        exitLatch.countDown();
    }

    /**
     * Launches the game window, and runs the game.
     */
    public static void main(String[] args) {
        // Use system default look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e1) {
            // Use Nimbus look and feel which looks better
            try {
                for (LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                    if ("Nimbus".equals(info.getName())) {
                        UIManager.setLookAndFeel(info.getClassName());
                        break;
                    }
                }
            } catch (Exception e2) {
                // If Nimbus is not available, leave it for default
            }
        }

        gtpConsole = new GtpConsoleDialog(null);
        optionSetting.getGtpConsoleWindowState().applyStateTo(gtpConsole);
        gtpConsole.setVisible(true);

        leelaz = new Leelaz(optionSetting.getLeelazCommandLine());
        board = new Board();
        leelaz.startEngine();
        board.linkBoardWithAnalyzeEngine();

        frame = new LizzieFrame();

        analysisDialog = AnalysisFrame.createAnalysisDialog(frame);
        analysisFrame = (AnalysisFrame) analysisDialog.getContentPane();

        optionDialog = new OptionDialog(frame);
        optionDialog.setDialogSetting(optionSetting);

        winrateHistogramDialog = new WinrateHistogramDialog(frame);

        setGuiPosition();

        analysisDialog.setVisible(optionSetting.isAnalysisWindowShow());
        winrateHistogramDialog.setVisible(optionSetting.isWinrateHistogramWindowShow());

        if (Files.exists(Paths.get("Zen.dll")) && Files.exists(Paths.get("YAZenGtp.exe"))) {
            scoreEstimator = new ZenScoreEstimator("YAZenGtp.exe");
        } else if (Files.exists(Paths.get("gnugo")) || Files.exists(Paths.get("gnugo.exe"))) {
            scoreEstimator = new GnuGoScoreEstimator("./gnugo --mode gtp");
        }

        try {
            leelaz.setThinking(true);
            gtpConsole.setVisible(optionSetting.isGtpConsoleWindowShow());
            exitLatch.await();
        } catch (InterruptedException e) {
            // Do nothing
        }

        exitLizzie(lizzieExitCode);
    }

    public static void clearBoardAndState() {
        board.clear();
    }

    public static void loadGameByPrompting() {
        FileNameExtensionFilter filter = new FileNameExtensionFilter("*.sgf", "SGF");
        final JFileChooser chooser = new JFileChooser(optionSetting.getLastChooserLocation());
        chooser.addChoosableFileFilter(filter);
        chooser.setMultiSelectionEnabled(false);
        chooser.setAcceptAllFileFilterUsed(false);

        setFileChooserAutoFocusOnTextField(chooser);

        int state = chooser.showOpenDialog(frame);
        if (state == JFileChooser.APPROVE_OPTION) {
            optionSetting.setLastChooserLocation(chooser.getSelectedFile().toPath().getParent().toString());

            File file = chooser.getSelectedFile();
            if (!file.getPath().toLowerCase().endsWith(".sgf")) {
                file = new File(file.getPath() + ".sgf");
            }

            loadGameByFile(file.toPath());
        }
    }

    private static void setFileChooserAutoFocusOnTextField(JFileChooser chooser) {
        chooser.addHierarchyListener(new HierarchyListener() {
            public void hierarchyChanged(HierarchyEvent he) {
                grabFocusForTextField(chooser.getComponents());
            }

            // Loop to find the JTextField, the first
            // JTextField in JFileChooser
            // Even if you setAccessory which contains a JTextField
            // or which is JTextField itself, it will not get focus
            private void grabFocusForTextField(Component[] components) {
                for (Component component : components) {
                    if (component instanceof JTextField) {
                        JTextField textField = (JTextField) component;
                        textField.grabFocus();
                        break;
                    } else if (component instanceof JPanel) {
                        JPanel panel = (JPanel) component;
                        grabFocusForTextField(panel.getComponents());
                    }
                }
            }
        });
    }

    public static void copyGameToClipboardInSgf() {
        try {
            Game game = snapshotCurrentGame();
            String sgfContent = writeSgfToString(game);

            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Transferable transferableString = new StringSelection(sgfContent);
            clipboard.setContents(transferableString, null);
        } catch (Exception e) {
            logger.error("Error in copying game to clipboard.");
        }
    }

    public static void pasteGameFromClipboardInSgf() {
        try {
            String sgfContent = null;
            // Read from clipboard
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Transferable clipboardContents = clipboard.getContents(null);
            if (clipboardContents != null) {
                if (clipboardContents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    sgfContent = (String) clipboardContents.getTransferData(DataFlavor.stringFlavor);
                }
            }

            if (StringUtils.isNotEmpty(sgfContent)) {
                Game game = Sgf.createFromString(sgfContent);
                loadGameToBoard(game);
            }
        } catch (Exception e) {
            logger.error("Error in copying game from clipboard.");
        }
    }

    public static void promptForChangeExistingMove() {
        ChangeMoveDialog changeMoveDialog = new ChangeMoveDialog(frame);
        changeMoveDialog.setVisible(true);
        if (changeMoveDialog.isUserApproved()) {
            int moveNumber = changeMoveDialog.getMoveNumber();
            String correctedMove = changeMoveDialog.getCorrectedMove().trim().toUpperCase();
            int[] convertedCoords = Board.convertDisplayNameToCoordinates(correctedMove);
            if (StringUtils.equalsIgnoreCase(correctedMove, "pass")) {
                Lizzie.miscExecutor.execute(() -> board.changeMove(moveNumber, (int[]) null));
            } else if (StringUtils.equalsIgnoreCase(correctedMove, "swap") || StringUtils.startsWithIgnoreCase(correctedMove, "trans")) {
                Lizzie.miscExecutor.execute(() -> board.swapMoveColor(moveNumber));
            } else if (Board.isValid(convertedCoords)) {
                Lizzie.miscExecutor.execute(() -> board.changeMove(moveNumber, convertedCoords));
            } else {
                JOptionPane.showMessageDialog(frame, resourceBundle.getString("Lizzie.prompt.invalidCoordinates"), "Lizzie", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    public static void switchEngineByProfileIndex(int profileIndex) {
        String newCommandLine = CollectionUtils.isEmpty(Lizzie.optionSetting.getEngineProfileList()) ? "" : Lizzie.optionSetting.getEngineProfileList().get(profileIndex);
        if (StringUtils.isNotEmpty(newCommandLine)) {
            Lizzie.optionSetting.setLeelazCommandLine(newCommandLine);

            switchEngineBySetting();
        }
    }

    public static void switchEngineBySetting() {
        final int moveNumber = board.getData().getMoveNumber();

        // Workaround for leelaz cannot exit when restarting
        leelaz.setThinking(false);

        board.resetHead();
        leelaz.restartEngine(Lizzie.optionSetting.getLeelazCommandLine());
        board.gotoMove(moveNumber);

        SwingUtilities.invokeLater(() -> frame.setEngineProfile(Lizzie.optionSetting.getLeelazCommandLine()));
    }

    private static class MoveReplayer {
        private boolean nextIsBlack;
        private int placedMoveCount;

        public MoveReplayer() {
            nextIsBlack = true;
            placedMoveCount = 0;
        }

        public void playMove(boolean isBlack, int x, int y) {
            if (nextIsBlack == isBlack) {
                Lizzie.board.place(x, y);
                nextIsBlack = !nextIsBlack;

                placedMoveCount += 1;
            } else {
                Lizzie.board.pass();
                Lizzie.board.place(x, y);

                placedMoveCount += 2;
            }
        }

        public int getPlacedMoveCount() {
            return placedMoveCount;
        }

        public void clearPlacedMoveCount () {
            placedMoveCount = 0;
        }
    }

    public static void loadGameByFile(Path gameFilePath) {
        try {
            Game game = Sgf.createFromPath(gameFilePath);
            loadGameToBoard(game);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "Error: cannot load sgf: " + e.getMessage(), "Lizzie", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void loadGameToBoard(final Game game) {
        Lizzie.leelaz.batchGtpCommands(() -> {
            try {
                clearBoardAndState();

                GameNode node = game.getRootNode();
                MoveReplayer replayer = new MoveReplayer();

                // Process pre-placed stones
                placePreplacedMove(replayer, game.getProperty("AB"), game.getProperty("AW"));
                int preplacedStonesCount = replayer.getPlacedMoveCount();

                do {
                    String preplacedBlack = node.getProperty("AB");
                    String preplacedWhite = node.getProperty("AW");
                    if (StringUtils.isNotEmpty(preplacedBlack) || StringUtils.isNotEmpty(preplacedWhite)) {
                        placePreplacedMove(replayer, preplacedBlack, preplacedWhite);
                    }
                    if (node.isMove()) {
                        if (StringUtils.isNotEmpty(node.getProperty("B"))) {
                            int[] coords = node.getCoords();
                            if (coords != null && coords[0] < 19 && coords[0] >= 0 && coords[1] < 19 && coords[1] >= 0) {
                                replayer.playMove(true, coords[0], coords[1]);
                            }
                        }
                        if (StringUtils.isNotEmpty(node.getProperty("W"))) {
                            int[] coords = node.getCoords();
                            if (coords != null && coords[0] < 19 && coords[0] >= 0 && coords[1] < 19 && coords[1] >= 0) {
                                replayer.playMove(false, coords[0], coords[1]);
                            }
                        }
                    }
                }
                while ((node = node.getNextNode()) != null);

                liveStatus.setHiddenMoveCount(preplacedStonesCount);
            } catch (Exception e) {
                // Ignore
            }
        });
    }

    private static void placePreplacedMove(MoveReplayer replayer, String preplacedBlackStoneString, String preplacedWhiteStoneString) {
        List<int[]> preplacedBlackStones = Collections.emptyList(), preplacedWhiteStones = Collections.emptyList();
        if (StringUtils.isNotEmpty(preplacedBlackStoneString)) {
            preplacedBlackStones = Arrays.stream(preplacedBlackStoneString.split(","))
                    .map(String::trim)
                    .map(Util::alphaToCoords)
                    .collect(Collectors.toList());
        }
        if (StringUtils.isNotEmpty(preplacedWhiteStoneString)) {
            preplacedWhiteStones = Arrays.stream(preplacedWhiteStoneString.split(","))
                    .map(String::trim)
                    .map(Util::alphaToCoords)
                    .collect(Collectors.toList());
        }

        if (CollectionUtils.isNotEmpty(preplacedBlackStones) || CollectionUtils.isNotEmpty(preplacedWhiteStones)) {
            int maxLength = Math.max(preplacedBlackStones.size(), preplacedWhiteStones.size());
            for (int i = 0; i < maxLength; ++i) {
                if (i < preplacedBlackStones.size()) {
                    replayer.playMove(true, preplacedBlackStones.get(i)[0], preplacedBlackStones.get(i)[1]);
                }
                if (i < preplacedWhiteStones.size()) {
                    replayer.playMove(false, preplacedWhiteStones.get(i)[0], preplacedWhiteStones.get(i)[1]);
                }
            }
        }
    }

    public static void storeGameByFile(Path filePath) {
        try {
            Game game = snapshotCurrentGame();
            writeSgfToFile(game, filePath);
        } catch (Exception e) {
            if (StringUtils.isEmpty(e.getMessage())) {
                JOptionPane.showMessageDialog(frame, "Error: cannot save sgf: " + e.getMessage(), "Lizzie", JOptionPane.ERROR_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(frame, "Error: cannot save sgf", "Lizzie", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    @NotNull
    private static Game snapshotCurrentGame() {
        final int BOARD_SIZE = Lizzie.optionSetting.getBoardSize().getWidth();
        Game game = new Game();

        game.addProperty("FF", "4"); // SGF version: 4
        game.addProperty("KM", String.valueOf(gameStatusManager.getGameInfo().getKomi()));
        game.addProperty("GM", "1"); // Go game
        game.addProperty("SZ", String.valueOf(BOARD_SIZE));
        game.addProperty("CA", "UTF-8");
        game.addProperty("AP", "MyLizzie");

        BoardHistoryList historyList = board.getHistory();
        BoardHistoryNode initialNode = historyList.getInitialNode();

        GameNode previousSgfNode = null;
        BoardHistoryNode previousNode = null;
        for (BoardHistoryNode p = initialNode.getNext(); p != null; p = p.getNext()) {
            GameNode gameNode = new GameNode(previousSgfNode);

            // Move node
            if (Objects.equals(p.getData().getLastMoveColor(), Stone.BLACK) || Objects.equals(p.getData().getLastMoveColor(), Stone.WHITE)) {
                int x, y;

                if (p.getData().getLastMove() == null) {
                    // Pass
                    x = BOARD_SIZE;
                    y = BOARD_SIZE;
                } else {
                    x = p.getData().getLastMove()[0];
                    y = p.getData().getLastMove()[1];

                    if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE) {
                        x = BOARD_SIZE;
                        y = BOARD_SIZE;
                    }
                }

                String moveKey = Objects.equals(p.getData().getLastMoveColor(), Stone.BLACK) ? "B" : "W";
                String moveValue = Util.coordToAlpha.get(x) + Util.coordToAlpha.get(y);

                gameNode.addProperty(moveKey, moveValue);
                if (p.getData().getCalculationCount() > 100) {
                    gameNode.addProperty("C", String.format("Black: %.1f; White: %.1f", p.getData().getBlackWinrate(), p.getData().getWhiteWinrate()));
                }
            }

            if (p.getData().getMoveNumber() > 0) {
                gameNode.setMoveNo(p.getData().getMoveNumber());
            }

            if (previousSgfNode != null) {
                previousSgfNode.addChild(gameNode);
                // Ensure we have already added child. The previousNode is not null here.
                //addVariationTrees(previousSgfNode, previousNode.getData());
                addTryPlayTrees(previousSgfNode, previousNode);
            } else {
                game.setRootNode(gameNode);
            }

            previousSgfNode = gameNode;
            previousNode = p;
        }

        // Ignore the last node
        // addVariationTree(previousNode, previousData);

        return game;
    }

    private static void addTryPlayTrees(GameNode baseSgfNode, BoardHistoryNode baseNode) {
        if (CollectionUtils.isEmpty(baseNode.getTryPlayHistory())) {
            return;
        }

        for (BoardHistoryNode node : baseNode.getTryPlayHistory()) {
            addTryPlayTree(baseSgfNode, baseNode, node);
        }
    }

    private static void addTryPlayTree(GameNode baseSgfNode, BoardHistoryNode baseNode, BoardHistoryNode tryPlayBeginNode) {
        final int BOARD_SIZE = Lizzie.optionSetting.getBoardSize().getWidth();
        GameNode previousSgfNode = baseSgfNode;

        for (BoardHistoryNode p = tryPlayBeginNode; p != null; p = p.getNext()) {
            GameNode gameNode = new GameNode(previousSgfNode);

            // Move node
            if (Objects.equals(p.getData().getLastMoveColor(), Stone.BLACK) || Objects.equals(p.getData().getLastMoveColor(), Stone.WHITE)) {
                int x, y;

                if (p.getData().getLastMove() == null) {
                    // Pass
                    x = BOARD_SIZE;
                    y = BOARD_SIZE;
                } else {
                    x = p.getData().getLastMove()[0];
                    y = p.getData().getLastMove()[1];

                    if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE) {
                        x = BOARD_SIZE;
                        y = BOARD_SIZE;
                    }
                }

                String moveKey = Objects.equals(p.getData().getLastMoveColor(), Stone.BLACK) ? "B" : "W";
                String moveValue = Util.coordToAlpha.get(x) + Util.coordToAlpha.get(y);

                gameNode.addProperty(moveKey, moveValue);
                if (p.getData().getCalculationCount() > 100) {
                    gameNode.addProperty("C", String.format("Black: %.1f; White: %.1f", p.getData().getBlackWinrate(), p.getData().getWhiteWinrate()));
                }
            }

            if (p.getData().getMoveNumber() > 0) {
                gameNode.setMoveNo(p.getData().getMoveNumber());
            }

            if (previousSgfNode != null) {
                previousSgfNode.addChild(gameNode);
            }

            previousSgfNode = gameNode;
        }
    }

    private static void addVariationTrees(GameNode baseNode, BoardData data) {
        if (CollectionUtils.isEmpty(data.getVariationDataList())) {
            return;
        }

        int treeCount = 0;
        for (VariationData variationData : data.getVariationDataList()) {
            // We only add variation whose playouts is greater than 200
            if (variationData.getPlayouts() > 200) {
                addVariationTree(baseNode, variationData);

                ++treeCount;
                // We only care for 5 or less variations
                if (treeCount > 5) {
                    break;
                }
            }
        }
    }

    private static void addVariationTree(GameNode baseNode, VariationData variationData) {
        final int BOARD_SIZE = Lizzie.optionSetting.getBoardSize().getWidth();
        Stone baseColor = baseNode.isBlack() ? Stone.BLACK : Stone.WHITE;
        GameNode previousNode = baseNode;

        int variationMoveCount = 0;
        for (int[] variation : variationData.getVariation()) {
            GameNode gameNode = new GameNode(previousNode);

            int x, y;
            if (variation == null) {
                // Pass
                x = BOARD_SIZE;
                y = BOARD_SIZE;
            } else {
                x = variation[0];
                y = variation[1];

                if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE) {
                    x = BOARD_SIZE;
                    y = BOARD_SIZE;
                }
            }

            String moveKey = Objects.equals(baseColor.opposite(), Stone.BLACK) ? "B" : "W";
            String moveValue = Util.coordToAlpha.get(x) + Util.coordToAlpha.get(y);
            gameNode.addProperty(moveKey, moveValue);

            if (previousNode == baseNode && variationData.getPlayouts() > 100) {
                double blackWinrate, whiteWinrate;
                if (moveKey.equals("B")) {
                    blackWinrate = variationData.getWinrate();
                    whiteWinrate = 100 - blackWinrate;
                } else {
                    whiteWinrate = variationData.getWinrate();
                    blackWinrate = 100 - whiteWinrate;
                }

                gameNode.addProperty("C", String.format("Black: %.1f; White: %.1f", blackWinrate, whiteWinrate));
            }

            previousNode.addChild(gameNode);

            previousNode = gameNode;
            baseColor = baseColor.opposite();

            ++variationMoveCount;
            if (variationMoveCount >= Lizzie.optionSetting.getVariationLimit()) {
                break;
            }
        }
    }

    public static String writeSgfToString(Game game) {
        try (StringWriter writer = new StringWriter()) {
            writeSgfToStream(game, writer);
            return writer.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void writeSgfToFile(Game game, Path destination) {
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(destination.toFile()), Charset.forName("UTF-8"))) {
            writeSgfToStream(game, writer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeSgfToStream(Game game, Writer writer) throws IOException {
        writer.write('(');

        // lets write all the root node properties
        Map<String, String> props = game.getProperties();
        if (props.size() > 0) {
            writer.write(";");
        }

        for (Map.Entry<String, String> entry : props.entrySet()) {
            writer.write(entry.getKey());
            writer.write('[');
            writer.write(entry.getValue());
            writer.write(']');
        }

        // write sgf nodes
        GameNode node = game.getRootNode();
        writeSgfSubTree(writer, node, 0);
        writer.write(')');
    }

    private static void writeSgfSubTree(Writer writer, GameNode subtreeRoot, int level) throws IOException {
        if (level > 0) {
            writer.write('(');
        }

        // DF pre-order traversal
        // Write root
        writer.write(';');
        writeNodeProperties(writer, subtreeRoot);

        // Write children
        GameNode mainChild;
        if ((mainChild = subtreeRoot.getNextNode()) != null) {
            writeSgfSubTree(writer, mainChild, level + 1);

            for (GameNode otherChild : subtreeRoot.getChildren()) {
                writeSgfSubTree(writer, otherChild, level + 1);
            }
        }

        if (level > 0) {
            writer.write(')');
        }
    }

    private static void writeNodeProperties(Writer writer, GameNode node) throws IOException {
        for (Map.Entry<String, String> entry : node.getProperties().entrySet()) {
            writer.write(entry.getKey());
            writer.write('[');
            writer.write(entry.getValue());
            writer.write(']');
        }
    }

    private static void storeBoardByFile(Path filePath) {
        BufferedImage bufferedImage = Lizzie.frame.getCachedImage();
        SVGGraphics2D svgGraphics2D = new SVGGraphics2D(bufferedImage.getWidth(), bufferedImage.getHeight());
        try {
            svgGraphics2D.drawImage(bufferedImage, 0, 0, null);
            String fileContent = svgGraphics2D.getSVGDocument();
            try (FileWriter writer = new FileWriter(filePath.toFile())) {
                writer.write(fileContent);
            } catch (Exception e) {
                if (StringUtils.isEmpty(e.getMessage())) {
                    JOptionPane.showMessageDialog(frame, "Error: cannot save svg: " + e.getMessage(), "Lizzie", JOptionPane.ERROR_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(frame, "Error: cannot save svg", "Lizzie", JOptionPane.ERROR_MESSAGE);
                }
            }
        } finally {
            svgGraphics2D.dispose();
        }
    }

    private static void storeBoardPngImageByFile(Path filePath) {
        BufferedImage bufferedImage = Lizzie.frame.getCachedImage();
        try (FileOutputStream stream = new FileOutputStream(filePath.toFile())) {
            ImageIO.write(bufferedImage, "PNG", stream);
        } catch (Exception e) {
            if (StringUtils.isEmpty(e.getMessage())) {
                JOptionPane.showMessageDialog(frame, "Error: cannot save png: " + e.getMessage(), "Lizzie", JOptionPane.ERROR_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(frame, "Error: cannot save png", "Lizzie", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    public static void storeGameByPrompting() {
        FileNameExtensionFilter sgfFilter = new FileNameExtensionFilter("*.sgf", "SGF");
        FileNameExtensionFilter svgFilter = new FileNameExtensionFilter("*.svg", "SVG");
        FileNameExtensionFilter pngFilter = new FileNameExtensionFilter("*.png", "PNG");

        JFileChooser chooser = new JFileChooser(optionSetting.getLastChooserLocation());
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.addChoosableFileFilter(sgfFilter);
        chooser.addChoosableFileFilter(svgFilter);
        chooser.addChoosableFileFilter(pngFilter);
        chooser.setMultiSelectionEnabled(false);

        setFileChooserAutoFocusOnTextField(chooser);

        int result = chooser.showSaveDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            optionSetting.setLastChooserLocation(file.getParent());

            if (!file.getPath().toLowerCase().endsWith(".sgf") && !file.getPath().toLowerCase().endsWith(".svg") && !file.getPath().toLowerCase().endsWith(".png")) {
                if (chooser.getFileFilter().equals(sgfFilter)) {
                    file = new File(file.getPath() + ".sgf");
                } else if (chooser.getFileFilter().equals(svgFilter)) {
                    file = new File(file.getPath() + ".svg");
                } else {
                    file = new File(file.getPath() + ".png");
                }
            }

            if (file.exists()) {
                int ret = JOptionPane.showConfirmDialog(frame, "The target file is exists, do you want replace it?", "Warning", JOptionPane.OK_CANCEL_OPTION);
                if (ret == JOptionPane.CANCEL_OPTION) {
                    return;
                }
            }

            if (file.getPath().toLowerCase().endsWith(".sgf")) {
                storeGameByFile(file.toPath());
            } else if (file.getPath().toLowerCase().endsWith(".svg")) {
                storeBoardByFile(file.toPath());
            } else {
                storeBoardPngImageByFile(file.toPath());
            }
        }
    }

    public static void readGuiPosition() {
        optionSetting.setMainWindowState(frame);
        optionSetting.setAnalysisWindowState(analysisDialog);
        optionSetting.setWinrateHistogramWindowState(winrateHistogramDialog);
        optionSetting.setGtpConsoleWindowState(gtpConsole);
    }

    public static void setGuiPosition() {
        SwingUtilities.invokeLater(() -> {
            optionSetting.getMainWindowState().applyStateTo(frame);
            optionSetting.getAnalysisWindowState().applyStateTo(analysisDialog);
            optionSetting.getWinrateHistogramWindowState().applyStateTo(winrateHistogramDialog);
            optionSetting.getGtpConsoleWindowState().applyStateTo(gtpConsole);
        });
    }

    public static void readSettingFile() {
        try (Reader reader = new FileReader(SETTING_FILE)) {
            optionSetting = gson.fromJson(reader, OptionSetting.class);
        } catch (FileNotFoundException e) {
            // Do nothing
        } catch (Exception e) {
            logger.error("Error in reading setting file.", e);
        }
    }

    public static void writeSettingFile() {
        try (Writer writer = new FileWriter(SETTING_FILE)) {
            writer.write(gson.toJson(optionSetting));
        } catch (Exception e) {
            logger.error("Error in writing setting file.", e);
        }
    }

    public static String getLizzieVersion() {
        if (Manifests.exists("Lizzie-Version")) {
            return Manifests.read("Lizzie-Version");
        } else {
            return null;
        }
    }
}
