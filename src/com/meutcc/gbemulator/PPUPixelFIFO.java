package com.meutcc.gbemulator;

import java.util.*;

/**
 * Implementação do Pipeline de Renderização (Pixel FIFO) da PPU
 * 
 * Esta classe implementa o sistema de renderização pixel-a-pixel do Game Boy,
 * simulando o comportamento real do hardware com FIFOs para background/window
 * e sprites, permitindo efeitos de mid-scanline e timing preciso.
 */
public class PPUPixelFIFO {
    
    // Estados do Fetcher
    public enum FetcherState {
        FETCH_TILE_ID,          // Buscar ID do tile (2 ciclos)
        FETCH_TILE_DATA_LOW,    // Buscar byte baixo do tile (2 ciclos)
        FETCH_TILE_DATA_HIGH,   // Buscar byte alto do tile (2 ciclos)
        PUSH_TO_FIFO,           // Empurrar pixels para FIFO (variável)
        SLEEP                   // Estado de sleep (para sprites)
    }
    
    // Estados do Sprite Fetcher
    public enum SpriteFetcherState {
        IDLE,
        FETCH_TILE_ID,
        FETCH_TILE_DATA_LOW, 
        FETCH_TILE_DATA_HIGH,
        PUSH_TO_FIFO
    }
    
    // Classe para representar um pixel na FIFO
    public static class FIFOPixel {
        int colorIndex;         // Índice de cor (0-3)
        int palette;           // Paleta usada (BGP, OBP0, OBP1)
        boolean spritePriority; // Prioridade do sprite sobre BG
        boolean bgPriority;    // Se é pixel de BG/Window (true) ou sprite (false)
        
        public FIFOPixel(int colorIndex, int palette, boolean spritePriority, boolean bgPriority) {
            this.colorIndex = colorIndex;
            this.palette = palette;
            this.spritePriority = spritePriority;
            this.bgPriority = bgPriority;
        }
        
        public FIFOPixel() {
            this(0, 0, false, true);
        }
    }
    
    // Classe para representar sprite na scanline
    public static class ScanlineSprite {
        int x;              // Posição X
        int tileIndex;      // Índice do tile
        int attributes;     // Atributos do sprite
        int oamIndex;       // Índice na OAM (para prioridade)
        
        public ScanlineSprite(int x, int tileIndex, int attributes, int oamIndex) {
            this.x = x;
            this.tileIndex = tileIndex;
            this.attributes = attributes;
            this.oamIndex = oamIndex;
        }
    }
    
    // Constantes do FIFO
    private static final int FIFO_SIZE = 16;           // Tamanho máximo da FIFO
    private static final int MIN_FIFO_SIZE = 8;       // Tamanho mínimo para render
    private static final int FETCHER_CYCLES = 2;      // Ciclos por estado do fetcher
    
    // Referências externas
    private final PPU ppu;
    
    // FIFOs
    private final Queue<FIFOPixel> bgWindowFIFO;
    private final Queue<FIFOPixel> spriteFIFO;
    
    // Estado do Background/Window Fetcher
    private FetcherState bgFetcherState;
    private int bgFetcherCycles;
    private int bgFetcherX;              // Posição X atual do fetcher
    private int bgTileId;                // ID do tile sendo buscado
    private int bgTileDataLow;           // Byte baixo do tile
    private int bgTileDataHigh;          // Byte alto do tile
    private boolean windowActive;        // Se a window está ativa
    private int windowLineCounter;       // Contador de linhas da window
    
    // Estado do Sprite Fetcher
    private SpriteFetcherState spriteFetcherState;
    private int spriteFetcherCycles;
    private ScanlineSprite currentSprite;
    private int spriteTileId;
    private int spriteTileDataLow;
    private int spriteTileDataHigh;
    
    // Lista de sprites na scanline atual
    private final List<ScanlineSprite> scanlineSprites;
    private int currentSpriteIndex;
    
    // Estado da renderização
    private int pixelsRendered;          // Pixels renderizados na scanline atual
    private boolean renderingActive;     // Se a renderização está ativa
    private int windowTileX;            // Posição X do tile na window
    private boolean windowTriggered;     // Se a window foi ativada nesta scanline
    
