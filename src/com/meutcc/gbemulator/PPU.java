package com.meutcc.gbemulator;

import java.util.*;

/**
 * PPU (Picture Processing Unit) - Unidade de Processamento de Imagem do Game Boy
 * 
 * Implementação precisa do PPU do Game Boy DMG com:
 * - Timing ciclo-a-ciclo para compatibilidade máxima
 * - Interrupções STAT precisas com edge detection
 * - Suporte ao STAT write bug (comportamento do hardware)
 * - Comparação LYC=LY em ciclos específicos
 * - Bug da linha 153→0 (LY=153 dura apenas 4 ciclos)
 * - Renderização pixel-a-pixel opcional (FIFO)
 * - Restrições de acesso VRAM/OAM durante modos específicos
 * 
 * MELHORIAS DE PRECISÃO (2025-10-25):
 * =====================================
 * 
 * 1. TIMING PRECISO DE LYC=LY:
 *    - Comparação LYC=LY ocorre no ciclo 4 do modo 2 (OAM Scan)
 *    - Comparação especial no ciclo 4 da linha 153 (transição 153→0)
 *    - Flag LYC=LY (STAT bit 2) atualizada imediatamente
 *    - Edge detection para prevenir interrupções duplicadas
 * 
 * 2. STAT WRITE BUG:
 *    - Escrever no STAT pode disparar interrupções espúrias
 *    - Ocorre quando: LCD ligado, não V-Blank, linha STAT estava baixa
 *    - Emula glitch do hardware que causa borda de subida artificial
 *    - Importante para compatibilidade com jogos que usam/sofrem com este bug
 * 
 * 3. BUG DA LINHA 153:
 *    - LY=153 dura apenas 4 T-cycles (em vez de 456)
 *    - No ciclo 4, LY muda de 153 para 0
 *    - Pode causar comportamento inesperado se LYC = 153 ou 0
 *    - Necessário para passar em test ROMs avançados
 * 
 * 4. INTERRUPÇÕES STAT NO CICLO EXATO:
 *    - Verificação ciclo-a-ciclo em updateSingleCycle()
 *    - Edge detection (0→1) na linha STAT
 *    - Transições de modo disparam interrupções imediatamente
 *    - Linha STAT = OR lógica de todas as condições habilitadas
 * 
 * VARIÁVEIS DE ESTADO ADICIONADAS:
 * - previousLycEqualsLy: Estado anterior da comparação (edge detection)
 * - lycComparisonThisCycle: Flag para forçar comparação em ciclos específicos
 * 
 * Referências:
 * - Pan Docs: https://gbdev.io/pandocs/STAT.html
 * - TCAGBD: Documentação de timing ciclo-a-ciclo
 * - mooneye-gb: Test ROMs para validação de timing
 * 
 * @see STAT_LYC_TIMING.md para documentação detalhada
 */
public class PPU {
    // Classe interna para representar um pixel com índice de cor e informações de prioridade
    private static class PixelInfo {
        int colorIndex;      // Índice de cor (0-3)
        boolean fromSprite;  // Se o pixel veio de um sprite
        boolean spritePriority; // Se o sprite tem prioridade sobre BG/Window (bit 7 do sprite)
        int paletteRegister; // Registrador de paleta usado (bgp, obp0, obp1)
        
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

    // Classe interna para representar um sprite visível na scanline
    private static class VisibleSprite {
        int spriteIndex;    // Índice do sprite na OAM (0-39)
        int spriteX;        // Coordenada X do sprite
        int spriteY;        // Coordenada Y do sprite
        int tileIndex;      // Índice do tile do sprite
        int attributes;     // Atributos do sprite
        
        VisibleSprite(int spriteIndex, int spriteX, int spriteY, int tileIndex, int attributes) {
            this.spriteIndex = spriteIndex;
            this.spriteX = spriteX;
            this.spriteY = spriteY;
            this.tileIndex = tileIndex;
            this.attributes = attributes;
        }
    }

    // Resolução da tela do Game Boy
    public static final int SCREEN_WIDTH = 160;
    public static final int SCREEN_HEIGHT = 144;
    
    // Timing preciso da PPU (em T-cycles)
    public static final int MODE_2_CYCLES = 80;     // OAM Scan
    public static final int MODE_3_BASE_CYCLES = 172; // Drawing base
    public static final int MODE_3_MAX_CYCLES = 289;  // Drawing máximo
    public static final int SCANLINE_CYCLES = 456;   // Total por scanline
    public static final int VBLANK_LINES = 10;       // Linhas 144-153
    public static final int TOTAL_LINES = 154;       // 0-153

    // Memória da PPU
    private final byte[] vram = new byte[8192]; // 8KB (0x8000-0x9FFF)
    private final byte[] oam = new byte[160];   // 160 bytes (0xFE00-0xFE9F) - Sprite Attribute Table

    // Buffer de pixels para a tela (um frame)
    // Usaremos inteiros para representar cores ARGB (A será FF)
    private final int[] screenBuffer = new int[SCREEN_WIDTH * SCREEN_HEIGHT];
    private boolean frameCompleted = false;

    // Cores do Game Boy Clássico (tons de cinza) - CORES CORRIGIDAS
    // Mapeadas para RGB para exibição mais precisa
    // 00: Branco, 01: Cinza Claro, 10: Cinza Escuro, 11: Preto
    private final int[] COLORS = {
            0xFFE0F8D0, // Branco (cor 0) - Verde claro Game Boy original
            0xFF88C070, // Cinza Claro (cor 1) - Verde médio
            0xFF346856, // Cinza Escuro (cor 2) - Verde escuro
            0xFF081820  // Preto (cor 3) - Verde muito escuro/preto
    };

    // Registradores da PPU (valores atuais)
    private int lcdc;  // 0xFF40 - LCD Control
    private int stat;  // 0xFF41 - LCD Status
    private int scy;   // 0xFF42 - Scroll Y
    private int scx;   // 0xFF43 - Scroll X
    private int ly;    // 0xFF44 - LCD Y-Coordinate (current scanline)
    private int lyc;   // 0xFF45 - LY Compare
    // DMA 0xFF46 é tratado na MMU
    private int bgp;   // 0xFF47 - BG Palette Data
    private int obp0;  // 0xFF48 - Object Palette 0 Data
    private int obp1;  // 0xFF49 - Object Palette 1 Data
    private int wy;    // 0xFF4A - Window Y Position
    private int wx;    // 0xFF4B - Window X Position - 7

