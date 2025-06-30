package com.meutcc.gbemulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

public class Cartridge {
    private MemoryBankController mbc;

    public Cartridge() {
        // Inicializa com um MBC "nulo" que não faz nada.
        this.mbc = new Mbc0RomOnly(new byte[0]);
    }

    public boolean loadROM(String filePath) {
        try {
            Path path = Paths.get(filePath);
            if (!Files.exists(path) || !Files.isReadable(path)) {
                System.err.println("Erro: Arquivo ROM não encontrado ou não pode ser lido: " + filePath);
                return false;
            }
            byte[] romData = Files.readAllBytes(path);
            System.out.println("ROM carregada: " + filePath + ", Tamanho: " + romData.length + " bytes");

            this.mbc = createMbc(romData);
            if (this.mbc == null) {
                System.err.println("Tipo de cartucho (MBC) não suportado ou cabeçalho inválido.");
                return false;
            }

            System.out.println("Cartucho pronto para uso com: " + this.mbc.getClass().getSimpleName());
            return true;

        } catch (IOException e) {
            System.err.println("Erro ao carregar ROM: " + e.getMessage());
            this.mbc = new Mbc0RomOnly(new byte[0]); // Retorna ao estado nulo
            return false;
        }
    }

    private MemoryBankController createMbc(byte[] romData) {
        if (romData.length < 0x150) {
            System.err.println("Cabeçalho da ROM inválido.");
            return null;
        }

        int mbcTypeCode = romData[0x0147] & 0xFF;
        int romSizeCode = romData[0x0148] & 0xFF;
        int ramSizeCode = romData[0x0149] & 0xFF;

        System.out.println(String.format("Cabeçalho - Tipo MBC: 0x%02X, Tamanho ROM: 0x%02X, Tamanho RAM: 0x%02X",
                mbcTypeCode, romSizeCode, ramSizeCode));

        switch (mbcTypeCode) {
            case 0x00:
            case 0x08: // ROM+RAM
            case 0x09: // ROM+RAM+BATTERY
                return new Mbc0RomOnly(romData);
            case 0x01: // MBC1
            case 0x02: // MBC1+RAM
            case 0x03: // MBC1+RAM+BATTERY
                return new MBC1(romData, ramSizeCode);

            // --- NOVAS ENTRADAS AQUI ---
            case 0x05: // MBC2
            case 0x06: // MBC2+BATTERY
                return new MBC2(romData);

            case 0x0F: // MBC3+TIMER+BATTERY
            case 0x10: // MBC3+TIMER+RAM+BATTERY
            case 0x11: // MBC3
            case 0x12: // MBC3+RAM
            case 0x13: // MBC3+RAM+BATTERY
                return new MBC3(romData, ramSizeCode);

            case 0x19: // MBC5
            case 0x1A: // MBC5+RAM
            case 0x1B: // MBC5+RAM+BATTERY
            case 0x1C: // MBC5+RUMBLE
            case 0x1D: // MBC5+RUMBLE+RAM
            case 0x1E: // MBC5+RUMBLE+RAM+BATTERY
                return new MBC5(romData, ramSizeCode); // Lógica de Rumble não implementada

            default:
                System.err.println("AVISO: Tipo de MBC 0x" + Integer.toHexString(mbcTypeCode) + " não suportado. Tratando como ROM Only.");
                return new Mbc0RomOnly(romData);
        }
    }

    public int read(int address) {
        return mbc.read(address);
    }

    public void write(int address, byte value) {
        mbc.write(address, value);
    }

    public int readRam(int address) {
        return mbc.readRam(address);
    }

    public void writeRam(int address, byte value) {
        mbc.writeRam(address, value);
    }

    public void update(int cycles) {
        mbc.update(cycles);
    }
}

class MBC2 extends AbstractMBC {
    private int romBank = 1;

    public MBC2(byte[] romData) {
        // MBC2 usa 512x4 bits de RAM interna, vamos alocar 256 bytes (512 nibbles)
        super(romData, 0x01); // 0x01 é um código inválido de RAM, mas alocamos manualmente
        this.ramData = new byte[512]; // 512 nibbles = 256 bytes, mas endereçamos até 511
        Arrays.fill(this.ramData, (byte) 0xFF);
        System.out.println("RAM Interna do MBC2 alocada: 512x4 bits.");
    }

    @Override
    public int read(int address) {
        if (address >= 0x0000 && address <= 0x3FFF) {
            return romData[address] & 0xFF; // Banco 0 fixo
        } else if (address >= 0x4000 && address <= 0x7FFF) {
            int mappedAddress = (romBank * 0x4000) + (address - 0x4000);
            if (mappedAddress < romData.length) {
                return romData[mappedAddress] & 0xFF;
            }
        }
        return 0xFF;
    }