    // Dados de acesso à VRAM
    private final byte[] vram;
    private final byte[] oam;
    
    public PPUPixelFIFO(PPU ppu, MMU mmu, byte[] vram, byte[] oam) {
        this.ppu = ppu;
        this.vram = vram;
        this.oam = oam;
        
        this.bgWindowFIFO = new LinkedList<>();
        this.spriteFIFO = new LinkedList<>();
        this.scanlineSprites = new ArrayList<>();
        
        reset();
    }
    
    public void reset() {
        bgWindowFIFO.clear();
        spriteFIFO.clear();
        scanlineSprites.clear();
        
        bgFetcherState = FetcherState.FETCH_TILE_ID;
        bgFetcherCycles = 0;
        bgFetcherX = 0;
        bgTileId = 0;
        bgTileDataLow = 0;
        bgTileDataHigh = 0;
        windowActive = false;
        windowLineCounter = 0;
        
        spriteFetcherState = SpriteFetcherState.IDLE;
        spriteFetcherCycles = 0;
        currentSprite = null;
        spriteTileId = 0;
        spriteTileDataLow = 0;
        spriteTileDataHigh = 0;
        
        pixelsRendered = 0;
        renderingActive = false;
        windowTileX = 0;
        windowTriggered = false;
        currentSpriteIndex = 0;
    }
    
    /**
     * Inicia a renderização de uma nova scanline
     */
    public void startScanline(int ly) {
        reset();
        renderingActive = true;
        
        // Verificar se a window deve ser ativada nesta scanline
        if (ppu.isWindowDisplayEnabled() && ly >= ppu.getWy()) {
            windowTriggered = true;
        }
        
        // Buscar sprites para esta scanline
        findSpritesForScanline(ly);
        
        // Descartar pixels do scroll X
        discardScrollPixels();
    }
    
    /**
     * Processa um ciclo do pipeline FIFO
     */
    public boolean processCycle() {
        if (!renderingActive) {
            return false;
        }
        
        // Processar Background/Window Fetcher
        processBgWindowFetcher();
        
        // Processar Sprite Fetcher
        processSpriteFetcher();
        
        // Tentar renderizar um pixel
        if (canRenderPixel()) {
            renderPixel();
            pixelsRendered++;
            
            // Verificar se a scanline foi completada
            if (pixelsRendered >= PPU.SCREEN_WIDTH) {
                renderingActive = false;
                return true; // Scanline completa
            }
        }
        
        return false;
    }
    
    /**
     * Processa o Background/Window Fetcher
     */
    private void processBgWindowFetcher() {
        bgFetcherCycles++;
        
        if (bgFetcherCycles < FETCHER_CYCLES) {
            return; // Ainda não completou o ciclo atual
        }
        
        bgFetcherCycles = 0;
        
        switch (bgFetcherState) {
            case FETCH_TILE_ID:
                fetchBgWindowTileId();
                bgFetcherState = FetcherState.FETCH_TILE_DATA_LOW;
                break;
                
            case FETCH_TILE_DATA_LOW:
                fetchBgWindowTileDataLow();
                bgFetcherState = FetcherState.FETCH_TILE_DATA_HIGH;
                break;
                
            case FETCH_TILE_DATA_HIGH:
                fetchBgWindowTileDataHigh();
                bgFetcherState = FetcherState.PUSH_TO_FIFO;
                break;
                
            case PUSH_TO_FIFO:
                if (pushBgWindowPixelsToFIFO()) {
                    bgFetcherState = FetcherState.FETCH_TILE_ID;
                    bgFetcherX += 8; // Próximo tile
                }
                break;
                
            case SLEEP:
                // Estado usado quando sprite fetcher está ativo
                break;
        }
    }
    
