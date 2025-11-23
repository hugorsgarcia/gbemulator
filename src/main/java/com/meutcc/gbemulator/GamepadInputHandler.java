package com.meutcc.gbemulator;

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
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        
        if (!enabled) {
            releaseAllButtons();
        }
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void releaseAllButtons() {
        for (MMU.Button button : MMU.Button.values()) {
            mmu.buttonReleased(button);
        }
    }
}
