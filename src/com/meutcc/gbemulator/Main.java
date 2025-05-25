package com.meutcc.gbemulator;


import javax.swing.*;

public class Main {
    public static void main(String[] args) {

        SwingUtilities.invokeLater(()->{
            GameBoyWindow window = new GameBoyWindow();
            window.setVisible(true);
            window.setResizable(true);

        });
    }
}