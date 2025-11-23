package com.meutcc.gbemulator;

import javax.swing.*;

public class Main {
    public static void main(String[] args) {
        try {
            NativeLibraryLoader.loadJInputLibraries();
        } catch (Exception e) {
            System.err.println("Aviso: Não foi possível carregar as bibliotecas nativas do JInput.");
            System.err.println("O suporte a gamepad pode não funcionar corretamente.");
            System.err.println("Detalhes: " + e.getMessage());
            e.printStackTrace();
        }

        SwingUtilities.invokeLater(() -> {
            GameBoyWindow window = new GameBoyWindow();
            window.setVisible(true);
        });
    }
}