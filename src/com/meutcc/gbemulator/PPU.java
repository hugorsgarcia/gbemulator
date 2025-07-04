package com.meutcc.gbemulator;

import java.util.Arrays;

public class PPU {
    // Resolução da tela do Game Boy
    public static final int SCREEN_WIDTH = 160;
    public static final int SCREEN_HEIGHT = 144;

    // Memória da PPU
    private final byte[] vram = new byte[8192]; // 8KB (0x8000-0x9FFF)
    private final byte[] oam = new byte[160];   // 160 bytes (0xFE00-0xFE9F) - Sprite Attribute Table

    // Buffer de pixels para a tela (um frame)
    // Usaremos inteiros para representar cores ARGB (A será FF)
    private final int[] screenBuffer = new int[SCREEN_WIDTH * SCREEN_HEIGHT];
    private boolean frameCompleted = false;

    // Cores do Game Boy Clássico (tons de cinza)
    // Mapeadas para RGB para exibição
    // 00: Branco, 01: Cinza Claro, 10: Cinza Escuro, 11: Preto
    private final int[] COLORS = {
            0xFFFFFFFF, // Branco (cor 0)
            0xFFAAAAAA, // Cinza Claro (cor 1)
            0xFF555555, // Cinza Escuro (cor 2)
            0xFF000000  // Preto (cor 3)
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
        System.out.println("PPU reset.");
    }

    // Atualiza o estado da PPU com base nos ciclos da CPU
    public void update(int cpuCycles) {
        if (!isLcdEnabled()) {
            // Se LCD está desligado, PPU está inativa, LY é 0, modo é 0 (HBlank) ou 1 (VBlank).
            // O comportamento exato do LCD desligado é complexo (timing, acesso a VRAM/OAM).
            // Simplificação: Se LCD desligado, não fazemos nada, LY=0.
            // A VRAM pode ser acessada, OAM não.
            ly = 0;
            ppuMode = 1; // Ou 0, dependendo da especificação. VBlank é mais seguro.
            cyclesCounter = 0;
            // Atualizar STAT e LY na MMU
            mmu.writeByte(MMU.REG_LY, ly);
            updateStatRegister();
            return;
        }

        cyclesCounter += cpuCycles;

        switch (ppuMode) {
            case 2: // OAM Scan (Procurando sprites na linha atual) - Dura 80 ciclos
                if (cyclesCounter >= 80) {
                    cyclesCounter -= 80; // ou cyclesCounter = cyclesCounter % 80
                    ppuMode = 3; // Muda para modo de desenho
                    updateStatRegister();
                }
                break;

            case 3: // Drawing Pixels (Transferindo para LCD) - Dura ~172-289 ciclos (varia)
                // Simplificação: usaremos um valor fixo, por exemplo, 172
                if (cyclesCounter >= 172) { // Duração mínima
                    cyclesCounter -= 172;
                    ppuMode = 0; // Muda para HBlank
                    updateStatRegister();
                    // Renderiza a scanline AQUI, pois os dados estão prontos
                    if (ly < SCREEN_HEIGHT) { // Só renderiza se estiver dentro da área visível
                        renderScanline();
                    }
                    // Solicitar interrupção STAT Modo 0 (HBlank), se habilitada no STAT
                    if ((stat & 0x08) != 0) requestLcdStatInterrupt();
                }
                break;

            case 0: // HBlank (Horizontal Blank) - Dura o resto dos 456 ciclos da linha
                // Total por linha = 456 ciclos. 80 (OAM) + 172 (Draw) = 252.
                // Restante para HBlank = 456 - 252 = 204 ciclos.
                if (cyclesCounter >= 204) {
                    cyclesCounter -= 204;
                    ly++; // Próxima scanline
                    mmu.writeByte(MMU.REG_LY, ly); // Atualiza o registrador LY na MMU

                    if (ly == SCREEN_HEIGHT) { // Chegou ao fim da tela visível
                        ppuMode = 1; // Entra em VBlank
                        updateStatRegister();
                        frameCompleted = true; // Sinaliza que um frame está pronto
                        // Solicitar interrupção VBlank (Bit 0 do IF)
                        byte currentIF = (byte) mmu.readByte(MMU.REG_IF);
                        mmu.writeByte(MMU.REG_IF, (byte) (currentIF | 0x01));
                        // Solicitar interrupção STAT Modo 1 (VBlank), se habilitada no STAT
                        if ((stat & 0x10) != 0) requestLcdStatInterrupt();

                    } else { // Ainda desenhando linhas visíveis
                        ppuMode = 2; // Volta para OAM Scan para a nova linha
                        updateStatRegister();
                        // Solicitar interrupção STAT Modo 2 (OAM), se habilitada no STAT
                        if ((stat & 0x20) != 0) requestLcdStatInterrupt();
                    }

                    // Checa LYC=LY após LY ser incrementado
                    checkLycEqualsLy();
                }
                break;

            case 1: // VBlank (Vertical Blank) - Linhas 144-153. Cada linha dura 456 ciclos.
                // VBlank dura 10 linhas * 456 ciclos/linha = 4560 ciclos.
                if (cyclesCounter >= 456) { // Uma linha inteira de VBlank passou
                    cyclesCounter -= 456;
                    ly++;
                    mmu.writeByte(MMU.REG_LY, ly); // Atualiza LY

                    if (ly > 153) { // Fim do VBlank (após linha 153)
                        ly = 0; // Reseta para primeira scanline
                        mmu.writeByte(MMU.REG_LY, ly);
                        ppuMode = 2; // Começa novo frame com OAM Scan
                        updateStatRegister();
                        // Solicitar interrupção STAT Modo 2 (OAM), se habilitada no STAT
                        if ((stat & 0x20) != 0) requestLcdStatInterrupt();
                    }
                    // Checa LYC=LY durante VBlank também
                    checkLycEqualsLy();
                }
                break;
        }
    }

