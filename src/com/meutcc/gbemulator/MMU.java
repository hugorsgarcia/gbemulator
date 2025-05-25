package com.meutcc.gbemulator;

import java.util.Arrays;

public class MMU {
    // Mapa de Memória do Game Boy Clássico (DMG)
    // 0000-3FFF: 16KB ROM Bank 00 (do cartucho, geralmente fixo)
    // 4000-7FFF: 16KB ROM Bank 01-NN (do cartucho, comutável via MBC)
    // 8000-9FFF: 8KB Video RAM (VRAM)
    // A000-BFFF: 8KB External RAM (no cartucho, se houver, comutável)
    // C000-CFFF: 4KB Work RAM Bank 0 (WRAM)
    // D000-DFFF: 4KB Work RAM Bank 1 (WRAM) (comutável em CGB, fixo em DMG)
    // E000-FDFF: Echo RAM (espelho de C000-DDFF) - Acesso aqui é redirecionado
    // FE00-FE9F: Sprite Attribute Table (OAM)
    // FEA0-FEFF: Não utilizável
    // FF00-FF7F: I/O Registers
    // FF80-FFFE: High RAM (HRAM)
    // FFFF: Interrupt Enable Register (IE)

    private final byte[] memory = new byte[0x10000]; // 64KB de espaço de endereço

    private Cartridge cartridge;
    private final PPU ppu; // Para acesso direto a VRAM, OAM e registradores da PPU
    private final APU apu; // Para acesso a registradores da APU

    // Registradores de I/O importantes (endereços)
    public static final int REG_JOYP = 0xFF00; // Joypad
    public static final int REG_SB = 0xFF01;   // Serial transfer data
    public static final int REG_SC = 0xFF02;   // Serial transfer control
    // Timer Registers
    public static final int REG_DIV = 0xFF04;  // Divider Register (16384 Hz)
    public static final int REG_TIMA = 0xFF05; // Timer Counter
    public static final int REG_TMA = 0xFF06;  // Timer Modulo
    public static final int REG_TAC = 0xFF07;  // Timer Control
    // Interrupt Flag Register
    public static final int REG_IF = 0xFF0F;   // Interrupt Flag
    // PPU Registers (alguns são manipulados pela PPU diretamente, outros lidos/escritos aqui)
    public static final int REG_LCDC = 0xFF40; // LCD Control
    public static final int REG_STAT = 0xFF41; // LCD Status
    public static final int REG_SCY = 0xFF42;  // Scroll Y
    public static final int REG_SCX = 0xFF43;  // Scroll X
    public static final int REG_LY = 0xFF44;   // LCD Y-Coordinate (ReadOnly for CPU)
    public static final int REG_LYC = 0xFF45;  // LY Compare
    public static final int REG_DMA = 0xFF46;  // DMA Transfer and Start Address
    public static final int REG_BGP = 0xFF47;  // BG Palette Data
    public static final int REG_OBP0 = 0xFF48; // Object Palette 0 Data
    public static final int REG_OBP1 = 0xFF49; // Object Palette 1 Data
    public static final int REG_WY = 0xFF4A;   // Window Y Position
    public static final int REG_WX = 0xFF4B;   // Window X Position - 7
    // Interrupt Enable Register
    public static final int REG_IE = 0xFFFF;   // Interrupt Enable

    // Para o Joypad
    private byte joypadState = (byte) 0xFF; // Todos os botões não pressionados
    // Bit 5: P15 Select Action buttons (0=Select)
    // Bit 4: P14 Select Direction buttons (0=Select)
    // Bit 3: P13 Input Down or Start (0=Pressed)
    // Bit 2: P12 Input Up or Select (0=Pressed)
    // Bit 1: P11 Input Left or Button B (0=Pressed)
    // Bit 0: P10 Input Right or Button A (0=Pressed)


