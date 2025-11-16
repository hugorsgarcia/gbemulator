package com.meutcc.gbemulator;

import java.util.*;

public class PPU {
    private static class PixelInfo {
        int colorIndex;      
        boolean fromSprite;  
        boolean spritePriority; 
        int paletteRegister; 
        
        PixelInfo(int colorIndex, boolean fromSprite, boolean spritePriority, int paletteRegister) {
            this.colorIndex = colorIndex;
            this.fromSprite = fromSprite;
            this.spritePriority = spritePriority;
            this.paletteRegister = paletteRegister;
        }
        
        PixelInfo() {
            this(0, false, false, 0);
        }
    }

    private static class VisibleSprite {
        int spriteIndex;    
        int spriteX;       
        int spriteY;        
        int tileIndex;     
        int attributes;     
        
        VisibleSprite(int spriteIndex, int spriteX, int spriteY, int tileIndex, int attributes) {
            this.spriteIndex = spriteIndex;
            this.spriteX = spriteX;
            this.spriteY = spriteY;
            this.tileIndex = tileIndex;
            this.attributes = attributes;
        }
    }

    public static final int SCREEN_WIDTH = 160;
    public static final int SCREEN_HEIGHT = 144;
    
    public static final int MODE_2_CYCLES = 80;     
    public static final int MODE_3_BASE_CYCLES = 172; 
    public static final int MODE_3_MAX_CYCLES = 289;  
    public static final int SCANLINE_CYCLES = 456;   
    public static final int VBLANK_LINES = 10;       
    public static final int TOTAL_LINES = 154;       

    private final byte[] vram = new byte[8192]; 
    private final byte[] oam = new byte[160];   

    private final int[] screenBuffer = new int[SCREEN_WIDTH * SCREEN_HEIGHT];
    private boolean frameCompleted = false;

    private ColorPalette currentPalette = ColorPalette.DMG_GREEN;
    
    private int[] getColors() {
        return currentPalette.getColors();
    }

    private int lcdc;  
    private int stat;  
    private int scy;   
    private int scx;   
    private int ly;    
    private int lyc;   
    private int bgp;  
    private int obp0; 
    private int obp1;  
    private int wy;   
    private int wx;   

    private int ppuMode; 
    private int cyclesCounter; 
    private int scanlineCycles;
    private boolean statInterruptLine; 
    private int mode3Duration; 
    private int windowLineCounter;
    
    private boolean previousLycEqualsLy;
    private boolean lycComparisonThisCycle; 
    
    private int pixelX; 
    private boolean enablePixelFifo; 
    private PixelInfo[] currentScanlineBuffer; 

    private MMU mmu;

    public PPU() {
        reset();
    }

    public void setMmu(MMU mmu) {
        this.mmu = mmu;
    }

    public void reset() {
        Arrays.fill(vram, (byte) 0);
        Arrays.fill(oam, (byte) 0);
        Arrays.fill(screenBuffer, getColors()[0]);
        frameCompleted = false;

        lcdc = 0x91; 
        stat = 0x85; 
        scy = 0;
        scx = 0;
        ly = 0;
        lyc = 0;
        bgp = 0xFC;  
        obp0 = 0xFF; 
        obp1 = 0xFF;
        wy = 0;
        wx = 0;

        ppuMode = 2; 
        cyclesCounter = 0;
        scanlineCycles = 0;
        statInterruptLine = false;
        mode3Duration = MODE_3_BASE_CYCLES;
        windowLineCounter = 0; 
        previousLycEqualsLy = false; 
        lycComparisonThisCycle = false;
        
        pixelX = 0;
        enablePixelFifo = false;
        currentScanlineBuffer = new PixelInfo[SCREEN_WIDTH];
        for (int i = 0; i < SCREEN_WIDTH; i++) {
            currentScanlineBuffer[i] = new PixelInfo();
        }
        
        System.out.println("PPU reset.");
    }

    public void update(int cpuCycles) {
        if (!isLcdEnabled()) {
            handleLcdDisabled();
            return;
        }

        for (int i = 0; i < cpuCycles; i++) {
            updateSingleCycle();
        }
    }
    
    private void updateSingleCycle() {
        scanlineCycles++;
        cyclesCounter++;
        
        switch (ppuMode) {
            case 2: 
                updateOamScanMode();
                break;
            case 3: 
                updateDrawingMode();
                break;
            case 0: 
                updateHBlankMode();
                break;
            case 1: 
                updateVBlankMode();
                break;
        }
        
        updateStatInterrupts();
    }
    
