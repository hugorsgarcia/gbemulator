package com.meutcc.gbemulator;

import java.util.Arrays;

public class MMU {
    private final byte[] memory = new byte[0x10000]; // 64KB de espaço de endereço

    private Cartridge cartridge;
    private final PPU ppu;
    private final APU apu;
    private CPU cpu; // Referência à CPU para interações (ex: reset do DIV)

    // Estado do Boot ROM
    private boolean bootRomEnabled = false; // Começa desabilitado se não houver arquivo de boot rom
    private final byte[] bootRom = new byte[256]; // Se você tiver um boot rom para carregar

    // --- I/O Registers ---
    public static final int REG_JOYP = 0xFF00;
    public static final int REG_SB = 0xFF01;
    public static final int REG_SC = 0xFF02;
    public static final int REG_DIV = 0xFF04;
    public static final int REG_TIMA = 0xFF05;
    public static final int REG_TMA = 0xFF06;
    public static final int REG_TAC = 0xFF07;
    public static final int REG_IF = 0xFF0F;
    public static final int REG_LCDC = 0xFF40;
    public static final int REG_STAT = 0xFF41;
    public static final int REG_SCY = 0xFF42;
    public static final int REG_SCX = 0xFF43;
    public static final int REG_LY = 0xFF44;
    public static final int REG_LYC = 0xFF45;
    public static final int REG_DMA = 0xFF46;
    public static final int REG_BGP = 0xFF47;
    public static final int REG_OBP0 = 0xFF48;
    public static final int REG_OBP1 = 0xFF49;
    public static final int REG_WY = 0xFF4A;
    public static final int REG_WX = 0xFF4B;
    public static final int REG_KEY1 = 0xFF4D; // CGB Speed switch
    public static final int REG_BOOT_ROM_DISABLE = 0xFF50;
    public static final int REG_IE = 0xFFFF;

    // --- Joypad State ---
    private byte joypadState = (byte) 0xFF;

    // --- Timer State ---
    private int divCounter = 0;  // Contador interno de 16-bit para DIV
    
    // Bits do DIV selecionados por TAC para TIMA
    // TAC[1:0] = 00: bit 9 (4096 Hz)
    // TAC[1:0] = 01: bit 3 (262144 Hz) 
    // TAC[1:0] = 10: bit 5 (65536 Hz)
    // TAC[1:0] = 11: bit 7 (16384 Hz)
    private static final int[] TIMER_BIT_POSITIONS = {9, 3, 5, 7};

    // --- DMA State ---
    private int dmaCyclesRemaining = 0;
    private boolean dmaMemoryBlocked = false; // Bloqueio de memória durante OAM DMA

    // --- Serial State ---
    private int serialTransferCounter = 0;
    private boolean serialTransferInProgress = false;

    public MMU(Cartridge cartridge, PPU ppu, APU apu) {
        this.cartridge = cartridge;
        this.ppu = ppu;
        this.apu = apu;
        Arrays.fill(memory, (byte) 0x00);
    }

    // Setter para a referência da CPU
    public void setCpu(CPU cpu) {
        this.cpu = cpu;
    }

    public void reset() {
        for (int i = 0xC000; i < 0xE000; i++) memory[i] = 0;
        for (int i = 0xFF80; i < 0xFFFF; i++) memory[i] = 0;
        
        // Reset do estado do DMA
        dmaCyclesRemaining = 0;
        dmaMemoryBlocked = false;

        writeByte(REG_JOYP, (byte) 0xCF);
        writeByte(REG_SB, (byte) 0x00);
        writeByte(REG_SC, (byte) 0x7E);
        writeByte(REG_TIMA, (byte) 0x00);
        writeByte(REG_TMA, (byte) 0x00);
        writeByte(REG_TAC, (byte) 0x00);
        writeByte(REG_IF, (byte) 0xE1);
        writeByte(REG_LCDC, (byte) 0x91);
        writeByte(REG_STAT, (byte) 0x85);
        writeByte(REG_SCY, (byte) 0x00);
        writeByte(REG_SCX, (byte) 0x00);
        writeByte(REG_LYC, (byte) 0x00);
        writeByte(REG_BGP, (byte) 0xFC);
        writeByte(REG_OBP0, (byte) 0xFF);
        writeByte(REG_OBP1, (byte) 0xFF);
        writeByte(REG_WY, (byte) 0x00);
        writeByte(REG_WX, (byte) 0x00);
        writeByte(REG_IE, (byte) 0x00);
        writeByte(REG_BOOT_ROM_DISABLE, (byte) 0x00);

        joypadState = (byte) 0xFF;
        // --- Reset Timer State ---
        divCounter = 0;
        serialTransferCounter = 0;
        serialTransferInProgress = false;
        bootRomEnabled = false; // Assumindo que não estamos carregando uma boot rom real por padrão

        System.out.println("MMU reset and I/O registers initialized.");
    }

