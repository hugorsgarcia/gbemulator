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
        final long NANOSECONDS_PER_FRAME = 1_000_000_000L / 60; // ~16.67ms por frame
        
        gameBoy.reset();
        gameBoy.setEmulatorSoundGloballyEnabled(globalSoundEnabled);

        long lastTime = System.nanoTime();
        long accumulatedTime = 0;
        int frameCount = 0;
        
        // Para estatísticas de desempenho (opcional, pode comentar depois)
        long fpsTimer = System.currentTimeMillis();

        while (running) {
            long currentTime = System.nanoTime();
            long frameTime = currentTime - lastTime;
            lastTime = currentTime;
            
            // Limita o frameTime para evitar saltos grandes após pausas
            if (frameTime > NANOSECONDS_PER_FRAME * 2) {
                frameTime = NANOSECONDS_PER_FRAME;
            }
            
            accumulatedTime += frameTime;

            // Processa frames acumulados (geralmente apenas 1)
            while (accumulatedTime >= NANOSECONDS_PER_FRAME) {
                // Executa um frame completo do Game Boy
                int cyclesThisFrame = 0;
                while (cyclesThisFrame < CYCLES_PER_FRAME) {
                    int cycles = gameBoy.step();
                    if (cycles == -1) {
                        running = false;
                        System.err.println("CPU Halted or fatal error in emulation step.");
                        break;
                    }
                    cyclesThisFrame += cycles;
                }

                if (!running) break;
                
                accumulatedTime -= NANOSECONDS_PER_FRAME;
                frameCount++;
            }

            if (!running) break;

            // Atualiza a tela
            screenPanel.updateScreen(gameBoy.getPpu().getScreenBuffer());

            // Sleep adaptativo para manter 60 FPS
            long nextFrameTime = lastTime + NANOSECONDS_PER_FRAME - accumulatedTime;
            long sleepTime = nextFrameTime - System.nanoTime();
            
            if (sleepTime > 1_000_000) { // Só dorme se tiver mais de 1ms
                try {
                    long sleepMs = sleepTime / 1_000_000;
                    int sleepNs = (int) (sleepTime % 1_000_000);
                    Thread.sleep(sleepMs, sleepNs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            } else if (sleepTime < -NANOSECONDS_PER_FRAME) {
                // Emulação está muito atrasada, reseta o acumulador
                accumulatedTime = 0;
            }

            // Estatísticas de FPS (opcional - mostra a cada 1 segundo)
            if (System.currentTimeMillis() - fpsTimer > 1000) {
                System.out.println("FPS: " + frameCount);
                frameCount = 0;
                fpsTimer = System.currentTimeMillis();
            }

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