    private void handleLcdDisabled() {
        ly = 0;
        ppuMode = 0; 
        cyclesCounter = 0;
        scanlineCycles = 0;
        statInterruptLine = false;
        previousLycEqualsLy = false;
        
        if (mmu != null) {
            mmu.writeByte(MMU.REG_LY, ly);
        }
        updateStatRegister();
    }
    
    private void updateOamScanMode() {
        if (cyclesCounter == 4) {
            lycComparisonThisCycle = true;
        }
        
        if (cyclesCounter >= MODE_2_CYCLES) {
            cyclesCounter = 0;
            pixelX = 0; 
            ppuMode = 3;
            lycComparisonThisCycle = false;
            
            calculateMode3Duration();
            updateStatRegister();
        }
    }
    
    private void updateDrawingMode() {
        if (enablePixelFifo) {
            if (cyclesCounter % 4 == 0 && pixelX < SCREEN_WIDTH) {
                renderSinglePixel(pixelX);
                pixelX++;
            }
            
            if (cyclesCounter >= mode3Duration) {
                transferScanlineToScreen();
                cyclesCounter = 0;
                pixelX = 0;
                ppuMode = 0;
                updateStatRegister();
            }
        } else {
            if (cyclesCounter == 1) { 
                if (ly < SCREEN_HEIGHT) {
                    renderScanline();
                }
            }
            
            if (cyclesCounter >= mode3Duration) {
                cyclesCounter = 0;
                ppuMode = 0;
                updateStatRegister();
            }
        }
    }
    
    private void updateHBlankMode() {
        if (scanlineCycles >= SCANLINE_CYCLES) {
            scanlineCycles = 0;
            cyclesCounter = 0;
            ly++;
            
            lycComparisonThisCycle = false;
            
            if (mmu != null) {
                mmu.writeByte(MMU.REG_LY, ly);
            }
            
            if (ly == SCREEN_HEIGHT) {
                ppuMode = 1;
                frameCompleted = true;
                
                requestVBlankInterrupt();
            } else {
                ppuMode = 2;
            }
            
            updateStatRegister();
        }
    }
    
    private void updateVBlankMode() {
        if (ly == 153 && scanlineCycles == 4) {
            ly = 0;
            lycComparisonThisCycle = true; 
            
            if (mmu != null) {
                mmu.writeByte(MMU.REG_LY, ly);
            }
        }
        
        if (scanlineCycles >= SCANLINE_CYCLES) {
            scanlineCycles = 0;
            cyclesCounter = 0;
            
            if (ly != 0) {
                ly++;
                lycComparisonThisCycle = false;
                
                if (mmu != null) {
                    mmu.writeByte(MMU.REG_LY, ly);
                }
            }
            
            if (ly >= TOTAL_LINES) {
                ly = 0;
                ppuMode = 2;
                windowLineCounter = 0; 
                lycComparisonThisCycle = false;
                
                if (mmu != null) {
                    mmu.writeByte(MMU.REG_LY, ly);
                }
            } else if (ly == 0) {
                ppuMode = 2;
                windowLineCounter = 0;
            }
            
            updateStatRegister();
        }
    }
    
    private void calculateMode3Duration() {
        int duration = MODE_3_BASE_CYCLES; // 172 cycles
        
        if (isSpriteDisplayEnabled()) {
            int visibleSprites = countSpritesOnScanline();
            duration += visibleSprites * 11;
        }
        
        duration += (scx % 8);
        
        if (isWindowDisplayEnabled() && ly >= wy && wx >= 0 && wx <= 166) {
            int actualWX = wx - 7;
            if (actualWX > 0 && actualWX < SCREEN_WIDTH) {
                duration += 6;
            }
        }
        
        mode3Duration = Math.min(duration, MODE_3_MAX_CYCLES);
    }
    
    private void renderSinglePixel(int x) {
        if (x < 0 || x >= SCREEN_WIDTH) return;
        
        PixelInfo pixel = new PixelInfo(0, false, false, bgp);
        
        if (isBgWindowDisplayEnabled()) {
            pixel = getBackgroundPixel(x, ly);
        }
        
        if (isWindowDisplayEnabled() && isBgWindowDisplayEnabled()) {
            int actualWX = wx - 7;
            if (x >= actualWX && ly >= wy) {
                pixel = getWindowPixel(x, ly);
            }
        }
        
        if (isSpriteDisplayEnabled()) {
            PixelInfo spritePixel = getSpritePixel(x, ly);
            if (spritePixel != null) {
                boolean shouldDraw = true;
                
                if (spritePixel.spritePriority && isBgWindowDisplayEnabled() && pixel.colorIndex != 0) {
                    shouldDraw = false; 
                }
                
                if (shouldDraw) {
                    pixel = spritePixel;
                }
            }
        }
        
        currentScanlineBuffer[x] = pixel;
    }
    
