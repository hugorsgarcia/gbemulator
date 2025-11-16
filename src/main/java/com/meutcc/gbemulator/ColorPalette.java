package com.meutcc.gbemulator;

/**
 * Representa as diferentes paletas de cores disponíveis para o emulador.
 * Cada paleta contém 4 cores que representam os 4 tons de cinza do Game Boy original.
 */
public enum ColorPalette {
    /**
     * Paleta clássica do Game Boy DMG (verde)
     */
    DMG_GREEN("DMG Classic", new int[] {
        0xFFE0F8D0,  // Branco esverdeado
        0xFF88C070,  // Verde claro
        0xFF346856,  // Verde escuro
        0xFF081820   // Preto esverdeado
    }),
    
    /**
     * Paleta em tons de cinza (Game Boy Pocket/Light)
     */
    GRAYSCALE("Grayscale", new int[] {
        0xFFFFFFFF,  // Branco
        0xFFAAAAAA,  // Cinza claro
        0xFF555555,  // Cinza escuro
        0xFF000000   // Preto
    }),
    
    /**
     * Paleta em tons de cinza suave
     */
    SOFT_GRAYSCALE("Soft Grayscale", new int[] {
        0xFFF8F8F8,  // Branco suave
        0xFFB0B0B0,  // Cinza claro
        0xFF606060,  // Cinza médio
        0xFF101010   // Preto suave
    }),
    
    /**
     * Paleta verde alternativa (mais vibrante)
     */
    GREEN_VIBRANT("Green Vibrant", new int[] {
        0xFFD0F8E0,  // Verde muito claro
        0xFF70C088,  // Verde brilhante
        0xFF2E6856,  // Verde intenso
        0xFF082018   // Verde escuro
    }),
    
    /**
     * Paleta âmbar (estilo terminal antigo)
     */
    AMBER("Amber", new int[] {
        0xFFFFD977,  // Âmbar claro
        0xFFFFAA00,  // Âmbar médio
        0xFFCC6600,  // Âmbar escuro
        0xFF663300   // Marrom escuro
    }),
    
    /**
     * Paleta azul (estilo terminal IBM)
     */
    BLUE("Blue", new int[] {
        0xFFE0F0FF,  // Azul muito claro
        0xFF7799DD,  // Azul claro
        0xFF334488,  // Azul escuro
        0xFF001133   // Azul muito escuro
    }),
    
    /**
     * Paleta sépia (estilo antigo)
     */
    SEPIA("Sepia", new int[] {
        0xFFFFF8E8,  // Creme
        0xFFD0B090,  // Bege
        0xFF907858,  // Marrom claro
        0xFF483020   // Marrom escuro
    }),
    
    /**
     * Paleta personalizada (pode ser modificada pelo usuário)
     */
    CUSTOM("Custom", new int[] {
        0xFFFFFFFF,
        0xFFAAAAAA,
        0xFF555555,
        0xFF000000
    });
    
    private final String displayName;
    private int[] colors;
    
    ColorPalette(String displayName, int[] colors) {
        this.displayName = displayName;
        this.colors = colors.clone();
    }
    
    /**
     * Retorna o nome de exibição da paleta
     */
    public String getDisplayName() {
        return displayName;
    }
    
    /**
     * Retorna o array de cores (4 cores)
     */
    public int[] getColors() {
        return colors.clone();
    }
    
    /**
     * Define as cores da paleta (apenas para CUSTOM)
     */
    public void setColors(int[] newColors) {
        if (this == CUSTOM && newColors != null && newColors.length == 4) {
            this.colors = newColors.clone();
        }
    }
    
    /**
     * Retorna uma cor específica da paleta (índice 0-3)
     */
    public int getColor(int index) {
        if (index >= 0 && index < 4) {
            return colors[index];
        }
        return colors[0]; // Retorna cor padrão se índice inválido
    }
    
    @Override
    public String toString() {
        return displayName;
    }
}
