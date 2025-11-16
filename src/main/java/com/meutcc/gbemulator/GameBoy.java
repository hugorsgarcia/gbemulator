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
        this.cpu = new CPU(mmu);
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

        if (mmu.isDmaActive()) {
            int dmaCycles = mmu.getDmaCyclesRemaining();
            cycles = dmaCycles;
            mmu.updateDma(dmaCycles);
        } else {
            cycles = cpu.step();
            if (cycles == -1) return -1;
        }

        ppu.update(cycles);
        mmu.updateTimers(cycles);
        cartridge.update(cycles);

        if (this.emulatorSoundGloballyEnabled && apu != null) {
            apu.update(cycles);
        }

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
    
    public void saveState(String filePath) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(filePath)))) {
            
            dos.writeInt(0x47425353); // Magic: "GBSS"
            dos.writeInt(2); // Version 2
            
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
            
            mmu.saveState(dos);
            ppu.saveState(dos);
            apu.saveState(dos);
            
            System.out.println("Estado salvo: " + filePath);
        }
    }
    
    public void loadState(String filePath) throws IOException {
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(filePath)))) {
            
            int magic = dis.readInt();
            if (magic != 0x47425353) {
                throw new IOException("Arquivo de save state inválido");
            }
            
            int version = dis.readInt();
            if (version < 1 || version > 2) {
                throw new IOException("Versão de save state não suportada: " + version);
            }
            
            System.out.println("Carregando save state versão " + version);
            
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
            
            mmu.loadState(dis);
            ppu.loadState(dis);
            apu.loadState(dis);
            
            System.out.println("Estado carregado: " + filePath);
        }
    }
}