    @Override
    public void write(int address, byte value) {
        if (address >= 0x0000 && address <= 0x3FFF) {
            // Bit 8 do endereço determina a função
            if ((address & 0x0100) == 0) { // Bit 8 = 0 -> RAM Enable
                ramEnabled = (value & 0x0F) == 0x0A;
            } else { // Bit 8 = 1 -> ROM Bank Select
                romBank = value & 0x0F;
                if (romBank == 0) romBank = 1;
            }
        }
    }

    @Override
    protected int readRamBank(int address) {
        // MBC2 tem 512 nibbles (4-bit) de RAM.
        int ramAddress = address & 0x01FF; // Endereços dão a volta a cada 512 bytes
        if (ramData != null && ramAddress < ramData.length) {
            return (ramData[ramAddress] & 0x0F) | 0xF0; // Retorna apenas os 4 bits de baixo
        }
        return 0xFF;
    }

    @Override
    protected void writeRamBank(int address, byte value) {
        int ramAddress = address & 0x01FF;
        if (ramData != null && ramAddress < ramData.length) {
            ramData[ramAddress] = (byte)(value & 0x0F); // Armazena apenas os 4 bits de baixo
        }
    }
}


// =========================================================================
// MBC 3 (com Relógio em Tempo Real - RTC)
// =========================================================================
class MBC3 extends AbstractMBC {
    private int romBank = 1;
    private int ramBankOrRtcRegister = 0; // 0-3 para RAM, 8-C para RTC
    private long rtcLatchedTime = 0;
    private long rtcLastUpdateTime = System.currentTimeMillis();
    private final byte[] rtcRegisters = new byte[5]; // S, M, H, DL, DH

    public MBC3(byte[] romData, int ramSizeCode) {
        super(romData, ramSizeCode);
    }

    @Override
    public void update(int cycles) {
        // Atualiza o RTC. Uma implementação mais precisa usaria o oscilador de 32768Hz,
        // mas usar o tempo do sistema é uma aproximação funcional.
        long now = System.currentTimeMillis();
        if (now - rtcLastUpdateTime > 1000) { // Atualiza a cada segundo
            rtcLastUpdateTime = now;
            if ((rtcRegisters[4] & 0x40) == 0) { // Se o relógio não estiver parado (Halt = 0)
                long totalSeconds = getRtcSeconds();
                totalSeconds++;
                setRtcSeconds(totalSeconds);
            }
        }
    }

    @Override
    public int read(int address) {
        if (address >= 0x0000 && address <= 0x3FFF) {
            return romData[address] & 0xFF;
        } else if (address >= 0x4000 && address <= 0x7FFF) {
            int mappedAddress = (romBank * 0x4000) + (address - 0x4000);
            if (mappedAddress < romData.length) {
                return romData[mappedAddress] & 0xFF;
            }
        }
        return 0xFF;
    }

    @Override
    public void write(int address, byte value) {
        if (address >= 0x0000 && address <= 0x1FFF) { // RAM e Timer Enable
            ramEnabled = (value & 0x0F) == 0x0A;
        } else if (address >= 0x2000 && address <= 0x3FFF) { // ROM Bank
            romBank = value & 0x7F;
            if (romBank == 0) romBank = 1;
        } else if (address >= 0x4000 && address <= 0x5FFF) { // RAM Bank ou RTC Register Select
            ramBankOrRtcRegister = value & 0x0F;
        } else if (address >= 0x6000 && address <= 0x7FFF) { // Latch Clock Data
            if ((value & 0x01) == 0x01) { // A transição 00 -> 01 faz o latch
                rtcLatchedTime = System.currentTimeMillis(); // Trava o tempo atual para leitura consistente
                // Uma implementação mais precisa travaria os valores dos contadores internos
            }
        }
    }

    @Override
    protected int readRamBank(int address) {
        if (ramBankOrRtcRegister >= 0x08 && ramBankOrRtcRegister <= 0x0C) { // Acesso ao RTC
            return rtcRegisters[ramBankOrRtcRegister - 0x08] & 0xFF;
        } else if (ramBankOrRtcRegister <= 0x03) { // Acesso à RAM
            int bank = ramBankOrRtcRegister;
            int mappedAddress = (bank * 0x2000) + (address & 0x1FFF);
            if (ramData != null && mappedAddress < ramData.length) {
                return ramData[mappedAddress] & 0xFF;
            }
        }
        return 0xFF;
    }