    public void updateTimers(int cycles) {
        // Atualizar DIV contador interno (16-bit) a cada ciclo
        for (int i = 0; i < cycles; i++) {
            updateTimersSingleCycle();
        }

        // --- Serial Logic ---
        if(serialTransferInProgress) {
            serialTransferCounter -= cycles;
            if(serialTransferCounter <= 0) {
                serialTransferInProgress = false;
                memory[REG_SC] &= ~0x80; // Reset transfer start flag
                memory[REG_IF] |= 0x08; // Request Serial Interrupt
            }
        }
    }
    
    /**
     * Atualiza os timers de forma precisa, ciclo por ciclo
     */
    private void updateTimersSingleCycle() {
        // Salvar o estado atual do bit selecionado ANTES de incrementar
        int tac = memory[REG_TAC] & 0xFF;
        int oldTimerBit = 0;
        
        if ((tac & 0x04) != 0) { // Timer habilitado
            int bitPosition = TIMER_BIT_POSITIONS[tac & 0x03];
            oldTimerBit = (divCounter >> bitPosition) & 1;
        }
        
        // Incrementar o contador interno DIV de 16-bit
        divCounter = (divCounter + 1) & 0xFFFF;
        
        // Atualizar o registrador DIV visível (bits 8-15 do contador interno)
        memory[REG_DIV] = (byte) ((divCounter >> 8) & 0xFF);
        
        // Verificar se TIMA deve incrementar baseado na transição
        if ((tac & 0x04) != 0) { // Timer habilitado
            int bitPosition = TIMER_BIT_POSITIONS[tac & 0x03];
            int newTimerBit = (divCounter >> bitPosition) & 1;
            
            // Detectar borda de subida (0 → 1)
            if (oldTimerBit == 0 && newTimerBit == 1) {
                incrementTIMA();
            }
        }
    }
    
    /**
     * Incrementa TIMA e trata overflow
     */
    private void incrementTIMA() {
        int tima = memory[REG_TIMA] & 0xFF;
        if (tima == 0xFF) {
            memory[REG_TIMA] = memory[REG_TMA]; // Reset TIMA to TMA
            // Request Timer Interrupt
            memory[REG_IF] |= 0x04;
        } else {
            memory[REG_TIMA] = (byte) ((tima + 1) & 0xFF);
        }
    }

    public boolean isDmaActive() {
        return this.dmaCyclesRemaining > 0;
    }
    
    /**
     * Verifica se o acesso à memória está bloqueado devido ao OAM DMA
     * @return true se a memória está bloqueada, false caso contrário
     */
    public boolean isDmaMemoryBlocked() {
        return this.dmaMemoryBlocked;
    }
    
    /**
     * Obtém o número de ciclos restantes do DMA
     * @return ciclos restantes do DMA
     */
    public int getDmaCyclesRemaining() {
        return this.dmaCyclesRemaining;
    }
    
    /**
     * Verifica se um endereço específico é acessível durante OAM DMA
     * Durante OAM DMA, apenas HRAM (0xFF80-0xFFFE) é acessível
     * @param address endereço a verificar
     * @return true se o endereço é acessível durante DMA
     */
    private boolean isAddressAccessibleDuringDMA(int address) {
        // HRAM (High RAM) é sempre acessível, mesmo durante DMA
        return (address >= 0xFF80 && address <= 0xFFFE);
    }

    public void updateDma(int cycles) {
        if (this.dmaCyclesRemaining > 0) {
            this.dmaCyclesRemaining -= cycles;
            if (this.dmaCyclesRemaining <= 0) {
                // DMA terminou, liberar acesso à memória
                this.dmaMemoryBlocked = false;
            }
        }
    }