    /**
     * Processa o Sprite Fetcher
     */
    private void processSpriteFetcher() {
        // Verificar se há sprite para buscar na posição atual
        checkForSpriteAtCurrentPosition();
        
        if (spriteFetcherState == SpriteFetcherState.IDLE) {
            return;
        }
        
        spriteFetcherCycles++;
        
        if (spriteFetcherCycles < FETCHER_CYCLES) {
            return;
        }
        
        spriteFetcherCycles = 0;
        
        switch (spriteFetcherState) {
            case IDLE:
                // Não faz nada no estado idle
                break;
                
            case FETCH_TILE_ID:
                fetchSpriteTileId();
                spriteFetcherState = SpriteFetcherState.FETCH_TILE_DATA_LOW;
                break;
                
            case FETCH_TILE_DATA_LOW:
                fetchSpriteTileDataLow();
                spriteFetcherState = SpriteFetcherState.FETCH_TILE_DATA_HIGH;
                break;
                
            case FETCH_TILE_DATA_HIGH:
                fetchSpriteTileDataHigh();
                spriteFetcherState = SpriteFetcherState.PUSH_TO_FIFO;
                break;
                
            case PUSH_TO_FIFO:
                pushSpritePixelsToFIFO();
                spriteFetcherState = SpriteFetcherState.IDLE;
                currentSprite = null;
                break;
        }
    }
    
    /**
     * Busca o ID do tile de background/window
     */
    private void fetchBgWindowTileId() {
        if (!ppu.isBgWindowDisplayEnabled()) {
            bgTileId = 0;
            return;
        }
        
        // Verificar se deve mudar para window
        if (shouldActivateWindow()) {
            windowActive = true;
        }
        
        if (windowActive) {
            // Buscar tile da window
            int tileMapArea = (ppu.getLcdc() & 0x40) != 0 ? 0x9C00 : 0x9800;
            int yInWindow = ppu.getLy() - ppu.getWy();
            int tileRow = yInWindow / 8;
            int tileCol = windowTileX;
            
            int tileMapOffset = tileRow * 32 + tileCol;
            int tileMapAddress = tileMapArea + tileMapOffset;
            
            if (tileMapAddress >= 0x8000 && tileMapAddress < 0xA000) {
                bgTileId = vram[tileMapAddress - 0x8000] & 0xFF;
            } else {
                bgTileId = 0;
            }
        } else {
            // Buscar tile do background
            int tileMapArea = (ppu.getLcdc() & 0x08) != 0 ? 0x9C00 : 0x9800;
            int yInBgMap = (ppu.getLy() + ppu.getScy()) & 0xFF;
            int tileRow = yInBgMap / 8;
            int xInBgMap = (bgFetcherX + ppu.getScx()) & 0xFF;
            int tileCol = xInBgMap / 8;
            
            int tileMapOffset = tileRow * 32 + tileCol;
            int tileMapAddress = tileMapArea + tileMapOffset;
            
            if (tileMapAddress >= 0x8000 && tileMapAddress < 0xA000) {
                bgTileId = vram[tileMapAddress - 0x8000] & 0xFF;
            } else {
                bgTileId = 0;
            }
        }
    }
    
    /**
     * Busca o byte baixo dos dados do tile de background/window
     */
    private void fetchBgWindowTileDataLow() {
        int tileAddress = calculateTileDataAddress(bgTileId);
        int yInTile;
        
        if (windowActive) {
            int yInWindow = ppu.getLy() - ppu.getWy();
            yInTile = yInWindow % 8;
        } else {
            int yInBgMap = (ppu.getLy() + ppu.getScy()) & 0xFF;
            yInTile = yInBgMap % 8;
        }
        
        int tileRowAddress = tileAddress + (yInTile * 2);
        
        if (tileRowAddress >= 0x8000 && tileRowAddress < 0xA000) {
            bgTileDataLow = vram[tileRowAddress - 0x8000] & 0xFF;
        } else {
            bgTileDataLow = 0;
        }
    }
    
    /**
     * Busca o byte alto dos dados do tile de background/window
     */
    private void fetchBgWindowTileDataHigh() {
        int tileAddress = calculateTileDataAddress(bgTileId);
        int yInTile;
        
        if (windowActive) {
            int yInWindow = ppu.getLy() - ppu.getWy();
            yInTile = yInWindow % 8;
        } else {
            int yInBgMap = (ppu.getLy() + ppu.getScy()) & 0xFF;
            yInTile = yInBgMap % 8;
        }
        
        int tileRowAddress = tileAddress + (yInTile * 2) + 1;
        
        if (tileRowAddress >= 0x8000 && tileRowAddress < 0xA000) {
            bgTileDataHigh = vram[tileRowAddress - 0x8000] & 0xFF;
        } else {
            bgTileDataHigh = 0;
        }
    }
    
