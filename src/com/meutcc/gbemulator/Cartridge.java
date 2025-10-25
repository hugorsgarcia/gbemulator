package com.meutcc.gbemulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

public class Cartridge {
    private MemoryBankController mbc;

    public Cartridge() {
        
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
            this.mbc = new Mbc0RomOnly(new byte[0]); 
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
            case 0x08: 
            case 0x09: 
                return new Mbc0RomOnly(romData);
            case 0x01: 
            case 0x02: 
            case 0x03: 
                return new MBC1(romData, ramSizeCode);

            
            case 0x05: 
            case 0x06: 
                return new MBC2(romData);

            case 0x0F: 
            case 0x10: 
            case 0x11: 
            case 0x12: 
            case 0x13: 
                return new MBC3(romData, ramSizeCode);

            case 0x19: 
            case 0x1A: 
            case 0x1B: 
            case 0x1C: 
            case 0x1D: 
            case 0x1E: 
                return new MBC5(romData, ramSizeCode); 

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
        super(romData, 0x01);
        this.ramData = new byte[512];
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
           
            if ((address & 0x0100) == 0) {
                ramEnabled = (value & 0x0F) == 0x0A;
            } else { // Bit 8 = 1 -> ROM Bank Select
                romBank = value & 0x0F;
                if (romBank == 0) romBank = 1;
            }
        }
    }

    @Override
    protected int readRamBank(int address) {
        
        int ramAddress = address & 0x01FF; 
        if (ramData != null && ramAddress < ramData.length) {
            return (ramData[ramAddress] & 0x0F) | 0xF0; 
        }
        return 0xFF;
    }

    @Override
    protected void writeRamBank(int address, byte value) {
        int ramAddress = address & 0x01FF;
        if (ramData != null && ramAddress < ramData.length) {
            ramData[ramAddress] = (byte)(value & 0x0F); 
        }
    }
}


class MBC3 extends AbstractMBC {
    private int romBank = 1;
    private int ramBankOrRtcRegister = 0;
    private long rtcLastUpdateTime = System.currentTimeMillis();
    private final byte[] rtcRegisters = new byte[5];

    public MBC3(byte[] romData, int ramSizeCode) {
        super(romData, ramSizeCode);
    }

    @Override
    public void update(int cycles) {
        long now = System.currentTimeMillis();
        if (now - rtcLastUpdateTime > 1000) {
            rtcLastUpdateTime = now;
            if ((rtcRegisters[4] & 0x40) == 0) {
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
        if (address >= 0x0000 && address <= 0x1FFF) {
            ramEnabled = (value & 0x0F) == 0x0A;
        } else if (address >= 0x2000 && address <= 0x3FFF) {
            romBank = value & 0x7F;
            if (romBank == 0) romBank = 1;
        } else if (address >= 0x4000 && address <= 0x5FFF) {
            ramBankOrRtcRegister = value & 0x0F;
        }
    }

    @Override
    protected int readRamBank(int address) {
        if (ramBankOrRtcRegister >= 0x08 && ramBankOrRtcRegister <= 0x0C) { 
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
        if (ramBankOrRtcRegister >= 0x08 && ramBankOrRtcRegister <= 0x0C) {
            rtcRegisters[ramBankOrRtcRegister - 0x08] = value;
        } else if (ramBankOrRtcRegister <= 0x03) { 
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
        rtcRegisters[4] &= 0xFE; 
        rtcRegisters[4] |= (byte) ((days >> 8) & 1); 
        if (days > 511) { 
            rtcRegisters[4] |= 0x80; 
        }
    }
}


class MBC5 extends AbstractMBC {
    private int romBank = 1;
    private int ramBank = 0;

    public MBC5(byte[] romData, int ramSizeCode) {
        super(romData, ramSizeCode);
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
        if (address >= 0x0000 && address <= 0x1FFF) {
            ramEnabled = (value & 0x0F) == 0x0A;
        } else if (address >= 0x2000 && address <= 0x2FFF) {
            romBank = (romBank & 0x100) | (value & 0xFF);
        } else if (address >= 0x3000 && address <= 0x3FFF) {
            romBank = (romBank & 0x0FF) | ((value & 0x01) << 8);
        } else if (address >= 0x4000 && address <= 0x5FFF) {
            ramBank = value & 0x0F;
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
        }
    }

    private int getRamSizeBytes(int code) {
        switch (code) {
            case 0x01: return 2 * 1024;
            case 0x02: return 8 * 1024;
            case 0x03: return 32 * 1024; 
            case 0x04: return 128 * 1024; 
            case 0x05: return 64 * 1024; 
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
    }

    protected abstract int readRamBank(int address);
    protected abstract void writeRamBank(int address, byte value);
}

class Mbc0RomOnly extends AbstractMBC {
    public Mbc0RomOnly(byte[] romData) {
        super(romData, 0x02);
        this.ramEnabled = true;
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


class MBC1 extends AbstractMBC {
    private int romBank = 1;
    private int ramBank = 0;
    private int bankingMode = 0;

    public MBC1(byte[] romData, int ramSizeCode) {
        super(romData, ramSizeCode);
    }

    @Override
    public int read(int address) {
        if (address >= 0x0000 && address <= 0x3FFF) {
            int bank;
            if (bankingMode == 1) {
                bank = (ramBank << 5);
            } else {
                bank = 0;
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
        if (address >= 0x0000 && address <= 0x1FFF) {
            ramEnabled = (value & 0x0F) == 0x0A;
        } else if (address >= 0x2000 && address <= 0x3FFF) {
            int low5 = value & 0x1F;
            if (low5 == 0) low5 = 1;
            romBank = (romBank & 0xE0) | low5;
        } else if (address >= 0x4000 && address <= 0x5FFF) {
            ramBank = value & 0x03;
        } else if (address >= 0x6000 && address <= 0x7FFF) {
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