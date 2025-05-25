package com.meutcc.gbemulator;

public class GameBoy {
    private final CPU cpu;
    private final MMU mmu;
    private final PPU ppu;
    private final APU apu; // Esqueleto por enquanto
    private final Cartridge cartridge;
    // Adicionar Timer aqui futuramente

    public GameBoy() {
        this.cartridge = new Cartridge();
        this.ppu = new PPU();
        this.apu = new APU(); // Esqueleto por enquanto
        this.mmu = new MMU(cartridge, ppu, apu); // MMU vai precisae de acesso à PPU para VRAM/OAM/Regs
        this.cpu = new CPU(mmu);

        this.ppu.setMmu(mmu); // PPU vai precisar da MMU para ler/escrever em registradores como IF
    }

    public boolean loadROM(String romPath) {
        if (cartridge.loadROM(romPath)) {
            mmu.loadCartridge(cartridge);
            cpu.reset();
            return true;
        }
        return false;
    }

    // Executa um passo de emulação (CPU + PPU + outros)
    // Retorna o número de ciclos de máquina (T-cycles) executados pela instrução
    public int step() {
        int cycles = cpu.step(); // Executa uma instrução da CPU e retorna os ciclos
        if (cycles == -1) return -1; // CPU halted or error

        ppu.update(cycles);   // Atualiza a PPU com os ciclos da CPU
        apu.update(cycles);   // Atualiza a APU (ainda esqueleto)
        // Timer.update(cycles) // Atualizar Timer aqui
        cpu.handleInterrupts(); // Verifica e trata interrupções

        return cycles;
    }

    public void reset() {
        cpu.reset();
        ppu.reset();
        mmu.reset(); // Se necessário, para limpar WRAM, HRAM, etc.
        // apu.reset();
        System.out.println("Game Boy components reset.");
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
}