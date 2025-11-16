package com.meutcc.gbemulator;

import net.java.games.input.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GamepadManager - Gerencia a detecção e leitura de gamepads conectados via USB/Bluetooth
 * usando a biblioteca JInput.
 * 
 * Funcionalidades:
 * - Detecta automaticamente gamepads conectados
 * - Lê continuamente o estado dos botões e eixos
 * - Permite configuração personalizada de mapeamento de botões
 * - Suporta múltiplos gamepads (usa o primeiro detectado por padrão)
 */
public class GamepadManager {
    
    private Controller[] controllers;
    private Controller activeGamepad;
    private Thread pollThread;
    private volatile boolean running;
    
    private Map<String, MMU.Button> buttonMapping;
    private Map<String, Float> axisThresholds;
    private Map<String, Float> lastAxisValues;
    
    private GamepadInputListener inputListener;
    
    // Threshold para eixos analógicos (direcional)
    private static final float DEFAULT_AXIS_THRESHOLD = 0.5f;
    
    /**
     * Interface para callbacks de eventos do gamepad
     */
    public interface GamepadInputListener {
        void onButtonPressed(MMU.Button button);
        void onButtonReleased(MMU.Button button);
    }
    
    public GamepadManager() {
        this.buttonMapping = new ConcurrentHashMap<>();
        this.axisThresholds = new ConcurrentHashMap<>();
        this.lastAxisValues = new ConcurrentHashMap<>();
        this.running = false;
        
        // Configuração padrão de mapeamento (estilo Xbox/PlayStation)
        initializeDefaultMapping();
    }
    
    /**
     * Inicializa o mapeamento padrão de botões
     */
    private void initializeDefaultMapping() {
        // Botões padrão (índices comuns em gamepads)
        buttonMapping.put("button_0", MMU.Button.GAMEBOY_A);        // A/Cross
        buttonMapping.put("button_1", MMU.Button.GAMEBOY_B);        // B/Circle
        buttonMapping.put("button_6", MMU.Button.GAMEBOY_SELECT);   // Select/Back
        buttonMapping.put("button_7", MMU.Button.GAMEBOY_START);    // Start
        
        // Eixos direcionais (D-Pad ou analógico esquerdo)
        buttonMapping.put("axis_x_positive", MMU.Button.GAMEBOY_RIGHT);
        buttonMapping.put("axis_x_negative", MMU.Button.GAMEBOY_LEFT);
        buttonMapping.put("axis_y_positive", MMU.Button.GAMEBOY_DOWN);
        buttonMapping.put("axis_y_negative", MMU.Button.GAMEBOY_UP);
        
        // Threshold padrão para todos os eixos
        axisThresholds.put("axis_x", DEFAULT_AXIS_THRESHOLD);
        axisThresholds.put("axis_y", DEFAULT_AXIS_THRESHOLD);
    }
    
    /**
     * Detecta gamepads conectados ao sistema
     * @return Lista de gamepads encontrados
     */
    public List<Controller> detectGamepads() {
        List<Controller> gamepads = new ArrayList<>();
        
        try {
            ControllerEnvironment ce = ControllerEnvironment.getDefaultEnvironment();
            controllers = ce.getControllers();
            
            for (Controller controller : controllers) {
                Controller.Type type = controller.getType();
                
                // Filtra apenas gamepads/joysticks
                if (type == Controller.Type.GAMEPAD || 
                    type == Controller.Type.STICK) {
                    gamepads.add(controller);
                    System.out.println("Gamepad detectado: " + controller.getName());
                }
            }
            
            if (!gamepads.isEmpty() && activeGamepad == null) {
                activeGamepad = gamepads.get(0);
                System.out.println("Gamepad ativo selecionado: " + activeGamepad.getName());
            }
            
        } catch (Exception e) {
            System.err.println("Erro ao detectar gamepads: " + e.getMessage());
            e.printStackTrace();
        }
        
        return gamepads;
    }
    
    /**
     * Inicia a leitura contínua do gamepad
     * @param listener Callback para eventos de input
     */
    public void startPolling(GamepadInputListener listener) {
        if (activeGamepad == null) {
            System.err.println("Nenhum gamepad ativo. Execute detectGamepads() primeiro.");
            return;
        }
        
        this.inputListener = listener;
        this.running = true;
        
        pollThread = new Thread(() -> {
            System.out.println("Thread de polling do gamepad iniciada");
            
            while (running) {
                if (!activeGamepad.poll()) {
                    System.err.println("Falha ao fazer poll do gamepad. Reconectando...");
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }
                
                processGamepadInput();
                
                try {
                    Thread.sleep(16); // ~60 Hz polling rate
                } catch (InterruptedException e) {
                    break;
                }
            }
            
            System.out.println("Thread de polling do gamepad finalizada");
        });
        
        pollThread.setName("GamepadPollingThread");
        pollThread.setDaemon(true);
        pollThread.start();
    }
    
