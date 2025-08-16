package com.meutcc.gbemulator;

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
}