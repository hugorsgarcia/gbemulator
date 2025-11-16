package com.meutcc.gbemulator;

import net.java.games.input.Component;
import net.java.games.input.Controller;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GamepadConfigDialog - Interface gráfica para configurar gamepads
 * 
 * Permite:
 * - Ver gamepads detectados
 * - Selecionar qual gamepad usar
 * - Configurar mapeamento de botões
 * - Testar inputs em tempo real
 */
public class GamepadConfigDialog extends JDialog {
    
    private final GamepadManager gamepadManager;
    private final JComboBox<String> gamepadComboBox;
    private final JPanel mappingPanel;
    private final JPanel testPanel;
    
    private final Map<MMU.Button, JButton> mappingButtons;
    private final Map<MMU.Button, JLabel> testLabels;
    
    private MMU.Button buttonBeingMapped = null;
    private Timer testTimer;
    
    private static final String[] BUTTON_NAMES = {
        "Cima", "Baixo", "Esquerda", "Direita",
        "Botão A", "Botão B", "Start", "Select"
    };
    
    private static final MMU.Button[] BUTTONS = {
        MMU.Button.GAMEBOY_UP, MMU.Button.GAMEBOY_DOWN,
        MMU.Button.GAMEBOY_LEFT, MMU.Button.GAMEBOY_RIGHT,
        MMU.Button.GAMEBOY_A, MMU.Button.GAMEBOY_B,
        MMU.Button.GAMEBOY_START, MMU.Button.GAMEBOY_SELECT
    };
    
