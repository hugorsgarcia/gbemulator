package com.meutcc.gbemulator;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.File;

public class GameBoyWindow extends JFrame {

    private static final int SCREEN_WIDTH = 160;
    private static final int SCREEN_HEIGHT = 144;
    private static final int SCALE = 3; // Escala da tela

    private final GameBoyScreenPanel screenPanel;
    private final GameBoy gameBoy;
    private Thread emulationThread;
    private volatile boolean running = false;
    private final InputHandler inputHandler;

    public GameBoyWindow() {
        setTitle("GameBoy Emulator TCC");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);

        screenPanel = new GameBoyScreenPanel();
        add(screenPanel, BorderLayout.CENTER);

        // Menu
        JMenuBar menuBar = new JMenuBar();

        // Menu "File"
        JMenu fileMenu = new JMenu("File");
        JMenuItem loadRomItem = new JMenuItem("Load ROM...");
        loadRomItem.addActionListener(e -> openRomChooser());
        fileMenu.add(loadRomItem);
        menuBar.add(fileMenu);

        // Menu "Controle"
        JMenu controlMenu = new JMenu("Controle");
        JMenuItem showControlsItem = new JMenuItem("Exibir Controles do Teclado");
        showControlsItem.addActionListener(e -> showControlMapping());
        controlMenu.add(showControlsItem);
        menuBar.add(controlMenu);

        setJMenuBar(menuBar);

        pack();
        setLocationRelativeTo(null); // Centralizar

        gameBoy = new GameBoy();
        inputHandler = new InputHandler(gameBoy.getMmu());
        addKeyListener(inputHandler); // Adiciona o listener de teclado ao JFrame
        setFocusable(true); // Garante que o JFrame pode receber foco para eventos de teclado
    }

    private void openRomChooser() {
        JFileChooser fileChooser = new JFileChooser(".");
        fileChooser.setDialogTitle("Select Game Boy ROM");
        fileChooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().toLowerCase().endsWith(".gb");
            }
            @Override
            public String getDescription() {
                return "Game Boy ROMs (*.gb)";
            }
        });

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            loadROM(selectedFile.getAbsolutePath());
        }
    }

    private void showControlMapping() {
        String mapping = "Mapeamento dos Controles do Game Boy no Teclado:\n\n" +
                "Seta Direita: Direita\n" +
                "Seta Esquerda: Esquerda\n" +
                "Seta Cima: Cima\n" +
                "Seta Baixo: Baixo\n" +
                "Tecla Z: Botão A\n" +
                "Tecla X: Botão B\n" +
                "Enter: Start\n" +
                "Shift: Select\n";
        JOptionPane.showMessageDialog(this, mapping, "Controles do Teclado", JOptionPane.INFORMATION_MESSAGE);
    }


    public void loadROM(String romPath) {
        if (gameBoy.loadROM(romPath)) {
            System.out.println("ROM loaded: " + romPath);
            startEmulation();
        } else {
            JOptionPane.showMessageDialog(this, "Failed to load ROM: " + romPath,
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void startEmulation() {
        if (emulationThread != null && emulationThread.isAlive()) {
            running = false;
            try {
                emulationThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        running = true;
        emulationThread = new Thread(this::emulationLoop);
        emulationThread.start();
    }

    private void emulationLoop() {
        // Game Boy clock speed is ~4.19 MHz (4194304 cycles per second)
        // Target FPS is ~59.7, so cycles per frame is ~70224
        final int CYCLES_PER_FRAME = 70224;
        final long NANOSECONDS_PER_FRAME = 1_000_000_000L / 60; // Target ~60 FPS

        long lastTime = System.nanoTime();
        gameBoy.reset();

        while (running) {
            int cyclesThisFrame = 0;
            while (cyclesThisFrame < CYCLES_PER_FRAME) {
                int cycles = gameBoy.step();
                if (cycles == -1) { // CPU Halted or error
                    running = false;
                    break;
                }
                cyclesThisFrame += cycles;
            }

            // Atualiza a tela
            screenPanel.updateScreen(gameBoy.getPpu().getScreenBuffer());

            // Sincronização de FPS
            long currentTime = System.nanoTime();
            long elapsedTime = currentTime - lastTime;
            long sleepTime = NANOSECONDS_PER_FRAME - elapsedTime;

            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime / 1_000_000, (int) (sleepTime % 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
            lastTime = System.nanoTime();
            if (!this.hasFocus() && this.isFocusOwner()) { // Tenta recuperar o foco se perdido
                this.requestFocusInWindow();
            }
        }
        System.out.println("Emulation stopped.");
    }

    @Override
    public void dispose() {
        running = false;
        if (emulationThread != null) {
            try {
                emulationThread.join(1000); // Aguarda a thread de emulação terminar
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        super.dispose();
    }

    private class GameBoyScreenPanel extends JPanel {
        private BufferedImage screenBuffer;

        public GameBoyScreenPanel() {
            setPreferredSize(new Dimension(SCREEN_WIDTH * SCALE, SCREEN_HEIGHT * SCALE));
            this.screenBuffer = new BufferedImage(SCREEN_WIDTH, SCREEN_HEIGHT, BufferedImage.TYPE_INT_RGB);
            // Inicializa com uma cor para ver que está funcionando
            Graphics2D g2d = this.screenBuffer.createGraphics();
            g2d.setColor(Color.DARK_GRAY);
            g2d.fillRect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
            g2d.dispose();
        }

        public void updateScreen(int[] pixelData) {
            if (pixelData != null && pixelData.length == SCREEN_WIDTH * SCREEN_HEIGHT) {
                screenBuffer.setRGB(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT, pixelData, 0, SCREEN_WIDTH);
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (screenBuffer != null) {
                g.drawImage(screenBuffer, 0, 0, getWidth(), getHeight(), null);
            }
        }
    }
}