    /**
     * @deprecated Este método não é mais usado. O DIV é agora atualizado automaticamente 
     * pelo updateTimers() com timing preciso de ciclo.
     */
    @Deprecated
    public void incrementDivRegister() {
        // Método mantido para compatibilidade, mas não faz nada
        // O DIV é agora atualizado automaticamente em updateTimersSingleCycle()
    }

    public void loadCartridge(Cartridge cart) {
        this.cartridge = cart;
        // O `readByte` agora lida com o acesso ao cartucho, não precisamos copiar a ROM inteira aqui.
        System.out.println("Cartridge loaded into MMU.");
    }

    public int readByte(int address) {
        address &= 0xFFFF;
        
        // Verificar se a memória está bloqueada devido ao OAM DMA
        if (dmaMemoryBlocked && !isAddressAccessibleDuringDMA(address)) {
            // Durante OAM DMA, retornar 0xFF para endereços inacessíveis
            return 0xFF;
        }

        if (bootRomEnabled && address <= 0x00FF) {
            return bootRom[address] & 0xFF;
        }

        if (address >= 0x0000 && address <= 0x7FFF) {
            return cartridge.read(address) & 0xFF;
        } else if (address >= 0x8000 && address <= 0x9FFF) {
            return ppu.readVRAM(address - 0x8000) & 0xFF;
        } else if (address >= 0xA000 && address <= 0xBFFF) {
            return cartridge.readRam(address - 0xA000) & 0xFF;
        } else if (address >= 0xC000 && address <= 0xDFFF) {
            return memory[address] & 0xFF;
        } else if (address >= 0xE000 && address <= 0xFDFF) {
            return memory[address - 0x2000] & 0xFF;
        } else if (address >= 0xFE00 && address <= 0xFE9F) {
            return ppu.readOAM(address - 0xFE00) & 0xFF;
        } else if (address >= 0xFEA0 && address <= 0xFEFF) {
            return 0xFF;
        } else if (address >= 0xFF00 && address <= 0xFF7F) {
            return readIORegister(address) & 0xFF;
        } else if (address >= 0xFF80 && address <= 0xFFFE) {
            return memory[address] & 0xFF;
        } else if (address == REG_IE) {
            return memory[address] & 0xFF;
        }

        return 0xFF;
    }

    public void writeByte(int address, int value) {
        address &= 0xFFFF;
        byte byteValue = (byte) (value & 0xFF);
        
        // Verificar se a memória está bloqueada devido ao OAM DMA
        if (dmaMemoryBlocked && !isAddressAccessibleDuringDMA(address)) {
            // Durante OAM DMA, ignorar escritas para endereços inacessíveis
            return;
        }

        if (address >= 0x0000 && address <= 0x7FFF) {
            cartridge.write(address, byteValue);
        } else if (address >= 0x8000 && address <= 0x9FFF) {
            ppu.writeVRAM(address - 0x8000, byteValue);
        } else if (address >= 0xA000 && address <= 0xBFFF) {
            cartridge.writeRam(address - 0xA000, byteValue);
        } else if (address >= 0xC000 && address <= 0xDFFF) {
            memory[address] = byteValue;
        } else if (address >= 0xE000 && address <= 0xFDFF) {
            memory[address - 0x2000] = byteValue;
        } else if (address >= 0xFE00 && address <= 0xFE9F) {
            ppu.writeOAM(address - 0xFE00, byteValue);
        } else if (address >= 0xFEA0 && address <= 0xFEFF) {
            // Ignorado
        } else if (address >= 0xFF00 && address <= 0xFF7F) {
            writeIORegister(address, byteValue);
        } else if (address >= 0xFF80 && address <= 0xFFFE) {
            memory[address] = byteValue;
        } else if (address == REG_IE) {
            memory[address] = byteValue;
        }
    }

