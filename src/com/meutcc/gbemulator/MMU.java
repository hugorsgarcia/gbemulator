package com.meutcc.gbemulator;

import java.util.Arrays;

public class MMU {
    private final byte[] memory = new byte[0x10000];

    private Cartridge cartridge;
    private final PPU ppu;
    private final APU apu;
    private CPU cpu;

    private boolean bootRomEnabled = false;
    private final byte[] bootRom = new byte[256];


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

    private byte joypadState = (byte) 0xFF;

    private int divCounter = 0;

    private static final int[] TIMER_BIT_POSITIONS = {9, 3, 5, 7};

    private int timaOverflowDelay = 0;
    private boolean timaOverflowPending = false;

    private int dmaCyclesRemaining = 0;
    private boolean dmaMemoryBlocked = false;

    private int serialTransferCounter = 0;
    private boolean serialTransferInProgress = false;

    public MMU(Cartridge cartridge, PPU ppu, APU apu) {
        this.cartridge = cartridge;
        this.ppu = ppu;
        this.apu = apu;
        this.cpu = null;
        Arrays.fill(memory, (byte) 0x00);
    }

    public void setCpu(CPU cpu) {
        this.cpu = cpu;
    }

    public void reset() {
        for (int i = 0xC000; i < 0xE000; i++) memory[i] = 0;
        for (int i = 0xFF80; i < 0xFFFF; i++) memory[i] = 0;

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
        divCounter = 0;
        timaOverflowPending = false;
        timaOverflowDelay = 0;
        serialTransferCounter = 0;
        serialTransferInProgress = false;
        bootRomEnabled = false;

        System.out.println("MMU reset and I/O registers initialized.");
    }

    public void updateTimers(int cycles) {
        for (int i = 0; i < cycles; i++) {
            updateTimersSingleCycle();
        }

        if(serialTransferInProgress) {
            serialTransferCounter -= cycles;
            if(serialTransferCounter <= 0) {
                serialTransferInProgress = false;
                memory[REG_SC] &= ~0x80;
                memory[REG_IF] |= 0x08;
            }
        }
    }

    private void updateTimersSingleCycle() {
        int oldTimerBit = getTimerBit();

        divCounter = (divCounter + 1) & 0xFFFF;

        memory[REG_DIV] = (byte) ((divCounter >> 8) & 0xFF);

        int newTimerBit = getTimerBit();
        if (oldTimerBit == 1 && newTimerBit == 0) {
            incrementTIMA();
        }

        if (timaOverflowPending) {
            timaOverflowDelay++;
            if (timaOverflowDelay >= 4) {
                memory[REG_TIMA] = memory[REG_TMA];
                memory[REG_IF] |= 0x04;
                timaOverflowPending = false;
                timaOverflowDelay = 0;
            }
        }
    }

    private int getTimerBit() {
        int tac = memory[REG_TAC] & 0xFF;

        if ((tac & 0x04) == 0) {
            return 0;
        }

        int bitPosition = TIMER_BIT_POSITIONS[tac & 0x03];
        return (divCounter >> bitPosition) & 1;
    }

    private void incrementTIMA() {
        int tima = memory[REG_TIMA] & 0xFF;

        if (tima == 0xFF) {
            memory[REG_TIMA] = 0x00;
            timaOverflowPending = true;
            timaOverflowDelay = 0;
        } else {
            memory[REG_TIMA] = (byte) ((tima + 1) & 0xFF);
        }
    }

    public boolean isDmaActive() {
        return this.dmaCyclesRemaining > 0;
    }

    public boolean isDmaMemoryBlocked() {
        return this.dmaMemoryBlocked;
    }

    public int getDmaCyclesRemaining() {
        return this.dmaCyclesRemaining;
    }

    private boolean isAddressAccessibleDuringDMA(int address) {
        return (address >= 0xFF80 && address <= 0xFFFE);
    }

    public void updateDma(int cycles) {
        if (this.dmaCyclesRemaining > 0) {
            this.dmaCyclesRemaining -= cycles;
            if (this.dmaCyclesRemaining <= 0) {
                this.dmaMemoryBlocked = false;
            }
        }
    }

