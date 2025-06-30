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
            // Adicione outros casos de MBC aqui
            default:
                System.err.println("AVISO: Tipo de MBC 0x" + Integer.toHexString(mbcTypeCode) + " não suportado. Tratando como ROM Only.");
                // Como fallback, podemos tratar como ROM Only para tentar carregar o jogo
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