    private int readIORegister(int address) {
        switch (address) {
            case REG_JOYP:
                byte joypVal = memory[REG_JOYP];
                joypVal |= 0xCF; // Começa com bits não selecionados em 1
                if ((joypVal & 0x10) == 0) {
                    return (joypVal & 0xF0) | (joypadState & 0x0F);
                } else if ((joypVal & 0x20) == 0) {
                    return (joypVal & 0xF0) | ((joypadState >> 4) & 0x0F);
                }
                return 0xFF;

            case REG_SB: return memory[REG_SB] & 0xFF;
            case REG_SC: return memory[REG_SC] & 0xFF | 0x7E;

            case REG_DIV: return memory[REG_DIV] & 0xFF;
            case REG_TIMA: return memory[REG_TIMA] & 0xFF;
            case REG_TMA: return memory[REG_TMA] & 0xFF;
            case REG_TAC: return memory[REG_TAC] & 0xFF;

            case REG_IF: return (memory[REG_IF] & 0x1F) | 0xE0;

            case REG_LCDC: return ppu.getLcdc();
            case REG_STAT: return ppu.getStat();
            case REG_SCY: return ppu.getScy();
            case REG_SCX: return ppu.getScx();
            case REG_LY: return ppu.getLy();
            case REG_LYC: return ppu.getLyc();
            case REG_BGP: return ppu.getBgp();
            case REG_OBP0: return ppu.getObp0();
            case REG_OBP1: return ppu.getObp1();
            case REG_WY: return ppu.getWy();
            case REG_WX: return ppu.getWx();

            case REG_KEY1: return memory[REG_KEY1] & 0xFF;

            default:
                // Registradores de áudio APU
                if ((address >= 0xFF10 && address <= 0xFF26) || (address >= 0xFF30 && address <= 0xFF3F)) {
                    return apu.readRegister(address);
                }
                return 0xFF;
        }
    }

    private void writeIORegister(int address, byte value) {
        switch (address) {
            case REG_DIV:
                // Verificar se o reset do DIV pode disparar TIMA (glitch de hardware)
                int tac = memory[REG_TAC] & 0xFF;
                if ((tac & 0x04) != 0) { // Timer habilitado
                    int bitPosition = TIMER_BIT_POSITIONS[tac & 0x03];
                    int currentTimerBit = (divCounter >> bitPosition) & 1;
                    
                    // Se o bit estava em 1, o reset para 0 seguido de uma 
                    // eventual transição 0→1 pode disparar TIMA
                    // Isso simula o comportamento do hardware onde o reset
                    // pode criar uma borda de subida artificial
                    if (currentTimerBit == 1) {
                        incrementTIMA();
                    }
                }
                
                // Resetar DIV para 0
                divCounter = 0;
                memory[REG_DIV] = 0;
                
                if (cpu != null) {
                    cpu.resetDivAccumulator();
                }
                break;
            case REG_JOYP:
                memory[REG_JOYP] = (byte) ((memory[REG_JOYP] & 0xCF) | (value & 0x30));
                break;
            case REG_SB:
                memory[REG_SB] = value;
                break;
            case REG_SC:
                memory[REG_SC] = value;
                if ((value & 0x81) == 0x81) { // Inicia transferência serial (interno)
                    memory[REG_SB] = (byte)0xFF; // Simula recebimento de nada
                    serialTransferInProgress = true;
                    serialTransferCounter = 4132; // Tempo para 8 bits a 8192 Hz
                }
                break;
            case REG_TIMA:
                memory[REG_TIMA] = value;
                break;
            case REG_TMA:
                memory[REG_TMA] = value;
                break;
            case REG_TAC:
                memory[REG_TAC] = value;
                break;
            case REG_IF:
                memory[REG_IF] = (byte) (value & 0x1F);
                break;
            case REG_LCDC: ppu.setLcdc(value); break;
            case REG_STAT: ppu.setStat(value); break;
            case REG_SCY: ppu.setScy(value); break;
            case REG_SCX: ppu.setScx(value); break;
            case REG_LYC: ppu.setLyc(value); break;
            case REG_DMA: doDMATransfer(value & 0xFF); break;
            case REG_BGP: ppu.setBgp(value); break;
            case REG_OBP0: ppu.setObp0(value); break;
            case REG_OBP1: ppu.setObp1(value); break;
            case REG_WY: ppu.setWy(value); break;
            case REG_WX: ppu.setWx(value); break;

            case REG_KEY1: // Placeholder para CGB
                memory[REG_KEY1] = value;
                break;

            case REG_BOOT_ROM_DISABLE:
                if ((value & 0x01) != 0) {
                    bootRomEnabled = false;
                }
                memory[address] = value;
                break;

            default:
                // Registradores de áudio APU
                if ((address >= 0xFF10 && address <= 0xFF26) || (address >= 0xFF30 && address <= 0xFF3F)) {
                    apu.writeRegister(address, value);
                } else {
                    memory[address] = value; // Comportamento padrão para registradores não especiais
                }
                break;
        }
    }