    private void checkLycEqualsLy() {
        if (ly == lyc) {
            stat |= 0x04; // Seta bit de coincidência LYC=LY no STAT
            if ((stat & 0x40) != 0) { // Se interrupção de LYC=LY está habilitada
                requestLcdStatInterrupt();
            }
        } else {
            stat &= ~0x04; // Reseta bit de coincidência
        }
        updateStatRegister(); // Garante que o registrador STAT na MMU está atualizado
    }

    private void requestLcdStatInterrupt() {
        byte currentIF = (byte) mmu.readByte(MMU.REG_IF);
        mmu.writeByte(MMU.REG_IF, (byte) (currentIF | 0x02)); // Seta bit 1 (LCD STAT)
    }

    private void updateStatRegister() {
        // Limpa os bits de modo (0 e 1) e o bit de coincidência (2) antes de setá-los.
        // Bit de coincidência (bit 2, 0x04) é atualizado em checkLycEqualsLy.
        // Os bits 3-6 (interrupt select) e bit 7 (não usado) são preservados.
        stat = (stat & 0xF8) | (ppuMode & 0x03) | (stat & 0x04);
        mmu.writeByte(MMU.REG_STAT, stat);
    }


    private void renderScanline() {
        if (!isLcdEnabled()) return;

        int[] scanlinePixels = new int[SCREEN_WIDTH]; // Pixels para a linha atual

        // 1. Renderizar Background (se habilitado)
        if (isBgWindowDisplayEnabled()) { // Bit 0 do LCDC (BG/Window display)
            renderBackgroundScanline(scanlinePixels);
        } else {
            // Se BG/Window está desabilitado, a tela é branca (cor 0)
            for (int x = 0; x < SCREEN_WIDTH; x++) {
                scanlinePixels[x] = getColorFromPalette(0, bgp);
            }
        }

        // 2. Renderizar Window (se habilitada e visível na scanline atual)
        if (isWindowDisplayEnabled() && isBgWindowDisplayEnabled()) { // Bit 5 e Bit 0 do LCDC
            renderWindowScanline(scanlinePixels);
        }

        // 3. Renderizar Sprites (se habilitados)
        if (isSpriteDisplayEnabled()) { // Bit 1 do LCDC
            renderSpritesScanline(scanlinePixels);
        }

        // Copia os pixels da scanline para o buffer de frame principal
        // (y * width + x)
        int baseIndex = ly * SCREEN_WIDTH;
        for (int x = 0; x < SCREEN_WIDTH; x++) {
            if (baseIndex + x < screenBuffer.length) { // Proteção de bounds
                screenBuffer[baseIndex + x] = scanlinePixels[x];
            }
        }
    }

