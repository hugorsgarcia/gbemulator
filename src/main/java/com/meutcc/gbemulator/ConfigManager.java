package com.meutcc.gbemulator;

import java.io.*;
import java.util.Properties;

/**
 * Gerencia o salvamento e carregamento de configurações do emulador.
 * Usa java.util.Properties para persistir configurações em arquivo.
 */
public class ConfigManager {

    private static final String CONFIG_FILENAME = "config.properties";
    private static final String CONFIG_DIR_FALLBACK = ".gbemulator";

    private final EmulatorConfig config;
    private File configFile;

    public ConfigManager() {
        this.config = new EmulatorConfig();
        this.configFile = determineConfigFilePath();
    }

    /**
     * Determina o caminho do arquivo de configuração.
     * Estratégia: tenta pasta do executável, senão usa home do usuário.
     */
    private File determineConfigFilePath() {
        try {
            // Tenta obter o diretório do JAR/executável
            File jarFile = new File(ConfigManager.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());

            File configDir;
            if (jarFile.isFile()) {
                // Estamos executando de um JAR, usa o diretório pai
                configDir = jarFile.getParentFile();
            } else {
                // Estamos executando de classes (dev mode), usa diretório de trabalho
                configDir = new File(System.getProperty("user.dir"));
            }

            File config = new File(configDir, CONFIG_FILENAME);

            // Testa se podemos escrever no diretório
            if (configDir.canWrite() || !config.exists()) {
                return config;
            }
        } catch (Exception e) {
            System.err.println("Não foi possível determinar diretório do JAR: " + e.getMessage());
        }

        // Fallback: usa diretório home do usuário
        String userHome = System.getProperty("user.home");
        File fallbackDir = new File(userHome, CONFIG_DIR_FALLBACK);
        if (!fallbackDir.exists()) {
            fallbackDir.mkdirs();
        }

        System.out.println("Usando diretório alternativo para configurações: " + fallbackDir.getAbsolutePath());
        return new File(fallbackDir, CONFIG_FILENAME);
    }

    /**
     * Carrega as configurações do arquivo.
     * Se o arquivo não existe, usa valores padrão.
     */
    public void loadConfig() {
        if (!configFile.exists()) {
            System.out.println("Arquivo de configuração não encontrado. Usando valores padrão.");
            return;
        }

        Properties props = new Properties();

        try (FileInputStream fis = new FileInputStream(configFile)) {
            props.load(fis);

            // Carregar configurações de janela
            config.setWindowScale(getIntProperty(props, "window.scale", 3));

            // Carregar configurações de áudio
            config.setAudioEnabled(getBooleanProperty(props, "audio.enabled", true));

            // Carregar configurações de ROM
            config.setLastRomDirectory(props.getProperty("rom.lastDirectory", ""));
            config.setLastRomFile(props.getProperty("rom.lastFile", ""));

            // Carregar configurações de vídeo
            config.setColorPalette(props.getProperty("video.colorPalette", "DMG_GREEN"));
            config.setScalingFilter(props.getProperty("video.scalingFilter", "NEAREST_NEIGHBOR"));

            // Carregar configurações de efeitos
            config.setGhostingEnabled(getBooleanProperty(props, "effects.ghosting.enabled", false));
            config.setGhostingIntensity(getFloatProperty(props, "effects.ghosting.intensity", 0.3f));
            config.setGridEnabled(getBooleanProperty(props, "effects.grid.enabled", false));
            config.setGridIntensity(getFloatProperty(props, "effects.grid.intensity", 0.15f));
            config.setScanlinesEnabled(getBooleanProperty(props, "effects.scanlines.enabled", false));
            config.setScanlineIntensity(getFloatProperty(props, "effects.scanlines.intensity", 0.2f));

            // Carregar configurações de gamepad
            config.setGamepadEnabled(getBooleanProperty(props, "gamepad.enabled", false));
            config.setSelectedGamepadName(props.getProperty("gamepad.selectedName", ""));
            config.setGamepadButtonMapping(props.getProperty("gamepad.buttonMapping", ""));

            System.out.println("Configurações carregadas de: " + configFile.getAbsolutePath());

        } catch (IOException e) {
            System.err.println("Erro ao carregar configurações: " + e.getMessage());
        }
    }

    /**
     * Salva as configurações no arquivo.
     */
    public void saveConfig() {
        Properties props = new Properties();

        // Salvar configurações de janela
        props.setProperty("window.scale", String.valueOf(config.getWindowScale()));

        // Salvar configurações de áudio
        props.setProperty("audio.enabled", String.valueOf(config.isAudioEnabled()));

        // Salvar configurações de ROM
        props.setProperty("rom.lastDirectory", config.getLastRomDirectory());
        props.setProperty("rom.lastFile", config.getLastRomFile());

        // Salvar configurações de vídeo
        props.setProperty("video.colorPalette", config.getColorPalette());
        props.setProperty("video.scalingFilter", config.getScalingFilter());

        // Salvar configurações de efeitos
        props.setProperty("effects.ghosting.enabled", String.valueOf(config.isGhostingEnabled()));
        props.setProperty("effects.ghosting.intensity", String.valueOf(config.getGhostingIntensity()));
        props.setProperty("effects.grid.enabled", String.valueOf(config.isGridEnabled()));
        props.setProperty("effects.grid.intensity", String.valueOf(config.getGridIntensity()));
        props.setProperty("effects.scanlines.enabled", String.valueOf(config.isScanlinesEnabled()));
        props.setProperty("effects.scanlines.intensity", String.valueOf(config.getScanlineIntensity()));

        // Salvar configurações de gamepad
        props.setProperty("gamepad.enabled", String.valueOf(config.isGamepadEnabled()));
        props.setProperty("gamepad.selectedName", config.getSelectedGamepadName());
        props.setProperty("gamepad.buttonMapping", config.getGamepadButtonMapping());

        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            props.store(fos, "Game Boy Emulator Configuration");
            System.out.println("Configurações salvas em: " + configFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("Erro ao salvar configurações: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Retorna o objeto de configuração.
     */
    public EmulatorConfig getConfig() {
        return config;
    }

    /**
     * Retorna o caminho do arquivo de configuração.
     */
    public String getConfigFilePath() {
        return configFile.getAbsolutePath();
    }

    // Métodos auxiliares para parsing

    private int getIntProperty(Properties props, String key, int defaultValue) {
        try {
            String value = props.getProperty(key);
            if (value != null) {
                return Integer.parseInt(value);
            }
        } catch (NumberFormatException e) {
            System.err.println("Valor inválido para " + key + ", usando padrão: " + defaultValue);
        }
        return defaultValue;
    }

    private float getFloatProperty(Properties props, String key, float defaultValue) {
        try {
            String value = props.getProperty(key);
            if (value != null) {
                return Float.parseFloat(value);
            }
        } catch (NumberFormatException e) {
            System.err.println("Valor inválido para " + key + ", usando padrão: " + defaultValue);
        }
        return defaultValue;
    }

    private boolean getBooleanProperty(Properties props, String key, boolean defaultValue) {
        String value = props.getProperty(key);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return defaultValue;
    }
}