    private PixelInfo getBackgroundPixel(int x, int y) {
        int tileDataArea = (lcdc & 0x10) != 0 ? 0x8000 : 0x8800;
        boolean signedAddressing = (tileDataArea == 0x8800);
        int tileMapArea = (lcdc & 0x08) != 0 ? 0x9C00 : 0x9800;

        int yInBgMap = (y + scy) & 0xFF;
        int tileRowInMap = yInBgMap / 8;
        int yInTile = yInBgMap % 8;
        
        int xInBgMap = (x + scx) & 0xFF;
        int tileColInMap = xInBgMap / 8;
        int xInTile = xInBgMap % 8;

        int tileMapOffset = tileRowInMap * 32 + tileColInMap;
        int tileIndexAddress = tileMapArea + tileMapOffset;
        
        if (tileIndexAddress >= 0xA000) {
            tileIndexAddress = tileMapArea + (tileMapOffset & 0x3FF);
        }
        
        int tileIndex = vram[tileIndexAddress - 0x8000] & 0xFF;

        int tileAddress;
        if (signedAddressing) {
            tileAddress = 0x9000 + ((byte)tileIndex * 16);
        } else {
            tileAddress = tileDataArea + (tileIndex * 16);
        }

        int tileRowDataAddress = tileAddress + (yInTile * 2);
        
        if (tileRowDataAddress < 0x8000 || tileRowDataAddress + 1 >= 0xA000) {
            return new PixelInfo(0, false, false, bgp);
        }

        int lsb = vram[tileRowDataAddress - 0x8000] & 0xFF;
        int msb = vram[tileRowDataAddress + 1 - 0x8000] & 0xFF;

        int bitPosition = 7 - xInTile;
        int colorBit0 = (lsb >> bitPosition) & 1;
        int colorBit1 = (msb >> bitPosition) & 1;
        int colorIndex = (colorBit1 << 1) | colorBit0;

        return new PixelInfo(colorIndex, false, false, bgp);
    }
    
    private PixelInfo getWindowPixel(int x, int y) {
        int actualWX = wx - 7;
        if (x < actualWX || y < wy) return null;
        
        int tileDataArea = (lcdc & 0x10) != 0 ? 0x8000 : 0x8800;
        boolean signedAddressing = (tileDataArea == 0x8800);
        int tileMapArea = (lcdc & 0x40) != 0 ? 0x9C00 : 0x9800;

        int yInWindow = windowLineCounter;
        int tileRowInMap = yInWindow / 8;
        int yInTile = yInWindow % 8;
        
        int xInWindow = x - actualWX;
        int tileColInMap = xInWindow / 8;
        int xInTile = xInWindow % 8;

        if (tileColInMap >= 32) return null;

        int tileMapOffset = tileRowInMap * 32 + tileColInMap;
        int tileIndexAddress = tileMapArea + tileMapOffset;

        if (tileIndexAddress < 0x8000 || tileIndexAddress >= 0xA000) return null;
        int tileIndex = vram[tileIndexAddress - 0x8000] & 0xFF;

        int tileAddress;
        if (signedAddressing) {
            tileAddress = 0x9000 + ((byte)tileIndex * 16);
        } else {
            tileAddress = tileDataArea + (tileIndex * 16);
        }

        int tileRowDataAddress = tileAddress + (yInTile * 2);
        if (tileRowDataAddress < 0x8000 || tileRowDataAddress + 1 >= 0xA000) return null;

        int lsb = vram[tileRowDataAddress - 0x8000] & 0xFF;
        int msb = vram[tileRowDataAddress + 1 - 0x8000] & 0xFF;

        int bitPosition = 7 - xInTile;
        int colorBit0 = (lsb >> bitPosition) & 1;
        int colorBit1 = (msb >> bitPosition) & 1;
        int colorIndex = (colorBit1 << 1) | colorBit0;

        return new PixelInfo(colorIndex, false, false, bgp);
    }
    
