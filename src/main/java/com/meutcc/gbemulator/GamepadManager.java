package com.meutcc.gbemulator;

import net.java.games.input.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GamepadManager {
    
    private Controller[] controllers;
    private Controller activeGamepad;
    private Thread pollThread;
    private volatile boolean running;
    
    private Map<String, MMU.Button> buttonMapping;
    private Map<String, Float> axisThresholds;
    private Map<String, Float> lastAxisValues;
    
    private GamepadInputListener inputListener;
    
    private static final float DEFAULT_AXIS_THRESHOLD = 0.5f;
    
   
    public interface GamepadInputListener {
        void onButtonPressed(MMU.Button button);
        void onButtonReleased(MMU.Button button);
    }
    
    public GamepadManager() {
        this.buttonMapping = new ConcurrentHashMap<>();
        this.axisThresholds = new ConcurrentHashMap<>();
        this.lastAxisValues = new ConcurrentHashMap<>();
        this.running = false;
        
        initializeDefaultMapping();
    }
    
    private void initializeDefaultMapping() {
        buttonMapping.put("button_0", MMU.Button.GAMEBOY_B);        
        buttonMapping.put("button_1", MMU.Button.GAMEBOY_A);        
        buttonMapping.put("button_2", MMU.Button.GAMEBOY_B);       
        buttonMapping.put("button_3", MMU.Button.GAMEBOY_A);       
        
        buttonMapping.put("button_6", MMU.Button.GAMEBOY_SELECT);  
        buttonMapping.put("button_7", MMU.Button.GAMEBOY_START);   
        buttonMapping.put("button_8", MMU.Button.GAMEBOY_SELECT);  
        buttonMapping.put("button_9", MMU.Button.GAMEBOY_START);   
        
        buttonMapping.put("button_4", MMU.Button.GAMEBOY_SELECT);  
        buttonMapping.put("button_5", MMU.Button.GAMEBOY_START);  
        
        buttonMapping.put("x_positive", MMU.Button.GAMEBOY_RIGHT);
        buttonMapping.put("x_negative", MMU.Button.GAMEBOY_LEFT);
        buttonMapping.put("y_positive", MMU.Button.GAMEBOY_DOWN);
        buttonMapping.put("y_negative", MMU.Button.GAMEBOY_UP);
        
        buttonMapping.put("axis_x_positive", MMU.Button.GAMEBOY_RIGHT);
        buttonMapping.put("axis_x_negative", MMU.Button.GAMEBOY_LEFT);
        buttonMapping.put("axis_y_positive", MMU.Button.GAMEBOY_DOWN);
        buttonMapping.put("axis_y_negative", MMU.Button.GAMEBOY_UP);
        
        buttonMapping.put("pov_positive", MMU.Button.GAMEBOY_RIGHT);
        buttonMapping.put("pov_negative", MMU.Button.GAMEBOY_LEFT);
        
        axisThresholds.put("x", DEFAULT_AXIS_THRESHOLD);
        axisThresholds.put("y", DEFAULT_AXIS_THRESHOLD);
        axisThresholds.put("axis_x", DEFAULT_AXIS_THRESHOLD);
        axisThresholds.put("axis_y", DEFAULT_AXIS_THRESHOLD);
        axisThresholds.put("z", DEFAULT_AXIS_THRESHOLD);
        axisThresholds.put("rz", DEFAULT_AXIS_THRESHOLD);
    }
    
    public List<Controller> detectGamepads() {
        List<Controller> gamepads = new ArrayList<>();
        
        try {
            ControllerEnvironment ce = ControllerEnvironment.getDefaultEnvironment();
            controllers = ce.getControllers();
            
            System.out.println("\n=== Detectando Controladores ===");
            System.out.println("Total de dispositivos encontrados: " + controllers.length);
            
            for (Controller controller : controllers) {
                Controller.Type type = controller.getType();
                String name = controller.getName();
                
                System.out.println("\nDispositivo: " + name);
                System.out.println("  Tipo: " + type);
                System.out.println("  Componentes: " + controller.getComponents().length);
                
                boolean isGamepad = type == Controller.Type.GAMEPAD || 
                                   type == Controller.Type.STICK ||
                                   name.toLowerCase().contains("pro controller") ||
                                   name.toLowerCase().contains("switch") ||
                                   name.toLowerCase().contains("xbox") ||
                                   name.toLowerCase().contains("playstation") ||
                                   name.toLowerCase().contains("ps3") ||
                                   name.toLowerCase().contains("ps4") ||
                                   name.toLowerCase().contains("ps5") ||
                                   name.toLowerCase().contains("dualshock") ||
                                   name.toLowerCase().contains("dualsense") ||
                                   (controller.getComponents().length >= 8 && 
                                    type != Controller.Type.KEYBOARD && 
                                    type != Controller.Type.MOUSE);
                
                if (isGamepad) {
                    gamepads.add(controller);
                    System.out.println("  ✓ Aceito como gamepad!");
                    
                    // Debug: mostra os componentes
                    System.out.println("  Componentes disponíveis:");
                    for (Component comp : controller.getComponents()) {
                        System.out.println("    - " + comp.getName() + 
                                         " (Tipo: " + comp.getIdentifier() + 
                                         ", Analógico: " + comp.isAnalog() + ")");
                    }
                } else {
                    System.out.println("  ✗ Ignorado (não é um gamepad)");
                }
            }
            
            System.out.println("\n=== Gamepads detectados: " + gamepads.size() + " ===\n");
            
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
    
    public void startPolling(GamepadInputListener listener) {
        if (activeGamepad == null) {
            System.err.println("Nenhum gamepad ativo. Execute detectGamepads() primeiro.");
            return;
        }
        
        this.inputListener = listener;
        this.running = true;
        
        pollThread = new Thread(() -> {
            System.out.println("Thread de polling do gamepad iniciada");
            System.out.println("Gamepad: " + activeGamepad.getName());
            
            int failCount = 0;
            int maxConsecutiveFails = 10;
            
            while (running) {
                try {
                    boolean pollSuccess = activeGamepad.poll();
                    
                    if (!pollSuccess) {
                        failCount++;
                        
                        if (failCount <= 3) {
                            System.err.println("Falha ao fazer poll do gamepad (tentativa " + failCount + ")");
                        } else if (failCount == maxConsecutiveFails) {
                            System.err.println("Muitas falhas consecutivas de polling. O gamepad pode estar desconectado.");
                            System.err.println("DICA: Se o controle está conectado via Bluetooth, tente:");
                            System.err.println("  1. Desconectar e reconectar o controle");
                            System.err.println("  2. Usar cabo USB em vez de Bluetooth");
                            System.err.println("  3. Atualizar os drivers do controle no Windows");
                        }
                        
                        try {
                            Thread.sleep(failCount < 5 ? 100 : 1000);
                        } catch (InterruptedException e) {
                            break;
                        }
                        
                        if (failCount >= maxConsecutiveFails) {
                            System.out.println("Tentando re-detectar gamepads...");
                            List<Controller> gamepads = detectGamepads();
                            if (!gamepads.isEmpty()) {
                                activeGamepad = gamepads.get(0);
                                failCount = 0;
                                System.out.println("Gamepad reconectado: " + activeGamepad.getName());
                            }
                        }
                        
                        continue;
                    }
                    
                    if (failCount > 0) {
                        System.out.println("Conexão com gamepad restabelecida!");
                        failCount = 0;
                    }
                    
                    processGamepadInput();
                    
                } catch (Exception e) {
                    System.err.println("Erro ao processar gamepad: " + e.getMessage());
                    failCount++;
                }
                
                try {
                    Thread.sleep(16);
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
                processAxisInput(componentId, value);
            } else {
                processButtonInput(componentId, value);
            }
        }
    }
    
    private void processAxisInput(String axisId, float value) {
        Float lastValue = lastAxisValues.getOrDefault(axisId, 0.0f);
        lastAxisValues.put(axisId, value);
        
        float threshold = axisThresholds.getOrDefault(axisId, DEFAULT_AXIS_THRESHOLD);
        
        MMU.Button positiveButton = null;
        for (String key : new String[]{axisId + "_positive", axisId.replace("axis_", "") + "_positive"}) {
            positiveButton = buttonMapping.get(key);
            if (positiveButton != null) break;
        }
        
        if (positiveButton != null) {
            if (value > threshold && lastValue <= threshold) {
                inputListener.onButtonPressed(positiveButton);
            } else if (value <= threshold && lastValue > threshold) {
                inputListener.onButtonReleased(positiveButton);
            }
        }
        
        MMU.Button negativeButton = null;
        for (String key : new String[]{axisId + "_negative", axisId.replace("axis_", "") + "_negative"}) {
            negativeButton = buttonMapping.get(key);
            if (negativeButton != null) break;
        }
        
        if (negativeButton != null) {
            if (value < -threshold && lastValue >= -threshold) {
                inputListener.onButtonPressed(negativeButton);
            } else if (value >= -threshold && lastValue < -threshold) {
                inputListener.onButtonReleased(negativeButton);
            }
        }
    }
    
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
    
    public Controller getActiveGamepad() {
        return activeGamepad;
    }

    public boolean hasActiveGamepad() {
        return activeGamepad != null;
    }
    
    public void setButtonMapping(String componentId, MMU.Button button) {
        buttonMapping.put(componentId.toLowerCase(), button);
    }
    
    public void addButtonMapping(String componentId, MMU.Button button) {
        buttonMapping.put(componentId.toLowerCase(), button);
    }
    
    public void removeButtonMappings(MMU.Button button) {
        buttonMapping.entrySet().removeIf(entry -> entry.getValue() == button);
    }

    public Map<String, MMU.Button> getButtonMapping() {
        return new HashMap<>(buttonMapping);
    }
    
    public void setAxisThreshold(String axisId, float threshold) {
        axisThresholds.put(axisId, threshold);
    }
    
    public Component[] getGamepadComponents() {
        if (activeGamepad == null) {
            return new Component[0];
        }
        return activeGamepad.getComponents();
    }
    
    public float pollComponent(Component component) {
        if (activeGamepad != null && activeGamepad.poll()) {
            return component.getPollData();
        }
        return 0.0f;
    }
    
    public void clearMapping() {
        buttonMapping.clear();
    }
    
    public void resetToDefaultMapping() {
        clearMapping();
        initializeDefaultMapping();
    }
    
    public void shutdown() {
        stopPolling();
        activeGamepad = null;
        controllers = null;
        inputListener = null;
    }
}