    public MMU(Cartridge cartridge, PPU ppu, APU apu) {
        this.cartridge = cartridge;
        this.ppu = ppu;
        this.apu = apu;
        // Inicializa a memória (opcional, mas bom para consistência)
        Arrays.fill(memory, (byte)0x00);
    }

    public void reset() {
        // Limpar WRAM, HRAM, alguns I/O regs. ROM e VRAM/OAM devem ser preservados ou
        // recarregados/resetados por seus respetivos componentes.
        for (int i = 0xC000; i < 0xE000; i++) memory[i] = 0; // WRAM
        for (int i = 0xFF80; i < 0xFFFF; i++) memory[i] = 0; // HRAM

        // Valores iniciais de alguns registradores I/O após boot ROM (se pulado)
        // Muitos desses são configurados pelo boot ROM do Game Boy.
        // Se você não está a emular o boot ROM, precisa setá-los.
        writeByte(REG_JOYP, (byte)0xCF); // Bits de seleção não pressionados
        writeByte(REG_SC, (byte)0x7E); // Serial Control
        writeByte(REG_TIMA, (byte)0x00);
        writeByte(REG_TMA, (byte)0x00);
        writeByte(REG_TAC, (byte)0x00);
        writeByte(REG_IF, (byte)0xE1); // Bits 4-0 são 1 (no logo do boot rom, IF=E1)
        // PPU registers são resetados na PPU.reset()
        writeByte(REG_LCDC, (byte)0x91);
        writeByte(REG_STAT, (byte)0x85); // Varia, mas 0x80 + modo inicial
        writeByte(REG_SCY, (byte)0x00);
        writeByte(REG_SCX, (byte)0x00);
        writeByte(REG_LYC, (byte)0x00);
        writeByte(REG_BGP, (byte)0xFC);
        writeByte(REG_OBP0, (byte)0xFF);
        writeByte(REG_OBP1, (byte)0xFF);
        writeByte(REG_WY, (byte)0x00);
        writeByte(REG_WX, (byte)0x00);
        // APU registers são resetados na APU.reset()

        writeByte(REG_IE, (byte)0x00); // Interrupt Enable

        joypadState = (byte) 0xFF;
        System.out.println("MMU reset and I/O registers initialized.");
    }

    public void loadCartridge(Cartridge cart) {
        this.cartridge = cart;
        // Copia os primeiros 32KB da ROM para a memória.
        // Para ROMs maiores, o MBC cuidaria disso.
        // Aqui, assumimos "ROM Only" ou o primeiro banco de um MBC.
        byte[] romData = cartridge.getRomData();
        int len = Math.min(romData.length, 0x8000); // Max 32KB para esta área
        System.arraycopy(romData, 0, memory, 0x0000, len);
        System.out.println("Cartridge data loaded into MMU memory (0x0000 - " + String.format("0x%04X", len-1) + ")");
    }