    private PixelInfo getSpritePixel(int x, int y) {
        boolean tallSprites = (lcdc & 0x04) != 0;
        int spriteHeight = tallSprites ? 16 : 8;

        VisibleSprite highestPrioritySprite = null;

        for (int i = 0; i < 40; i++) {
            int oamAddr = i * 4;

            int spriteY = (oam[oamAddr] & 0xFF) - 16;
            int spriteX = (oam[oamAddr + 1] & 0xFF) - 8;
            int tileIndex = oam[oamAddr + 2] & 0xFF;
            int attributes = oam[oamAddr + 3] & 0xFF;

            if (y >= spriteY && y < (spriteY + spriteHeight) &&
                x >= spriteX && x < (spriteX + 8)) {
                
                if (highestPrioritySprite == null || 
                    spriteX < highestPrioritySprite.spriteX ||
                    (spriteX == highestPrioritySprite.spriteX && i < highestPrioritySprite.spriteIndex)) {
                    highestPrioritySprite = new VisibleSprite(i, spriteX, spriteY, tileIndex, attributes);
                }
            }
        }

        if (highestPrioritySprite == null) {
            return null;
        }

        boolean yFlip = (highestPrioritySprite.attributes & 0x40) != 0;
        boolean xFlip = (highestPrioritySprite.attributes & 0x20) != 0;
        boolean bgPriority = (highestPrioritySprite.attributes & 0x80) != 0;
        int paletteReg = (highestPrioritySprite.attributes & 0x10) != 0 ? obp1 : obp0;

        int tileIndex = highestPrioritySprite.tileIndex;
        if (tallSprites) {
            tileIndex &= 0xFE;
        }

        int yInSpriteTile = y - highestPrioritySprite.spriteY;
        if (yFlip) {
            yInSpriteTile = (spriteHeight - 1) - yInSpriteTile;
        }

        if (tallSprites && yInSpriteTile >= 8) {
            tileIndex |= 0x01;
            yInSpriteTile -= 8;
        }

        int tileAddress = 0x8000 + (tileIndex * 16);
        int tileRowDataAddress = tileAddress + (yInSpriteTile * 2);

        if (tileRowDataAddress < 0x8000 || tileRowDataAddress + 1 >= 0xA000) {
            return null;
        }

        int lsb = vram[tileRowDataAddress - 0x8000] & 0xFF;
        int msb = vram[tileRowDataAddress + 1 - 0x8000] & 0xFF;

        int px = x - highestPrioritySprite.spriteX;
        int xInTilePixel = xFlip ? (7 - px) : px;

        int bitPosition = 7 - xInTilePixel;
        int colorBit0 = (lsb >> bitPosition) & 1;
        int colorBit1 = (msb >> bitPosition) & 1;
        int colorIndex = (colorBit1 << 1) | colorBit0;

        if (colorIndex == 0) return null; 

        return new PixelInfo(colorIndex, true, bgPriority, paletteReg);
    }
    
    private void transferScanlineToScreen() {
        int baseIndex = ly * SCREEN_WIDTH;
        for (int x = 0; x < SCREEN_WIDTH; x++) {
            if (baseIndex + x < screenBuffer.length) {
                PixelInfo pixel = currentScanlineBuffer[x];
                screenBuffer[baseIndex + x] = getColorFromPalette(pixel.colorIndex, pixel.paletteRegister);
            }
        }
    }
    
    private int countSpritesOnScanline() {
        if (!isSpriteDisplayEnabled()) return 0;
        
        int spriteHeight = isSpriteSize8x16() ? 16 : 8;
        int count = 0;
        
        for (int i = 0; i < 40 && count < 10; i++) {
            int spriteY = (oam[i * 4] & 0xFF) - 16;
            
            if (ly >= spriteY && ly < spriteY + spriteHeight) {
                count++;
            }
        }
        
        return count;
    }
    
    private void updateStatInterrupts() {
        boolean newStatLine = false;
        
        if ((stat & 0x08) != 0 && ppuMode == 0) { 
            newStatLine = true;
        }
        
        if ((stat & 0x10) != 0 && ppuMode == 1) { 
            newStatLine = true;
        }
        
        if ((stat & 0x20) != 0 && ppuMode == 2) { 
            newStatLine = true;
        }
        
        boolean lycEqualsLy = (ly == lyc);
        
        if (lycEqualsLy) {
            stat |= 0x04; 
        } else {
            stat &= ~0x04; 
        }
        
        if ((stat & 0x40) != 0 && lycEqualsLy) {
            if (lycComparisonThisCycle || (!previousLycEqualsLy && lycEqualsLy)) {
                newStatLine = true;
            }
        }
        
        previousLycEqualsLy = lycEqualsLy;
        
        lycComparisonThisCycle = false;
        
        if (newStatLine && !statInterruptLine) {
            requestLcdStatInterrupt();
        }
        
        statInterruptLine = newStatLine;
        updateStatRegister();
    }
    
    private void requestVBlankInterrupt() {
        if (mmu != null) {
            byte currentIF = (byte) mmu.readByte(MMU.REG_IF);
            mmu.writeByte(MMU.REG_IF, (byte) (currentIF | 0x01)); 
        }
    }

