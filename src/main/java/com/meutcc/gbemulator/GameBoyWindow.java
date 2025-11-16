package com.meutcc.gbemulator;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

public class GameBoyWindow extends JFrame {

    private static final int SCREEN_WIDTH = 160;
    private static final int SCREEN_HEIGHT = 144;
    private static final int DEFAULT_SCALE = 3;
    private static final int MIN_SCALE = 1;
    private static final double ASPECT_RATIO = (double) SCREEN_WIDTH / SCREEN_HEIGHT;

    private final GameBoyScreenPanel screenPanel;
    private final GameBoy gameBoy;
    private Thread emulationThread;
    private volatile boolean running = false;
    private volatile boolean paused = false;
    private final InputHandler inputHandler;
    
    // Gamepad support
    private GamepadManager gamepadManager;
    private GamepadInputHandler gamepadInputHandler;

    private boolean globalSoundEnabled = true;

    public GameBoyWindow() {
        setTitle("GameBoy Emulator TCC");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setResizable(true);
        
        
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                if (running) {
                    running = false;
                    try {
                        if (emulationThread != null) {
                            emulationThread.join(500);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                
                gameBoy.getCartridge().saveBatteryRam();
                System.exit(0);
            }
        });

        gameBoy = new GameBoy();
        gameBoy.setEmulatorSoundGloballyEnabled(globalSoundEnabled);
        inputHandler = new InputHandler(gameBoy.getMmu());
        
        // Inicializa gamepad support
        initializeGamepadSupport();

        screenPanel = new GameBoyScreenPanel();
        add(screenPanel, BorderLayout.CENTER);

        JMenuBar menuBar = new JMenuBar();

        JMenu fileMenu = new JMenu("File");
        JMenuItem loadRomItem = new JMenuItem("Load ROM...");
        loadRomItem.addActionListener(e -> openRomChooser());
        fileMenu.add(loadRomItem);
        
        fileMenu.addSeparator();
        
        JMenuItem saveStateItem = new JMenuItem("Save State...");
        saveStateItem.addActionListener(e -> saveState());
        fileMenu.add(saveStateItem);
        
        JMenuItem loadStateItem = new JMenuItem("Load State...");
        loadStateItem.addActionListener(e -> loadState());
        fileMenu.add(loadStateItem);
        
        JMenu windowMenu = new JMenu("Janela");
        for (int scale = 1; scale <= 6; scale++) {
            final int currentScale = scale;
            JMenuItem scaleItem = new JMenuItem(scale + "x (" + (SCREEN_WIDTH * scale) + "x" + (SCREEN_HEIGHT * scale) + ")");
            scaleItem.addActionListener(e -> setWindowSize(currentScale));
            windowMenu.add(scaleItem);
        }
        menuBar.add(fileMenu);
        menuBar.add(windowMenu);

        JMenu controlMenu = new JMenu("Controle");
        JMenuItem showControlsItem = new JMenuItem("Exibir Controles do Teclado");
        showControlsItem.addActionListener(e -> showControlMapping());
        controlMenu.add(showControlsItem);
        
        controlMenu.addSeparator();
        
        JMenuItem configGamepadItem = new JMenuItem("Configurar Gamepad...");
        configGamepadItem.addActionListener(e -> showGamepadConfig());
        controlMenu.add(configGamepadItem);
        
        JCheckBoxMenuItem enableGamepadItem = new JCheckBoxMenuItem("Habilitar Gamepad", false);
        enableGamepadItem.addActionListener(e -> {
            boolean enabled = enableGamepadItem.isSelected();
            setGamepadEnabled(enabled);
        });
        controlMenu.add(enableGamepadItem);
        
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
        
        // Menu "Link Cable" (Multiplayer/Periféricos)
        JMenu linkMenu = new JMenu("Link Cable");
        
        JMenu multiplayerMenu = new JMenu("Multiplayer");
        JMenuItem hostGameItem = new JMenuItem("Host Game (Servidor)...");
        hostGameItem.addActionListener(e -> hostMultiplayerGame());
        multiplayerMenu.add(hostGameItem);
        
        JMenuItem joinGameItem = new JMenuItem("Join Game (Cliente)...");
        joinGameItem.addActionListener(e -> joinMultiplayerGame());
        multiplayerMenu.add(joinGameItem);
        
        JMenuItem disconnectItem = new JMenuItem("Disconnect");
        disconnectItem.addActionListener(e -> disconnectLink());
        multiplayerMenu.add(disconnectItem);
        
        linkMenu.add(multiplayerMenu);
        linkMenu.addSeparator();
        
        JMenuItem printerItem = new JMenuItem("Connect Game Boy Printer");
        printerItem.addActionListener(e -> connectPrinter());
        linkMenu.add(printerItem);
        
        JMenuItem cameraItem = new JMenuItem("Connect Game Boy Camera");
        cameraItem.addActionListener(e -> connectCamera());
        linkMenu.add(cameraItem);
        
        linkMenu.addSeparator();
        
        JMenuItem disconnectDeviceItem = new JMenuItem("Disconnect Device");
        disconnectDeviceItem.addActionListener(e -> disconnectDevice());
        linkMenu.add(disconnectDeviceItem);
        
        menuBar.add(linkMenu);
        setJMenuBar(menuBar);
        
        addMenuPauseListeners(fileMenu);
        addMenuPauseListeners(windowMenu);
        addMenuPauseListeners(controlMenu);
        addMenuPauseListeners(soundMenu);
        addMenuPauseListeners(linkMenu);
        
        addKeyListener(inputHandler);
        setFocusable(true);
        pack();
        setLocationRelativeTo(null);
    }
    
