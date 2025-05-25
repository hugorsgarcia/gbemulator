package com.meutcc.gbemulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Cartridge {
    private byte[] romData;
    private byte[] ramData; // Para cartuchos com RAM externa (não usado inicialmente)
    private int mbcType = 0; // Tipo de Memory Bank Controller (0 = ROM Only)
    // Outras informações do cabeçalho da ROM (título, tamanho, etc.)

    public Cartridge() {
        // Inicializa sem ROM carregada
    }

    public boolean loadROM(String filePath) {
        try {
            Path path = Paths.get(filePath);
            if (!Files.exists(path) || !Files.isReadable(path)) {
                System.err.println("Erro: Arquivo ROM não encontrado ou não pode ser lido: " + filePath);
                return false;
            }
            this.romData = Files.readAllBytes(path);
            System.out.println("ROM carregada: " + filePath + ", Tamanho: " + romData.length + " bytes");

            // Analisar cabeçalho da ROM para tipo de cartucho, tamanho da RAM, etc.
            parseHeader();

            if (mbcType != 0) {
                System.out.println("Aviso: Esta ROM usa MBC tipo " + mbcType +
                        ". Apenas ROM Only (tipo 0) é suportada inicialmente.");
                // Por ora, ainda carregamos, mas o bank switching não funcionará.
            }
            if (getRamSize() > 0) {
                System.out.println("Aviso: Esta ROM requer RAM externa de " + getRamSize() + " bytes." +
                        " RAM externa não totalmente implementada.");
                this.ramData = new byte[getRamSize()]; // Aloca, mas salvamento/carregamento não feito
            }


            return true;
        } catch (IOException e) {
            System.err.println("Erro ao carregar ROM: " + e.getMessage());
            this.romData = null;
            return false;
        }
    }

    private void parseHeader() {
        if (romData == null || romData.length < 0x150) {
            System.err.println("Cabeçalho da ROM inválido ou ROM muito pequena.");
            return;
        }
        // 0x0147: Tipo de Cartucho
        this.mbcType = romData[0x0147] & 0xFF;

        // Título do jogo (0x0134 - 0x0143)
        // ... e outras informações úteis podem ser extraídas aqui.
        String title = new String(romData, 0x0134, 16).trim();
        System.out.println("Título: " + title);
        System.out.println("Tipo de Cartucho (MBC): " + String.format("0x%02X", mbcType));
    }

    public int getRamSize() {
        if (romData == null || romData.length < 0x149) return 0;
        int ramSizeCode = romData[0x0149] & 0xFF;
        switch (ramSizeCode) {
            case 0x00: return 0;      // No RAM
            case 0x01: return 2 * 1024; // 2 KB (Unused)
            case 0x02: return 8 * 1024; // 8 KB
            case 0x03: return 32 * 1024;// 32 KB (4 banks of 8KB)
            case 0x04: return 128 * 1024;// 128 KB (16 banks of 8KB)
            case 0x05: return 64 * 1024; // 64 KB (8 banks of 8KB)
            default:   return 0;
        }
    }


    // Leitura da ROM (considerando MBC futuramente)
    public int read(int address) {
        if (romData == null) return 0xFF; // Nenhuma ROM carregada

        // Para ROM Only (mbcType == 0), o endereço é direto.
        // Para MBCs, o endereço 0x4000-0x7FFF seria mapeado para um banco diferente.
        if (address >= 0 && address < romData.length) {
            return romData[address] & 0xFF;
        }
        return 0xFF; // Fora dos limites da ROM (pode acontecer com ROMs menores que 32KB)
    }

    // Escrita no cartucho (geralmente para controle do MBC ou escrita na RAM)
    public void write(int address, byte value) {
        if (romData == null) return;

        // TODO: Implementar lógica de MBC aqui.
        // Exemplo: if (mbcType == 1) { handleMBC1Write(address, value); }
        // Por enquanto, escritas na área da ROM são ignoradas ou tratadas
        // como controle de RAM para MBCs simples.
        // Ex: Habilitar/desabilitar RAM em 0x0000-0x1FFF
        // Selecionar banco de ROM em 0x2000-0x3FFF, etc.
    }

    // Leitura da RAM externa do cartucho
    public int readRam(int address) {
        if (ramData == null || address < 0 || address >= ramData.length) {
            return 0xFF; // Nenhuma RAM ou fora dos limites
        }
        // TODO: Considerar bancos de RAM para MBCs que suportam mais de 8KB de RAM.
        return ramData[address] & 0xFF;
    }

    // Escrita na RAM externa do cartucho
    public void writeRam(int address, byte value) {
        if (ramData == null || address < 0 || address >= ramData.length) {
            return; // Nenhuma RAM ou fora dos limites
        }
        // TODO: Considerar bancos de RAM e proteção de escrita.
        ramData[address] = value;
    }

    public byte[] getRomData() {
        return romData;
    }
}