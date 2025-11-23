package com.meutcc.gbemulator;

import net.java.games.input.Component;
import net.java.games.input.Controller;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


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
        this.gamepadComboBox = new JComboBox<>();
        
        setLayout(new BorderLayout(10, 10));
        setSize(700, 600);
        setLocationRelativeTo(parent);
        
        JPanel topPanel = createGamepadSelectionPanel();
        add(topPanel, BorderLayout.NORTH);
        
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        
        mappingPanel = createMappingPanel();
        testPanel = createTestPanel();
        
        splitPane.setTopComponent(mappingPanel);
        splitPane.setBottomComponent(testPanel);
        splitPane.setDividerLocation(350);
        
        add(splitPane, BorderLayout.CENTER);
        
        JPanel bottomPanel = createButtonPanel();
        add(bottomPanel, BorderLayout.SOUTH);
        
        detectAndPopulateGamepads();
        
        startTestTimer();
        
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopTestTimer();
            }
        });
    }
    
   
    private JPanel createGamepadSelectionPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        JLabel label = new JLabel("Gamepad:");
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        
        gamepadComboBox.addActionListener(e -> onGamepadSelected(gamepadComboBox.getSelectedIndex()));
        
        JButton detectButton = new JButton("Detectar Gamepads");
        detectButton.addActionListener(e -> detectAndPopulateGamepads());
        
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        leftPanel.add(label);
        leftPanel.add(gamepadComboBox);
        
        panel.add(leftPanel, BorderLayout.WEST);
        panel.add(detectButton, BorderLayout.EAST);
        
        return panel;
    }
    
   
    private JPanel createMappingPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new TitledBorder("Mapeamento de Botões (Clique para adicionar mais inputs)"));
        
        JPanel gridPanel = new JPanel(new GridLayout(0, 3, 10, 10));
        gridPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        for (int i = 0; i < BUTTONS.length; i++) {
            MMU.Button button = BUTTONS[i];
            String buttonName = BUTTON_NAMES[i];
            
            JLabel label = new JLabel(buttonName + ":");
            label.setFont(label.getFont().deriveFont(Font.BOLD));
            
            JButton mapButton = new JButton("Pressione um botão...");
            mapButton.addActionListener(e -> startButtonMapping(button, mapButton));
            
            JButton clearButton = new JButton("✕");
            clearButton.setToolTipText("Limpar mapeamento");
            clearButton.setPreferredSize(new Dimension(40, mapButton.getPreferredSize().height));
            clearButton.addActionListener(e -> {
                gamepadManager.removeButtonMappings(button);
                updateSingleButtonDisplay(button);
            });
            
            mappingButtons.put(button, mapButton);
            
            gridPanel.add(label);
            gridPanel.add(mapButton);
            gridPanel.add(clearButton);
        }
        
        panel.add(gridPanel, BorderLayout.CENTER);
        
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        
        JLabel infoLabel = new JLabel("💡 Dica: Você pode mapear vários inputs (D-Pad + Analógico)");
        infoLabel.setFont(infoLabel.getFont().deriveFont(Font.ITALIC));
        buttonPanel.add(infoLabel);
        
        buttonPanel.add(Box.createHorizontalStrut(20));
        
        JButton resetButton = new JButton("Restaurar Padrão");
        resetButton.addActionListener(e -> resetToDefault());
        buttonPanel.add(resetButton);
        
        panel.add(buttonPanel, BorderLayout.SOUTH);
        
        updateMappingDisplay();
        
        return panel;
    }
    
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
    
    private void detectAndPopulateGamepads() {
        List<Controller> gamepads = gamepadManager.detectGamepads();
        
        gamepadComboBox.removeAllItems();
        
        if (gamepads.isEmpty()) {
            gamepadComboBox.addItem("Nenhum gamepad detectado");
            JOptionPane.showMessageDialog(this,
                "Nenhum gamepad foi detectado.\n" +
                "Verifique se o gamepad está conectado e funcionando.\n\n" +
                "Nota: Pode ser necessário instalar drivers ou a biblioteca JInput.",
                "Gamepads não encontrados",
                JOptionPane.WARNING_MESSAGE);
        } else {
            for (Controller gamepad : gamepads) {
                gamepadComboBox.addItem(gamepad.getName());
            }
            
            if (gamepadManager.hasActiveGamepad()) {
                String activeName = gamepadManager.getActiveGamepad().getName();
                for (int i = 0; i < gamepadComboBox.getItemCount(); i++) {
                    if (gamepadComboBox.getItemAt(i).equals(activeName)) {
                        gamepadComboBox.setSelectedIndex(i);
                        break;
                    }
                }
            }
        }
        
        updateMappingDisplay();
    }
    
    private void onGamepadSelected(int index) {
        List<Controller> gamepads = gamepadManager.detectGamepads();
        if (index >= 0 && index < gamepads.size()) {
            gamepadManager.setActiveGamepad(gamepads.get(index));
            updateMappingDisplay();
        }
    }

    private void startButtonMapping(MMU.Button button, JButton displayButton) {
        if (buttonBeingMapped != null) {
            // Cancela mapeamento anterior
            JButton oldButton = mappingButtons.get(buttonBeingMapped);
            if (oldButton != null) {
                updateSingleButtonDisplay(buttonBeingMapped);
                oldButton.setBackground(null);
            }
        }
        
        buttonBeingMapped = button;
        displayButton.setText(">>> Pressione um botão no gamepad <<<");
        displayButton.setBackground(Color.YELLOW);
        
        new Thread(() -> {
            boolean success = waitForGamepadInput(button, 1);
            SwingUtilities.invokeLater(() -> {
                displayButton.setBackground(null);
                if (success) {
                    updateSingleButtonDisplay(button);
                } else {
                    displayButton.setText("Timeout - tente novamente");
                }
                buttonBeingMapped = null;
            });
        }).start();
    }
    
    private boolean waitForGamepadInput(MMU.Button button, int mappingMode) {
        if (!gamepadManager.hasActiveGamepad()) {
            return false;
        }
        
        long startTime = System.currentTimeMillis();
        long timeout = 10000;
        
        Controller gamepad = gamepadManager.getActiveGamepad();
        Component[] components = gamepadManager.getGamepadComponents();
        
        if (mappingMode == 0 || mappingMode == 2) {
            gamepadManager.removeButtonMappings(button);
        }
        
        Map<String, Float> initialValues = new HashMap<>();
        gamepad.poll();
        for (Component component : components) {
            String componentId = component.getIdentifier().getName().toLowerCase();
            initialValues.put(componentId, component.getPollData());
        }
        
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            return false;
        }
        
        while (System.currentTimeMillis() - startTime < timeout) {
            if (!gamepad.poll()) {
                return false;
            }
            
            for (Component component : components) {
                float value = component.getPollData();
                String componentId = component.getIdentifier().getName().toLowerCase();
                float initialValue = initialValues.getOrDefault(componentId, 0f);
                
                if (component.isAnalog()) {
                    float delta = Math.abs(value - initialValue);
                    
                    if (delta > 0.4f && Math.abs(value) > 0.7f) {
                        String key = value > 0 ? componentId + "_positive" : componentId + "_negative";
                        gamepadManager.addButtonMapping(key, button);
                        return true;
                    }
                } else {
                    if (value > 0.5f && initialValue < 0.5f) {
                        gamepadManager.addButtonMapping(componentId, button);
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
    
    private void updateMappingDisplay() {
        Map<String, MMU.Button> mapping = gamepadManager.getButtonMapping();
        Map<MMU.Button, java.util.List<String>> reverseMapping = new HashMap<>();
        
        for (Map.Entry<String, MMU.Button> entry : mapping.entrySet()) {
            MMU.Button button = entry.getValue();
            reverseMapping.computeIfAbsent(button, k -> new ArrayList<>()).add(entry.getKey());
        }
        
        for (Map.Entry<MMU.Button, JButton> entry : mappingButtons.entrySet()) {
            MMU.Button button = entry.getKey();
            JButton displayButton = entry.getValue();
            
            java.util.List<String> mappedComponents = reverseMapping.get(button);
            if (mappedComponents != null && !mappedComponents.isEmpty()) {
                if (mappedComponents.size() == 1) {
                    displayButton.setText(formatComponentName(mappedComponents.get(0)));
                } else {
                    String text = mappedComponents.stream()
                        .map(this::formatComponentName)
                        .collect(java.util.stream.Collectors.joining(" / "));
                    displayButton.setText(text);
                }
            } else {
                displayButton.setText("Não mapeado");
            }
        }
    }
    
    private void updateSingleButtonDisplay(MMU.Button button) {
        Map<String, MMU.Button> mapping = gamepadManager.getButtonMapping();
        java.util.List<String> mappedComponents = new ArrayList<>();
        
        for (Map.Entry<String, MMU.Button> entry : mapping.entrySet()) {
            if (entry.getValue() == button) {
                mappedComponents.add(entry.getKey());
            }
        }
        
        JButton displayButton = mappingButtons.get(button);
        if (displayButton != null) {
            if (!mappedComponents.isEmpty()) {
                if (mappedComponents.size() == 1) {
                    displayButton.setText(formatComponentName(mappedComponents.get(0)));
                } else {
                    String text = mappedComponents.stream()
                        .map(this::formatComponentName)
                        .collect(java.util.stream.Collectors.joining(" / "));
                    displayButton.setText(text);
                }
            } else {
                displayButton.setText("Não mapeado");
            }
        }
    }
    
    private String formatComponentName(String componentId) {
        componentId = componentId.replace("_", " ");
        componentId = componentId.replace("button", "Botão");
        componentId = componentId.replace("axis", "Eixo");
        componentId = componentId.replace("positive", "+");
        componentId = componentId.replace("negative", "-");
        return componentId.substring(0, 1).toUpperCase() + componentId.substring(1);
    }
    
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

    private void startTestTimer() {
        testTimer = new Timer(50, e -> updateTestDisplay());
        testTimer.start();
    }
    
    private void stopTestTimer() {
        if (testTimer != null) {
            testTimer.stop();
            testTimer = null;
        }
    }
    
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
        
        for (MMU.Button button : BUTTONS) {
            buttonStates.put(button, false);
        }
        
        Component[] components = gamepad.getComponents();
        for (Component component : components) {
            float value = component.getPollData();
            String componentId = component.getIdentifier().getName().toLowerCase();
            
            if (component.isAnalog()) {
                if (value > 0.5f) {
                    MMU.Button button = mapping.get(componentId + "_positive");
                    if (button != null) {
                        buttonStates.put(button, true);
                    }
                }
                if (value < -0.5f) {
                    MMU.Button button = mapping.get(componentId + "_negative");
                    if (button != null) {
                        buttonStates.put(button, true);
                    }
                }
            } else {
                if (value > 0.5f) {
                    MMU.Button button = mapping.get(componentId);
                    if (button != null) {
                        buttonStates.put(button, true);
                    }
                }
            }
        }
        
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
