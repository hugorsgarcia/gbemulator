package com.meutcc.gbemulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

public class Cartridge {
    private MemoryBankController mbc;
    private boolean hasBattery = false;
    private String currentRomPath = null;
    private int mbcTypeCode = 0;

    public Cartridge() {
        
        this.mbc = new Mbc0RomOnly(new byte[0]);
    }

    public boolean loadROM(String filePath) {
        try {
            // Save current battery RAM before loading new ROM
            if (hasBattery && currentRomPath != null) {
                saveBatteryRam(currentRomPath);
            }

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

            this.currentRomPath = filePath;
            
            // Load battery RAM if cartridge has battery
            if (hasBattery) {
                loadBatteryRam(filePath);
            }

            System.out.println("Cartucho pronto para uso com: " + this.mbc.getClass().getSimpleName());
            if (hasBattery) {
                System.out.println("Cartucho possui bateria para salvar dados.");
            }
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

        mbcTypeCode = romData[0x0147] & 0xFF;
        int romSizeCode = romData[0x0148] & 0xFF;
        int ramSizeCode = romData[0x0149] & 0xFF;

        System.out.println(String.format("Cabeçalho - Tipo MBC: 0x%02X, Tamanho ROM: 0x%02X, Tamanho RAM: 0x%02X",
                mbcTypeCode, romSizeCode, ramSizeCode));

        // Detect if cartridge has battery
        hasBattery = hasBatteryBackedRam(mbcTypeCode);

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

    private boolean hasBatteryBackedRam(int mbcType) {
        switch (mbcType) {
            case 0x03: 
            case 0x06: 
            case 0x09: 
            case 0x0D: 
            case 0x0F: 
            case 0x10: 
            case 0x13: 
            case 0x1B: 
            case 0x1E: 
            case 0xFF: 
                return true;
            default:
                return false;
        }
    }

    private String getSaveFilePath(String romPath) {
        
        if (romPath.toLowerCase().endsWith(".gb")) {
            return romPath.substring(0, romPath.length() - 3) + ".sav";
        } else if (romPath.toLowerCase().endsWith(".gbc")) {
            return romPath.substring(0, romPath.length() - 4) + ".sav";
        } else {
            return romPath + ".sav";
        }
    }

    private void loadBatteryRam(String romPath) {
        String savePath = getSaveFilePath(romPath);
        Path path = Paths.get(savePath);
        
        if (!Files.exists(path)) {
            System.out.println("Nenhum arquivo de save encontrado: " + savePath);
            return;
        }

        try {
            byte[] saveData = Files.readAllBytes(path);
            byte[] ramData = mbc.getRamData();
            
            if (ramData != null) {
                int bytesToCopy = Math.min(saveData.length, ramData.length);
                System.arraycopy(saveData, 0, ramData, 0, bytesToCopy);
                System.out.println("Save carregado: " + savePath + " (" + bytesToCopy + " bytes)");
                
                
                if (mbc instanceof MBC3 && saveData.length > ramData.length) {
                    ((MBC3)mbc).loadRtcData(saveData, ramData.length);
                }
            }
        } catch (IOException e) {
            System.err.println("Erro ao carregar save: " + e.getMessage());
        }
    }

    private void saveBatteryRam(String romPath) {
        String savePath = getSaveFilePath(romPath);
        byte[] ramData = mbc.getRamData();
        
        if (ramData == null) {
            return;
        }

        try {
            
            byte[] dataToSave;
            if (mbc instanceof MBC3) {
                byte[] rtcData = ((MBC3)mbc).getRtcData();
                dataToSave = new byte[ramData.length + rtcData.length];
                System.arraycopy(ramData, 0, dataToSave, 0, ramData.length);
                System.arraycopy(rtcData, 0, dataToSave, ramData.length, rtcData.length);
            } else {
                dataToSave = ramData;
            }
            
            Files.write(Paths.get(savePath), dataToSave);
            System.out.println("Save gravado: " + savePath + " (" + dataToSave.length + " bytes)");
        } catch (IOException e) {
            System.err.println("Erro ao salvar save: " + e.getMessage());
        }
    }

    public void saveBatteryRam() {
        if (hasBattery && currentRomPath != null) {
            saveBatteryRam(currentRomPath);
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
    private final byte[] rtcLatchedRegisters = new byte[5];
    private int latchState = 0; 

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
        } else if (address >= 0x6000 && address <= 0x7FFF) {
            
            if (value == 0x00) {
                latchState = 1;
            } else if (value == 0x01 && latchState == 1) {
                
                System.arraycopy(rtcRegisters, 0, rtcLatchedRegisters, 0, 5);
                latchState = 0;
            }
        }
    }

    @Override
    protected int readRamBank(int address) {
        if (ramBankOrRtcRegister >= 0x08 && ramBankOrRtcRegister <= 0x0C) { 
            
            return rtcLatchedRegisters[ramBankOrRtcRegister - 0x08] & 0xFF;
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
            // Write to actual RTC registers (not latched)
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

    public byte[] getRtcData() {
        
        byte[] rtcData = new byte[48];
        System.arraycopy(rtcRegisters, 0, rtcData, 0, 5);
        
        
        long timestamp = System.currentTimeMillis() / 1000; // seconds since epoch
        rtcData[5] = (byte) (timestamp >> 56);
        rtcData[6] = (byte) (timestamp >> 48);
        rtcData[7] = (byte) (timestamp >> 40);
        rtcData[8] = (byte) (timestamp >> 32);
        rtcData[9] = (byte) (timestamp >> 24);
        rtcData[10] = (byte) (timestamp >> 16);
        rtcData[11] = (byte) (timestamp >> 8);
        rtcData[12] = (byte) timestamp;
        
        return rtcData;
    }

    public void loadRtcData(byte[] saveData, int offset) {
        if (saveData.length >= offset + 48) {
            
            System.arraycopy(saveData, offset, rtcRegisters, 0, 5);
            System.arraycopy(saveData, offset, rtcLatchedRegisters, 0, 5);
            
            
            long savedTimestamp = 0;
            for (int i = 0; i < 8; i++) {
                savedTimestamp = (savedTimestamp << 8) | (saveData[offset + 5 + i] & 0xFF);
            }
            
            long currentTimestamp = System.currentTimeMillis() / 1000;
            long elapsedSeconds = currentTimestamp - savedTimestamp;
            
           
            if ((rtcRegisters[4] & 0x40) == 0 && elapsedSeconds > 0) {
                long totalSeconds = getRtcSeconds() + elapsedSeconds;
                setRtcSeconds(totalSeconds);
            }
            
            rtcLastUpdateTime = System.currentTimeMillis();
            System.out.println("RTC data loaded with " + elapsedSeconds + " seconds elapsed.");
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

    @Override
    public byte[] getRamData() {
        return ramData;
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