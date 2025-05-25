package com.meutcc.gbemulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

public class Cartridge {
    private byte[] romData;
    private byte[] ramData;

    private int mbcType = 0;
    private int romSizeCode = 0;
    private int ramSizeCode = 0;

    // MBC1 specific state
    private int currentRomBank = 1; // ROM bank number (1-127 for MBC1, 0 is treated as 1 for 0x4000-0x7FFF)
    private int currentRamBank = 0; // RAM bank number (0-3 for MBC1)
    private boolean ramEnabled = false;
    private int bankingMode = 0; // 0 = ROM banking mode, 1 = RAM banking mode

    // Constants for MBC1 registers access
    private static final int RAM_ENABLE_REGISTER_END = 0x1FFF;
    private static final int ROM_BANK_LOW_REGISTER_END = 0x3FFF;
    private static final int RAM_BANK_OR_ROM_BANK_HIGH_REGISTER_END = 0x5FFF;
    private static final int BANKING_MODE_REGISTER_END = 0x7FFF;

    public Cartridge() {
        resetState();
    }

    private void resetState() {
        romData = null;
        ramData = null;
        mbcType = 0;
        romSizeCode = 0;
        ramSizeCode = 0;
        currentRomBank = 1;
        currentRamBank = 0;
        ramEnabled = false;
        bankingMode = 0;
    }

    public boolean loadROM(String filePath) {
        resetState(); // Reset state before loading a new ROM
        try {
            Path path = Paths.get(filePath);
            if (!Files.exists(path) || !Files.isReadable(path)) {
                System.err.println("Erro: Arquivo ROM não encontrado ou não pode ser lido: " + filePath);
                return false;
            }
            this.romData = Files.readAllBytes(path);
            System.out.println("ROM carregada: " + filePath + ", Tamanho: " + romData.length + " bytes");

            if (!parseHeader()) {
                this.romData = null; // Invalidate ROM if header is bad
                return false;
            }

            // Initialize RAM if the cartridge type indicates it and RAM size is known
            int ramSizeBytes = getRamSizeBytes();
            if (ramSizeBytes > 0 && (mbcType >= 1 && mbcType <= 3)) { // MBC1, MBC2, MBC3 typically have RAM
                this.ramData = new byte[ramSizeBytes];
                Arrays.fill(this.ramData, (byte)0xFF); // Initialize RAM (optional, some games expect 0xFF)
                System.out.println("RAM Externa alocada: " + ramSizeBytes + " bytes.");
                // TODO: Implement loading/saving RAM to a .sav file
            } else {
                this.ramData = null; // No RAM or unsupported MBC for RAM yet
            }

            System.out.println("Cartucho pronto para uso com MBC Tipo: " + mbcType);
            return true;
        } catch (IOException e) {
            System.err.println("Erro ao carregar ROM: " + e.getMessage());
            this.romData = null;
            return false;
        }
    }

