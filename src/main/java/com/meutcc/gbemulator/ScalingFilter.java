package com.meutcc.gbemulator;

import java.awt.RenderingHints;

/**
 * Representa os diferentes algoritmos de escalonamento de imagem disponíveis.
 * Cada filtro define como a imagem de 160x144 pixels será ampliada para a tela.
 */
public enum ScalingFilter {
    /**
     * Nearest Neighbor - Mantém pixels quadrados nítidos (padrão para pixel art)
     */
    NEAREST_NEIGHBOR("Nearest Neighbor (Sharp)", 
        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR),
    
    /**
     * Bilinear - Suavização linear básica
     */
    BILINEAR("Bilinear (Smooth)", 
        RenderingHints.VALUE_INTERPOLATION_BILINEAR),
    
    /**
     * Bicubic - Suavização de alta qualidade
     */
    BICUBIC("Bicubic (High Quality)", 
        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    
    private final String displayName;
    private final Object renderingHintValue;
    
    ScalingFilter(String displayName, Object renderingHintValue) {
        this.displayName = displayName;
        this.renderingHintValue = renderingHintValue;
    }
    
    /**
     * Retorna o nome de exibição do filtro
     */
    public String getDisplayName() {
        return displayName;
    }
    
    /**
     * Retorna o valor de RenderingHint correspondente
     */
    public Object getRenderingHintValue() {
        return renderingHintValue;
    }
    
    @Override
    public String toString() {
        return displayName;
    }
}