    private void requestLcdStatInterrupt() {
        if (mmu != null) {
            byte currentIF = (byte) mmu.readByte(MMU.REG_IF);
            mmu.writeByte(MMU.REG_IF, (byte) (currentIF | 0x02)); 
        }
    }

    private void updateStatRegister() {
        stat = (stat & 0xF8) | (ppuMode & 0x03) | (stat & 0x04);
        if (mmu != null) {
            mmu.writeByte(MMU.REG_STAT, stat);
        }
    }


    private void renderScanline() {
        if (!isLcdEnabled()) return;

        PixelInfo[] scanlinePixelInfo = new PixelInfo[SCREEN_WIDTH]; 
        
        for (int x = 0; x < SCREEN_WIDTH; x++) {
            scanlinePixelInfo[x] = new PixelInfo(0, false, false, bgp);
        }

        if (isBgWindowDisplayEnabled()) { 
            renderBackgroundScanlineWithInfo(scanlinePixelInfo);
        }

        if (isWindowDisplayEnabled() && isBgWindowDisplayEnabled()) {
            renderWindowScanlineWithInfo(scanlinePixelInfo);
        }

        if (isSpriteDisplayEnabled()) { // Bit 1 do LCDC
            renderSpritesScanlineWithInfo(scanlinePixelInfo);
        }

        int baseIndex = ly * SCREEN_WIDTH;
        for (int x = 0; x < SCREEN_WIDTH; x++) {
            if (baseIndex + x < screenBuffer.length) { 
                PixelInfo pixel = scanlinePixelInfo[x];
                screenBuffer[baseIndex + x] = getColorFromPalette(pixel.colorIndex, pixel.paletteRegister);
            }
        }
    }

    private void renderBackgroundScanlineWithInfo(PixelInfo[] scanlinePixelInfo) {
        int tileDataArea = (lcdc & 0x10) != 0 ? 0x8000 : 0x8800;
        boolean signedAddressing = (tileDataArea == 0x8800);
        int tileMapArea = (lcdc & 0x08) != 0 ? 0x9C00 : 0x9800;

        int yInBgMap = (ly + scy) & 0xFF; 
        int tileRowInMap = yInBgMap / 8;  
        int yInTile = yInBgMap % 8;       

        for (int x = 0; x < SCREEN_WIDTH; x++) {
            int xInBgMap = (x + scx) & 0xFF; 
            int tileColInMap = xInBgMap / 8;  
            int xInTile = xInBgMap % 8;       

            int tileMapOffset = tileRowInMap * 32 + tileColInMap;
            int tileIndexAddress = tileMapArea + tileMapOffset;
            
            if (tileIndexAddress >= 0xA000) {
                tileIndexAddress = tileMapArea + (tileMapOffset & 0x3FF); 
            }
            
            int tileIndex = vram[tileIndexAddress - 0x8000] & 0xFF;

            int tileAddress;
            if (signedAddressing) {
                tileAddress = 0x9000 + ((byte)tileIndex * 16);
            } else {
                tileAddress = tileDataArea + (tileIndex * 16);
            }

            int tileRowDataAddress = tileAddress + (yInTile * 2);
            
            if (tileRowDataAddress < 0x8000 || tileRowDataAddress + 1 >= 0xA000) {
                scanlinePixelInfo[x] = new PixelInfo(0, false, false, bgp);
                continue;
            }

            int lsb = vram[tileRowDataAddress - 0x8000] & 0xFF;
            int msb = vram[tileRowDataAddress + 1 - 0x8000] & 0xFF;

            int bitPosition = 7 - xInTile;
            int colorBit0 = (lsb >> bitPosition) & 1;
            int colorBit1 = (msb >> bitPosition) & 1;
            int colorIndex = (colorBit1 << 1) | colorBit0;

            scanlinePixelInfo[x] = new PixelInfo(colorIndex, false, false, bgp);
        }
    }