    private void renderBackgroundScanline(int[] scanlinePixels) {
        // LCDC Bit 4: BG Tile Data Select (0=0x8800-0x97FF, 1=0x8000-0x8FFF)
        // LCDC Bit 3: BG Tile Map Display Select (0=0x9800-0x9BFF, 1=0x9C00-0x9FFF)
        int tileDataArea = (lcdc & 0x10) != 0 ? 0x8000 : 0x8800;
        boolean signedAddressing = (tileDataArea == 0x8800); // Se 0x8800, o índice do tile é signed byte
        int tileMapArea = (lcdc & 0x08) != 0 ? 0x9C00 : 0x9800;

        // Posição Y no mapa de tiles do background (considerando o scroll SCY)
        // O mapa de tiles tem 32x32 tiles. Cada tile tem 8x8 pixels.
        // O background "real" tem 256x256 pixels.
        int yInBgMap = (ly + scy) & 0xFF; // Y atual + scroll Y, dentro do mapa de 256 pixels
        int tileRowInMap = yInBgMap / 8;  // Linha do tile no mapa de 32x32 tiles
        int yInTile = yInBgMap % 8;       // Linha do pixel dentro do tile (0-7)

        for (int x = 0; x < SCREEN_WIDTH; x++) {
            int xInBgMap = (x + scx) & 0xFF; // X atual + scroll X, dentro do mapa de 256 pixels
            int tileColInMap = xInBgMap / 8;  // Coluna do tile no mapa de 32x32 tiles
            int xInTile = xInBgMap % 8;       // Coluna do pixel dentro do tile (0-7)

            // Endereço do índice do tile no mapa de tiles
            int tileMapOffset = tileRowInMap * 32 + tileColInMap;
            int tileIndexAddress = tileMapArea + tileMapOffset;
            int tileIndex = vram[tileIndexAddress - 0x8000] & 0xFF;

            // Endereço do tile na VRAM
            int tileAddress;
            if (signedAddressing) {
                // Índice é um byte assinado (-128 a 127), relativo a 0x9000
                tileAddress = tileDataArea + ((byte)tileIndex * 16); // Cada tile tem 16 bytes
            } else {
                // Índice é um byte não assinado (0 a 255), relativo ao início da tileDataArea
                tileAddress = tileDataArea + (tileIndex * 16);
            }

            // Cada tile tem 8 linhas, cada linha tem 2 bytes
            // Byte 1: bits menos significativos das cores
            // Byte 2: bits mais significativos das cores
            int tileRowDataAddress = tileAddress + (yInTile * 2);
            if (tileRowDataAddress < 0x8000 || tileRowDataAddress +1 >= 0xA000) continue; // Fora da VRAM

            int lsb = vram[tileRowDataAddress - 0x8000] & 0xFF;
            int msb = vram[tileRowDataAddress + 1 - 0x8000] & 0xFF;

            // Extrai a cor do pixel (2 bits)
            // Os pixels são armazenados da esquerda para a direita, bit 7 é o mais à esquerda.
            // Para pegar o bit correto, invertemos xInTile (7-xInTile)
            int bitPosition = 7 - xInTile;
            int colorBit0 = (lsb >> bitPosition) & 1;
            int colorBit1 = (msb >> bitPosition) & 1;
            int colorIndex = (colorBit1 << 1) | colorBit0; // 00, 01, 10, 11

            scanlinePixels[x] = getColorFromPalette(colorIndex, bgp);
        }
    }