    public GamepadConfigDialog(Frame parent, GamepadManager gamepadManager) {
        super(parent, "Configuração de Gamepad", true);
        this.gamepadManager = gamepadManager;
        this.mappingButtons = new HashMap<>();
        this.testLabels = new HashMap<>();
        
        setLayout(new BorderLayout(10, 10));
        setSize(700, 600);
        setLocationRelativeTo(parent);
        
        // Painel superior - Seleção de gamepad
        JPanel topPanel = createGamepadSelectionPanel();
        add(topPanel, BorderLayout.NORTH);
        
        // Painel central - Dividido entre mapeamento e teste
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        
        mappingPanel = createMappingPanel();
        testPanel = createTestPanel();
        
        splitPane.setTopComponent(mappingPanel);
        splitPane.setBottomComponent(testPanel);
        splitPane.setDividerLocation(350);
        
        add(splitPane, BorderLayout.CENTER);
        
        // Painel inferior - Botões de ação
        JPanel bottomPanel = createButtonPanel();
        add(bottomPanel, BorderLayout.SOUTH);
        
        // Detecta gamepads ao abrir
        detectAndPopulateGamepads();
        
        // Inicia timer de teste
        startTestTimer();
        
        // Para o timer ao fechar
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopTestTimer();
            }
        });
        
        gamepadComboBox = new JComboBox<>();
    }
    
    /**
     * Cria o painel de seleção de gamepad
     */
    private JPanel createGamepadSelectionPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        JLabel label = new JLabel("Gamepad:");
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        
        JComboBox<String> comboBox = new JComboBox<>();
        comboBox.addActionListener(e -> onGamepadSelected(comboBox.getSelectedIndex()));
        
        JButton detectButton = new JButton("Detectar Gamepads");
        detectButton.addActionListener(e -> detectAndPopulateGamepads());
        
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        leftPanel.add(label);
        leftPanel.add(comboBox);
        
        panel.add(leftPanel, BorderLayout.WEST);
        panel.add(detectButton, BorderLayout.EAST);
        
        // Armazena referência para atualizar depois
        panel.putClientProperty("comboBox", comboBox);
        
        return panel;
    }
    
    /**
     * Cria o painel de mapeamento de botões
     */
    private JPanel createMappingPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new TitledBorder("Mapeamento de Botões"));
        
        JPanel gridPanel = new JPanel(new GridLayout(0, 2, 10, 10));
        gridPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        for (int i = 0; i < BUTTONS.length; i++) {
            MMU.Button button = BUTTONS[i];
            String buttonName = BUTTON_NAMES[i];
            
            JLabel label = new JLabel(buttonName + ":");
            label.setFont(label.getFont().deriveFont(Font.BOLD));
            
            JButton mapButton = new JButton("Pressione um botão...");
            mapButton.addActionListener(e -> startButtonMapping(button, mapButton));
            
            mappingButtons.put(button, mapButton);
            
            gridPanel.add(label);
            gridPanel.add(mapButton);
        }
        
        panel.add(gridPanel, BorderLayout.CENTER);
        
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        JButton resetButton = new JButton("Restaurar Padrão");
        resetButton.addActionListener(e -> resetToDefault());
        buttonPanel.add(resetButton);
        
        panel.add(buttonPanel, BorderLayout.SOUTH);
        
        updateMappingDisplay();
        
        return panel;
    }
    
    /**
     * Cria o painel de teste de input
     */
    private JPanel createTestPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new TitledBorder("Teste de Input (Pressione botões no gamepad)"));
        
        JPanel gridPanel = new JPanel(new GridLayout(0, 2, 10, 10));
        gridPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        for (int i = 0; i < BUTTONS.length; i++) {
            MMU.Button button = BUTTONS[i];
            String buttonName = BUTTON_NAMES[i];
            
            JLabel nameLabel = new JLabel(buttonName + ":");
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
            
            JLabel statusLabel = new JLabel("Solto");
            statusLabel.setOpaque(true);
            statusLabel.setBackground(Color.LIGHT_GRAY);
            statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
            statusLabel.setBorder(BorderFactory.createLineBorder(Color.BLACK));
            
            testLabels.put(button, statusLabel);
            
            gridPanel.add(nameLabel);
            gridPanel.add(statusLabel);
        }
        
        panel.add(gridPanel, BorderLayout.CENTER);
        
        JLabel infoLabel = new JLabel("Os botões ficarão verdes quando pressionados no gamepad.");
        infoLabel.setHorizontalAlignment(SwingConstants.CENTER);
        infoLabel.setBorder(new EmptyBorder(10, 10, 10, 10));
        panel.add(infoLabel, BorderLayout.SOUTH);
        
        return panel;
    }
    
    /**
     * Cria o painel de botões de ação
     */
    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        JButton saveButton = new JButton("Salvar e Fechar");
        saveButton.addActionListener(e -> {
            stopTestTimer();
            dispose();
        });
        
        JButton cancelButton = new JButton("Cancelar");
        cancelButton.addActionListener(e -> {
            stopTestTimer();
            dispose();
        });
        
        panel.add(saveButton);
        panel.add(cancelButton);
        
        return panel;
    }
    
    /**
     * Detecta gamepads e popula o combo box
     */
    private void detectAndPopulateGamepads() {
        List<Controller> gamepads = gamepadManager.detectGamepads();
        
        // Encontra o combo box
        JComboBox<String> comboBox = findComboBox();
        if (comboBox == null) return;
        
        comboBox.removeAllItems();
        
        if (gamepads.isEmpty()) {
            comboBox.addItem("Nenhum gamepad detectado");
            JOptionPane.showMessageDialog(this,
                "Nenhum gamepad foi detectado.\n" +
                "Verifique se o gamepad está conectado e funcionando.\n\n" +
                "Nota: Pode ser necessário instalar drivers ou a biblioteca JInput.",
                "Gamepads não encontrados",
                JOptionPane.WARNING_MESSAGE);
        } else {
            for (Controller gamepad : gamepads) {
                comboBox.addItem(gamepad.getName());
            }
            
            if (gamepadManager.hasActiveGamepad()) {
                String activeName = gamepadManager.getActiveGamepad().getName();
                for (int i = 0; i < comboBox.getItemCount(); i++) {
                    if (comboBox.getItemAt(i).equals(activeName)) {
                        comboBox.setSelectedIndex(i);
                        break;
                    }
                }
            }
        }
        
        updateMappingDisplay();
    }
    
    /**
     * Encontra o combo box de seleção
     */
    @SuppressWarnings("unchecked")
    private JComboBox<String> findComboBox() {
        java.awt.Component[] components = ((JPanel) getContentPane().getComponent(0)).getComponents();
        for (java.awt.Component comp : components) {
            if (comp instanceof JPanel) {
                Object prop = ((JPanel) comp).getClientProperty("comboBox");
                if (prop instanceof JComboBox) {
                    return (JComboBox<String>) prop;
                }
            }
        }
        return null;
    }
    
    /**
     * Chamado quando um gamepad é selecionado
     */
    private void onGamepadSelected(int index) {
        List<Controller> gamepads = gamepadManager.detectGamepads();
        if (index >= 0 && index < gamepads.size()) {
            gamepadManager.setActiveGamepad(gamepads.get(index));
            updateMappingDisplay();
        }
    }
    
    /**
     * Inicia o processo de mapeamento de um botão
     */
    private void startButtonMapping(MMU.Button button, JButton displayButton) {
        if (buttonBeingMapped != null) {
            // Cancela mapeamento anterior
            JButton oldButton = mappingButtons.get(buttonBeingMapped);
            if (oldButton != null) {
                updateMappingDisplay();
            }
        }
        
        buttonBeingMapped = button;
        displayButton.setText(">>> Pressione um botão no gamepad <<<");
        displayButton.setBackground(Color.YELLOW);
        
        // Inicia thread para detectar input
        new Thread(() -> {
            if (waitForGamepadInput(button)) {
                SwingUtilities.invokeLater(() -> {
                    displayButton.setBackground(null);
                    updateMappingDisplay();
                    buttonBeingMapped = null;
                });
            }
        }).start();
    }
    
    /**
     * Aguarda input do gamepad para mapeamento
     */
    private boolean waitForGamepadInput(MMU.Button button) {
        if (!gamepadManager.hasActiveGamepad()) {
            return false;
        }
        
        long startTime = System.currentTimeMillis();
        long timeout = 10000; // 10 segundos
        
        Controller gamepad = gamepadManager.getActiveGamepad();
        Component[] components = gamepadManager.getGamepadComponents();
        
        // Aguarda até 10 segundos por um input
        while (System.currentTimeMillis() - startTime < timeout) {
            if (!gamepad.poll()) {
                return false;
            }
            
            for (Component component : components) {
                float value = component.getPollData();
                String componentId = component.getIdentifier().getName().toLowerCase();
                
                if (component.isAnalog()) {
                    // Detecta movimento significativo no eixo
                    if (Math.abs(value) > 0.7f) {
                        String key = value > 0 ? componentId + "_positive" : componentId + "_negative";
                        gamepadManager.setButtonMapping(key, button);
                        return true;
                    }
                } else {
                    // Detecta botão pressionado
                    if (value > 0.5f) {
                        gamepadManager.setButtonMapping(componentId, button);
                        return true;
                    }
                }
            }
            
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                return false;
            }
        }
        
        return false;
    }
    
    /**
     * Atualiza a exibição do mapeamento atual
     */
    private void updateMappingDisplay() {
        Map<String, MMU.Button> mapping = gamepadManager.getButtonMapping();
        Map<MMU.Button, String> reverseMapping = new HashMap<>();
        
        // Inverte o mapeamento para exibição
        for (Map.Entry<String, MMU.Button> entry : mapping.entrySet()) {
            reverseMapping.put(entry.getValue(), entry.getKey());
        }
        
        // Atualiza os botões
        for (Map.Entry<MMU.Button, JButton> entry : mappingButtons.entrySet()) {
            MMU.Button button = entry.getKey();
            JButton displayButton = entry.getValue();
            
            String mapped = reverseMapping.get(button);
            if (mapped != null) {
                displayButton.setText(formatComponentName(mapped));
            } else {
                displayButton.setText("Não mapeado");
            }
        }
    }
    
    /**
     * Formata o nome do componente para exibição
     */
    private String formatComponentName(String componentId) {
        componentId = componentId.replace("_", " ");
        componentId = componentId.replace("button", "Botão");
        componentId = componentId.replace("axis", "Eixo");
        componentId = componentId.replace("positive", "+");
        componentId = componentId.replace("negative", "-");
        return componentId.substring(0, 1).toUpperCase() + componentId.substring(1);
    }
    
    /**
     * Restaura o mapeamento padrão
     */
    private void resetToDefault() {
        int result = JOptionPane.showConfirmDialog(this,
            "Deseja restaurar o mapeamento padrão?",
            "Confirmar",
            JOptionPane.YES_NO_OPTION);
        
        if (result == JOptionPane.YES_OPTION) {
            gamepadManager.resetToDefaultMapping();
            updateMappingDisplay();
        }
    }
    
    /**
     * Inicia o timer de teste de input
     */
    private void startTestTimer() {
        testTimer = new Timer(50, e -> updateTestDisplay());
        testTimer.start();
    }
    
    /**
     * Para o timer de teste
     */
    private void stopTestTimer() {
        if (testTimer != null) {
            testTimer.stop();
            testTimer = null;
        }
    }
    
    /**
     * Atualiza a exibição do teste de input
     */
    private void updateTestDisplay() {
        if (!gamepadManager.hasActiveGamepad()) {
            return;
        }
        
        Controller gamepad = gamepadManager.getActiveGamepad();
        if (!gamepad.poll()) {
            return;
        }
        
        Map<String, MMU.Button> mapping = gamepadManager.getButtonMapping();
        Map<MMU.Button, Boolean> buttonStates = new HashMap<>();
        
        // Inicializa todos como soltos
        for (MMU.Button button : BUTTONS) {
            buttonStates.put(button, false);
        }
        
        // Verifica estado atual dos componentes
        Component[] components = gamepad.getComponents();
        for (Component component : components) {
            float value = component.getPollData();
            String componentId = component.getIdentifier().getName().toLowerCase();
            
            if (component.isAnalog()) {
                // Verifica eixo positivo
                if (value > 0.5f) {
                    MMU.Button button = mapping.get(componentId + "_positive");
                    if (button != null) {
                        buttonStates.put(button, true);
                    }
                }
                // Verifica eixo negativo
                if (value < -0.5f) {
                    MMU.Button button = mapping.get(componentId + "_negative");
                    if (button != null) {
                        buttonStates.put(button, true);
                    }
                }
            } else {
                // Verifica botão digital
                if (value > 0.5f) {
                    MMU.Button button = mapping.get(componentId);
                    if (button != null) {
                        buttonStates.put(button, true);
                    }
                }
            }
        }
        
        // Atualiza labels
        for (Map.Entry<MMU.Button, JLabel> entry : testLabels.entrySet()) {
            MMU.Button button = entry.getKey();
            JLabel label = entry.getValue();
            boolean pressed = buttonStates.getOrDefault(button, false);
            
            if (pressed) {
                label.setText("PRESSIONADO");
                label.setBackground(Color.GREEN);
            } else {
                label.setText("Solto");
                label.setBackground(Color.LIGHT_GRAY);
            }
        }
    }
}
