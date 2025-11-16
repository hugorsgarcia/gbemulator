package com.meutcc.gbemulator;

/**
 * GamepadInputHandler - Conecta o GamepadManager ao sistema de input do emulador
 * 
 * Esta classe atua como uma ponte entre o GamepadManager (que lê hardware)
 * e o MMU (que processa os botões do Game Boy).
 */
public class GamepadInputHandler implements GamepadManager.GamepadInputListener {
    
    private final MMU mmu;
    private boolean enabled;
    
    public GamepadInputHandler(MMU mmu) {
        this.mmu = mmu;
        this.enabled = true;
    }
    
    @Override
    public void onButtonPressed(MMU.Button button) {
        if (enabled && button != null) {
            mmu.buttonPressed(button);
        }
    }
    
    @Override
    public void onButtonReleased(MMU.Button button) {
        if (enabled && button != null) {
            mmu.buttonReleased(button);
        }
    }
    
    /**
     * Habilita ou desabilita o input do gamepad
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        
        // Se desabilitado, libera todos os botões
        if (!enabled) {
            releaseAllButtons();
        }
    }
    
    /**
     * Verifica se o input está habilitado
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Libera todos os botões do Game Boy
     * Útil ao desconectar o gamepad ou trocar de controle
     */
    public void releaseAllButtons() {
        for (MMU.Button button : MMU.Button.values()) {
            mmu.buttonReleased(button);
        }
    }
}