    private void renderWindowScanlineWithInfo(PixelInfo[] scanlinePixelInfo) {
        if (ly < wy) return;

        int tileDataArea = (lcdc & 0x10) != 0 ? 0x8000 : 0x8800;
        boolean signedAddressing = (tileDataArea == 0x8800);
        int tileMapArea = (lcdc & 0x40) != 0 ? 0x9C00 : 0x9800;

        int actualWX = wx - 7;

        boolean windowRenderedThisLine = false;

        int yInWindow = windowLineCounter;
        int tileRowInMap = yInWindow / 8;
        int yInTile = yInWindow % 8;

        for (int x = 0; x < SCREEN_WIDTH; x++) {
            if (x < actualWX) continue;

            windowRenderedThisLine = true; 

            int xInWindow = x - actualWX; 
            int tileColInMap = xInWindow / 8;
            int xInTile = xInWindow % 8;

            if (tileColInMap >= 32) continue;

            int tileMapOffset = tileRowInMap * 32 + tileColInMap;
            int tileIndexAddress = tileMapArea + tileMapOffset;

            if (tileIndexAddress < 0x8000 || tileIndexAddress >= 0xA000) continue;
            int tileIndex = vram[tileIndexAddress - 0x8000] & 0xFF;

            int tileAddress;
            if (signedAddressing) {
                tileAddress = 0x9000 + ((byte)tileIndex * 16);
            } else {
                tileAddress = tileDataArea + (tileIndex * 16);
            }

            int tileRowDataAddress = tileAddress + (yInTile * 2);
            if (tileRowDataAddress < 0x8000 || tileRowDataAddress +1 >= 0xA000) continue;

            int lsb = vram[tileRowDataAddress - 0x8000] & 0xFF;
            int msb = vram[tileRowDataAddress + 1 - 0x8000] & 0xFF;

            int bitPosition = 7 - xInTile;
            int colorBit0 = (lsb >> bitPosition) & 1;
            int colorBit1 = (msb >> bitPosition) & 1;
            int colorIndex = (colorBit1 << 1) | colorBit0;

            scanlinePixelInfo[x] = new PixelInfo(colorIndex, false, false, bgp); 
        }

        if (windowRenderedThisLine) {
            windowLineCounter++;
        }
    }

    private void renderSpritesScanlineWithInfo(PixelInfo[] scanlinePixelInfo) {
        if (!isSpriteDisplayEnabled()) return; 
        
        boolean tallSprites = (lcdc & 0x04) != 0;
        int spriteHeight = tallSprites ? 16 : 8;

        List<VisibleSprite> visibleSprites = new ArrayList<>();

        for (int i = 0; i < 40; i++) {
            int oamAddr = i * 4; 

            int spriteY = (oam[oamAddr] & 0xFF) - 16; 
            int spriteX = (oam[oamAddr + 1] & 0xFF) - 8; 
            int tileIndex = oam[oamAddr + 2] & 0xFF;
            int attributes = oam[oamAddr + 3] & 0xFF;

            if (ly >= spriteY && ly < (spriteY + spriteHeight)) {
                visibleSprites.add(new VisibleSprite(i, spriteX, spriteY, tileIndex, attributes));
                
                if (visibleSprites.size() >= 10) break;
            }
        }

       
        visibleSprites.sort((a, b) -> {
            if (a.spriteX != b.spriteX) {
                return Integer.compare(b.spriteX, a.spriteX); 
            }
            return Integer.compare(b.spriteIndex, a.spriteIndex); 
        });

        for (VisibleSprite sprite : visibleSprites) {
            boolean yFlip = (sprite.attributes & 0x40) != 0;
            boolean xFlip = (sprite.attributes & 0x20) != 0;
            boolean bgPriority = (sprite.attributes & 0x80) != 0; 
            int paletteReg = (sprite.attributes & 0x10) != 0 ? obp1 : obp0;

            int tileIndex = sprite.tileIndex;
            if (tallSprites) {
                tileIndex &= 0xFE; 
            }

            int yInSpriteTile = ly - sprite.spriteY;
            if (yFlip) {
                yInSpriteTile = (spriteHeight - 1) - yInSpriteTile;
            }

            if (tallSprites && yInSpriteTile >= 8) {
                tileIndex |= 0x01; 
                yInSpriteTile -= 8;  
            }

            int tileAddress = 0x8000 + (tileIndex * 16); 
            int tileRowDataAddress = tileAddress + (yInSpriteTile * 2);

            if (tileRowDataAddress < 0x8000 || tileRowDataAddress + 1 >= 0xA000) continue;

            int lsb = vram[tileRowDataAddress - 0x8000] & 0xFF;
            int msb = vram[tileRowDataAddress + 1 - 0x8000] & 0xFF;

            for (int px = 0; px < 8; px++) {
                int screenX = sprite.spriteX + px;
                if (screenX >= 0 && screenX < SCREEN_WIDTH) { 
                    int xInTilePixel = xFlip ? (7 - px) : px;

                    int bitPosition = 7 - xInTilePixel;
                    int colorBit0 = (lsb >> bitPosition) & 1;
                    int colorBit1 = (msb >> bitPosition) & 1;
                    int colorIndex = (colorBit1 << 1) | colorBit0;

                    if (colorIndex == 0) continue; 

                    PixelInfo currentPixel = scanlinePixelInfo[screenX];
                    boolean shouldDraw = true;
                    
                    if (currentPixel.fromSprite) {
                        shouldDraw = false;
                    }
                    
                    if (!currentPixel.fromSprite && bgPriority && isBgWindowDisplayEnabled()) {
                        if (currentPixel.colorIndex != 0) {
                            shouldDraw = false; 
                        }
                    }
                    
                    if (shouldDraw) {
                        scanlinePixelInfo[screenX] = new PixelInfo(colorIndex, true, bgPriority, paletteReg);
                    }
                }
            }
        }
    }

