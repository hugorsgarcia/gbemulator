package com.meutcc.gbemulator;

import java.io.*;

public class GameBoy {
    private final CPU cpu;
    private final MMU mmu;
    private final PPU ppu;
    private final APU apu;
    private final Cartridge cartridge;
    private boolean emulatorSoundGloballyEnabled = true;

    public GameBoy() {
        this.cartridge = new Cartridge();
        this.ppu = new PPU();
        this.apu = new APU();
        this.mmu = new MMU(cartridge, ppu, apu);
        this.cpu = new CPU(mmu); // CPU agora passa a referência da MMU
        
        // Set CPU reference in MMU for STOP mode wake-up
        this.mmu.setCpu(cpu);

        this.ppu.setMmu(mmu);
        if (this.apu != null) {
            this.apu.setEmulatorSoundGloballyEnabled(this.emulatorSoundGloballyEnabled);
        }
    }

    public boolean loadROM(String romPath) {
        if (cartridge.loadROM(romPath)) {
            mmu.loadCartridge(cartridge);
            if (this.apu != null) {
                this.apu.setEmulatorSoundGloballyEnabled(this.emulatorSoundGloballyEnabled);
            }
            return true;
        }
        return false;
    }

    public void reset() {
        cpu.reset();
        ppu.reset();
        mmu.reset();
        if (apu != null) {
            apu.reset();
            apu.setEmulatorSoundGloballyEnabled(this.emulatorSoundGloballyEnabled);
        }
        System.out.println("Game Boy components reset.");
    }

    public int step() {
        int cycles;

        // Verifica se uma transferência DMA está em andamento.
        if (mmu.isDmaActive()) {
            // A CPU está paralisada. Nenhum passo da CPU é executado.
            // Apenas avançamos o tempo para os outros componentes.
            // Usamos 4 ciclos (equivalente a um NOP) como unidade de tempo.
            cycles = 4;
            mmu.updateDma(cycles); // Decrementa o contador de ciclos do DMA.
        } else {
            cycles = cpu.step();
            if (cycles == -1) return -1;
        }

        ppu.update(cycles);
        mmu.updateTimers(cycles); // <-- Você já tem isso, ótimo!

        cartridge.update(cycles); // Para atualizar lógicas internas do cartucho, como o RTC

        if (this.emulatorSoundGloballyEnabled && apu != null) {
            apu.update(cycles);
        }

        // A chamada a handleInterrupts foi movida para dentro do CPU.step() na sua versão, mantenha assim.
        // cpu.handleInterrupts(); <-- Se estiver aqui, pode remover. Se estiver no CPU.step(), está ok.

        return cycles;
    }

    public void setEmulatorSoundGloballyEnabled(boolean enabled) {
        this.emulatorSoundGloballyEnabled = enabled;
        if (this.apu != null) {
            this.apu.setEmulatorSoundGloballyEnabled(enabled);
        }
    }

    public MMU getMmu() {
        return mmu;
    }

    public PPU getPpu() {
        return ppu;
    }

    public CPU getCpu() {
        return cpu;
    }

    public APU getApu() {
        return this.apu;
    }
    
    public Cartridge getCartridge() {
        return cartridge;
    }
    
    // ========================================================================
    // SAVE/LOAD STATE
    // ========================================================================
    
    /**
     * Salva o estado atual do emulador em um arquivo
     */
    public void saveState(String filePath) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(filePath)))) {
            
            // Header
            dos.writeInt(0x47425353); // "GBSS" - GameBoy Save State
            dos.writeInt(1); // Versão
            
            // CPU State
            dos.writeInt(cpu.getA());
            dos.writeInt(cpu.getB());
            dos.writeInt(cpu.getC());
            dos.writeInt(cpu.getD());
            dos.writeInt(cpu.getE());
            dos.writeInt(cpu.getH());
            dos.writeInt(cpu.getL());
            dos.writeInt(cpu.getF());
            dos.writeInt(cpu.getSP());
            dos.writeInt(cpu.getPC());
            dos.writeBoolean(cpu.getIME());
            dos.writeBoolean(cpu.isHalted());
            
            // MMU/PPU/APU states serão salvos pelos respectivos componentes
            mmu.saveState(dos);
            ppu.saveState(dos);
            apu.saveState(dos);
            
            System.out.println("Estado salvo: " + filePath);
        }
    }
    
    /**
     * Carrega um estado salvo de um arquivo
     */
    public void loadState(String filePath) throws IOException {
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(filePath)))) {
            
            // Header
            int magic = dis.readInt();
            if (magic != 0x47425353) {
                throw new IOException("Arquivo de save state inválido");
            }
            
            int version = dis.readInt();
            if (version != 1) {
                throw new IOException("Versão de save state não suportada: " + version);
            }
            
            // CPU State
            cpu.setA(dis.readInt());
            cpu.setB(dis.readInt());
            cpu.setC(dis.readInt());
            cpu.setD(dis.readInt());
            cpu.setE(dis.readInt());
            cpu.setH(dis.readInt());
            cpu.setL(dis.readInt());
            cpu.setF(dis.readInt());
            cpu.setSP(dis.readInt());
            cpu.setPC(dis.readInt());
            cpu.setIME(dis.readBoolean());
            cpu.setHalted(dis.readBoolean());
            
            // MMU/PPU/APU states
            mmu.loadState(dis);
            ppu.loadState(dis);
            apu.loadState(dis);
            
            System.out.println("Estado carregado: " + filePath);
        }
    }
}