    public int readByte(int address) {
        address &= 0xFFFF; // Garante que o endereço está dentro dos 16 bits

        if (address >= 0x0000 && address <= 0x7FFF) { // ROM (Cartucho)
            // Para MBCs, isso seria mais complexo. Por ora, acesso direto.
            return cartridge.read(address) & 0xFF;
        } else if (address >= 0x8000 && address <= 0x9FFF) { // VRAM
            return ppu.readVRAM(address - 0x8000) & 0xFF;
        } else if (address >= 0xA000 && address <= 0xBFFF) { // External RAM (Cartucho)
            return cartridge.readRam(address - 0xA000) & 0xFF;
        } else if (address >= 0xC000 && address <= 0xDFFF) { // Work RAM (WRAM)
            return memory[address] & 0xFF;
        } else if (address >= 0xE000 && address <= 0xFDFF) { // Echo RAM (espelho de C000-DDFF)
            return memory[address - 0x2000] & 0xFF; // Redireciona para C000-DDFF
        } else if (address >= 0xFE00 && address <= 0xFE9F) { // Sprite Attribute Table (OAM)
            // A OAM só é acessível pela CPU durante HBlank/VBlank.
            // A PPU pode bloquear o acesso. Para simplificar, permitimos sempre.
            return ppu.readOAM(address - 0xFE00) & 0xFF;
        } else if (address >= 0xFEA0 && address <= 0xFEFF) { // Não utilizável
            // Leitura de área não utilizável geralmente retorna 0xFF ou lixo.
            // Alguns emuladores retornam (address >> 8) & 0xFF ou (address & 0xFF)
            // Outros retornam dados baseados no estado da PPU se OAM está sendo acessada.
            // Para simplificar:
            return 0xFF;
        } else if (address >= 0xFF00 && address <= 0xFF7F) { // I/O Registers
            return readIORegister(address) & 0xFF;
        } else if (address >= 0xFF80 && address <= 0xFFFE) { // High RAM (HRAM)
            return memory[address] & 0xFF;
        } else if (address == REG_IE) { // Interrupt Enable Register
            return memory[address] & 0xFF;
        }

        // System.err.println(String.format("MMU: Read from unhandled address 0x%04X", address));
        return 0xFF; // Padrão para endereços não mapeados ou erros
    }

    public void writeByte(int address, int value) {
        address &= 0xFFFF;
        byte byteValue = (byte) (value & 0xFF);

        if (address >= 0x0000 && address <= 0x7FFF) { // ROM (Cartucho)
            // Escrita na ROM geralmente é para controle do MBC.
            cartridge.write(address, byteValue);
            // Não alteramos memory[] aqui, pois a ROM é "Read Only" para a CPU em termos de conteúdo.
        } else if (address >= 0x8000 && address <= 0x9FFF) { // VRAM
            ppu.writeVRAM(address - 0x8000, byteValue);
        } else if (address >= 0xA000 && address <= 0xBFFF) { // External RAM (Cartucho)
            cartridge.writeRam(address - 0xA000, byteValue);
        } else if (address >= 0xC000 && address <= 0xDFFF) { // Work RAM (WRAM)
            memory[address] = byteValue;
        } else if (address >= 0xE000 && address <= 0xFDFF) { // Echo RAM
            memory[address - 0x2000] = byteValue; // Escreve no endereço espelhado
            // E também no próprio endereço de eco, conforme alguns comportamentos
            memory[address] = byteValue;
        } else if (address >= 0xFE00 && address <= 0xFE9F) { // Sprite Attribute Table (OAM)
            // A OAM só é acessível pela CPU durante HBlank/VBlank.
            // A PPU pode bloquear o acesso. Para simplificar, permitimos sempre.
            ppu.writeOAM(address - 0xFE00, byteValue);
        } else if (address >= 0xFEA0 && address <= 0xFEFF) { // Não utilizável
            // Escrita ignorada.
        } else if (address >= 0xFF00 && address <= 0xFF7F) { // I/O Registers
            writeIORegister(address, byteValue);
        } else if (address >= 0xFF80 && address <= 0xFFFE) { // High RAM (HRAM)
            memory[address] = byteValue;
        } else if (address == REG_IE) { // Interrupt Enable Register
            memory[address] = byteValue;
        } else {
            // System.err.println(String.format("MMU: Write to unhandled address 0x%04X with value 0x%02X", address, byteValue));
        }
    }

