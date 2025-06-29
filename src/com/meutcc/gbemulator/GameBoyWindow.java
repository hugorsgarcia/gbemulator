package com.meutcc.gbemulator;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

public class GameBoyWindow extends JFrame {

    private static final int SCREEN_WIDTH = 160;
    private static final int SCREEN_HEIGHT = 144;
    private static final int SCALE = 3;

    private final GameBoyScreenPanel screenPanel;
    private final GameBoy gameBoy;
    private Thread emulationThread;
    private volatile boolean running = false;
    private final InputHandler inputHandler;

    // Flag para controlar o estado global do som do emulador
    private boolean globalSoundEnabled = true;

    public GameBoyWindow() {
        setTitle("GameBoy Emulator TCC");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);


        gameBoy = new GameBoy();
        gameBoy.setEmulatorSoundGloballyEnabled(globalSoundEnabled);
        inputHandler = new InputHandler(gameBoy.getMmu());


        screenPanel = new GameBoyScreenPanel();
        add(screenPanel, BorderLayout.CENTER);

        JMenuBar menuBar = new JMenuBar();

        JMenu fileMenu = new JMenu("File");
        JMenuItem loadRomItem = new JMenuItem("Load ROM...");
        loadRomItem.addActionListener(e -> openRomChooser());
        fileMenu.add(loadRomItem);
        menuBar.add(fileMenu);

        JMenu controlMenu = new JMenu("Controle");
        JMenuItem showControlsItem = new JMenuItem("Exibir Controles do Teclado");
        showControlsItem.addActionListener(e -> showControlMapping());
        controlMenu.add(showControlsItem);
        menuBar.add(controlMenu);

        // Menu "Som"
        JMenu soundMenu = new JMenu("Som");
        JCheckBoxMenuItem toggleSoundItem = new JCheckBoxMenuItem("Habilitar Som", globalSoundEnabled);
        toggleSoundItem.addActionListener(e -> {
            globalSoundEnabled = toggleSoundItem.isSelected();
            gameBoy.setEmulatorSoundGloballyEnabled(globalSoundEnabled);
            System.out.println("Emulação de Som: " + (globalSoundEnabled ? "Habilitada" : "Desabilitada"));
        });

        soundMenu.add(toggleSoundItem);
        menuBar.add(soundMenu);
        setJMenuBar(menuBar);
        addKeyListener(inputHandler);
        setFocusable(true);
        pack();
        setLocationRelativeTo(null); // Centralizar
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
        // Interrompe a emulação antiga antes de carregar uma nova ROM
        if (emulationThread != null && emulationThread.isAlive()) {
            running = false;
            try {
                emulationThread.join(500); // Espera um pouco para a thread terminar
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Thread de emulação interrompida durante troca de ROM.");
            }
        }

        if (gameBoy.loadROM(romPath)) {
            System.out.println("ROM loaded: " + romPath);
            gameBoy.setEmulatorSoundGloballyEnabled(globalSoundEnabled);
            startEmulation();
        } else {
            JOptionPane.showMessageDialog(this, "Failed to load ROM: " + romPath,
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void startEmulation() {
        // Certifique-se de que qualquer emulação anterior parou completamente
        if (emulationThread != null && emulationThread.isAlive()) {
            running = false;
            try {
                emulationThread.join(500); // Espera pela thread anterior
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }


        if (gameBoy != null) {
            gameBoy.setEmulatorSoundGloballyEnabled(globalSoundEnabled);
        }

        running = true;
        emulationThread = new Thread(this::emulationLoop);
        emulationThread.setName("EmulationThread"); // Bom para depuração
        emulationThread.start();
    }

    private void emulationLoop() {
        final int CYCLES_PER_FRAME = 70224;
        final long NANOSECONDS_PER_FRAME = 1_000_000_000L / 60;

        long lastTime = System.nanoTime();
        gameBoy.reset();
        // reconfirma o estado do som, o reset pode alterar flags na APU ou em outros componentes que afetam a APU.
        gameBoy.setEmulatorSoundGloballyEnabled(globalSoundEnabled);


        while (running) {
            int cyclesThisFrame = 0;
            while (cyclesThisFrame < CYCLES_PER_FRAME) {
                int cycles = gameBoy.step();
                if (cycles == -1) { // CPU Halted indefinidamente ou erro fatal
                    running = false;
                    System.err.println("CPU Halted or fatal error in emulation step.");
                    break;
                }
                cyclesThisFrame += cycles;
            }

            if (!running) break; // Sai do loop principal se a emulação foi parada

            screenPanel.updateScreen(gameBoy.getPpu().getScreenBuffer());

            long currentTime = System.nanoTime();
            long elapsedTime = currentTime - lastTime;
            long sleepTime = NANOSECONDS_PER_FRAME - elapsedTime;

            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime / 1_000_000, (int) (sleepTime % 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false; //sai loop se a thread for interrompida
                }
            }
            lastTime = System.nanoTime();

            if (!this.hasFocus() && this.isFocusOwner()) {
                 this.requestFocusInWindow();
            }
        }
        System.out.println("Emulation loop stopped.");
    }

    @Override
    public void dispose() {
        System.out.println("GameBoyWindow dispose() called.");
        running = false;
        if (emulationThread != null) {
            try {
                System.out.println("Waiting for emulation thread to join...");
                emulationThread.join(1000);
                if (emulationThread.isAlive()){
                    System.out.println("Emulation thread did not stop in time, interrupting.");
                    emulationThread.interrupt(); // Tenta interromper se não parou
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while waiting for emulation thread to dispose.");
            }
        }

        // Chama o close da APU através do GameBoy
        if (gameBoy != null && gameBoy.getApu() != null) {
            System.out.println("Closing APU resources...");
            gameBoy.getApu().close();
        } else {
            System.out.println("APU or GameBoy instance was null during dispose, skipping APU close.");
        }

        super.dispose();
        System.out.println("GameBoyWindow disposed.");
    }

    private class GameBoyScreenPanel extends JPanel {
        private BufferedImage screenBuffer;

        public GameBoyScreenPanel() {
            setPreferredSize(new Dimension(SCREEN_WIDTH * SCALE, SCREEN_HEIGHT * SCALE));
            this.screenBuffer = new BufferedImage(SCREEN_WIDTH, SCREEN_HEIGHT, BufferedImage.TYPE_INT_RGB);
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