    private int getColorFromPalette(int colorIndex, int paletteRegister) {
        int shift = colorIndex * 2;
        int paletteColorIndex = (paletteRegister >> shift) & 0x03;
        
        return getColors()[paletteColorIndex];
    }
    
    /**
     * Define a paleta de cores atual
     */
    public void setColorPalette(ColorPalette palette) {
        if (palette != null) {
            this.currentPalette = palette;
            System.out.println("Paleta alterada para: " + palette.getDisplayName());
        }
    }
    public ColorPalette getColorPalette() {
        return currentPalette;
    }

    public boolean isLcdEnabled() { return (lcdc & 0x80) != 0; } 
    public boolean isWindowDisplayEnabled() { return (lcdc & 0x20) != 0; } 
    public boolean isSpriteSize8x16() { return (lcdc & 0x04) != 0; } 
    public boolean isSpriteDisplayEnabled() { return (lcdc & 0x02) != 0; } 
    public boolean isBgWindowDisplayEnabled() { return (lcdc & 0x01) != 0; } 

    public int getLcdc() { return lcdc; }
    public void setLcdc(int value) {
        boolean wasLcdEnabled = isLcdEnabled();
        this.lcdc = value & 0xFF;
        boolean isLcdEnabled = isLcdEnabled();
        
        if (wasLcdEnabled && !isLcdEnabled) {
            ly = 0;
            if (mmu != null) {
                mmu.writeByte(MMU.REG_LY, ly);
            }
            ppuMode = 0; 
            cyclesCounter = 0;
            scanlineCycles = 0;
            statInterruptLine = false;
            previousLycEqualsLy = false;
            lycComparisonThisCycle = false;
            updateStatRegister();
            
            Arrays.fill(screenBuffer, getColors()[0]);
            frameCompleted = true;
        } else if (!wasLcdEnabled && isLcdEnabled) {
            ly = 0;
            if (mmu != null) {
                mmu.writeByte(MMU.REG_LY, ly);
            }
            ppuMode = 2; 
            cyclesCounter = 0;
            scanlineCycles = 0;
            statInterruptLine = false;
            previousLycEqualsLy = false;
            lycComparisonThisCycle = false;
            updateStatRegister();
        }
    }

    public int getStat() { return (stat & 0xFF) | 0x80; } 
    public void setStat(int value) { 
        boolean oldStatLine = statInterruptLine;
        
        int readOnlyBits = stat & 0x07; 
        this.stat = (value & 0x78) | readOnlyBits; 
        
        if (isLcdEnabled() && ppuMode != 1 && !oldStatLine) {
            boolean anyConditionActive = false;
            
            if ((stat & 0x08) != 0 && ppuMode == 0) { 
                anyConditionActive = true;
            }
            
            if ((stat & 0x20) != 0 && ppuMode == 2) { 
                anyConditionActive = true;
            }
            
            if ((stat & 0x40) != 0 && (stat & 0x04) != 0) { 
                anyConditionActive = true;
            }
            
            if (anyConditionActive) {
                requestLcdStatInterrupt();
                statInterruptLine = true; 
            }
        }
    }

    public int getScy() { return scy; }
    public void setScy(int value) { this.scy = value & 0xFF; }

    public int getScx() { return scx; }
    public void setScx(int value) { this.scx = value & 0xFF; }

    public int getLy() { return ly; }

    public int getLyc() { return lyc; }
    public void setLyc(int value) { 
        this.lyc = value & 0xFF;
        
        boolean newLycEqualsLy = (ly == lyc);
        
        if (newLycEqualsLy && !previousLycEqualsLy) {
            lycComparisonThisCycle = true; 
        }
        
        if (newLycEqualsLy) {
            stat |= 0x04;
        } else {
            stat &= ~0x04;
        }
    }

    public int getBgp() { return bgp; }
    public void setBgp(int value) { this.bgp = value & 0xFF; }

    public int getObp0() { return obp0; }
    public void setObp0(int value) { this.obp0 = value & 0xFF; }

    public int getObp1() { return obp1; }
    public void setObp1(int value) { this.obp1 = value & 0xFF; }

    public int getWy() { return wy; }
    public void setWy(int value) { this.wy = value & 0xFF; }