    private int readIORegister(int address) {
        switch (address) {
            case REG_JOYP: // Joypad input
                // O valor lido depende dos bits 4 e 5 escritos anteriormente
                byte joypVal = memory[REG_JOYP]; // Lê o que foi escrito para P14/P15
                joypVal |= 0x0F; // Começa com os bits de input (0-3) em 1 (não pressionado)

                if ((joypVal & 0x10) == 0) { // Bit 4 (P14) é 0 - Direcionais selecionados
                    joypVal &= (joypadState & 0x0F); // Aplica estado dos direcionais
                } else if ((joypVal & 0x20) == 0) { // Bit 5 (P15) é 0 - Botões de ação selecionados
                    joypVal &= ((joypadState >> 4) & 0x0F); // Aplica estado dos botões de ação
                } else {
                    // Se nenhum (ou ambos) estiverem selecionados, os bits de input são 1.
                    // Ou, em hardware real, pode ser uma combinação. Para muitos jogos, 0xF funciona.
                    joypVal |= 0x0F;
                }
                return joypVal & 0xFF;

            case REG_SB: return memory[REG_SB] & 0xFF;
            case REG_SC: return memory[REG_SC] & 0xFF; // Implementar serial pode modificar isso

            case REG_DIV:  return 0; // TODO: Implementar Timer - DIV é resetado na escrita
            case REG_TIMA: return 0; // TODO: Implementar Timer
            case REG_TMA:  return 0; // TODO: Implementar Timer
            case REG_TAC:  return memory[REG_TAC] & 0xFF; // TODO: Implementar Timer

            case REG_IF: return memory[REG_IF] & 0xFF;

            // PPU Registers - Muitos são tratados pela PPU, mas podem ser lidos via MMU
            case REG_LCDC: return ppu.getLcdc() & 0xFF;
            case REG_STAT: return ppu.getStat() & 0xFF;
            case REG_SCY:  return ppu.getScy() & 0xFF;
            case REG_SCX:  return ppu.getScx() & 0xFF;
            case REG_LY:   return ppu.getLy() & 0xFF; // LY é Read-only para a CPU
            case REG_LYC:  return ppu.getLyc() & 0xFF;
            // REG_DMA é Write-only
            case REG_BGP:  return ppu.getBgp() & 0xFF;
            case REG_OBP0: return ppu.getObp0() & 0xFF;
            case REG_OBP1: return ppu.getObp1() & 0xFF;
            case REG_WY:   return ppu.getWy() & 0xFF;
            case REG_WX:   return ppu.getWx() & 0xFF;

            // Registradores de Áudio (APU) - Leitura de esqueleto
            // 0xFF10 - 0xFF26 (NR10-NR52)
            // Muitos registradores da APU são write-only ou têm bits read-only.
            // Exemplo simplificado:
            // if (address >= 0xFF10 && address <= 0xFF26) {
            //    return apu.readRegister(address) & 0xFF;
            // }
            // Por enquanto, apenas permitimos leitura dos registradores de "ondas" (FF27-FF2F não existem)
            // e de controle/status (FF30-FF3F não existem no DMG, são Wave Pattern RAM)
            // Mas FF24 (NR50), FF25 (NR51), FF26 (NR52) são importantes
            case 0xFF24: // NR50 - Channel control / ON-OFF / Volume
            case 0xFF25: // NR51 - Selection of Sound output terminal
            case 0xFF26: // NR52 - Sound on/off
                return apu.readRegister(address) & 0xFF; // Delegar à APU

            // Wave Pattern RAM (0xFF30 - 0xFF3F) - Acessível diretamente
            case int n when (n >= 0xFF30 && n <= 0xFF3F):
                return memory[n] & 0xFF; // Ou apu.readWaveRam(address - 0xFF30);

            default:
                // Para registradores de I/O não explicitamente tratados,
                // alguns podem ser apenas de escrita (retornam 0xFF na leitura),
                // outros podem ter valores padrão ou serem lidos de `memory[]`.
                // O comportamento "aberto" do barramento do Game Boy retorna 0xFF.
                // System.out.println("MMU: Read I/O unhandled: " + String.format("0x%04X", address));
                if (address >= 0xFF00 && address < 0xFF80) { // Região geral de I/O
                    // Se não é um registro especial, talvez seja um dos não implementados?
                    // Retornar o valor de memory pode ser um placeholder.
                    // Para muitos registros de I/O não usados ou write-only, 0xFF é comum.
                    return 0xFF; // Comportamento mais seguro para I/O não tratado
                }
                return memory[address] & 0xFF; // Fallback
        }
    }