    private void renderWindowScanline(int[] scanlinePixels) {
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

        // Posição Y na janela
        int yInWindow = ly - wy; // Linha atual relativa ao topo da janela
        int tileRowInMap = yInWindow / 8;
        int yInTile = yInWindow % 8;

        for (int x = 0; x < SCREEN_WIDTH; x++) {
            // A janela só cobre pixels onde x >= WX'
            if (x < actualWX) continue;

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
                tileAddress = tileDataArea + ((byte)tileIndex * 16);
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

            // Cor 0 da janela é transparente para o background.
            // Outras cores da janela sobrescrevem o background.
            if (colorIndex != 0) {
                scanlinePixels[x] = getColorFromPalette(colorIndex, bgp); // Window usa BGP
            }
        }
    }

    private void renderSpritesScanline(int[] scanlinePixels) {
        // LCDC Bit 2: Sprite Size (0=8x8, 1=8x16)
        boolean tallSprites = (lcdc & 0x04) != 0;
        int spriteHeight = tallSprites ? 16 : 8;
        int spritesRendered = 0; // Máximo de 10 sprites por scanline

        // Iterar sobre todos os 40 sprites na OAM
        for (int i = 0; i < 40 && spritesRendered < 10; i++) {
            int oamAddr = i * 4; // Cada sprite tem 4 bytes na OAM

            int spriteY = (oam[oamAddr] & 0xFF) - 16; // Y-coord do topo do sprite
            int spriteX = (oam[oamAddr + 1] & 0xFF) - 8;  // X-coord da esquerda do sprite
            int tileIndex = oam[oamAddr + 2] & 0xFF;
            int attributes = oam[oamAddr + 3] & 0xFF;

            // Sprite está na scanline atual?
            // ly é a scanline atual. spriteY é o topo.
            if (ly >= spriteY && ly < (spriteY + spriteHeight)) {
                // Sprite está visível horizontalmente?
                if (spriteX < -7 || spriteX >= SCREEN_WIDTH) continue; // Completamente fora

                spritesRendered++;

                boolean yFlip = (attributes & 0x40) != 0;
                boolean xFlip = (attributes & 0x20) != 0;
                boolean bgPriority = (attributes & 0x80) != 0; // 0: Sprite sobre BG/Win, 1: Sprite atrás de BG/Win cores 1-3
                int paletteReg = (attributes & 0x10) != 0 ? obp1 : obp0;

                // Para sprites 8x16, o tileIndex do tile inferior é tileIndex | 1
                // e o tileIndex do tile superior é tileIndex & 0xFE
                if (tallSprites) {
                    tileIndex &= 0xFE; // Ignora o bit 0 para o tile superior
                }

                int yInSpriteTile = ly - spriteY;
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

                if (tileRowDataAddress < 0x8000 || tileRowDataAddress +1 >= 0xA000) continue;

                int lsb = vram[tileRowDataAddress - 0x8000] & 0xFF;
                int msb = vram[tileRowDataAddress + 1 - 0x8000] & 0xFF;

                // Desenha os 8 pixels do sprite na linha
                for (int px = 0; px < 8; px++) {
                    int screenX = spriteX + px;
                    if (screenX >= 0 && screenX < SCREEN_WIDTH) { // Dentro da tela
                        int xInTilePixel = xFlip ? (7 - px) : px;

                        int bitPosition = 7 - xInTilePixel;
                        int colorBit0 = (lsb >> bitPosition) & 1;
                        int colorBit1 = (msb >> bitPosition) & 1;
                        int colorIndex = (colorBit1 << 1) | colorBit0;

                        if (colorIndex == 0) continue; // Cor 0 é transparente para sprites

                        // Prioridade: Se bgPriority é true, o sprite só aparece se
                        // o pixel do BG/Window subjacente for cor 0.
                        if (bgPriority) {
                            // Para checar a cor do BG/Win, precisamos do valor do índice de cor, não da cor RGB.
                            // Isso é mais complexo. Simplificação: se bgPriority, o sprite está atrás.
                            // Uma checagem correta envolveria obter o colorIndex do BG/Janela naquele pixel.
                            // Para simplificar, vamos permitir que o sprite seja desenhado,
                            // mas uma implementação completa verificaria scanlinePixels[screenX] e a BGP.
                            // A condição é: (BG color index for pixel [X, Y] is 0)
                            // Por agora, vamos ignorar bgPriority se for muito complexo para o esqueleto.
                            // Uma forma simples (mas não 100% correta) seria:
                            // if (getColorFromPalette(0, bgp) != scanlinePixels[screenX]) continue;
                            // Isto acima é problemático porque scanlinePixels já tem cor RGB.
                            // A prioridade é: Sprite só aparece se o pixel do BG/Window for cor 0 da paleta BG/Window.
                            // Se o BG/Window Display (LCDC Bit 0) está desabilitado, essa prioridade não se aplica
                            // e o sprite é desenhado.
                            boolean bgEnabled = isBgWindowDisplayEnabled();
                            if (bgEnabled) {
                                // Precisamos re-calcular a cor do BG/Window para este pixel (sem sprites)
                                // Esta é uma simplificação grosseira e pode ser lenta.
                                // Uma PPU correta armazena mais metadados por pixel.
                                int bgColorAtPixel = getBgWindowPixelColorIndex(screenX, ly);
                                if (bgColorAtPixel != 0) { // Se BG/Win não é cor 0, sprite é escondido
                                    continue;
                                }
                            }
                        }
                        scanlinePixels[screenX] = getColorFromPalette(colorIndex, paletteReg);
                    }
                }
            }
        }
    }