    // Estado interno da PPU
    private int ppuMode; // 0: HBlank, 1: VBlank, 2: OAM Scan, 3: Drawing
    private int cyclesCounter; // Contador de ciclos para o modo atual da PPU
    private int scanlineCycles; // Ciclos acumulados na scanline atual (para timing preciso)
    private boolean statInterruptLine; // Estado da linha de interrupção STAT (para edge detection)
    private int mode3Duration; // Duração calculada do modo 3 para scanline atual
    private int windowLineCounter; // Contador interno de linha da Window (incrementado quando window é renderizada)
    
    // Variáveis para timing preciso de LYC=LY
    private boolean previousLycEqualsLy; // Estado anterior da comparação LYC=LY (para edge detection)
    private boolean lycComparisonThisCycle; // Flag para indicar que a comparação LYC=LY deve ocorrer neste ciclo
    
    // Pixel FIFO para renderização pixel-a-pixel (para efeitos mid-scanline)
    private int pixelX; // Posição X atual do pixel sendo renderizado (0-159)
    private boolean enablePixelFifo; // Habilita renderização pixel-a-pixel para efeitos mid-scanline
    private PixelInfo[] currentScanlineBuffer; // Buffer temporário da scanline atual

    private MMU mmu; // Referência à MMU para solicitar interrupções

    public PPU() {
        reset();
    }

    public void setMmu(MMU mmu) {
        this.mmu = mmu;
    }

    public void reset() {
        Arrays.fill(vram, (byte) 0);
        Arrays.fill(oam, (byte) 0);
        Arrays.fill(screenBuffer, COLORS[0]); // Tela branca
        frameCompleted = false;

        lcdc = 0x91; // Valor comum de inicialização (PPU ligada, BG e Window ligados, Sprites ligados)
        stat = 0x85; // Modo 1 (VBlank) no início, LYC=LY flag pode estar setado
        scy = 0;
        scx = 0;
        ly = 0;
        lyc = 0;
        bgp = 0xFC;  // Paleta padrão 00,01,10,11 -> 11111100 (0,1,2,3)
        obp0 = 0xFF; // Paletas de sprite não usadas
        obp1 = 0xFF;
        wy = 0;
        wx = 0;

        ppuMode = 2; // Inicia em modo OAM Scan para a primeira linha
        cyclesCounter = 0;
        scanlineCycles = 0;
        statInterruptLine = false;
        mode3Duration = MODE_3_BASE_CYCLES;
        windowLineCounter = 0; // Reset do contador de linha da Window
        previousLycEqualsLy = false; // Reset do estado anterior de LYC=LY
        lycComparisonThisCycle = false; // Reset da flag de comparação
        
        // Inicializar pixel FIFO
        pixelX = 0;
        enablePixelFifo = false; // Desabilitado por padrão para compatibilidade
        currentScanlineBuffer = new PixelInfo[SCREEN_WIDTH];
        for (int i = 0; i < SCREEN_WIDTH; i++) {
            currentScanlineBuffer[i] = new PixelInfo();
        }
        
        System.out.println("PPU reset.");
    }

    // Atualiza o estado da PPU com base nos ciclos da CPU
    public void update(int cpuCycles) {
        if (!isLcdEnabled()) {
            handleLcdDisabled();
            return;
        }

        // Processar ciclo por ciclo para timing preciso
        for (int i = 0; i < cpuCycles; i++) {
            updateSingleCycle();
        }
    }
    
    /**
     * Atualiza a PPU um ciclo por vez para timing preciso
     */
    private void updateSingleCycle() {
        scanlineCycles++;
        cyclesCounter++;
        
        // Processar baseado no modo atual
        switch (ppuMode) {
            case 2: // OAM Scan
                updateOamScanMode();
                break;
            case 3: // Drawing
                updateDrawingMode();
                break;
            case 0: // H-Blank
                updateHBlankMode();
                break;
            case 1: // V-Blank
                updateVBlankMode();
                break;
        }
        
        // Verificar interrupções STAT após cada ciclo
        updateStatInterrupts();
    }
    
    /**
     * Gerencia LCD desabilitado
     */
    private void handleLcdDisabled() {
        ly = 0;
        ppuMode = 0; // H-Blank quando LCD desabilitado
        cyclesCounter = 0;
        scanlineCycles = 0;
        statInterruptLine = false;
        previousLycEqualsLy = false;
        
        if (mmu != null) {
            mmu.writeByte(MMU.REG_LY, ly);
        }
        updateStatRegister();
    }
    
    /**
     * Modo 2: OAM Scan (80 ciclos)
     */
    private void updateOamScanMode() {
        // A comparação LYC=LY acontece no ciclo 4 do modo 2
        if (cyclesCounter == 4) {
            lycComparisonThisCycle = true;
        }
        
        if (cyclesCounter >= MODE_2_CYCLES) {
            cyclesCounter = 0;
            pixelX = 0; // Reset do pixel X para nova scanline
            ppuMode = 3;
            lycComparisonThisCycle = false;
            
            // Calcular duração do modo 3 baseado no conteúdo da scanline
            calculateMode3Duration();
            updateStatRegister();
        }
    }
    