    private boolean parseHeader() {
        if (romData == null || romData.length < 0x150) {
            System.err.println("Cabeçalho da ROM inválido ou ROM muito pequena.");
            return false;
        }

        // Game Title (0x0134-0x0143)
        // For CGB compatibility (0x0143), last byte of title area indicates CGB features.
        // byte cgbFlag = romData[0x0143];
        // if ((cgbFlag & 0x80) != 0) { System.out.println("Suporte CGB: Sim"); }
        // if ((cgbFlag & 0x40) != 0) { System.out.println("Apenas CGB: Sim - NÃO SUPORTADO"); return false;}


        this.mbcType = romData[0x0147] & 0xFF;
        this.romSizeCode = romData[0x0148] & 0xFF;
        this.ramSizeCode = romData[0x0149] & 0xFF;

        String title = "";
        try {
            // Read title until null terminator or max length
            int titleEnd = 0x0134;
            while(titleEnd <= 0x0143 && romData[titleEnd] != 0) {
                titleEnd++;
            }
            title = new String(romData, 0x0134, titleEnd - 0x0134, "ASCII").trim();
        } catch (Exception e) {
            title = "N/A";
        }


        System.out.println("--- Informações do Cartucho ---");
        System.out.println("Título: " + title);
        System.out.println(String.format("Tipo de Cartucho (MBC): 0x%02X (%s)", mbcType, getMbcTypeName()));
        System.out.println(String.format("Tamanho da ROM: 0x%02X (%d KB, %d bancos de 16KB)", romSizeCode, getRomSizeBytes() / 1024, getRomSizeBytes() / (16 * 1024)));
        System.out.println(String.format("Tamanho da RAM Externa: 0x%02X (%s)", ramSizeCode, getRamSizeString()));
        System.out.println("-------------------------------");

        if (getRomSizeBytes() > romData.length) {
            System.err.println("Erro: Tamanho da ROM no cabeçalho (" + getRomSizeBytes() +
                    ") excede o tamanho real do arquivo (" + romData.length + ").");
            return false;
        }

        // For now, only explicitly support ROM_ONLY and MBC1.
        // Other MBCs will behave like ROM_ONLY (which will break them).
        if (mbcType != 0x00 && mbcType != 0x01 && mbcType != 0x02 && mbcType != 0x03) {
            System.out.println("AVISO: Tipo de MBC " + String.format("0x%02X", mbcType) +
                    " não é totalmente suportado. Tratando como MBC1 (limitado) ou ROM Only.");
            // Allow loading but functionality will be incorrect for unsupported MBCs.
            // For simplicity, we'll apply MBC1 logic if mbcType > 0.
        }
        return true;
    }

    private String getMbcTypeName() {
        switch (mbcType) {
            case 0x00: return "ROM ONLY";
            case 0x01: return "MBC1";
            case 0x02: return "MBC1+RAM";
            case 0x03: return "MBC1+RAM+BATTERY";
            case 0x05: return "MBC2";
            case 0x06: return "MBC2+BATTERY";
            case 0x08: return "ROM+RAM"; // Usually no MBC, just direct RAM
            case 0x09: return "ROM+RAM+BATTERY";
            case 0x0B: return "MMM01";
            case 0x0C: return "MMM01+RAM";
            case 0x0D: return "MMM01+RAM+BATTERY";
            case 0x0F: return "MBC3+TIMER+BATTERY";
            case 0x10: return "MBC3+TIMER+RAM+BATTERY";
            case 0x11: return "MBC3";
            case 0x12: return "MBC3+RAM";
            case 0x13: return "MBC3+RAM+BATTERY";
            case 0x19: return "MBC5";
            case 0x1A: return "MBC5+RAM";
            case 0x1B: return "MBC5+RAM+BATTERY";
            case 0x1C: return "MBC5+RUMBLE";
            case 0x1D: return "MBC5+RUMBLE+RAM";
            case 0x1E: return "MBC5+RUMBLE+RAM+BATTERY";
            case 0x20: return "MBC6";
            case 0x22: return "MBC7+SENSOR+RUMBLE+RAM+BATTERY";
            case 0xFC: return "POCKET CAMERA";
            case 0xFD: return "BANDAI TAMA5";
            case 0xFE: return "HuC3";
            case 0xFF: return "HuC1+RAM+BATTERY";
            default:   return "Desconhecido";
        }
    }

    public int getRomSizeBytes() {
        if (romSizeCode > 0x08) { // Values above 0x08 are not standard or are for very large/custom ROMs
            System.out.println("Aviso: Código de tamanho de ROM incomum: " + String.format("0x%02X", romSizeCode));
            // Fallback for potentially larger than 8MB ROMs if needed, though rare and complex
            // For now, cap at 8MB for simplicity with standard codes.
            return (32 * 1024) << Math.min(romSizeCode, 8); // Cap at 8MB
        }
        return (32 * 1024) << romSizeCode; // 32KB * 2^romSizeCode
    }