    public int getWx() { return wx; }
    public void setWx(int value) { this.wx = value & 0xFF; }

    public byte readVRAM(int address) {
        if (isLcdEnabled() && ppuMode == 3) {
            return (byte) 0xFF; 
        }
        if (address >= 0 && address < vram.length) {
            return vram[address];
        }
        return (byte) 0xFF;
    }

    public void writeVRAM(int address, byte value) {
        if (isLcdEnabled() && ppuMode == 3) {
            return; 
        }
        if (address >= 0 && address < vram.length) {
            vram[address] = value;
        }
    }

    public byte readOAM(int address) {
        if (isLcdEnabled() && (ppuMode == 2 || ppuMode == 3)) {
            return (byte) 0xFF;
        }
        if (address >= 0 && address < oam.length) {
            return oam[address];
        }
        return (byte) 0xFF;
    }

    public void writeOAM(int address, byte value) {
        if (isLcdEnabled() && (ppuMode == 2 || ppuMode == 3)) {
            return; 
        }
        if (address >= 0 && address < oam.length) {
            oam[address] = value;
        }
    }

    public int[] getScreenBuffer() {
        return screenBuffer;
    }

    public boolean isFrameCompleted() {
        if (frameCompleted) {
            frameCompleted = false; 
            return true;
        }
        return false;
    }
    
    public void setPixel(int x, int y, int color) {
        if (x >= 0 && x < SCREEN_WIDTH && y >= 0 && y < SCREEN_HEIGHT) {
            int index = y * SCREEN_WIDTH + x;
            if (index < screenBuffer.length) {
                screenBuffer[index] = color;
            }
        }
    }
    
    public int getPpuMode() {
        return ppuMode;
    }
    
    public void setPixelFifoEnabled(boolean enable) {
        this.enablePixelFifo = enable;
        System.out.println("Pixel FIFO " + (enable ? "habilitado" : "desabilitado"));
    }
    
    public boolean isPixelFifoEnabled() {
        return enablePixelFifo;
    }

    public void saveState(java.io.DataOutputStream dos) throws java.io.IOException {
        for (int i = 0; i < vram.length; i++) {
            dos.writeByte(vram[i]);
        }
        for (int i = 0; i < oam.length; i++) {
            dos.writeByte(oam[i]);
        }
        dos.writeByte(lcdc);
        dos.writeByte(stat);
        dos.writeByte(scy);
        dos.writeByte(scx);
        dos.writeByte(ly);
        dos.writeByte(lyc);
        dos.writeByte(bgp);
        dos.writeByte(obp0);
        dos.writeByte(obp1);
        dos.writeByte(wy);
        dos.writeByte(wx);
        dos.writeInt(ppuMode);
        dos.writeInt(cyclesCounter);
        dos.writeBoolean(frameCompleted);
        
        // Save state v2: Color Palette
        dos.writeUTF(currentPalette.name());
        
        // Save state v2: Advanced PPU timing
        dos.writeInt(scanlineCycles);
        dos.writeBoolean(statInterruptLine);
        dos.writeInt(mode3Duration);
        dos.writeInt(windowLineCounter);
    }

    public void loadState(java.io.DataInputStream dis) throws java.io.IOException {
        for (int i = 0; i < vram.length; i++) {
            vram[i] = dis.readByte();
        }
        for (int i = 0; i < oam.length; i++) {
            oam[i] = dis.readByte();
        }
        lcdc = dis.readByte();
        stat = dis.readByte();
        scy = dis.readByte();
        scx = dis.readByte();
        ly = dis.readByte();
        lyc = dis.readByte();
        bgp = dis.readByte();
        obp0 = dis.readByte();
        obp1 = dis.readByte();
        wy = dis.readByte();
        wx = dis.readByte();
        ppuMode = dis.readInt();
        cyclesCounter = dis.readInt();
        frameCompleted = dis.readBoolean();
        
        try {
            String paletteName = dis.readUTF();
            currentPalette = ColorPalette.valueOf(ColorPalette.class, paletteName);
        } catch (java.io.EOFException e) {
            currentPalette = ColorPalette.DMG_GREEN;
        } catch (IllegalArgumentException e) {
            currentPalette = ColorPalette.DMG_GREEN;
        }
        
        try {
            scanlineCycles = dis.readInt();
            statInterruptLine = dis.readBoolean();
            mode3Duration = dis.readInt();
            windowLineCounter = dis.readInt();
        } catch (java.io.EOFException e) {
            scanlineCycles = 0;
            statInterruptLine = false;
            mode3Duration = MODE_3_BASE_CYCLES;
            windowLineCounter = 0;
        }
    }
}