    private void doDMATransfer(int sourceHighByte) {
        int sourceAddress = sourceHighByte << 8;
        
        // Ativar bloqueio de memória durante OAM DMA
        this.dmaMemoryBlocked = true;
        
        // Transferir 160 bytes (0xA0) para a OAM
        for (int i = 0; i < 0xA0; i++) {
            // A escrita na OAM é bloqueada durante o DMA, então a cópia deve ser direta.
            // A leitura da fonte não deve ter efeitos colaterais de I/O.
            // Uma leitura "raw" da memória seria mais segura, mas readByte funciona na maioria dos casos.
            ppu.writeOAM(i, (byte) readByteForDMA(sourceAddress + i)); // Leitura especial para DMA
        }
        
        // A CPU é paralisada por 160 ciclos (não 640)
        // 160 M-cycles para transferir 160 bytes (1 byte por M-cycle)
        this.dmaCyclesRemaining = 160;
    }
    
    /**
     * Leitura de memória especial para DMA que ignora o bloqueio de memória
     * @param address endereço a ser lido
     * @return valor do byte no endereço
     */
    private int readByteForDMA(int address) {
        address &= 0xFFFF;

        if (bootRomEnabled && address <= 0x00FF) {
            return bootRom[address] & 0xFF;
        }

        if (address >= 0x0000 && address <= 0x7FFF) {
            return cartridge.read(address) & 0xFF;
        } else if (address >= 0x8000 && address <= 0x9FFF) {
            return ppu.readVRAM(address - 0x8000) & 0xFF;
        } else if (address >= 0xA000 && address <= 0xBFFF) {
            return cartridge.readRam(address - 0xA000) & 0xFF;
        } else if (address >= 0xC000 && address <= 0xDFFF) {
            return memory[address] & 0xFF;
        } else if (address >= 0xE000 && address <= 0xFDFF) {
            return memory[address - 0x2000] & 0xFF;
        } else if (address >= 0xFE00 && address <= 0xFE9F) {
            return ppu.readOAM(address - 0xFE00) & 0xFF;
        } else if (address >= 0xFEA0 && address <= 0xFEFF) {
            return 0xFF;
        } else if (address >= 0xFF00 && address <= 0xFF7F) {
            return readIORegister(address) & 0xFF;
        } else if (address >= 0xFF80 && address <= 0xFFFE) {
            return memory[address] & 0xFF;
        } else if (address == REG_IE) {
            return memory[address] & 0xFF;
        }

        return 0xFF;
    }

    public void buttonPressed(Button button) {
        joypadState &= (byte) ~(1 << button.bit);
        memory[REG_IF] |= 0x10; // Solicita interrupção do Joypad
    }

    public void buttonReleased(Button button) {
        joypadState |= (byte) (1 << button.bit);
    }

    public enum Button {
        GAMEBOY_RIGHT(0), GAMEBOY_LEFT(1), GAMEBOY_UP(2), GAMEBOY_DOWN(3),
        GAMEBOY_A(4), GAMEBOY_B(5), GAMEBOY_SELECT(6), GAMEBOY_START(7);

        public final int bit;
        Button(int bit) { this.bit = bit; }
    }

    // Save/Load State
    public void saveState(java.io.DataOutputStream dos) throws java.io.IOException {
        // Save WRAM (0xC000-0xDFFF)
        for (int i = 0xC000; i <= 0xDFFF; i++) {
            dos.writeByte(memory[i]);
        }
        // Save HRAM (0xFF80-0xFFFE)
        for (int i = 0xFF80; i <= 0xFFFE; i++) {
            dos.writeByte(memory[i]);
        }
        // Save I/O registers
        dos.writeByte(joypadState);
        dos.writeInt(divCounter);
        dos.writeByte(memory[REG_IF]);
        dos.writeByte(memory[REG_IE]);
    }

    public void loadState(java.io.DataInputStream dis) throws java.io.IOException {
        // Load WRAM
        for (int i = 0xC000; i <= 0xDFFF; i++) {
            memory[i] = dis.readByte();
        }
        // Load HRAM
        for (int i = 0xFF80; i <= 0xFFFE; i++) {
            memory[i] = dis.readByte();
        }
        // Load I/O registers
        joypadState = dis.readByte();
        divCounter = dis.readInt();
        memory[REG_IF] = dis.readByte();
        memory[REG_IE] = dis.readByte();
    }
}