    // Função auxiliar para obter o índice de cor do BG/Window em um pixel específico
    // (Necessário para a prioridade de sprite BG vs Sprite) - Simplificada
    private int getBgWindowPixelColorIndex(int screenX, int screenY) {
        // Esta é uma versão muito simplificada e pode ser imprecisa/incompleta.
        // Ignora Window por simplicidade agora, apenas BG.
        if (!isBgWindowDisplayEnabled()) return 0; // BG/Win off, então cor 0 (transparente para sprite)

        int tileDataArea = (lcdc & 0x10) != 0 ? 0x8000 : 0x8800;
        boolean signedAddressing = (tileDataArea == 0x8800);
        int tileMapArea = (lcdc & 0x08) != 0 ? 0x9C00 : 0x9800;

        int yInBgMap = (screenY + scy) & 0xFF;
        int tileRowInMap = yInBgMap / 8;
        int yInTile = yInBgMap % 8;

        int xInBgMap = (screenX + scx) & 0xFF;
        int tileColInMap = xInBgMap / 8;
        int xInTile = xInBgMap % 8;

        int tileMapOffset = tileRowInMap * 32 + tileColInMap;
        int tileIndexAddress = tileMapArea + tileMapOffset;
        if (tileIndexAddress < 0x8000 || tileIndexAddress >= 0xA000) return 0; // Fora VRAM
        int tileIndex = vram[tileIndexAddress - 0x8000] & 0xFF;

        int tileAddress;
        if (signedAddressing) {
            tileAddress = tileDataArea + ((byte)tileIndex * 16);
        } else {
            tileAddress = tileDataArea + (tileIndex * 16);
        }

        int tileRowDataAddress = tileAddress + (yInTile * 2);
        if (tileRowDataAddress < 0x8000 || tileRowDataAddress + 1 >= 0xA000) return 0; // Fora VRAM

        int lsb = vram[tileRowDataAddress - 0x8000] & 0xFF;
        int msb = vram[tileRowDataAddress + 1 - 0x8000] & 0xFF;

        int bitPosition = 7 - xInTile;
        int colorBit0 = (lsb >> bitPosition) & 1;
        int colorBit1 = (msb >> bitPosition) & 1;
        return (colorBit1 << 1) | colorBit0;
    }


