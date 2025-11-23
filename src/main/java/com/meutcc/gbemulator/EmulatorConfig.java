package com.meutcc.gbemulator;

public class EmulatorConfig {


    private int windowScale = 3;

    private boolean audioEnabled = true;

    private String lastRomDirectory = "";
    private String lastRomFile = "";

    private String colorPalette = "DMG_GREEN"; 
    private String scalingFilter = "NEAREST_NEIGHBOR";

    private boolean ghostingEnabled = false;
    private float ghostingIntensity = 0.3f;
    private boolean gridEnabled = false;
    private float gridIntensity = 0.15f;
    private boolean scanlinesEnabled = false;
    private float scanlineIntensity = 0.2f;

    private String gamepadButtonMapping = "";
    private boolean gamepadEnabled = false;
    private String selectedGamepadName = "";

    public int getWindowScale() {
        return windowScale;
    }

    public void setWindowScale(int windowScale) {
        if (windowScale >= 1 && windowScale <= 6) {
            this.windowScale = windowScale;
        }
    }

    public boolean isAudioEnabled() {
        return audioEnabled;
    }

    public void setAudioEnabled(boolean audioEnabled) {
        this.audioEnabled = audioEnabled;
    }

    public String getLastRomDirectory() {
        return lastRomDirectory;
    }

    public void setLastRomDirectory(String lastRomDirectory) {
        this.lastRomDirectory = lastRomDirectory != null ? lastRomDirectory : "";
    }

    public String getLastRomFile() {
        return lastRomFile;
    }

    public void setLastRomFile(String lastRomFile) {
        this.lastRomFile = lastRomFile != null ? lastRomFile : "";
    }

    public String getColorPalette() {
        return colorPalette;
    }

    public void setColorPalette(String colorPalette) {
        this.colorPalette = colorPalette != null ? colorPalette : "DMG_GREEN";
    }

    public String getScalingFilter() {
        return scalingFilter;
    }

    public void setScalingFilter(String scalingFilter) {
        this.scalingFilter = scalingFilter != null ? scalingFilter : "NEAREST_NEIGHBOR";
    }

    public boolean isGhostingEnabled() {
        return ghostingEnabled;
    }

    public void setGhostingEnabled(boolean ghostingEnabled) {
        this.ghostingEnabled = ghostingEnabled;
    }

    public float getGhostingIntensity() {
        return ghostingIntensity;
    }

    public void setGhostingIntensity(float ghostingIntensity) {
        this.ghostingIntensity = Math.max(0.0f, Math.min(1.0f, ghostingIntensity));
    }

    public boolean isGridEnabled() {
        return gridEnabled;
    }

    public void setGridEnabled(boolean gridEnabled) {
        this.gridEnabled = gridEnabled;
    }

    public float getGridIntensity() {
        return gridIntensity;
    }

    public void setGridIntensity(float gridIntensity) {
        this.gridIntensity = Math.max(0.0f, Math.min(1.0f, gridIntensity));
    }

    public boolean isScanlinesEnabled() {
        return scanlinesEnabled;
    }

    public void setScanlinesEnabled(boolean scanlinesEnabled) {
        this.scanlinesEnabled = scanlinesEnabled;
    }

    public float getScanlineIntensity() {
        return scanlineIntensity;
    }

    public void setScanlineIntensity(float scanlineIntensity) {
        this.scanlineIntensity = Math.max(0.0f, Math.min(1.0f, scanlineIntensity));
    }

    public String getGamepadButtonMapping() {
        return gamepadButtonMapping;
    }

    public void setGamepadButtonMapping(String gamepadButtonMapping) {
        this.gamepadButtonMapping = gamepadButtonMapping != null ? gamepadButtonMapping : "";
    }

    public boolean isGamepadEnabled() {
        return gamepadEnabled;
    }

    public void setGamepadEnabled(boolean gamepadEnabled) {
        this.gamepadEnabled = gamepadEnabled;
    }

    public String getSelectedGamepadName() {
        return selectedGamepadName;
    }

    public void setSelectedGamepadName(String selectedGamepadName) {
        this.selectedGamepadName = selectedGamepadName != null ? selectedGamepadName : "";
    }
}