    /**
     * Empurra pixels de background/window para a FIFO
     */
    private boolean pushBgWindowPixelsToFIFO() {
        // Só empurra se a FIFO estiver com menos de 8 pixels
        if (bgWindowFIFO.size() <= MIN_FIFO_SIZE) {
            int startX = windowActive ? 0 : ((ppu.getScx() + bgFetcherX) % 8);
            
            for (int x = startX; x < 8; x++) {
                int bitPosition = 7 - x;
                int colorBit0 = (bgTileDataLow >> bitPosition) & 1;
                int colorBit1 = (bgTileDataHigh >> bitPosition) & 1;
                int colorIndex = (colorBit1 << 1) | colorBit0;
                
                FIFOPixel pixel = new FIFOPixel(colorIndex, ppu.getBgp(), false, true);
                bgWindowFIFO.offer(pixel);
                
                if (bgWindowFIFO.size() >= FIFO_SIZE) {
                    break;
                }
            }
            
            if (windowActive) {
                windowTileX++;
            }
            
            return true;
        }
        
        return false;
    }
    
    /**
     * Verifica se há sprite na posição atual
     */
    private void checkForSpriteAtCurrentPosition() {
        if (spriteFetcherState != SpriteFetcherState.IDLE || !ppu.isSpriteDisplayEnabled()) {
            return;
        }
        
        int currentX = pixelsRendered + 8; // 8 pixels de lookhead
        
        for (int i = currentSpriteIndex; i < scanlineSprites.size(); i++) {
            ScanlineSprite sprite = scanlineSprites.get(i);
            
            if (sprite.x <= currentX && sprite.x > currentX - 8) {
                currentSprite = sprite;
                currentSpriteIndex = i + 1;
                spriteFetcherState = SpriteFetcherState.FETCH_TILE_ID;
                spriteFetcherCycles = 0;
                
                // Pausar o BG fetcher
                bgFetcherState = FetcherState.SLEEP;
                break;
            }
        }
    }
    
    /**
     * Busca o ID do tile do sprite
     */
    private void fetchSpriteTileId() {
        if (currentSprite != null) {
            spriteTileId = currentSprite.tileIndex;
            
            // Para sprites 8x16, ajustar o tile ID
            if (ppu.isSpriteSize8x16()) {
                spriteTileId &= 0xFE; // Tile par para parte superior
                
                // Verificar se deve usar a parte inferior do sprite
                int spriteY = (oam[currentSprite.oamIndex * 4] & 0xFF) - 16;
                int yInSprite = ppu.getLy() - spriteY;
                if ((currentSprite.attributes & 0x40) != 0) { // Flip Y
                    yInSprite = 15 - yInSprite;
                }
                
                if (yInSprite >= 8) {
                    spriteTileId |= 0x01; // Tile ímpar para parte inferior
                }
            }
        }
    }
    
    /**
     * Busca o byte baixo dos dados do tile do sprite
     */
    private void fetchSpriteTileDataLow() {
        int tileAddress = 0x8000 + (spriteTileId * 16);
        int spriteY = (oam[currentSprite.oamIndex * 4] & 0xFF) - 16;
        int yInSprite = ppu.getLy() - spriteY;
        
        // Aplicar flip Y se necessário
        if ((currentSprite.attributes & 0x40) != 0) {
            int spriteHeight = ppu.isSpriteSize8x16() ? 16 : 8;
            yInSprite = spriteHeight - 1 - yInSprite;
        }
        
        // Para sprites 8x16, ajustar Y dentro do tile
        if (ppu.isSpriteSize8x16()) {
            yInSprite %= 8;
        }
        
        int tileRowAddress = tileAddress + (yInSprite * 2);
        
        if (tileRowAddress >= 0x8000 && tileRowAddress < 0xA000) {
            spriteTileDataLow = vram[tileRowAddress - 0x8000] & 0xFF;
        } else {
            spriteTileDataLow = 0;
        }
    }
    