    // Traduz um índice de cor (0-3) para uma cor RGB usando a paleta fornecida
    private int getColorFromPalette(int colorIndex, int paletteRegister) {
        // A paleta (BGP, OBP0, OBP1) define quais tons de cinza correspondem
        // aos índices de cor 00, 01, 10, 11.
        // Bits 1-0: Cor para o índice 00
        // Bits 3-2: Cor para o índice 01
        // Bits 5-4: Cor para o índice 10
        // Bits 7-6: Cor para o índice 11
        int actualColor = 0;
        switch (colorIndex) {
            case 0: actualColor = (paletteRegister >> 0) & 0x03; break;
            case 1: actualColor = (paletteRegister >> 2) & 0x03; break;
            case 2: actualColor = (paletteRegister >> 4) & 0x03; break;
            case 3: actualColor = (paletteRegister >> 6) & 0x03; break;
        }
        return COLORS[actualColor];
    }

    // --- Getters para os bits de controle do LCDC ---
    public boolean isLcdEnabled() { return (lcdc & 0x80) != 0; } // Bit 7
    // Bit 6: Window Tile Map Select (0=9800-9BFF, 1=9C00-9FFF)
    public boolean isWindowDisplayEnabled() { return (lcdc & 0x20) != 0; } // Bit 5
    // Bit 4: BG & Window Tile Data Select (0=8800-97FF, 1=8000-8FFF)
    // Bit 3: BG Tile Map Display Select (0=9800-9BFF, 1=9C00-9FFF)
    public boolean isSpriteDisplayEnabled() { return (lcdc & 0x02) != 0; } // Bit 1
    public boolean isBgWindowDisplayEnabled() { return (lcdc & 0x01) != 0; } // Bit 0 (BG display no DMG, BG/Win master no CGB)

    // --- Getters e Setters para registradores (usados pela MMU/CPU) ---
    public int getLcdc() { return lcdc; }
    public void setLcdc(int value) {
        this.lcdc = value & 0xFF;
        if (!isLcdEnabled()) { // Se LCD foi desligado
            ly = 0;
            mmu.writeByte(MMU.REG_LY, ly); // LY reseta para 0
            ppuMode = 1; // Entra em VBlank Mode (ou HBlank, depende da fonte)
            cyclesCounter = 0;
            updateStatRegister();
            // Limpa a tela (opcional, mas alguns jogos esperam isso)
            Arrays.fill(screenBuffer, COLORS[0]);
            frameCompleted = true; // Para atualizar a tela em branco
        }
    }

    public int getStat() { return (stat & 0xFF) | 0x80; } // Bit 7 do STAT é sempre 1
    public void setStat(int value) { this.stat = (value & 0x7F); } // Ignora escrita no bit 7

    public int getScy() { return scy; }
    public void setScy(int value) { this.scy = value & 0xFF; }

    public int getScx() { return scx; }
    public void setScx(int value) { this.scx = value & 0xFF; }

    public int getLy() { return ly; }
    // LY é read-only para a CPU, apenas a PPU o modifica.

    public int getLyc() { return lyc; }
    public void setLyc(int value) { this.lyc = value & 0xFF; checkLycEqualsLy(); }

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

    // --- Acesso à VRAM e OAM pela MMU ---
    public byte readVRAM(int address) {
        // A VRAM só é acessível pela CPU quando a PPU não está no modo 3 (Drawing)
        // Simplificação: permitimos sempre por enquanto.
        // if (ppuMode == 3 && isLcdEnabled()) return (byte)0xFF; // VRAM inacessível
        if (address >= 0 && address < vram.length) {
            return vram[address];
        }
        return (byte) 0xFF; // Fora dos limites
    }

    public void writeVRAM(int address, byte value) {
        // if (ppuMode == 3 && isLcdEnabled()) return; // VRAM inacessível
        if (address >= 0 && address < vram.length) {
            vram[address] = value;
        }
    }

    public byte readOAM(int address) {
        // A OAM só é acessível pela CPU quando a PPU não está nos modos 2 (OAM Scan) ou 3 (Drawing)
        // Simplificação: permitimos sempre.
        // if ((ppuMode == 2 || ppuMode == 3) && isLcdEnabled()) return (byte)0xFF; // OAM inacessível
        if (address >= 0 && address < oam.length) {
            return oam[address];
        }
        return (byte) 0xFF;
    }

    public void writeOAM(int address, byte value) {
        // if ((ppuMode == 2 || ppuMode == 3) && isLcdEnabled()) return; // OAM inacessível
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
}