    private void writeIORegister(int address, byte value) {
        memory[address] = value; // Armazena o valor bruto para alguns casos

        switch (address) {
            case REG_JOYP: // Joypad - Bits 4 e 5 são escrevíveis (seleção de linha)
                // Bits 0-3 são read-only (estado dos botões)
                memory[REG_JOYP] = (byte) ((memory[REG_JOYP] & 0xCF) | (value & 0x30)); // Mantém bits 0-3, 6, 7 e atualiza 4, 5
                break;

            case REG_SB: // Serial data
                memory[REG_SB] = value;
                break;
            case REG_SC: // Serial control
                memory[REG_SC] = value;
                // Aqui você poderia iniciar uma transferência serial se implementado
                // e.g. if ((value & 0x81) == 0x81) { startSerialTransfer(); }
                break;

            case REG_DIV:  // Divider Register - Qualquer escrita reseta para 0
                // TODO: Implementar Timer - resetar contador interno do DIV
                memory[REG_DIV] = 0; // O registrador em si é sempre resetado
                break;
            case REG_TIMA: // TODO: Implementar Timer
            case REG_TMA:  // TODO: Implementar Timer
            case REG_TAC:  // TODO: Implementar Timer
                memory[address] = value;
                break;

            case REG_IF:   // Interrupt Flag - Bits 0-4 são escrevíveis
                memory[REG_IF] = (byte) ((value & 0x1F) | (memory[REG_IF] & 0xE0)); // Mantém bits superiores (não usados)
                break;

            // PPU Registers
            case REG_LCDC: ppu.setLcdc(value); break;
            case REG_STAT: ppu.setStat(value); break;
            case REG_SCY:  ppu.setScy(value);  break;
            case REG_SCX:  ppu.setScx(value);  break;
            // LY é Read-only
            case REG_LYC:  ppu.setLyc(value);  break;
            case REG_DMA:  doDMATransfer(value); break; // Inicia transferência DMA
            case REG_BGP:  ppu.setBgp(value);  break;
            case REG_OBP0: ppu.setObp0(value); break;
            case REG_OBP1: ppu.setObp1(value); break;
            case REG_WY:   ppu.setWy(value);   break;
            case REG_WX:   ppu.setWx(value);   break;

            // Registradores de Áudio (APU)
            // 0xFF10 - 0xFF26 (NR10-NR52)
            // Wave Pattern RAM (0xFF30 - 0xFF3F)
            case int n when (n >= 0xFF10 && n <= 0xFF26):
                apu.writeRegister(address, value);
                break;
            case int n when (n >= 0xFF30 && n <= 0xFF3F):
                // Wave Pattern RAM é diretamente endereçável
                memory[n] = value; // Ou apu.writeWaveRam(address - 0xFF30, value);
                break;

            // TODO: Outros registradores de I/O (bootstrap ROM disable, speed switch CGB, etc.)
            // FF50 - Bootstrap ROM disable
            // FF4D - KEY1 (CGB speed switch)
            // FF4F - VBK (CGB VRAM Bank)
            // ... e outros específicos de CGB

            default:
                // Registradores não explicitamente tratados, mas dentro da faixa de I/O
                // Muitos são apenas de leitura ou não existem/não têm efeito em DMG
                // System.out.println("MMU: Write I/O unhandled: " + String.format("0x%04X", address) + " val: " + String.format("0x%02X", value));
                // memory[address] = value; // Comportamento padrão, pode não ser correto para todos.
                break;
        }
    }