    public int getRamSizeBytes() {
        switch (ramSizeCode) {
            case 0x00: return 0;       // No RAM
            case 0x01: return 2 * 1024; // 2 KB (often unused for MBC1, which prefers 8KB or 32KB chunks)
            case 0x02: return 8 * 1024; // 8 KB
            case 0x03: return 32 * 1024;// 32 KB (4 banks of 8KB)
            case 0x04: return 128 * 1024;// 128 KB (Not standard for MBC1, more for MBC3/5)
            case 0x05: return 64 * 1024; // 64 KB (Not standard for MBC1)
            default:   return 0;
        }
    }
    private String getRamSizeString() {
        int size = getRamSizeBytes();
        if (size == 0) return "Nenhuma";
        if (size < 1024) return size + " Bytes";
        return (size / 1024) + " KB";
    }


    // Leitura da ROM (considerando MBC)
    public int read(int address) {
        if (romData == null) return 0xFF;

        // Bank 00 (0x0000 - 0x3FFF)
        if (address <= 0x3FFF) {
            // For MBC1, if in ROM mode and ROM bank upper bits (from 0x4000-0x5FFF) are set,
            // this area can also be banked for ROMs >= 1MB.
            // This is a "simple" MBC1 for now: bank 0 is always bank 0.
            // TODO: Implement advanced MBC1 banking for 0x0000-0x3FFF if needed.
            if (address < romData.length) {
                return romData[address] & 0xFF;
            }
            return 0xFF; // Out of actual ROM bounds
        }
        // Switched ROM Bank (0x4000 - 0x7FFF)
        else if (address <= 0x7FFF) {
            int effectiveRomBank = currentRomBank;
            // Some sources say bank 0 and 1 are the same for this region, or bank 0 is not selectable.
            // If currentRomBank (after all calculations) is 0, it usually maps to bank 1.
            // Our currentRomBank is already 1-based.

            // For MBC1, ROM banks 0x20, 0x40, 0x60 are aliases of 0x00, 0x01, 0x01 etc.
            // (more accurately, they are not directly addressable via the 5-bit low register,
            // but are selected by the 2 high bits).
            // For simplicity, we'll allow the bank number to be what it is,
            // and ensure it doesn't exceed the number of actual banks.
            int numRomBanks = getRomSizeBytes() / (16 * 1024);
            if (effectiveRomBank >= numRomBanks) {
                effectiveRomBank %= numRomBanks; // Wrap around (may not be 100% accurate for all edge cases)
                if (effectiveRomBank == 0 && numRomBanks > 1) effectiveRomBank = 1; // Avoid bank 0 here if it's an alias
            }


            long mappedAddress = (long)effectiveRomBank * 0x4000L + (address - 0x4000);
            if (mappedAddress < romData.length) {
                return romData[(int)mappedAddress] & 0xFF;
            }
            //System.err.println("ROM read out of bounds: bank " + effectiveRomBank + " addr " + String.format("0x%X", address));
            return 0xFF;
        }
        return 0xFF; // Should not happen if address is within 0x0000-0x7FFF
    }

