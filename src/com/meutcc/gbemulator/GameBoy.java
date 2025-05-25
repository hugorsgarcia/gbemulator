package com.meutcc.gbemulator;

public class GameBoy {
    private final CPU cpu;
    private final MMU mmu;
    private final PPU ppu;
    private final APU apu; // Sua instância da APU
    private final Cartridge cartridge;
    private boolean emulatorSoundGloballyEnabled = true; // Flag que adicionamos

    public GameBoy() {
        this.cartridge = new Cartridge();
        this.ppu = new PPU();
        this.apu = new APU(); // APU é instanciada aqui
        this.mmu = new MMU(cartridge, ppu, apu); // Passando 'this' (GameBoy) para MMU
        this.cpu = new CPU(mmu);

        this.ppu.setMmu(mmu);
        if (this.apu != null) { // Adicionado para segurança
            this.apu.setEmulatorSoundGloballyEnabled(this.emulatorSoundGloballyEnabled);
        }
    }

    public boolean loadROM(String romPath) {
        // ... seu código de loadROM ...
        if (cartridge.loadROM(romPath)) {
            mmu.loadCartridge(cartridge);
            if (this.apu != null) { // Adicionado para segurança
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
        if (apu != null) { // Adicionado para segurança
            apu.reset();
            apu.setEmulatorSoundGloballyEnabled(this.emulatorSoundGloballyEnabled);
        }
        System.out.println("Game Boy components reset.");
    }

    public int step() {
        int cycles = cpu.step();
        if (cycles == -1) return -1;

        ppu.update(cycles);
        if (this.emulatorSoundGloballyEnabled && apu != null) { // Verifique apu != null
            apu.update(cycles);
        }
        cpu.handleInterrupts();
        return cycles;
    }

    public void setEmulatorSoundGloballyEnabled(boolean enabled) {
        this.emulatorSoundGloballyEnabled = enabled;
        if (this.apu != null) { // Verifique apu != null
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

    // --- ADICIONE ESTE MÉTODO AQUI ---
    public APU getApu() {
        return this.apu;
    }
    // ---------------------------------

    // Adicione este método se a MMU precisar chamar o halt da CPU
    // (se você passou a instância do GameBoy para a MMU para isso)
    public void haltCPU() {
        if (cpu != null) {
            // cpu.halt(); // Supondo que você tenha um método halt público na CPU se necessário
            // Ou, se a flag 'halted' na CPU for pública ou tiver um setter:
            // cpu.setHalted(true);
            System.out.println("CPU Halted by external component (e.g., MMU trying to write illegal opcode).");
        }
    }
}