    @Override
    protected void writeRamBank(int address, byte value) {
        if (ramBankOrRtcRegister >= 0x08 && ramBankOrRtcRegister <= 0x0C) { // Escreve no RTC
            rtcRegisters[ramBankOrRtcRegister - 0x08] = value;
        } else if (ramBankOrRtcRegister <= 0x03) { // Escreve na RAM
            int bank = ramBankOrRtcRegister;
            int mappedAddress = (bank * 0x2000) + (address & 0x1FFF);
            if (ramData != null && mappedAddress < ramData.length) {
                ramData[mappedAddress] = value;
            }
        }
    }

    private long getRtcSeconds() {
        long days = ((rtcRegisters[4] & 1) << 8) | (rtcRegisters[3] & 0xFF);
        return rtcRegisters[0] + (rtcRegisters[1] * 60) + (rtcRegisters[2] * 3600) + (days * 86400);
    }

    private void setRtcSeconds(long totalSeconds) {
        long days = totalSeconds / 86400;
        totalSeconds %= 86400;
        long hours = totalSeconds / 3600;
        totalSeconds %= 3600;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;

        rtcRegisters[0] = (byte) seconds;
        rtcRegisters[1] = (byte) minutes;
        rtcRegisters[2] = (byte) hours;
        rtcRegisters[3] = (byte) (days & 0xFF);
        rtcRegisters[4] &= 0xFE; // Limpa o bit 0 (bit 8 dos dias)
        rtcRegisters[4] |= (byte) ((days >> 8) & 1); // Seta o bit 8
        if (days > 511) { // Se o contador de dias estourou
            rtcRegisters[4] |= 0x80; // Seta o Carry bit
        }
    }
}


// =========================================================================
// MBC 5
// =========================================================================
class MBC5 extends AbstractMBC {
    private int romBank = 1; // Pode ser de 0 a 511
    private int ramBank = 0; // Pode ser de 0 a 15

    public MBC5(byte[] romData, int ramSizeCode) {
        super(romData, ramSizeCode);
    }

    @Override
    public int read(int address) {
        if (address >= 0x0000 && address <= 0x3FFF) {
            return romData[address] & 0xFF; // Banco 0 sempre fixo
        } else if (address >= 0x4000 && address <= 0x7FFF) {
            int mappedAddress = (romBank * 0x4000) + (address - 0x4000);
            if (mappedAddress < romData.length) {
                return romData[mappedAddress] & 0xFF;
            }
        }
        return 0xFF;
    }

    @Override
    public void write(int address, byte value) {
        if (address >= 0x0000 && address <= 0x1FFF) { // RAM Enable
            ramEnabled = (value & 0x0F) == 0x0A;
        } else if (address >= 0x2000 && address <= 0x2FFF) { // ROM Bank (8 bits de baixo)
            romBank = (romBank & 0x100) | (value & 0xFF);
        } else if (address >= 0x3000 && address <= 0x3FFF) { // ROM Bank (9º bit)
            romBank = (romBank & 0x0FF) | ((value & 0x01) << 8);
        } else if (address >= 0x4000 && address <= 0x5FFF) { // RAM Bank
            ramBank = value & 0x0F; // TODO: Lógica de Rumble
        }
    }

    @Override
    protected int readRamBank(int address) {
        int mappedAddress = (ramBank * 0x2000) + (address & 0x1FFF);
        if (ramData != null && mappedAddress < ramData.length) {
            return ramData[mappedAddress] & 0xFF;
        }
        return 0xFF;
    }

    @Override
    protected void writeRamBank(int address, byte value) {
        int mappedAddress = (ramBank * 0x2000) + (address & 0x1FFF);
        if (ramData != null && mappedAddress < ramData.length) {
            ramData[mappedAddress] = value;
        }
    }
}

// =========================================================================
// Implementação base para todos os MBCs
// =========================================================================
abstract class AbstractMBC implements MemoryBankController {
    protected final byte[] romData;
    protected byte[] ramData;
    protected boolean ramEnabled = false;

    public AbstractMBC(byte[] romData, int ramSizeCode) {
        this.romData = romData;
        int ramSize = getRamSizeBytes(ramSizeCode);
        if (ramSize > 0) {
            this.ramData = new byte[ramSize];
            Arrays.fill(this.ramData, (byte)0xFF);
            System.out.println("RAM Externa alocada: " + ramSize + " bytes.");
            // TODO: Carregar save .sav aqui se existir
        }
    }