    /**
     * Busca o byte alto dos dados do tile do sprite
     */
    private void fetchSpriteTileDataHigh() {
        int tileAddress = 0x8000 + (spriteTileId * 16);
        int spriteY = (oam[currentSprite.oamIndex * 4] & 0xFF) - 16;
        int yInSprite = ppu.getLy() - spriteY;
        
        // Aplicar flip Y se necessário
        if ((currentSprite.attributes & 0x40) != 0) {
            int spriteHeight = ppu.isSpriteSize8x16() ? 16 : 8;
            yInSprite = spriteHeight - 1 - yInSprite;
        }
        
        // Para sprites 8x16, ajustar Y dentro do tile
        if (ppu.isSpriteSize8x16()) {
            yInSprite %= 8;
        }
        
        int tileRowAddress = tileAddress + (yInSprite * 2) + 1;
        
        if (tileRowAddress >= 0x8000 && tileRowAddress < 0xA000) {
            spriteTileDataHigh = vram[tileRowAddress - 0x8000] & 0xFF;
        } else {
            spriteTileDataHigh = 0;
        }
    }
    
    /**
     * Empurra pixels do sprite para a FIFO
     */
    private void pushSpritePixelsToFIFO() {
        // Calcular a posição X de início do sprite na tela
        int spriteScreenX = currentSprite.x - 8;
        boolean flipX = (currentSprite.attributes & 0x20) != 0;
        boolean spritePriority = (currentSprite.attributes & 0x80) == 0;
        int palette = ((currentSprite.attributes & 0x10) != 0) ? ppu.getObp1() : ppu.getObp0();
        
        // Expandir a FIFO de sprites se necessário
        while (spriteFIFO.size() < bgWindowFIFO.size()) {
            spriteFIFO.offer(new FIFOPixel(0, 0, false, false));
        }
        
        // Converter para array para facilitar a modificação
        FIFOPixel[] spritePixels = spriteFIFO.toArray(new FIFOPixel[0]);
        spriteFIFO.clear();
        
        // Adicionar pixels do sprite
        for (int x = 0; x < 8; x++) {
            int bitPosition = flipX ? x : (7 - x);
            int colorBit0 = (spriteTileDataLow >> bitPosition) & 1;
            int colorBit1 = (spriteTileDataHigh >> bitPosition) & 1;
            int colorIndex = (colorBit1 << 1) | colorBit0;
            
            // Pixel transparente (índice 0) não sobrescreve
            if (colorIndex != 0) {
                int pixelIndex = spriteScreenX + x - pixelsRendered;
                
                if (pixelIndex >= 0 && pixelIndex < spritePixels.length) {
                    // Só sobrescreve se não há pixel de sprite já presente
                    if (spritePixels[pixelIndex].colorIndex == 0 || spritePixels[pixelIndex].bgPriority) {
                        spritePixels[pixelIndex] = new FIFOPixel(colorIndex, palette, spritePriority, false);
                    }
                }
            }
        }
        
        // Recriar a FIFO
        for (FIFOPixel pixel : spritePixels) {
            spriteFIFO.offer(pixel);
        }
        
        // Reativar o BG fetcher
        bgFetcherState = FetcherState.FETCH_TILE_ID;
    }
    
    /**
     * Verifica se pode renderizar um pixel
     */
    private boolean canRenderPixel() {
        return !bgWindowFIFO.isEmpty() && bgWindowFIFO.size() > MIN_FIFO_SIZE;
    }
    
    /**
     * Renderiza um pixel combinando BG/Window e Sprite
     */
    private void renderPixel() {
        FIFOPixel bgPixel = bgWindowFIFO.poll();
        FIFOPixel spritePixel = spriteFIFO.isEmpty() ? new FIFOPixel() : spriteFIFO.poll();
        
        // Determinar pixel final baseado nas regras de prioridade
        FIFOPixel finalPixel = mixPixels(bgPixel, spritePixel);
        
        // Converter para cor RGB e armazenar no buffer
        int color = getColorFromPalette(finalPixel.colorIndex, finalPixel.palette);
        ppu.setPixel(pixelsRendered, ppu.getLy(), color);
    }
    
