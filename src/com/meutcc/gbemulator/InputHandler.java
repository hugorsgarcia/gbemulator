package com.meutcc.gbemulator;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

public class InputHandler implements KeyListener {

    private final MMU mmu;

    public InputHandler(MMU mmu) {
        this.mmu = mmu;
    }

    @Override
    public void keyTyped(KeyEvent e) {
    }

    @Override
    public void keyPressed(KeyEvent e) {
        MMU.Button button = mapKeyCodeToButton(e.getKeyCode());
        if (button != null) {
            mmu.buttonPressed(button);
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        MMU.Button button = mapKeyCodeToButton(e.getKeyCode());
        if (button != null) {
            mmu.buttonReleased(button);
        }
    }

    private MMU.Button mapKeyCodeToButton(int keyCode) {
        switch (keyCode) {
            case KeyEvent.VK_RIGHT: return MMU.Button.GAMEBOY_RIGHT;
            case KeyEvent.VK_LEFT: return MMU.Button.GAMEBOY_LEFT;
            case KeyEvent.VK_UP: return MMU.Button.GAMEBOY_UP;
            case KeyEvent.VK_DOWN: return MMU.Button.GAMEBOY_DOWN;
            case KeyEvent.VK_Z: return MMU.Button.GAMEBOY_A;
            case KeyEvent.VK_X: return MMU.Button.GAMEBOY_B;
            case KeyEvent.VK_ENTER: return MMU.Button.GAMEBOY_START;
            case KeyEvent.VK_SHIFT: return MMU.Button.GAMEBOY_SELECT;
            default: return null;
        }
    }
}