    public void loadCartridge(Cartridge cart) {
        this.cartridge = cart;
        System.out.println("Cartridge loaded into MMU.");
    }

    public int readByte(int address) {
        address &= 0xFFFF;

        if (dmaMemoryBlocked && !isAddressAccessibleDuringDMA(address)) {
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

        if (dmaMemoryBlocked && !isAddressAccessibleDuringDMA(address)) {
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
                joypVal |= 0xCF;
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
                if ((address >= 0xFF10 && address <= 0xFF26) || (address >= 0xFF30 && address <= 0xFF3F)) {
                    return apu.readRegister(address);
                }
                return 0xFF;
        }
    }

    private void writeIORegister(int address, byte value) {
        switch (address) {
            case REG_DIV:
                int oldDivTimerBit = getTimerBit();

                divCounter = 0;
                memory[REG_DIV] = 0;

                if (oldDivTimerBit == 1) {
                    incrementTIMA();
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
                if ((value & 0x81) == 0x81) {
                    memory[REG_SB] = (byte)0xFF;
                    serialTransferInProgress = true;
                    serialTransferCounter = 4132;
                }
                break;
            case REG_TIMA:
                memory[REG_TIMA] = value;
                if (timaOverflowPending) {
                    timaOverflowPending = false;
                    timaOverflowDelay = 0;
                }
                break;
            case REG_TMA:
                memory[REG_TMA] = value;
                if (timaOverflowPending && timaOverflowDelay >= 3) {
                    memory[REG_TIMA] = value;
                }
                break;
            case REG_TAC:
                int oldTacTimerBit = getTimerBit();

                memory[REG_TAC] = value;

                int newTimerBit = getTimerBit();

                if (oldTacTimerBit == 1 && newTimerBit == 0) {
                    incrementTIMA();
                }
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

            case REG_KEY1:
                memory[REG_KEY1] = value;
                break;

            case REG_BOOT_ROM_DISABLE:
                if ((value & 0x01) != 0) {
                    bootRomEnabled = false;
                }
                memory[address] = value;
                break;

            default:
                if ((address >= 0xFF10 && address <= 0xFF26) || (address >= 0xFF30 && address <= 0xFF3F)) {
                    apu.writeRegister(address, value);
                } else {
                    memory[address] = value;
                }
                break;
        }
    }

    private void doDMATransfer(int sourceHighByte) {
        int sourceAddress = sourceHighByte << 8;

        this.dmaMemoryBlocked = true;

        for (int i = 0; i < 0xA0; i++) {
            ppu.writeOAM(i, (byte) readByteForDMA(sourceAddress + i));
        }

        this.dmaCyclesRemaining = 644;
    }

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
        memory[REG_IF] |= 0x10;

        if (cpu != null) {
            cpu.wakeFromStop();
        }
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

    public void saveState(java.io.DataOutputStream dos) throws java.io.IOException {
        for (int i = 0xC000; i <= 0xDFFF; i++) {
            dos.writeByte(memory[i]);
        }
        for (int i = 0xFF80; i <= 0xFFFE; i++) {
            dos.writeByte(memory[i]);
        }
        dos.writeByte(joypadState);
        dos.writeInt(divCounter);
        dos.writeBoolean(timaOverflowPending);
        dos.writeInt(timaOverflowDelay);
        dos.writeByte(memory[REG_IF]);
        dos.writeByte(memory[REG_IE]);
    }

    public void loadState(java.io.DataInputStream dis) throws java.io.IOException {
        for (int i = 0xC000; i <= 0xDFFF; i++) {
            memory[i] = dis.readByte();
        }
        for (int i = 0xFF80; i <= 0xFFFE; i++) {
            memory[i] = dis.readByte();
        }
        joypadState = dis.readByte();
        divCounter = dis.readInt();
        timaOverflowPending = dis.readBoolean();
        timaOverflowDelay = dis.readInt();
        memory[REG_IF] = dis.readByte();
        memory[REG_IE] = dis.readByte();
    }
}