    private void addMenuPauseListeners(JMenu menu) {
        menu.addMenuListener(new javax.swing.event.MenuListener() {
            @Override
            public void menuSelected(javax.swing.event.MenuEvent e) {
                pauseEmulation();
            }

            @Override
            public void menuDeselected(javax.swing.event.MenuEvent e) {
                resumeEmulation();
            }

            @Override
            public void menuCanceled(javax.swing.event.MenuEvent e) {
                resumeEmulation();
            }
        });
    }
    
    private void pauseEmulation() {
        if (running && !paused) {
            paused = true;
            System.out.println("Emulação pausada (menu aberto)");
        }
    }
    
    private void resumeEmulation() {
        if (running && paused) {
            paused = false;
            System.out.println("Emulação resumida");
        }
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

    private void setWindowSize(int scale) {
        screenPanel.setPreferredSize(new Dimension(SCREEN_WIDTH * scale, SCREEN_HEIGHT * scale));
        pack();
        setLocationRelativeTo(null);
    }

    private void saveState() {
        JFileChooser fileChooser = new JFileChooser(".");
        fileChooser.setDialogTitle("Save State");
        fileChooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().toLowerCase().endsWith(".sav");
            }
            @Override
            public String getDescription() {
                return "Save State Files (*.sav)";
            }
        });

        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            String filePath = selectedFile.getAbsolutePath();
            if (!filePath.toLowerCase().endsWith(".sav")) {
                filePath += ".sav";
            }
            
            try {
                gameBoy.saveState(filePath);
                JOptionPane.showMessageDialog(this, "State saved successfully!", 
                    "Save State", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Failed to save state: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
                ex.printStackTrace();
            }
        }
    }

    private void loadState() {
        JFileChooser fileChooser = new JFileChooser(".");
        fileChooser.setDialogTitle("Load State");
        fileChooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().toLowerCase().endsWith(".sav");
            }
            @Override
            public String getDescription() {
                return "Save State Files (*.sav)";
            }
        });

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            try {
                gameBoy.loadState(selectedFile.getAbsolutePath());
                JOptionPane.showMessageDialog(this, "State loaded successfully!", 
                    "Load State", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Failed to load state: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
                ex.printStackTrace();
            }
        }
    }


    public void loadROM(String romPath) {
        if (emulationThread != null && emulationThread.isAlive()) {
            running = false;
            try {
                emulationThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Thread de emulação interrompida durante troca de ROM.");
            }
        }

       
        gameBoy.getCartridge().saveBatteryRam();

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
        if (emulationThread != null && emulationThread.isAlive()) {
            running = false;
            try {
                emulationThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (gameBoy != null) {
            gameBoy.setEmulatorSoundGloballyEnabled(globalSoundEnabled);
        }

        running = true;
        emulationThread = new Thread(this::emulationLoop);
        emulationThread.setName("EmulationThread");
        emulationThread.start();
    }

    private void emulationLoop() {
        final int CYCLES_PER_FRAME = 70224;
        final long NANOSECONDS_PER_FRAME = 1_000_000_000L / 60;
        
        gameBoy.reset();
        gameBoy.setEmulatorSoundGloballyEnabled(globalSoundEnabled);

        long lastTime = System.nanoTime();
        long accumulatedTime = 0;
        int actualFramesRendered = 0;
        int loopIterations = 0;
        
        long fpsTimer = System.currentTimeMillis();

        while (running) {
            loopIterations++;
            long currentTime = System.nanoTime();
            long frameTime = currentTime - lastTime;
            lastTime = currentTime;
            
            if (frameTime > NANOSECONDS_PER_FRAME * 2) {
                frameTime = NANOSECONDS_PER_FRAME;
            }
            
            accumulatedTime += frameTime;

            int framesProcessedThisIteration = 0;
            while (accumulatedTime >= NANOSECONDS_PER_FRAME && !paused && framesProcessedThisIteration < 3) {
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
                framesProcessedThisIteration++;
            }
            
            if (framesProcessedThisIteration >= 3) {
                accumulatedTime = 0;
            }

            if (paused) {
                accumulatedTime = 0;
                lastTime = System.nanoTime();
            }

            if (!running) break;

            if (gameBoy.getPpu().isFrameCompleted()) {
                screenPanel.updateScreen(gameBoy.getPpu().getScreenBuffer());
                actualFramesRendered++;
            }

            long nextFrameTime = lastTime + NANOSECONDS_PER_FRAME - accumulatedTime;
            long sleepTime = nextFrameTime - System.nanoTime();
            
            if (sleepTime > 1_000_000) {
                try {
                    long sleepMs = sleepTime / 1_000_000;
                    int sleepNs = (int) (sleepTime % 1_000_000);
                    Thread.sleep(sleepMs, sleepNs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            } else if (sleepTime < -NANOSECONDS_PER_FRAME) {
                accumulatedTime = 0;
            }

            if (System.currentTimeMillis() - fpsTimer > 1000) {
                System.out.println("FPS: " + actualFramesRendered + " | Loops/sec: " + loopIterations);
                actualFramesRendered = 0;
                loopIterations = 0;
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
                    emulationThread.interrupt();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while waiting for emulation thread to dispose.");
            }
        }

        if (gameBoy != null && gameBoy.getApu() != null) {
            System.out.println("Closing APU resources...");
            gameBoy.getApu().close();
        } else {
            System.out.println("APU or GameBoy instance was null during dispose, skipping APU close.");
        }
        
        // Shutdown gamepad
        if (gamepadManager != null) {
            System.out.println("Shutting down gamepad manager...");
            gamepadManager.shutdown();
        }

        super.dispose();
        System.out.println("GameBoyWindow disposed.");
    }
    
    // ==================== Gamepad Support ====================
    
    /**
     * Inicializa o suporte a gamepads
     */
    private void initializeGamepadSupport() {
        try {
            gamepadManager = new GamepadManager();
            gamepadInputHandler = new GamepadInputHandler(gameBoy.getMmu());
            
            // Por padrão, desabilitado até o usuário configurar
            gamepadInputHandler.setEnabled(false);
            
            System.out.println("Suporte a gamepad inicializado. Use o menu Controle para configurar.");
        } catch (NoClassDefFoundError e) {
            System.err.println("Biblioteca JInput não encontrada. Suporte a gamepad desabilitado.");
            System.err.println("Para usar gamepads, instale JInput 2.0.9 ou superior.");
            gamepadManager = null;
            gamepadInputHandler = null;
        } catch (Exception e) {
            System.err.println("Erro ao inicializar gamepad: " + e.getMessage());
            gamepadManager = null;
            gamepadInputHandler = null;
        }
    }
    
    /**
     * Abre o diálogo de configuração de gamepad
     */
    private void showGamepadConfig() {
        if (gamepadManager == null) {
            JOptionPane.showMessageDialog(this,
                "Suporte a gamepad não está disponível.\n\n" +
                "Para usar gamepads, você precisa instalar a biblioteca JInput:\n" +
                "1. Baixe jinput-2.0.9.jar e jinput-platform-2.0.9-natives-all.jar\n" +
                "2. Adicione os JARs ao classpath do projeto\n" +
                "3. Reinicie o emulador\n\n" +
                "Veja o README.md para instruções detalhadas.",
                "JInput não encontrado",
                JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        GamepadConfigDialog dialog = new GamepadConfigDialog(this, gamepadManager);
        dialog.setVisible(true);
    }
    
    /**
     * Habilita ou desabilita o input do gamepad
     */
    private void setGamepadEnabled(boolean enabled) {
        if (gamepadManager == null || gamepadInputHandler == null) {
            JOptionPane.showMessageDialog(this,
                "Suporte a gamepad não está disponível.\n" +
                "Instale a biblioteca JInput para usar gamepads.",
                "Gamepad não disponível",
                JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        if (enabled) {
            // Verifica se há gamepad conectado
            if (!gamepadManager.hasActiveGamepad()) {
                gamepadManager.detectGamepads();
            }
            
            if (!gamepadManager.hasActiveGamepad()) {
                JOptionPane.showMessageDialog(this,
                    "Nenhum gamepad detectado.\n" +
                    "Conecte um gamepad e tente novamente.",
                    "Gamepad não encontrado",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            
            // Inicia polling do gamepad
            gamepadInputHandler.setEnabled(true);
            gamepadManager.startPolling(gamepadInputHandler);
            
            System.out.println("Gamepad habilitado: " + gamepadManager.getActiveGamepad().getName());
            JOptionPane.showMessageDialog(this,
                "Gamepad habilitado!\n\n" +
                "Gamepad ativo: " + gamepadManager.getActiveGamepad().getName() + "\n\n" +
                "Use 'Configurar Gamepad' no menu para personalizar os botões.",
                "Gamepad Ativado",
                JOptionPane.INFORMATION_MESSAGE);
        } else {
            // Desabilita gamepad
            gamepadManager.stopPolling();
            gamepadInputHandler.setEnabled(false);
            gamepadInputHandler.releaseAllButtons();
            
            System.out.println("Gamepad desabilitado.");
        }
    }
    
    // ==================== Link Cable / Multiplayer / Periféricos ====================
    
    private NetworkLinkCable networkLink = null;
    private PrinterEmulator printer = null;
    private CameraEmulator camera = null;
    
    private void hostMultiplayerGame() {
        String portStr = JOptionPane.showInputDialog(this, 
            "Digite a porta para hospedar (padrão: 5555):", "5555");
        
        if (portStr == null || portStr.trim().isEmpty()) {
            return;
        }
        
        try {
            int port = Integer.parseInt(portStr.trim());
            
            networkLink = new NetworkLinkCable();
            networkLink.setConnectionListener(new NetworkLinkCable.ConnectionListener() {
                @Override
                public void onConnected(String remoteAddress) {
                    JOptionPane.showMessageDialog(GameBoyWindow.this,
                        "Conectado: " + remoteAddress,
                        "Link Cable", JOptionPane.INFORMATION_MESSAGE);
                }
                
                @Override
                public void onDisconnected(String reason) {
                    JOptionPane.showMessageDialog(GameBoyWindow.this,
                        "Desconectado: " + reason,
                        "Link Cable", JOptionPane.WARNING_MESSAGE);
                }
                
                @Override
                public void onError(String error) {
                    JOptionPane.showMessageDialog(GameBoyWindow.this,
                        "Erro: " + error,
                        "Link Cable", JOptionPane.ERROR_MESSAGE);
                }
            });
            
            networkLink.startServer(port);
            gameBoy.getMmu().getSerial().connectDevice(networkLink);
            
            JOptionPane.showMessageDialog(this,
                "Aguardando conexão na porta " + port + "...\n" +
                "Outro jogador deve usar 'Join Game' com seu IP.",
                "Multiplayer - Host", JOptionPane.INFORMATION_MESSAGE);
                
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this,
                "Porta inválida!",
                "Erro", JOptionPane.ERROR_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                "Erro ao iniciar servidor: " + e.getMessage(),
                "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void joinMultiplayerGame() {
        String host = JOptionPane.showInputDialog(this,
            "Digite o IP do host:", "localhost");
        
        if (host == null || host.trim().isEmpty()) {
            return;
        }
        
        String portStr = JOptionPane.showInputDialog(this,
            "Digite a porta (padrão: 5555):", "5555");
        
        if (portStr == null || portStr.trim().isEmpty()) {
            return;
        }
        
        try {
            int port = Integer.parseInt(portStr.trim());
            
            networkLink = new NetworkLinkCable();
            networkLink.setConnectionListener(new NetworkLinkCable.ConnectionListener() {
                @Override
                public void onConnected(String remoteAddress) {
                    JOptionPane.showMessageDialog(GameBoyWindow.this,
                        "Conectado a: " + remoteAddress,
                        "Link Cable", JOptionPane.INFORMATION_MESSAGE);
                }
                
                @Override
                public void onDisconnected(String reason) {
                    JOptionPane.showMessageDialog(GameBoyWindow.this,
                        "Desconectado: " + reason,
                        "Link Cable", JOptionPane.WARNING_MESSAGE);
                }
                
                @Override
                public void onError(String error) {
                    JOptionPane.showMessageDialog(GameBoyWindow.this,
                        "Erro: " + error,
                        "Link Cable", JOptionPane.ERROR_MESSAGE);
                }
            });
            
            networkLink.connectToServer(host.trim(), port);
            gameBoy.getMmu().getSerial().connectDevice(networkLink);
            
            JOptionPane.showMessageDialog(this,
                "Conectando a " + host + ":" + port + "...",
                "Multiplayer - Join", JOptionPane.INFORMATION_MESSAGE);
                
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this,
                "Porta inválida!",
                "Erro", JOptionPane.ERROR_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                "Erro ao conectar: " + e.getMessage(),
                "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void disconnectLink() {
        if (networkLink != null) {
            networkLink.disconnect();
            gameBoy.getMmu().getSerial().disconnectDevice();
            networkLink = null;
            
            JOptionPane.showMessageDialog(this,
                "Link Cable desconectado.",
                "Link Cable", JOptionPane.INFORMATION_MESSAGE);
        } else {
            JOptionPane.showMessageDialog(this,
                "Não há conexão ativa.",
                "Link Cable", JOptionPane.WARNING_MESSAGE);
        }
    }
    
    private void connectPrinter() {
        if (printer == null) {
            printer = new PrinterEmulator();
        }
        
        String dir = JOptionPane.showInputDialog(this,
            "Diretório para salvar imagens (padrão: printer_output):",
            "printer_output");
        
        if (dir != null && !dir.trim().isEmpty()) {
            printer.setOutputDirectory(dir.trim());
        }
        
        gameBoy.getMmu().getSerial().connectDevice(printer);
        
        JOptionPane.showMessageDialog(this,
            "Game Boy Printer conectado!\n" +
            "Imagens serão salvas em: " + dir,
            "Printer", JOptionPane.INFORMATION_MESSAGE);
    }
    
    private void connectCamera() {
        if (camera == null) {
            camera = new CameraEmulator();
        }
        
        String imagePath = JOptionPane.showInputDialog(this,
            "Caminho da imagem para simular captura (opcional):");
        
        if (imagePath != null && !imagePath.trim().isEmpty()) {
            camera.loadImage(imagePath.trim());
        }
        
        gameBoy.getMmu().getSerial().connectDevice(camera);
        
        JOptionPane.showMessageDialog(this,
            "Game Boy Camera conectado!\n" +
            "Nota: Esta é uma simulação básica.\n" +
            "Captura real de webcam requer bibliotecas externas.",
            "Camera", JOptionPane.INFORMATION_MESSAGE);
    }
    
    private void disconnectDevice() {
        gameBoy.getMmu().getSerial().disconnectDevice();
        
        if (networkLink != null) {
            networkLink.disconnect();
            networkLink = null;
        }
        
        printer = null;
        camera = null;
        
        JOptionPane.showMessageDialog(this,
            "Dispositivo desconectado.",
            "Link Cable", JOptionPane.INFORMATION_MESSAGE);
    }

    private class GameBoyScreenPanel extends JPanel {
        private BufferedImage screenBuffer;

        public GameBoyScreenPanel() {
            setPreferredSize(new Dimension(SCREEN_WIDTH * DEFAULT_SCALE, SCREEN_HEIGHT * DEFAULT_SCALE));
            setMinimumSize(new Dimension(SCREEN_WIDTH * MIN_SCALE, SCREEN_HEIGHT * MIN_SCALE));
            setBackground(Color.BLACK);
            
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
            
            if (screenBuffer == null) return;
            
            Graphics2D g2d = (Graphics2D) g;
            
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
            
            int panelWidth = getWidth();
            int panelHeight = getHeight();
            
            int scaledWidth, scaledHeight;
            
            scaledWidth = panelWidth;
            scaledHeight = (int) (panelWidth / ASPECT_RATIO);
            
            if (scaledHeight > panelHeight) {
                scaledHeight = panelHeight;
                scaledWidth = (int) (panelHeight * ASPECT_RATIO);
            }
            
            int scale = Math.max(1, Math.min(scaledWidth / SCREEN_WIDTH, scaledHeight / SCREEN_HEIGHT));
            scaledWidth = SCREEN_WIDTH * scale;
            scaledHeight = SCREEN_HEIGHT * scale;
            
            int x = (panelWidth - scaledWidth) / 2;
            int y = (panelHeight - scaledHeight) / 2;
            
            g2d.drawImage(screenBuffer, x, y, scaledWidth, scaledHeight, null);
        }
    }
}