    /**
     * Modo 3: Drawing (172-289 ciclos variável)
     */
    private void updateDrawingMode() {
        if (enablePixelFifo) {
            // Renderização pixel-a-pixel para efeitos mid-scanline
            // A cada ~4 ciclos, renderizar 1 pixel (aproximação)
            if (cyclesCounter % 4 == 0 && pixelX < SCREEN_WIDTH) {
                renderSinglePixel(pixelX);
                pixelX++;
            }
            
            if (cyclesCounter >= mode3Duration) {
                // Fim do modo 3, transferir buffer para tela
                transferScanlineToScreen();
                cyclesCounter = 0;
                pixelX = 0;
                ppuMode = 0;
                updateStatRegister();
            }
        } else {
            // Renderização tradicional por scanline completa
            if (cyclesCounter == 1) { // Renderizar no primeiro ciclo do modo 3
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
    
    /**
     * Modo 0: H-Blank (resto dos 456 ciclos)
     */
    private void updateHBlankMode() {
        if (scanlineCycles >= SCANLINE_CYCLES) {
            // Fim da scanline
            scanlineCycles = 0;
            cyclesCounter = 0;
            ly++;
            
            // A comparação LYC=LY acontece no ciclo 4 da próxima scanline (modo 2)
            // Então não fazemos a comparação aqui, apenas atualizamos LY
            lycComparisonThisCycle = false;
            
            if (mmu != null) {
                mmu.writeByte(MMU.REG_LY, ly);
            }
            
            if (ly == SCREEN_HEIGHT) {
                // Entrar em V-Blank
                ppuMode = 1;
                frameCompleted = true;
                
                // Solicitar interrupção V-Blank
                requestVBlankInterrupt();
            } else {
                // Próxima scanline - modo 2 (OAM Scan)
                ppuMode = 2;
            }
            
            updateStatRegister();
        }
    }
    
    /**
     * Modo 1: V-Blank (10 linhas × 456 ciclos)
     */
    private void updateVBlankMode() {
        // Bug da linha 153: LY=153 dura apenas 4 ciclos, depois vai para 0
        if (ly == 153 && scanlineCycles == 4) {
            // Transição especial da linha 153 para 0
            ly = 0;
            lycComparisonThisCycle = true; // Comparar LYC=LY neste ciclo especial
            
            if (mmu != null) {
                mmu.writeByte(MMU.REG_LY, ly);
            }
        }
        
        if (scanlineCycles >= SCANLINE_CYCLES) {
            scanlineCycles = 0;
            cyclesCounter = 0;
            
            // Se não estamos na linha 153 (que tem tratamento especial acima)
            if (ly != 0) {
                ly++;
                lycComparisonThisCycle = false;
                
                if (mmu != null) {
                    mmu.writeByte(MMU.REG_LY, ly);
                }
            }
            
            if (ly >= TOTAL_LINES) {
                // Fim do V-Blank, reiniciar frame
                // Este caso não deve acontecer devido ao tratamento da linha 153
                ly = 0;
                ppuMode = 2;
                windowLineCounter = 0; // Reset do contador de linha da Window no início do frame
                lycComparisonThisCycle = false;
                
                if (mmu != null) {
                    mmu.writeByte(MMU.REG_LY, ly);
                }
            } else if (ly == 0) {
                // Transição de V-Blank para nova frame
                ppuMode = 2;
                windowLineCounter = 0;
            }
            
            updateStatRegister();
        }
    }
    
    /**
     * Calcula a duração do modo 3 baseado no conteúdo da scanline
     * Baseado em hardware real do Game Boy DMG
     * Fórmula aproximada: 172 + (sprites * 11) + (scx % 8) + (window penalty)
     */
    private void calculateMode3Duration() {
        int duration = MODE_3_BASE_CYCLES; // 172 ciclos base
        
        // Adicionar ciclos por sprites visíveis na scanline
        // Cada sprite adiciona ~11 ciclos (variação: 6-11 ciclos dependendo do alinhamento)
        if (isSpriteDisplayEnabled()) {
            int visibleSprites = countSpritesOnScanline();
            duration += visibleSprites * 11; // Penalidade por sprite (~11 ciclos no pior caso)
        }
        
        // Scroll horizontal adiciona ciclos (0-7 ciclos extras)
        // SCX % 8 != 0 causa pixels extras a serem fetchados
        duration += (scx % 8);
        
        // Window adiciona ciclos se estiver ativa nesta scanline
        if (isWindowDisplayEnabled() && ly >= wy && wx >= 0 && wx <= 166) {
            // Window adiciona ~6 ciclos quando ativa
            duration += 6;
        }
        
        // Limitar ao máximo de 289 ciclos
        mode3Duration = Math.min(duration, MODE_3_MAX_CYCLES);
    }
    
    /**
     * Renderiza um único pixel na posição X da scanline atual
     * Permite efeitos mid-scanline (mudanças de paleta, scroll, etc)
     */
    private void renderSinglePixel(int x) {
        if (x < 0 || x >= SCREEN_WIDTH) return;
        
        PixelInfo pixel = new PixelInfo(0, false, false, bgp);
        
        // 1. Renderizar Background pixel
        if (isBgWindowDisplayEnabled()) {
            pixel = getBackgroundPixel(x, ly);
        }
        
        // 2. Renderizar Window pixel (se aplicável)
        if (isWindowDisplayEnabled() && isBgWindowDisplayEnabled()) {
            int actualWX = wx - 7;
            if (x >= actualWX && ly >= wy) {
                pixel = getWindowPixel(x, ly);
            }
        }
        
        // 3. Renderizar Sprite pixel (se aplicável)
        if (isSpriteDisplayEnabled()) {
            PixelInfo spritePixel = getSpritePixel(x, ly);
            if (spritePixel != null) {
                // Aplicar lógica de prioridade sprite vs BG
                boolean shouldDraw = true;
                
                if (spritePixel.spritePriority && isBgWindowDisplayEnabled() && pixel.colorIndex != 0) {
                    shouldDraw = false; // BG tem prioridade
                }
                
                if (shouldDraw) {
                    pixel = spritePixel;
                }
            }
        }
        
        currentScanlineBuffer[x] = pixel;
    }
    
    /**
     * Obtém o pixel de background na posição (x, y)
     */
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
    
    /**
     * Obtém o pixel de window na posição (x, y)
     */
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
    
    /**
     * Obtém o pixel de sprite na posição (x, y)
     * Retorna null se nenhum sprite visível nesta posição
     */
    private PixelInfo getSpritePixel(int x, int y) {
        boolean tallSprites = (lcdc & 0x04) != 0;
        int spriteHeight = tallSprites ? 16 : 8;

        // Procurar sprites visíveis nesta posição (com prioridade correta)
        for (int i = 0; i < 40; i++) {
            int oamAddr = i * 4;

            int spriteY = (oam[oamAddr] & 0xFF) - 16;
            int spriteX = (oam[oamAddr + 1] & 0xFF) - 8;
            int tileIndex = oam[oamAddr + 2] & 0xFF;
            int attributes = oam[oamAddr + 3] & 0xFF;

            // Sprite está na posição (x, y)?
            if (y >= spriteY && y < (spriteY + spriteHeight) &&
                x >= spriteX && x < (spriteX + 8)) {
                
                boolean yFlip = (attributes & 0x40) != 0;
                boolean xFlip = (attributes & 0x20) != 0;
                boolean bgPriority = (attributes & 0x80) != 0;
                int paletteReg = (attributes & 0x10) != 0 ? obp1 : obp0;

                if (tallSprites) {
                    tileIndex &= 0xFE;
                }

                int yInSpriteTile = y - spriteY;
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

                int px = x - spriteX;
                int xInTilePixel = xFlip ? (7 - px) : px;

                int bitPosition = 7 - xInTilePixel;
                int colorBit0 = (lsb >> bitPosition) & 1;
                int colorBit1 = (msb >> bitPosition) & 1;
                int colorIndex = (colorBit1 << 1) | colorBit0;

                if (colorIndex == 0) continue; // Cor 0 é transparente

                return new PixelInfo(colorIndex, true, bgPriority, paletteReg);
            }
        }

        return null;
    }
    
    /**
     * Transfere o buffer da scanline atual para o buffer de tela
     */
    private void transferScanlineToScreen() {
        int baseIndex = ly * SCREEN_WIDTH;
        for (int x = 0; x < SCREEN_WIDTH; x++) {
            if (baseIndex + x < screenBuffer.length) {
                PixelInfo pixel = currentScanlineBuffer[x];
                screenBuffer[baseIndex + x] = getColorFromPalette(pixel.colorIndex, pixel.paletteRegister);
            }
        }
    }
    
    /**
     * Conta sprites visíveis na scanline atual (implementação corrigida)
     * No hardware DMG real, apenas os primeiros 10 sprites que aparecem na scanline
     * são selecionados, independentemente da posição X
     */
    private int countSpritesOnScanline() {
        if (!isSpriteDisplayEnabled()) return 0;
        
        int spriteHeight = isSpriteSize8x16() ? 16 : 8;
        int count = 0;
        
        // OAM Scan: Verificar os 40 sprites em ordem, selecionar os primeiros 10 que aparecem na scanline
        // Este é o comportamento real do DMG - os primeiros 10 encontrados, não necessariamente os de menor X
        for (int i = 0; i < 40 && count < 10; i++) {
            int spriteY = (oam[i * 4] & 0xFF) - 16;
            
            // Sprite está na scanline atual?
            if (ly >= spriteY && ly < spriteY + spriteHeight) {
                count++;
            }
        }
        
        return count;
    }
    
    /**
     * Atualiza as interrupções STAT com detecção de borda precisa
     * 
     * Implementação baseada no comportamento do hardware DMG:
     * - Interrupções STAT são disparadas na borda de subida (0→1) da linha STAT
     * - A linha STAT é a OR lógica de todas as condições habilitadas
     * - LYC=LY é comparada em ciclos específicos (ciclo 4 do modo 2, e ciclo 4 da linha 153→0)
     * - Mudanças no STAT podem causar interrupções espúrias (STAT write bug)
     */
    private void updateStatInterrupts() {
        boolean newStatLine = false;
        
        // Verificar condições de interrupção STAT baseadas no modo atual
        // Mode 0 (H-Blank) interrupt
        if ((stat & 0x08) != 0 && ppuMode == 0) {
            newStatLine = true;
        }
        
        // Mode 1 (V-Blank) interrupt  
        if ((stat & 0x10) != 0 && ppuMode == 1) {
            newStatLine = true;
        }
        
        // Mode 2 (OAM) interrupt
        if ((stat & 0x20) != 0 && ppuMode == 2) {
            newStatLine = true;
        }
        
        // LYC=LY interrupt - comparação acontece em ciclos específicos
        boolean lycEqualsLy = (ly == lyc);
        
        // Atualizar a flag LYC=LY no registrador STAT
        if (lycEqualsLy) {
            stat |= 0x04; // Set bit 2 (LYC=LY flag)
        } else {
            stat &= ~0x04; // Clear bit 2
        }
        
        // A interrupção LYC=LY é disparada quando:
        // 1. LYC=LY interrupt está habilitada (bit 6 do STAT)
        // 2. A condição LYC=LY é verdadeira
        // 3. Estamos em um ciclo onde a comparação deve ocorrer (lycComparisonThisCycle)
        //    ou a comparação acabou de se tornar verdadeira (edge detection)
        if ((stat & 0x40) != 0 && lycEqualsLy) {
            // Se estamos em um ciclo de comparação ou houve transição 0→1
            if (lycComparisonThisCycle || (!previousLycEqualsLy && lycEqualsLy)) {
                newStatLine = true;
            }
        }
        
        // Atualizar estado anterior para próxima comparação
        previousLycEqualsLy = lycEqualsLy;
        
        // Reset da flag de comparação (ela é setada apenas em ciclos específicos)
        lycComparisonThisCycle = false;
        
        // Disparar interrupção apenas na borda de subida (edge detection)
        // Isso previne múltiplas interrupções da mesma condição
        if (newStatLine && !statInterruptLine) {
            requestLcdStatInterrupt();
        }
        
        statInterruptLine = newStatLine;
        updateStatRegister();
    }
    
    /**
     * Solicita interrupção V-Blank
     */
    private void requestVBlankInterrupt() {
        if (mmu != null) {
            byte currentIF = (byte) mmu.readByte(MMU.REG_IF);
            mmu.writeByte(MMU.REG_IF, (byte) (currentIF | 0x01)); // Bit 0: V-Blank
        }
    }

    private void requestLcdStatInterrupt() {
        if (mmu != null) {
            byte currentIF = (byte) mmu.readByte(MMU.REG_IF);
            mmu.writeByte(MMU.REG_IF, (byte) (currentIF | 0x02)); // Seta bit 1 (LCD STAT)
        }
    }

    private void updateStatRegister() {
        // Limpa os bits de modo (0 e 1) e o bit de coincidência (2) antes de setá-los.
        // Bit de coincidência (bit 2, 0x04) é atualizado em checkLycEqualsLy.
        // Os bits 3-6 (interrupt select) e bit 7 (não usado) são preservados.
        stat = (stat & 0xF8) | (ppuMode & 0x03) | (stat & 0x04);
        if (mmu != null) {
            mmu.writeByte(MMU.REG_STAT, stat);
        }
    }


    private void renderScanline() {
        if (!isLcdEnabled()) return;

        PixelInfo[] scanlinePixelInfo = new PixelInfo[SCREEN_WIDTH]; // Informações dos pixels para a linha atual
        
        // Inicializa array de pixels com cor 0 (branco/transparente)
        for (int x = 0; x < SCREEN_WIDTH; x++) {
            scanlinePixelInfo[x] = new PixelInfo(0, false, false, bgp);
        }

        // 1. Renderizar Background (se habilitado)
        if (isBgWindowDisplayEnabled()) { // Bit 0 do LCDC (BG/Window display)
            renderBackgroundScanlineWithInfo(scanlinePixelInfo);
        }

        // 2. Renderizar Window (se habilitada e visível na scanline atual)
        if (isWindowDisplayEnabled() && isBgWindowDisplayEnabled()) { // Bit 5 e Bit 0 do LCDC
            renderWindowScanlineWithInfo(scanlinePixelInfo);
        }

        // 3. Renderizar Sprites (se habilitados) - SEMPRE processar se LCDC.1 está ativo
        if (isSpriteDisplayEnabled()) { // Bit 1 do LCDC
            renderSpritesScanlineWithInfo(scanlinePixelInfo);
        }

        // 4. Converter índices de cor para cores RGB finais e colocar no buffer de tela
        int baseIndex = ly * SCREEN_WIDTH;
        for (int x = 0; x < SCREEN_WIDTH; x++) {
            if (baseIndex + x < screenBuffer.length) { // Proteção de bounds
                PixelInfo pixel = scanlinePixelInfo[x];
                screenBuffer[baseIndex + x] = getColorFromPalette(pixel.colorIndex, pixel.paletteRegister);
            }
        }
    }

    private void renderBackgroundScanlineWithInfo(PixelInfo[] scanlinePixelInfo) {
        // IMPLEMENTAÇÃO CORRIGIDA baseada no Pandocs
        // LCDC Bit 4: BG Tile Data Select (0=0x8800-0x97FF, 1=0x8000-0x8FFF)
        // LCDC Bit 3: BG Tile Map Display Select (0=0x9800-0x9BFF, 1=0x9C00-0x9FFF)
        int tileDataArea = (lcdc & 0x10) != 0 ? 0x8000 : 0x8800;
        boolean signedAddressing = (tileDataArea == 0x8800);
        int tileMapArea = (lcdc & 0x08) != 0 ? 0x9C00 : 0x9800;

        // Posição Y no mapa de tiles do background (considerando o scroll SCY)
        int yInBgMap = (ly + scy) & 0xFF; // Y atual + scroll Y, wrapping em 256 pixels
        int tileRowInMap = yInBgMap / 8;  // Linha do tile no mapa 32x32
        int yInTile = yInBgMap % 8;       // Linha do pixel dentro do tile (0-7)

        for (int x = 0; x < SCREEN_WIDTH; x++) {
            int xInBgMap = (x + scx) & 0xFF; // X atual + scroll X, wrapping em 256 pixels
            int tileColInMap = xInBgMap / 8;  // Coluna do tile no mapa 32x32
            int xInTile = xInBgMap % 8;       // Coluna do pixel dentro do tile (0-7)

            // Endereço do índice do tile no mapa de tiles
            int tileMapOffset = tileRowInMap * 32 + tileColInMap;
            int tileIndexAddress = tileMapArea + tileMapOffset;
            
            // Proteção contra overflow - wrapping do tile map
            if (tileIndexAddress >= 0xA000) {
                tileIndexAddress = tileMapArea + (tileMapOffset & 0x3FF); // 1024 tiles max
            }
            
            int tileIndex = vram[tileIndexAddress - 0x8000] & 0xFF;

            // Endereço do tile na VRAM
            int tileAddress;
            if (signedAddressing) {
                // Índice é um byte assinado (-128 a 127), relativo a 0x9000
                tileAddress = 0x9000 + ((byte)tileIndex * 16);
            } else {
                // Índice é um byte não assinado (0 a 255), relativo ao início da tileDataArea
                tileAddress = tileDataArea + (tileIndex * 16);
            }

            // Cada tile tem 8 linhas, cada linha tem 2 bytes
            int tileRowDataAddress = tileAddress + (yInTile * 2);
            
            // Proteção contra acesso inválido à VRAM
            if (tileRowDataAddress < 0x8000 || tileRowDataAddress + 1 >= 0xA000) {
                scanlinePixelInfo[x] = new PixelInfo(0, false, false, bgp);
                continue;
            }

            int lsb = vram[tileRowDataAddress - 0x8000] & 0xFF;
            int msb = vram[tileRowDataAddress + 1 - 0x8000] & 0xFF;

            // Extrai a cor do pixel (2 bits)
            int bitPosition = 7 - xInTile;
            int colorBit0 = (lsb >> bitPosition) & 1;
            int colorBit1 = (msb >> bitPosition) & 1;
            int colorIndex = (colorBit1 << 1) | colorBit0;

            scanlinePixelInfo[x] = new PixelInfo(colorIndex, false, false, bgp);
        }
    }

    private void renderWindowScanlineWithInfo(PixelInfo[] scanlinePixelInfo) {
        // A janela só é exibida se ly >= WY
        if (ly < wy) return;

        // LCDC Bit 6: Window Tile Map Display Select (0=0x9800-0x9BFF, 1=0x9C00-0x9FFF)
        // LCDC Bit 4: BG/Window Tile Data Select (mesmo que o BG)
        int tileDataArea = (lcdc & 0x10) != 0 ? 0x8000 : 0x8800;
        boolean signedAddressing = (tileDataArea == 0x8800);
        int tileMapArea = (lcdc & 0x40) != 0 ? 0x9C00 : 0x9800;

        // WX é a coordenada X da janela - 7.
        // Se WX < 7, a janela começa na borda esquerda.
        int actualWX = wx - 7;

        // Verificar se a window está visível nesta scanline (se pelo menos um pixel será desenhado)
        boolean windowRenderedThisLine = false;

        // Usar o contador interno da Window ao invés de ly - wy
        // O contador só incrementa quando a window é realmente renderizada
        int yInWindow = windowLineCounter;
        int tileRowInMap = yInWindow / 8;
        int yInTile = yInWindow % 8;

        for (int x = 0; x < SCREEN_WIDTH; x++) {
            // A janela só cobre pixels onde x >= WX'
            if (x < actualWX) continue;

            windowRenderedThisLine = true; // Pelo menos um pixel da window será desenhado

            int xInWindow = x - actualWX; // Coordenada X relativa à borda esquerda da janela
            int tileColInMap = xInWindow / 8;
            int xInTile = xInWindow % 8;

            // Se a coluna do tile for > 31, não há mais tiles da janela nessa linha.
            if (tileColInMap >= 32) continue;

            int tileMapOffset = tileRowInMap * 32 + tileColInMap;
            int tileIndexAddress = tileMapArea + tileMapOffset;

            // Proteção de bounds para tileIndexAddress dentro da VRAM
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

            // A Window SEMPRE sobrescreve o background (incluindo cor 0)
            // Window não é transparente como sprites
            scanlinePixelInfo[x] = new PixelInfo(colorIndex, false, false, bgp); // Window usa BGP
        }

        // Incrementar o contador interno da Window apenas se ela foi renderizada nesta linha
        if (windowRenderedThisLine) {
            windowLineCounter++;
        }
    }

    private void renderSpritesScanlineWithInfo(PixelInfo[] scanlinePixelInfo) {
        if (!isSpriteDisplayEnabled()) return; // LCDC bit 1
        
        // LCDC Bit 2: Sprite Size (0=8x8, 1=8x16)
        boolean tallSprites = (lcdc & 0x04) != 0;
        int spriteHeight = tallSprites ? 16 : 8;

        // Lista de sprites visíveis na scanline atual
        List<VisibleSprite> visibleSprites = new ArrayList<>();

        // 1. OAM Scan: Encontrar até 10 sprites visíveis na scanline atual
        // No DMG real, os primeiros 10 sprites encontrados são selecionados (ordem da OAM)
        for (int i = 0; i < 40; i++) {
            int oamAddr = i * 4; // Cada sprite tem 4 bytes na OAM

            int spriteY = (oam[oamAddr] & 0xFF) - 16; // Y-coord do topo do sprite
            int spriteX = (oam[oamAddr + 1] & 0xFF) - 8;  // X-coord da esquerda do sprite
            int tileIndex = oam[oamAddr + 2] & 0xFF;
            int attributes = oam[oamAddr + 3] & 0xFF;

            // Sprite está na scanline atual?
            if (ly >= spriteY && ly < (spriteY + spriteHeight)) {
                visibleSprites.add(new VisibleSprite(i, spriteX, spriteY, tileIndex, attributes));
                
                // Limite de 10 sprites por scanline (comportamento do hardware DMG)
                if (visibleSprites.size() >= 10) break;
            }
        }

        // 2. Ordenar sprites por prioridade de renderização (importante para ordem de desenho)
        // Prioridade de renderização (da menor para maior prioridade visual):
        // - Sprites com X maior são desenhados primeiro (menor prioridade)
        // - Se X é igual, índice OAM maior é desenhado primeiro
        // Resultado: sprites com menor X aparecem na frente, e com X igual, menor índice aparece na frente
        visibleSprites.sort((a, b) -> {
            // Ordenar por X decrescente (X maior = menor prioridade visual = desenhar primeiro)
            if (a.spriteX != b.spriteX) {
                return Integer.compare(b.spriteX, a.spriteX);
            }
            // Se X igual, ordenar por índice OAM decrescente (índice maior = desenhar primeiro)
            return Integer.compare(b.spriteIndex, a.spriteIndex);
        });

        // 3. Renderizar sprites na ordem de prioridade
        // Sprites com menor prioridade visual são desenhados primeiro,
        // depois sprites com maior prioridade sobrescrevem
        for (VisibleSprite sprite : visibleSprites) {
            boolean yFlip = (sprite.attributes & 0x40) != 0;
            boolean xFlip = (sprite.attributes & 0x20) != 0;
            boolean bgPriority = (sprite.attributes & 0x80) != 0; // 0: Sprite sobre BG/Win, 1: Sprite atrás de BG/Win cores 1-3
            int paletteReg = (sprite.attributes & 0x10) != 0 ? obp1 : obp0;

            int tileIndex = sprite.tileIndex;
            // Para sprites 8x16, o tileIndex do tile inferior é tileIndex | 1
            // e o tileIndex do tile superior é tileIndex & 0xFE
            if (tallSprites) {
                tileIndex &= 0xFE; // Ignora o bit 0 para o tile superior
            }

            int yInSpriteTile = ly - sprite.spriteY;
            if (yFlip) {
                yInSpriteTile = (spriteHeight - 1) - yInSpriteTile;
            }

            // Se for 8x16 e estamos na metade inferior do sprite
            if (tallSprites && yInSpriteTile >= 8) {
                tileIndex |= 0x01; // Usa o tile inferior
                yInSpriteTile -= 8;  // Ajusta y para o tile inferior
            }

            int tileAddress = 0x8000 + (tileIndex * 16); // Sprites sempre usam 0x8000-0x8FFF
            int tileRowDataAddress = tileAddress + (yInSpriteTile * 2);

            if (tileRowDataAddress < 0x8000 || tileRowDataAddress + 1 >= 0xA000) continue;

            int lsb = vram[tileRowDataAddress - 0x8000] & 0xFF;
            int msb = vram[tileRowDataAddress + 1 - 0x8000] & 0xFF;

            // Desenha os 8 pixels do sprite na linha
            for (int px = 0; px < 8; px++) {
                int screenX = sprite.spriteX + px;
                if (screenX >= 0 && screenX < SCREEN_WIDTH) { // Dentro da tela
                    int xInTilePixel = xFlip ? (7 - px) : px;

                    int bitPosition = 7 - xInTilePixel;
                    int colorBit0 = (lsb >> bitPosition) & 1;
                    int colorBit1 = (msb >> bitPosition) & 1;
                    int colorIndex = (colorBit1 << 1) | colorBit0;

                    if (colorIndex == 0) continue; // Cor 0 é transparente para sprites

                    // Aplicar lógica de prioridade sprite vs BG/Window
                    // Baseada no Pandocs e comportamento do hardware DMG
                    PixelInfo currentPixel = scanlinePixelInfo[screenX];
                    boolean shouldDraw = true;
                    
                    // Se já existe um sprite desenhado neste pixel, não sobrescrever
                    // (sprite com maior prioridade visual já foi desenhado)
                    if (currentPixel.fromSprite) {
                        shouldDraw = false;
                    }
                    
                    // Se bgPriority (bit 7) está ativo e BG/Window display está ativo
                    // Sprite só aparece se o pixel do BG/Window for cor 0 (branco/transparente)
                    if (!currentPixel.fromSprite && bgPriority && isBgWindowDisplayEnabled() && currentPixel.colorIndex != 0) {
                        shouldDraw = false; // BG/Window tem prioridade sobre este sprite
                    }
                    
                    if (shouldDraw) {
                        scanlinePixelInfo[screenX] = new PixelInfo(colorIndex, true, bgPriority, paletteReg);
                    }
                }
            }
        }
    }

    /**
     * Converte um índice de cor (0-3) para cor RGB usando a paleta especificada
     * Implementação corrigida baseada no Pandocs
     */
    private int getColorFromPalette(int colorIndex, int paletteRegister) {
        // Cada cor usa 2 bits na paleta
        int shift = colorIndex * 2;
        int paletteColorIndex = (paletteRegister >> shift) & 0x03;
        
        // Retorna a cor RGB correspondente
        return COLORS[paletteColorIndex];
    }

    // --- Getters para os bits de controle do LCDC ---
    public boolean isLcdEnabled() { return (lcdc & 0x80) != 0; } // Bit 7
    // Bit 6: Window Tile Map Select (0=9800-9BFF, 1=9C00-9FFF)
    public boolean isWindowDisplayEnabled() { return (lcdc & 0x20) != 0; } // Bit 5
    // Bit 4: BG & Window Tile Data Select (0=8800-97FF, 1=8000-8FFF)
    // Bit 3: BG Tile Map Display Select (0=9800-9BFF, 1=9C00-9FFF)
    public boolean isSpriteSize8x16() { return (lcdc & 0x04) != 0; } // Bit 2
    public boolean isSpriteDisplayEnabled() { return (lcdc & 0x02) != 0; } // Bit 1
    public boolean isBgWindowDisplayEnabled() { return (lcdc & 0x01) != 0; } // Bit 0 (BG display no DMG, BG/Win master no CGB)

    // --- Getters e Setters para registradores (usados pela MMU/CPU) ---
    public int getLcdc() { return lcdc; }
    public void setLcdc(int value) {
        boolean wasLcdEnabled = isLcdEnabled();
        this.lcdc = value & 0xFF;
        boolean isLcdEnabled = isLcdEnabled();
        
        if (wasLcdEnabled && !isLcdEnabled) {
            // LCD foi desligado
            ly = 0;
            if (mmu != null) {
                mmu.writeByte(MMU.REG_LY, ly);
            }
            ppuMode = 0; // H-Blank quando LCD desabilitado
            cyclesCounter = 0;
            scanlineCycles = 0;
            statInterruptLine = false;
            previousLycEqualsLy = false;
            lycComparisonThisCycle = false;
            updateStatRegister();
            
            // Limpa a tela
            Arrays.fill(screenBuffer, COLORS[0]);
            frameCompleted = true;
        } else if (!wasLcdEnabled && isLcdEnabled) {
            // LCD foi ligado
            ly = 0;
            if (mmu != null) {
                mmu.writeByte(MMU.REG_LY, ly);
            }
            ppuMode = 2; // Começar com OAM Scan
            cyclesCounter = 0;
            scanlineCycles = 0;
            statInterruptLine = false;
            previousLycEqualsLy = false;
            lycComparisonThisCycle = false;
            updateStatRegister();
        }
    }

    public int getStat() { return (stat & 0xFF) | 0x80; } // Bit 7 do STAT é sempre 1
    public void setStat(int value) { 
        // Salvar o estado anterior da linha STAT antes da escrita
        boolean oldStatLine = statInterruptLine;
        
        // Preservar os bits read-only (bits 0-2: modo e LYC=LY flag)
        // Apenas os bits 3-6 (interrupt enables) podem ser escritos
        int readOnlyBits = stat & 0x07; // Bits 0-2 (modo atual e LYC=LY flag)
        this.stat = (value & 0x78) | readOnlyBits; // Bits 3-6 são writable, bit 7 é sempre 1
        
        // STAT write bug: escrever no STAT pode causar uma interrupção espúria
        // Isto acontece se:
        // 1. O LCD está ligado
        // 2. Não estamos no modo 1 (V-Blank) - o bug não ocorre no V-Blank
        // 3. A linha STAT estava baixa (false) antes da escrita
        // 
        // A razão é que escrever no STAT causa um pequeno glitch no hardware
        // que pode ser interpretado como uma borda de subida na linha STAT
        if (isLcdEnabled() && ppuMode != 1 && !oldStatLine) {
            // Verificar se alguma condição de interrupção está ativa após a escrita
            boolean anyConditionActive = false;
            
            // Mode 0 interrupt
            if ((stat & 0x08) != 0 && ppuMode == 0) {
                anyConditionActive = true;
            }
            
            // Mode 2 interrupt
            if ((stat & 0x20) != 0 && ppuMode == 2) {
                anyConditionActive = true;
            }
            
            // LYC=LY interrupt
            if ((stat & 0x40) != 0 && (stat & 0x04) != 0) {
                anyConditionActive = true;
            }
            
            // Se alguma condição está ativa, disparar interrupção espúria (STAT write bug)
            if (anyConditionActive) {
                requestLcdStatInterrupt();
                statInterruptLine = true; // Atualizar o estado da linha
            }
        }
    }

    public int getScy() { return scy; }
    public void setScy(int value) { this.scy = value & 0xFF; }

    public int getScx() { return scx; }
    public void setScx(int value) { this.scx = value & 0xFF; }

    public int getLy() { return ly; }
    // LY é read-only para a CPU, apenas a PPU o modifica.

    public int getLyc() { return lyc; }
    public void setLyc(int value) { 
        this.lyc = value & 0xFF;
        
        // Escrever em LYC pode causar uma comparação imediata (não é exatamente um bug, mas comportamento do hardware)
        // A comparação LYC=LY normalmente acontece em ciclos específicos, mas mudanças em LYC
        // podem disparar uma verificação imediata
        boolean newLycEqualsLy = (ly == lyc);
        
        // Se a nova comparação é verdadeira e a anterior era falsa, isso é uma transição
        if (newLycEqualsLy && !previousLycEqualsLy) {
            lycComparisonThisCycle = true; // Marcar para verificação neste ciclo
        }
        
        // Atualizar imediatamente a flag LYC=LY no STAT
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

    // --- Acesso à VRAM e OAM pela MMU com restrições implementadas ---
    /**
     * Lê um byte da VRAM com restrições de timing
     * VRAM é inacessível durante o modo 3 (Drawing) quando o LCD está ligado
     * @return 0xFF se inacessível, valor da VRAM caso contrário
     */
    public byte readVRAM(int address) {
        // Restrição de acesso: VRAM não pode ser acessada durante Drawing (modo 3) quando LCD está ligado
        if (isLcdEnabled() && ppuMode == 3) {
            return (byte) 0xFF; // VRAM inacessível durante Drawing - retorna 0xFF conforme hardware real
        }
        if (address >= 0 && address < vram.length) {
            return vram[address];
        }
        return (byte) 0xFF; // Fora dos limites
    }

    /**
     * Escreve um byte na VRAM com restrições de timing
     * VRAM é inacessível durante o modo 3 (Drawing) quando o LCD está ligado
     */
    public void writeVRAM(int address, byte value) {
        // Restrição de acesso: VRAM não pode ser escrita durante Drawing (modo 3) quando LCD está ligado
        if (isLcdEnabled() && ppuMode == 3) {
            return; // VRAM inacessível durante Drawing - escrita é ignorada
        }
        if (address >= 0 && address < vram.length) {
            vram[address] = value;
        }
    }

    /**
     * Lê um byte da OAM com restrições de timing
     * OAM é inacessível durante os modos 2 (OAM Scan) e 3 (Drawing) quando o LCD está ligado
     * @return 0xFF se inacessível, valor da OAM caso contrário
     */
    public byte readOAM(int address) {
        // Restrição de acesso: OAM não pode ser acessada durante OAM Scan (modo 2) ou Drawing (modo 3) quando LCD está ligado
        if (isLcdEnabled() && (ppuMode == 2 || ppuMode == 3)) {
            return (byte) 0xFF; // OAM inacessível durante OAM Scan e Drawing - retorna 0xFF conforme hardware real
        }
        if (address >= 0 && address < oam.length) {
            return oam[address];
        }
        return (byte) 0xFF;
    }

    /**
     * Escreve um byte na OAM com restrições de timing
     * OAM é inacessível durante os modos 2 (OAM Scan) e 3 (Drawing) quando o LCD está ligado
     */
    public void writeOAM(int address, byte value) {
        // Restrição de acesso: OAM não pode ser escrita durante OAM Scan (modo 2) ou Drawing (modo 3) quando LCD está ligado
        if (isLcdEnabled() && (ppuMode == 2 || ppuMode == 3)) {
            return; // OAM inacessível durante OAM Scan e Drawing - escrita é ignorada
        }
        if (address >= 0 && address < oam.length) {
            oam[address] = value;
        }
    }

    // --- Para a Janela de Exibição ---
    public int[] getScreenBuffer() {
        return screenBuffer;
    }

    public boolean isFrameCompleted() {
        if (frameCompleted) {
            frameCompleted = false; // Reseta para a próxima frame
            return true;
        }
        return false;
    }
    
    /**
     * Define um pixel no buffer de tela (usado pelo sistema FIFO)
     */
    public void setPixel(int x, int y, int color) {
        if (x >= 0 && x < SCREEN_WIDTH && y >= 0 && y < SCREEN_HEIGHT) {
            int index = y * SCREEN_WIDTH + x;
            if (index < screenBuffer.length) {
                screenBuffer[index] = color;
            }
        }
    }
    
    /**
     * Getter para o modo atual da PPU (para debug)
     */
    public int getPpuMode() {
        return ppuMode;
    }
    
    /**
     * Habilita ou desabilita renderização pixel-a-pixel (Pixel FIFO)
     * Quando habilitado, permite efeitos mid-scanline mas pode ter impacto na performance
     * @param enable true para habilitar, false para usar renderização por scanline
     */
    public void setPixelFifoEnabled(boolean enable) {
        this.enablePixelFifo = enable;
        System.out.println("Pixel FIFO " + (enable ? "habilitado" : "desabilitado"));
    }
    
    /**
     * Verifica se o Pixel FIFO está habilitado
     */
    public boolean isPixelFifoEnabled() {
        return enablePixelFifo;
    }

    // Save/Load State
    public void saveState(java.io.DataOutputStream dos) throws java.io.IOException {
        // Save VRAM (8KB)
        for (int i = 0; i < vram.length; i++) {
            dos.writeByte(vram[i]);
        }
        // Save OAM (160 bytes)
        for (int i = 0; i < oam.length; i++) {
            dos.writeByte(oam[i]);
        }
        // Save LCD registers
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
        // Save PPU state
        dos.writeInt(ppuMode);
        dos.writeInt(cyclesCounter);
        dos.writeBoolean(frameCompleted);
    }

    public void loadState(java.io.DataInputStream dis) throws java.io.IOException {
        // Load VRAM
        for (int i = 0; i < vram.length; i++) {
            vram[i] = dis.readByte();
        }
        // Load OAM
        for (int i = 0; i < oam.length; i++) {
            oam[i] = dis.readByte();
        }
        // Load LCD registers
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
        // Load PPU state
        ppuMode = dis.readInt();
        cyclesCounter = dis.readInt();
        frameCompleted = dis.readBoolean();
    }
}