    /**
     * Mistura pixels de BG/Window e Sprite seguindo as regras de prioridade
     */
    private FIFOPixel mixPixels(FIFOPixel bgPixel, FIFOPixel spritePixel) {
        // Se sprite é transparente, usar BG
        if (spritePixel.colorIndex == 0) {
            return bgPixel;
        }
        
        // Se BG é transparente e temos sprite, usar sprite
        if (bgPixel.colorIndex == 0) {
            return spritePixel;
        }
        
        // Aplicar regras de prioridade
        if (spritePixel.spritePriority) {
            // Sprite tem prioridade sobre BG
            return spritePixel;
        } else {
            // BG tem prioridade sobre sprite
            return bgPixel;
        }
    }
    
    /**
     * Encontra sprites visíveis na scanline atual
     */
    private void findSpritesForScanline(int ly) {
        scanlineSprites.clear();
        
        if (!ppu.isSpriteDisplayEnabled()) {
            return;
        }
        
        int spriteHeight = ppu.isSpriteSize8x16() ? 16 : 8;
        
        for (int i = 0; i < 40; i++) {
            int oamAddress = i * 4;
            int spriteY = (oam[oamAddress] & 0xFF) - 16;
            int spriteX = (oam[oamAddress + 1] & 0xFF) - 8;
            int tileIndex = oam[oamAddress + 2] & 0xFF;
            int attributes = oam[oamAddress + 3] & 0xFF;
            
            // Verificar se o sprite é visível na scanline atual
            if (ly >= spriteY && ly < spriteY + spriteHeight) {
                scanlineSprites.add(new ScanlineSprite(spriteX, tileIndex, attributes, i));
                
                // Máximo de 10 sprites por scanline
                if (scanlineSprites.size() >= 10) {
                    break;
                }
            }
        }
        
        // Ordenar sprites por coordenada X (prioridade: menor X primeiro)
        scanlineSprites.sort((a, b) -> {
            if (a.x != b.x) {
                return Integer.compare(a.x, b.x);
            }
            // Se X é igual, prioridade pelo índice OAM
            return Integer.compare(a.oamIndex, b.oamIndex);
        });
    }
    
    /**
     * Descarta pixels devido ao scroll X
     */
    private void discardScrollPixels() {
        int pixelsToDiscard = ppu.getScx() % 8;
        
        for (int i = 0; i < pixelsToDiscard; i++) {
            if (!bgWindowFIFO.isEmpty()) {
                bgWindowFIFO.poll();
            }
        }
    }
    
    /**
     * Verifica se deve ativar a window
     */
    private boolean shouldActivateWindow() {
        if (!windowTriggered || windowActive) {
            return false;
        }
        
        return pixelsRendered >= (ppu.getWx() - 7);
    }
    
    /**
     * Calcula o endereço dos dados do tile
     */
    private int calculateTileDataAddress(int tileId) {
        int tileDataArea = (ppu.getLcdc() & 0x10) != 0 ? 0x8000 : 0x8800;
        
        if (tileDataArea == 0x8800) {
            // Addressing com sinal
            byte signedTileId = (byte) tileId;
            return 0x9000 + (signedTileId * 16);
        } else {
            // Addressing sem sinal
            return 0x8000 + (tileId * 16);
        }
    }
    
    /**
     * Converte índice de cor para RGB usando paleta
     */
    private int getColorFromPalette(int colorIndex, int paletteRegister) {
        int actualColor = 0;
        switch (colorIndex) {
            case 0: actualColor = (paletteRegister >> 0) & 0x03; break;
            case 1: actualColor = (paletteRegister >> 2) & 0x03; break;
            case 2: actualColor = (paletteRegister >> 4) & 0x03; break;
            case 3: actualColor = (paletteRegister >> 6) & 0x03; break;
        }
        
        // Cores do Game Boy (tons de cinza)
        int[] colors = {
            0xFFFFFFFF, // Branco
            0xFFAAAAAA, // Cinza claro
            0xFF555555, // Cinza escuro
            0xFF000000  // Preto
        };
        
        return colors[actualColor];
    }
    
    // Getters para estado interno
    public boolean isRenderingActive() {
        return renderingActive;
    }
    
    public int getPixelsRendered() {
        return pixelsRendered;
    }
    
    public int getBgFIFOSize() {
        return bgWindowFIFO.size();
    }
    
    public int getSpriteFIFOSize() {
        return spriteFIFO.size();
    }
}
