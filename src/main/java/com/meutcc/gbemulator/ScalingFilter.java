package com.meutcc.gbemulator;

import java.awt.RenderingHints;

public enum ScalingFilter {
    
    NEAREST_NEIGHBOR("Nearest Neighbor (Sharp)", 
        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR),
    
    
    BILINEAR("Bilinear (Smooth)", 
        RenderingHints.VALUE_INTERPOLATION_BILINEAR),
    
    
    BICUBIC("Bicubic (High Quality)", 
        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    
    private final String displayName;
    private final Object renderingHintValue;
    
    ScalingFilter(String displayName, Object renderingHintValue) {
        this.displayName = displayName;
        this.renderingHintValue = renderingHintValue;
    }
    
   
    public String getDisplayName() {
        return displayName;
    }
    
    
    public Object getRenderingHintValue() {
        return renderingHintValue;
    }
    
    @Override
    public String toString() {
        return displayName;
    }
}