    /**
     * Processa o input do gamepad e dispara callbacks
     */
    private void processGamepadInput() {
        if (inputListener == null || activeGamepad == null) {
            return;
        }
        
        EventQueue queue = activeGamepad.getEventQueue();
        Event event = new Event();
        
        while (queue.getNextEvent(event)) {
            Component component = event.getComponent();
            float value = event.getValue();
            String componentId = component.getIdentifier().getName();
            
            if (component.isAnalog()) {
                // Processa eixos analógicos (direcionais)
                processAxisInput(componentId, value);
            } else {
                // Processa botões digitais
                processButtonInput(componentId, value);
            }
        }
    }
    
    /**
     * Processa input de eixos analógicos
     */
    private void processAxisInput(String axisId, float value) {
        Float lastValue = lastAxisValues.getOrDefault(axisId, 0.0f);
        lastAxisValues.put(axisId, value);
        
        float threshold = axisThresholds.getOrDefault(axisId, DEFAULT_AXIS_THRESHOLD);
        
        // Verifica se o eixo ultrapassou o threshold na direção positiva
        String positiveKey = axisId + "_positive";
        MMU.Button positiveButton = buttonMapping.get(positiveKey);
        
        if (positiveButton != null) {
            if (value > threshold && lastValue <= threshold) {
                inputListener.onButtonPressed(positiveButton);
            } else if (value <= threshold && lastValue > threshold) {
                inputListener.onButtonReleased(positiveButton);
            }
        }
        
        // Verifica se o eixo ultrapassou o threshold na direção negativa
        String negativeKey = axisId + "_negative";
        MMU.Button negativeButton = buttonMapping.get(negativeKey);
        
        if (negativeButton != null) {
            if (value < -threshold && lastValue >= -threshold) {
                inputListener.onButtonPressed(negativeButton);
            } else if (value >= -threshold && lastValue < -threshold) {
                inputListener.onButtonReleased(negativeButton);
            }
        }
    }
    
    /**
     * Processa input de botões digitais
     */
    private void processButtonInput(String buttonId, float value) {
        String buttonKey = buttonId.toLowerCase();
        MMU.Button gameBoyButton = buttonMapping.get(buttonKey);
        
        if (gameBoyButton != null) {
            if (value > 0.5f) {
                inputListener.onButtonPressed(gameBoyButton);
            } else {
                inputListener.onButtonReleased(gameBoyButton);
            }
        }
    }
    
    /**
     * Para a leitura do gamepad
     */
    public void stopPolling() {
        running = false;
        
        if (pollThread != null) {
            try {
                pollThread.join(1000);
            } catch (InterruptedException e) {
                pollThread.interrupt();
            }
        }
    }
    
    /**
     * Define qual gamepad usar
     */
    public void setActiveGamepad(Controller gamepad) {
        boolean wasRunning = running;
        
        if (wasRunning) {
            stopPolling();
        }
        
        this.activeGamepad = gamepad;
        System.out.println("Gamepad ativo alterado para: " + gamepad.getName());
        
        if (wasRunning && inputListener != null) {
            startPolling(inputListener);
        }
    }
    
    /**
     * Obtém o gamepad ativo atual
     */
    public Controller getActiveGamepad() {
        return activeGamepad;
    }
    
    /**
     * Verifica se há um gamepad ativo
     */
    public boolean hasActiveGamepad() {
        return activeGamepad != null;
    }
    
    /**
     * Define o mapeamento customizado de um botão/eixo
     */
    public void setButtonMapping(String componentId, MMU.Button button) {
        buttonMapping.put(componentId.toLowerCase(), button);
    }
    
    /**
     * Obtém o mapeamento atual de botões
     */
    public Map<String, MMU.Button> getButtonMapping() {
        return new HashMap<>(buttonMapping);
    }
    
    /**
     * Define o threshold para um eixo analógico
     */
    public void setAxisThreshold(String axisId, float threshold) {
        axisThresholds.put(axisId, threshold);
    }
    
    /**
     * Obtém todos os componentes do gamepad ativo
     */
    public Component[] getGamepadComponents() {
        if (activeGamepad == null) {
            return new Component[0];
        }
        return activeGamepad.getComponents();
    }
    
    /**
     * Testa um componente específico do gamepad
     */
    public float pollComponent(Component component) {
        if (activeGamepad != null && activeGamepad.poll()) {
            return component.getPollData();
        }
        return 0.0f;
    }
    
    /**
     * Limpa o mapeamento atual
     */
    public void clearMapping() {
        buttonMapping.clear();
    }
    
    /**
     * Restaura o mapeamento padrão
     */
    public void resetToDefaultMapping() {
        clearMapping();
        initializeDefaultMapping();
    }
    
    /**
     * Libera recursos
     */
    public void shutdown() {
        stopPolling();
        activeGamepad = null;
        controllers = null;
        inputListener = null;
    }
}