    // Escrita no cartucho (controle do MBC ou escrita na RAM)
    public void write(int address, byte value) {
        if (romData == null) return; // No ROM loaded, nothing to control

        // --- MBC1 Specific Logic ---
        if (mbcType >= 1 && mbcType <= 3) { // MBC1, MBC1+RAM, MBC1+RAM+BATTERY
            if (address <= RAM_ENABLE_REGISTER_END) { // 0x0000 - 0x1FFF: RAM Enable
                if ((value & 0x0F) == 0x0A) {
                    ramEnabled = true;
                } else {
                    ramEnabled = false;
                }
            } else if (address <= ROM_BANK_LOW_REGISTER_END) { // 0x2000 - 0x3FFF: ROM Bank Number (lower 5 bits)
                int low5bits = value & 0x1F;
                if (low5bits == 0) low5bits = 1; // Bank 0 is not selectable, treated as bank 1.
                currentRomBank = (currentRomBank & 0xE0) | low5bits; // Keep upper 2 bits, set lower 5
                // currentRomBank &= (getTotalRomBanks() - 1); // Ensure bank is within limits
            } else if (address <= RAM_BANK_OR_ROM_BANK_HIGH_REGISTER_END) { // 0x4000 - 0x5FFF: RAM Bank or ROM Bank High
                int data = value & 0x03; // 2 bits
                if (bankingMode == 0) { // ROM Banking Mode
                    // These bits become bits 5 and 6 of the ROM bank number.
                    currentRomBank = (currentRomBank & 0x1F) | (data << 5); // Keep lower 5 bits, set upper 2
                    // currentRomBank &= (getTotalRomBanks() - 1);
                } else { // RAM Banking Mode
                    currentRamBank = data;
                    // currentRamBank &= (getTotalRamBanks() - 1);
                }
            } else if (address <= BANKING_MODE_REGISTER_END) { // 0x6000 - 0x7FFF: Banking Mode Select
                bankingMode = (value & 0x01); // 0 for ROM mode, 1 for RAM mode
            }
        }
        // --- End MBC1 Specific Logic ---

        // TODO: Implement other MBC types (MBC2, MBC3, MBC5) here using similar structures
        // else if (mbcType == 0x05 || mbcType == 0x06) { /* MBC2 Logic */ }
        // etc.
    }

    // Leitura da RAM externa do cartucho
    public int readRam(int address) {
        if (ramData == null || !ramEnabled) {
            return 0xFF; // No RAM, RAM disabled, or address out of mapped range
        }

        int mappedAddress = getMappedRamAddress(address);
        if (mappedAddress != -1 && mappedAddress < ramData.length) {
            return ramData[mappedAddress] & 0xFF;
        }
        return 0xFF;
    }

    // Escrita na RAM externa do cartucho
    public void writeRam(int address, byte value) {
        if (ramData == null || !ramEnabled) {
            return; // No RAM or RAM disabled
        }

        int mappedAddress = getMappedRamAddress(address);
        if (mappedAddress != -1 && mappedAddress < ramData.length) {
            ramData[mappedAddress] = value;
        }
    }

    private int getMappedRamAddress(int address) {
        if (ramData == null || getRamSizeBytes() == 0) return -1;

        int bankSize = 8 * 1024; // Typically 8KB RAM banks for MBC1
        int localAddress = address & (bankSize - 1); // Address within the 8KB window (0x0000-0x1FFF for RAM A000-BFFF)

        if (mbcType >= 1 && mbcType <= 3) { // MBC1
            if (bankingMode == 1) { // RAM Banking Mode
                return (currentRamBank * bankSize) + localAddress;
            } else { // ROM Banking Mode - only RAM bank 0 is accessible
                if (currentRamBank != 0 && getRamSizeBytes() > 8192) {
                    // In ROM mode, if RAM > 8KB, only bank 0 is typically addressable.
                    // Some interpretations say currentRamBank can still select for larger RAMs,
                    // but it's often tied to the upper bits of ROM bank in this mode.
                    // For simplicity here, if in ROM mode, assume RAM bank 0.
                    // If you need more than 8KB RAM in ROM mode, it's complex.
                    // This simplification allows access to the first 8KB bank.
                    // If a game uses >8KB RAM and relies on ROM mode for RAM banking, this is wrong.
                    // return localAddress; // Accesses only the first 8KB bank (bank 0)
                    return (currentRamBank * bankSize) + localAddress; // Allow RAM bank selection even in ROM mode if RAM is big enough. This is a common simplification/interpretation for >8KB RAM.
                }
                return localAddress; // Accesses only the first 8KB bank (bank 0)
            }
        } else if (mbcType == 0x00 || mbcType == 0x08 || mbcType == 0x09) { // ROM_ONLY or ROM+RAM (no banking)
            return localAddress; // Direct mapping if RAM is present
        }
        return -1; // Not handled or no RAM for this MBC type in this configuration
    }

    public byte[] getRomData() {
        return romData;
    }
}