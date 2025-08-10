package com.meutcc.gbemulator;


import javax.swing.*;

public class Main {
    public static void main(String[] args) {

        SwingUtilities.invokeLater(()->{
            GameBoyWindow window = new GameBoyWindow();
            // A janela já é configurada como não-redimensionável em seu construtor (GameBoyWindow).
            // Manter um comportamento consistente é uma boa prática.
            window.setVisible(true);

        });
    }
}