    private void doDMATransfer(int sourceHighByte) {
        int sourceAddress = (sourceHighByte & 0xFF) << 8; // Endereço fonte é 0xXX00
        // Fonte pode ser ROM ou RAM (0x0000-0xDF00, mas não de FE00-FFFF)
        // Se sourceAddress >= 0xE000, ele é espelhado para C000.
        if (sourceAddress >= 0xE000 && sourceAddress <=0xFDFF) {
            sourceAddress -= 0x2000;
        }
        // DMA transfere 160 bytes (0xA0 bytes) da sourceAddress para OAM (0xFE00 - 0xFE9F)
        for (int i = 0; i < 0xA0; i++) {
            if (sourceAddress + i > 0xDFFF && sourceAddress < 0xE000) { // Proteção adicional
                // Fontes inválidas como HRAM ou I/O regs podem ter comportamento indefinido.
                // A especificação diz que a fonte não deve ser de 0xFE00-0xFFFF.
                // Simplificando, não copiamos de fontes potencialmente problemáticas.
                // Porém, jogos podem tentar DMA de áreas estranhas. PanDocs diz que a fonte
                // não pode ser maior que F1h (0xF1xx).
                // Aqui, simplesmente lemos da memória mapeada, o que é geralmente seguro.
            }
            ppu.writeOAM(i, (byte) readByte(sourceAddress + i));
        }
        // O DMA leva tempo da CPU (160 microsegundos, ~672 T-cycles no clock normal).
        // Este tempo deve ser contabilizado no emulador. A CPU fica "parada" durante o DMA.
        // Por ora, a transferência é instantânea. Uma implementação mais precisa
        // faria a CPU esperar ou adicionaria um contador de ciclos de DMA.
        // gameBoy.getCpu().addCycles(160 * 4 + X); // Aproximadamente
    }

    // --- Métodos para o InputHandler ---
    public void buttonPressed(Button button) {
        joypadState &= (byte) ~(1 << button.bit);
        // Solicitar interrupção do Joypad (Bit 4 do IF)
        // Apenas se o jogo está interessado nesse tipo de botão (direcional/ação)
        // e a interrupção do joypad está habilitada no IE
        // Isso é simplificado, a lógica de interrupção do joypad é mais complexa.
        // Por ora, só atualizamos o estado. A CPU verificará o joypad quando ler 0xFF00.
        // Uma interrupção de joypad real ocorre na transição de alto para baixo do sinal.

        // Solicita interrupção do Joypad
        byte currentIF = (byte) readByte(REG_IF);
        writeByte(REG_IF, (byte) (currentIF | 0x10)); // Seta bit 4 (Joypad)
    }

    public void buttonReleased(Button button) {
        joypadState |= (byte) (1 << button.bit);
    }

    public enum Button {
        RIGHT_A (0), // P10
        LEFT_B  (1), // P11
        UP_SELECT(2), // P12
        DOWN_START(3),// P13
        // Bits 0-3 são para Action se P15=0, ou Direction se P14=0
        // Mapeamento para joypadState (que usa 8 bits):
        // Ações nos bits baixos (0-3), Direções nos bits altos (4-7) do byte de estado interno
        // Ou podemos usar o mesmo layout do registrador e deixar a lógica de seleção cuidar.
        // Vamos usar bits separados para clareza no InputHandler, e o MMU traduz.
        // Reconsiderando: `joypadState` reflete a organização do HW.
        // Linha P14 (Directions): Bit 0: Right, Bit 1: Left, Bit 2: Up, Bit 3: Down
        // Linha P15 (Actions):  Bit 0: A, Bit 1: B, Bit 2: Select, Bit 3: Start

        // Para o joypadState interno:
        // Bit 0: Right
        // Bit 1: Left
        // Bit 2: Up
        // Bit 3: Down
        // Bit 4: A
        // Bit 5: B
        // Bit 6: Select
        // Bit 7: Start
        // Ajustando o enum para refletir isso:
        GAMEBOY_RIGHT(0), GAMEBOY_LEFT(1), GAMEBOY_UP(2), GAMEBOY_DOWN(3),
        GAMEBOY_A(4), GAMEBOY_B(5), GAMEBOY_SELECT(6), GAMEBOY_START(7);

        public final int bit;
        Button(int bit) { this.bit = bit; }
    }
}