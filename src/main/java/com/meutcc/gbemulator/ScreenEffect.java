package com.meutcc.gbemulator;

import java.awt.*;
import java.awt.image.BufferedImage;

public class ScreenEffect {

    private boolean ghostingEnabled = false;
    private float ghostingIntensity = 0.3f;
    private BufferedImage previousFrame = null;

    private boolean gridEnabled = false;
    private float gridIntensity = 0.15f;

    private boolean scanlinesEnabled = false;
    private float scanlineIntensity = 0.2f;

    public BufferedImage applyEffects(BufferedImage currentFrame, int scaledWidth, int scaledHeight) {
        BufferedImage result = currentFrame;

        if (ghostingEnabled && previousFrame != null) {
            result = applyGhosting(currentFrame);
        }

        if (ghostingEnabled) {
            storePreviousFrame(currentFrame);
        }

        return result;
    }

    private BufferedImage applyGhosting(BufferedImage currentFrame) {
        if (previousFrame == null)
            return currentFrame;

        int width = currentFrame.getWidth();
        int height = currentFrame.getHeight();

        BufferedImage ghostedFrame = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int currentColor = currentFrame.getRGB(x, y);
                int previousColor = previousFrame.getRGB(x, y);

                int currR = (currentColor >> 16) & 0xFF;
                int currG = (currentColor >> 8) & 0xFF;
                int currB = currentColor & 0xFF;

                int prevR = (previousColor >> 16) & 0xFF;
                int prevG = (previousColor >> 8) & 0xFF;
                int prevB = previousColor & 0xFF;

                int r = (int) (currR * (1.0f - ghostingIntensity) + prevR * ghostingIntensity);
                int g = (int) (currG * (1.0f - ghostingIntensity) + prevG * ghostingIntensity);
                int b = (int) (currB * (1.0f - ghostingIntensity) + prevB * ghostingIntensity);

                r = Math.min(255, Math.max(0, r));
                g = Math.min(255, Math.max(0, g));
                b = Math.min(255, Math.max(0, b));

                int blendedColor = 0xFF000000 | (r << 16) | (g << 8) | b;
                ghostedFrame.setRGB(x, y, blendedColor);
            }
        }

        return ghostedFrame;
    }

    private void storePreviousFrame(BufferedImage currentFrame) {
        if (previousFrame == null ||
                previousFrame.getWidth() != currentFrame.getWidth() ||
                previousFrame.getHeight() != currentFrame.getHeight()) {
            previousFrame = new BufferedImage(
                    currentFrame.getWidth(),
                    currentFrame.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
        }

        Graphics2D g = previousFrame.createGraphics();
        g.drawImage(currentFrame, 0, 0, null);
        g.dispose();
    }

    public void drawGridLines(Graphics2D g2d, int x, int y, int scaledWidth, int scaledHeight,
            int screenWidth, int screenHeight) {
        if (!gridEnabled)
            return;

        float pixelWidth = (float) scaledWidth / screenWidth;
        float pixelHeight = (float) scaledHeight / screenHeight;

        int alpha = (int) (gridIntensity * 255);
        g2d.setColor(new Color(0, 0, 0, alpha));

        for (int i = 1; i < screenWidth; i++) {
            int lineX = x + (int) (i * pixelWidth);
            g2d.drawLine(lineX, y, lineX, y + scaledHeight);
        }

        for (int i = 1; i < screenHeight; i++) {
            int lineY = y + (int) (i * pixelHeight);
            g2d.drawLine(x, lineY, x + scaledWidth, lineY);
        }
    }

    public void drawScanlines(Graphics2D g2d, int x, int y, int scaledWidth, int scaledHeight) {
        if (!scanlinesEnabled)
            return;

        int alpha = (int) (scanlineIntensity * 255);
        g2d.setColor(new Color(0, 0, 0, alpha));

        for (int i = 0; i < scaledHeight; i += 2) {
            g2d.drawLine(x, y + i, x + scaledWidth, y + i);
        }
    }

    public boolean isGhostingEnabled() {
        return ghostingEnabled;
    }

    public void setGhostingEnabled(boolean enabled) {
        this.ghostingEnabled = enabled;
        if (!enabled) {
            previousFrame = null;
        }
    }

    public float getGhostingIntensity() {
        return ghostingIntensity;
    }

    public void setGhostingIntensity(float intensity) {
        this.ghostingIntensity = Math.max(0.0f, Math.min(1.0f, intensity));
    }

    public boolean isGridEnabled() {
        return gridEnabled;
    }

    public void setGridEnabled(boolean enabled) {
        this.gridEnabled = enabled;
    }

    public float getGridIntensity() {
        return gridIntensity;
    }

    public void setGridIntensity(float intensity) {
        this.gridIntensity = Math.max(0.0f, Math.min(1.0f, intensity));
    }

    public boolean isScanlinesEnabled() {
        return scanlinesEnabled;
    }

    public void setScanlinesEnabled(boolean enabled) {
        this.scanlinesEnabled = enabled;
    }

    public float getScanlineIntensity() {
        return scanlineIntensity;
    }

    public void setScanlineIntensity(float intensity) {
        this.scanlineIntensity = Math.max(0.0f, Math.min(1.0f, intensity));
    }

    public void reset() {
        previousFrame = null;
    }
}