    private int getRamSizeBytes(int code) {
        switch (code) {
            case 0x01: return 2 * 1024;
            case 0x02: return 8 * 1024;
            case 0x03: return 32 * 1024;  // 4 banks de 8KB
            case 0x04: return 128 * 1024; // 16 banks de 8KB
            case 0x05: return 64 * 1024;  // 8 banks de 8KB
            default: return 0;
        }
    }

    @Override
    public int readRam(int address) {
        if (ramData == null || !ramEnabled) {
            return 0xFF;
        }
        return readRamBank(address);
    }

    @Override
    public void writeRam(int address, byte value) {
        if (ramData == null || !ramEnabled) {
            return;
        }
        writeRamBank(address, value);
    }

    @Override
    public void update(int cycles) {
        // Padrão é não fazer nada. Apenas MBCs com RTC precisam disso.
    }

    // Métodos a serem implementados pelas subclasses
    protected abstract int readRamBank(int address);
    protected abstract void writeRamBank(int address, byte value);
}


// =========================================================================
// MBC 0: ROM Only (e ROM+RAM sem banking)
// =========================================================================
class Mbc0RomOnly extends AbstractMBC {
    public Mbc0RomOnly(byte[] romData) {
        super(romData, 0x02); // Assumindo até 8KB de RAM para ROM+RAM
        this.ramEnabled = true; // ROM+RAM está sempre habilitada se presente
    }

    @Override
    public int read(int address) {
        if (address < romData.length) {
            return romData[address] & 0xFF;
        }
        return 0xFF;
    }

    @Override
    public void write(int address, byte value) {
        // Não faz nada em ROM Only
    }

    @Override
    protected int readRamBank(int address) {
        if (ramData != null && address < ramData.length) {
            return ramData[address] & 0xFF;
        }
        return 0xFF;
    }

    @Override
    protected void writeRamBank(int address, byte value) {
        if (ramData != null && address < ramData.length) {
            ramData[address] = value;
        }
    }
}


// =========================================================================
// MBC 1
// =========================================================================
class MBC1 extends AbstractMBC {
    private int romBank = 1;
    private int ramBank = 0;
    private int bankingMode = 0; // 0=ROM, 1=RAM

    public MBC1(byte[] romData, int ramSizeCode) {
        super(romData, ramSizeCode);
    }

    @Override
    public int read(int address) {
        if (address >= 0x0000 && address <= 0x3FFF) {
            int bank;
            if (bankingMode == 1) { // Modo RAM
                bank = (ramBank << 5); // Usa os bits do registrador de RAM como bits altos da ROM
            } else { // Modo ROM
                bank = 0; // Banco 0
            }
            int mappedAddress = (bank * 0x4000) + address;
            if(mappedAddress < romData.length) {
                return romData[mappedAddress] & 0xFF;
            }
            return 0xFF;

        } else if (address >= 0x4000 && address <= 0x7FFF) {
            int bank = (ramBank << 5) | romBank;
            int mappedAddress = (bank * 0x4000) + (address - 0x4000);
            if(mappedAddress < romData.length) {
                return romData[mappedAddress] & 0xFF;
            }
            return 0xFF;
        }
        return 0xFF;
    }

    @Override
    public void write(int address, byte value) {
        if (address >= 0x0000 && address <= 0x1FFF) { // RAM Enable
            ramEnabled = (value & 0x0F) == 0x0A;
        } else if (address >= 0x2000 && address <= 0x3FFF) { // ROM Bank (low 5 bits)
            int low5 = value & 0x1F;
            if (low5 == 0) low5 = 1;
            romBank = (romBank & 0xE0) | low5;
        } else if (address >= 0x4000 && address <= 0x5FFF) { // RAM Bank or ROM Bank (high 2 bits)
            ramBank = value & 0x03;
        } else if (address >= 0x6000 && address <= 0x7FFF) { // Banking Mode Select
            bankingMode = value & 0x01;
        }
    }

    @Override
    protected int readRamBank(int address) {
        int bank = (bankingMode == 1) ? ramBank : 0;
        int mappedAddress = (bank * 0x2000) + address;
        if (ramData != null && mappedAddress < ramData.length) {
            return ramData[mappedAddress] & 0xFF;
        }
        return 0xFF;
    }

    @Override
    protected void writeRamBank(int address, byte value) {
        int bank = (bankingMode == 1) ? ramBank : 0;
        int mappedAddress = (bank * 0x2000) + address;
        if (ramData != null && mappedAddress < ramData.length) {
            ramData[mappedAddress] = value;
        }
    }
}