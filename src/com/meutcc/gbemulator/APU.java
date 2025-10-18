package com.meutcc.gbemulator;

import javax.sound.sampled.*;
import java.util.Arrays;

/**
 * Game Boy Audio Processing Unit (APU) - Refatoração Completa v2.0
 * 
 * Implementa os 4 canais de áudio do Game Boy com precisão ciclo-exata:
 * - Canal 1: Square wave com sweep
 * - Canal 2: Square wave
 * - Canal 3: Wave RAM programável
 * - Canal 4: Noise com LFSR
 * 
 * Melhorias implementadas:
 * - Taxa de amostragem: 48kHz Estéreo
 * - Panning estéreo preciso (NR51)
 * - High-pass filter para remover DC bias
 * - Timing ciclo-preciso com Frame Sequencer a 512 Hz
 * - DAC enable/disable correto para evitar clicks/pops
 * - Normalização e mixagem adequadas
 * 
 * @author Hugo Garcia
 * @version 2.0
 */
public class APU {
    // ========================================================================
    // CONSTANTS
    // ========================================================================
    private static final int CPU_CLOCK_SPEED = 4194304; // 4.194304 MHz
    public static final int SAMPLE_RATE = 48000;        // 48 kHz (melhorado de 44.1 kHz)
    private static final int CHANNELS = 2;              // Estéreo (melhorado de Mono)
    private static final int SAMPLE_SIZE_BITS = 16;     // 16-bit audio
    private static final boolean SIGNED = true;
    private static final boolean BIG_ENDIAN = false;
    
    // Buffer sizes
    private static final int AUDIO_OUTPUT_BUFFER_SAMPLES = 2048;
    private static final int INTERNAL_SAMPLE_BUFFER_SIZE = 512;
    
    // Timing
    private static final double CYCLES_PER_OUTPUT_SAMPLE = (double) CPU_CLOCK_SPEED / SAMPLE_RATE;
    private static final int FRAME_SEQUENCER_CYCLES_PERIOD = CPU_CLOCK_SPEED / 512; // 512 Hz
    
    // High-pass filter constant (para remover DC bias)
    private static final float HIGHPASS_CHARGE = 0.999f;

    // ========================================================================
    // AUDIO OUTPUT
    // ========================================================================
    private SourceDataLine sourceDataLine;
    private boolean javaSoundInitialized = false;
    private final byte[] outputByteBuffer;
    private final float[] internalSampleBuffer; // Mudado para float para melhor precisão
    private int internalBufferPos = 0;
    
    // High-pass filter state (um para cada canal estéreo)
    private float highpassLeft = 0.0f;
    private float highpassRight = 0.0f;

    // ========================================================================
    // APU STATE
    // ========================================================================
    private boolean masterSoundEnable = false;
    private int apuCycleAccumulator = 0;
    private int frameSequencerCycleCounter = 0;
    private int frameSequencerStep = 0;
    private volatile boolean emulatorSoundGloballyEnabled = true;

    // ========================================================================
    // CHANNELS
    // ========================================================================
    private final PulseChannel channel1;
    private final PulseChannel channel2;
    private final WaveChannel channel3;
    private final NoiseChannel channel4;

    // ========================================================================
    // CONTROL REGISTERS
    // ========================================================================
    private int nr50_master_volume = 0x77;  // NR50 (FF24): Master volume & VIN
    private int nr51_panning = 0xFF;        // NR51 (FF25): Sound panning (estéreo)
    // NR52 (FF26) é calculado dinamicamente
    
    // Raw register storage
    final byte[] audioRegisters = new byte[0x30];
    final byte[] wavePatternRam = new byte[16];

    // ========================================================================
    // CONSTRUCTOR
    // ========================================================================
    public APU() {
        outputByteBuffer = new byte[INTERNAL_SAMPLE_BUFFER_SIZE * (SAMPLE_SIZE_BITS / 8) * CHANNELS];
        internalSampleBuffer = new float[INTERNAL_SAMPLE_BUFFER_SIZE * CHANNELS];

        channel1 = new PulseChannel(this, true);  // Com sweep
        channel2 = new PulseChannel(this, false); // Sem sweep
        channel3 = new WaveChannel(this);
        channel4 = new NoiseChannel(this);

        initializeSoundAPI();
        reset();
    }

    // ========================================================================
    // PUBLIC METHODS
    // ========================================================================
    
    public void setEmulatorSoundGloballyEnabled(boolean enabled) {
        this.emulatorSoundGloballyEnabled = enabled;
        if (!enabled && javaSoundInitialized && sourceDataLine != null) {
            sourceDataLine.flush();
            System.out.println("APU: Sound " + (enabled ? "ENABLED" : "DISABLED"));
        }
    }

    public void reset() {
        masterSoundEnable = false;
        apuCycleAccumulator = 0;
        frameSequencerCycleCounter = 0;
        frameSequencerStep = 0;
        internalBufferPos = 0;
        
        // Reset high-pass filter
        highpassLeft = 0.0f;
        highpassRight = 0.0f;

        Arrays.fill(audioRegisters, (byte) 0);
        Arrays.fill(wavePatternRam, (byte) 0);

        nr50_master_volume = 0x77;
        nr51_panning = 0xFF;

        channel1.reset();
        channel2.reset();
        channel3.reset();
        channel4.reset();

        audioRegisters[0xFF26 - 0xFF10] = (byte) 0x70;
        System.out.println("APU: Reset complete (48kHz Stereo, High-pass filter enabled)");
    }

    public void update(int cpuCycles) {
        if (!emulatorSoundGloballyEnabled || !javaSoundInitialized) {
            return;
        }

        // 1. Frame Sequencer (512 Hz)
        frameSequencerCycleCounter += cpuCycles;
        while (frameSequencerCycleCounter >= FRAME_SEQUENCER_CYCLES_PERIOD) {
            frameSequencerCycleCounter -= FRAME_SEQUENCER_CYCLES_PERIOD;
            if (masterSoundEnable) {
                clockFrameSequencer();
            }
        }

        // 2. Channel frequency timers (ciclo-preciso)
        if (masterSoundEnable) {
            channel1.step(cpuCycles);
            channel2.step(cpuCycles);
            channel3.step(cpuCycles);
            channel4.step(cpuCycles);
        }

        // 3. Sample generation
        apuCycleAccumulator += cpuCycles;
        while (apuCycleAccumulator >= CYCLES_PER_OUTPUT_SAMPLE) {
            apuCycleAccumulator -= CYCLES_PER_OUTPUT_SAMPLE;
            generateAndBufferSample();
        }
    }

    public void close() {
        if (javaSoundInitialized && sourceDataLine != null) {
            flushInternalBufferToSoundCard();
            sourceDataLine.drain();
            sourceDataLine.stop();
            sourceDataLine.close();
            javaSoundInitialized = false;
            System.out.println("APU: Sound resources released");
        }
    }

    // ========================================================================
    // REGISTER ACCESS
    // ========================================================================
    
    public byte readRegister(int address) {
        int regIndex = address - 0xFF10;

        if (address >= 0xFF10 && address <= 0xFF26) {
            switch (address) {
                // Channel 1
                case 0xFF10: return (byte)(audioRegisters[regIndex] | 0x80);
                case 0xFF11: return (byte)(audioRegisters[regIndex] | 0x3F);
                case 0xFF13: return (byte)0xFF; // Write-only
                case 0xFF14: return (byte)(audioRegisters[regIndex] | 0xBF);

                // Channel 2
                case 0xFF16: return (byte)(audioRegisters[regIndex] | 0x3F);
                case 0xFF18: return (byte)0xFF; // Write-only
                case 0xFF19: return (byte)(audioRegisters[regIndex] | 0xBF);

                // Channel 3
                case 0xFF1A: return (byte)(audioRegisters[regIndex] | 0x7F);
                case 0xFF1B: return (byte)0xFF; // Write-only
                case 0xFF1C: return (byte)(audioRegisters[regIndex] | 0x9F);
                case 0xFF1D: return (byte)0xFF; // Write-only
                case 0xFF1E: return (byte)(audioRegisters[regIndex] | 0xBF);

                // Channel 4
                case 0xFF20: return (byte)0xFF; // Write-only
                case 0xFF23: return (byte)(audioRegisters[regIndex] | 0xBF);

                // Control registers
                case 0xFF24: return (byte)nr50_master_volume;
                case 0xFF25: return (byte)nr51_panning;
                case 0xFF26:
                    int status = masterSoundEnable ? 0x80 : 0x00;
                    if (channel1.isActive()) status |= 0x01;
                    if (channel2.isActive()) status |= 0x02;
                    if (channel3.isActive()) status |= 0x04;
                    if (channel4.isActive()) status |= 0x08;
                    status |= 0x70; // Bits 4-6 always 1
                    return (byte) status;

                default:
                    return audioRegisters[regIndex];
            }
        } else if (address >= 0xFF30 && address <= 0xFF3F) {
            // Wave RAM
            if (masterSoundEnable) {
                return wavePatternRam[address - 0xFF30];
            }
            return (byte) 0xFF;
        }
        
        return (byte) 0xFF;
    }

    public void writeRegister(int address, byte value) {
        if (address >= 0xFF10 && address <= 0xFF2F) {
            audioRegisters[address - 0xFF10] = value;
        }

        // Quando APU está off, apenas NR52 e length counters podem ser escritos
        if (!masterSoundEnable && address != 0xFF26) {
            if (address == 0xFF11 || address == 0xFF16 || address == 0xFF20) {
                // Permite escrita de length counters
            } else {
                return;
            }
        }

        switch (address) {
            // Channel 1
            case 0xFF10: channel1.writeNRX0(value); break;
            case 0xFF11: channel1.writeNRX1(value); break;
            case 0xFF12: channel1.writeNRX2(value); break;
            case 0xFF13: channel1.writeNRX3(value); break;
            case 0xFF14: channel1.writeNRX4(value); break;

            // Channel 2
            case 0xFF16: channel2.writeNRX1(value); break;
            case 0xFF17: channel2.writeNRX2(value); break;
            case 0xFF18: channel2.writeNRX3(value); break;
            case 0xFF19: channel2.writeNRX4(value); break;

            // Channel 3
            case 0xFF1A: channel3.writeNR30(value); break;
            case 0xFF1B: channel3.writeNR31(value); break;
            case 0xFF1C: channel3.writeNR32(value); break;
            case 0xFF1D: channel3.writeNR33(value); break;
            case 0xFF1E: channel3.writeNR34(value); break;

            // Channel 4
            case 0xFF20: channel4.writeNR41(value); break;
            case 0xFF21: channel4.writeNR42(value); break;
            case 0xFF22: channel4.writeNR43(value); break;
            case 0xFF23: channel4.writeNR44(value); break;

            // Control
            case 0xFF24: nr50_master_volume = value & 0xFF; break;
            case 0xFF25: nr51_panning = value & 0xFF; break;
            case 0xFF26:
                boolean newMasterEnable = (value & 0x80) != 0;
                if (masterSoundEnable && !newMasterEnable) {
                    // Turning OFF - clear all registers
                    for (int i = 0xFF10; i <= 0xFF25; i++) {
                        audioRegisters[i - 0xFF10] = 0;
                    }
                    channel1.resetOnAPUDisable();
                    channel2.resetOnAPUDisable();
                    channel3.resetOnAPUDisable();
                    channel4.resetOnAPUDisable();
                    frameSequencerStep = 0;
                    frameSequencerCycleCounter = 0;
                } else if (!masterSoundEnable && newMasterEnable) {
                    // Turning ON
                    frameSequencerStep = 0;
                    frameSequencerCycleCounter = 0;
                }
                masterSoundEnable = newMasterEnable;
                audioRegisters[address - 0xFF10] = value;
                break;

            // Wave RAM
            default:
                if (address >= 0xFF30 && address <= 0xFF3F) {
                    if (masterSoundEnable) {
                        wavePatternRam[address - 0xFF30] = value;
                        channel3.updateWaveRamByte(address - 0xFF30, value);
                    }
                }
                break;
        }
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================
    
    byte getReg(int offset) {
        return audioRegisters[offset];
    }
    
    byte getWaveRamByte(int offset) {
        return wavePatternRam[offset];
    }
    
    boolean isMasterSoundEnabled() {
        return masterSoundEnable;
    }

    // ========================================================================
    // PRIVATE METHODS
    // ========================================================================
    
    private void initializeSoundAPI() {
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS, SIGNED, BIG_ENDIAN);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

            if (AudioSystem.isLineSupported(info)) {
                sourceDataLine = (SourceDataLine) AudioSystem.getLine(info);
                sourceDataLine.open(format, AUDIO_OUTPUT_BUFFER_SAMPLES * (SAMPLE_SIZE_BITS / 8) * CHANNELS);
                sourceDataLine.start();
                javaSoundInitialized = true;
                System.out.println("APU: Initialized - " + format);
            } else {
                System.err.println("APU: Audio format not supported - " + format);
            }
        } catch (LineUnavailableException e) {
            System.err.println("APU: Error initializing audio - " + e.getMessage());
            javaSoundInitialized = false;
        }
    }

    private void clockFrameSequencer() {
        switch (frameSequencerStep) {
            case 0: // Length
                channel1.clockLength();
                channel2.clockLength();
                channel3.clockLength();
                channel4.clockLength();
                break;
            case 2: // Length + Sweep
                channel1.clockLength();
                channel1.clockSweep();
                channel2.clockLength();
                channel3.clockLength();
                channel4.clockLength();
                break;
            case 4: // Length
                channel1.clockLength();
                channel2.clockLength();
                channel3.clockLength();
                channel4.clockLength();
                break;
            case 6: // Length + Sweep
                channel1.clockLength();
                channel1.clockSweep();
                channel2.clockLength();
                channel3.clockLength();
                channel4.clockLength();
                break;
            case 7: // Envelope
                channel1.clockEnvelope();
                channel2.clockEnvelope();
                channel4.clockEnvelope();
                break;
        }
        frameSequencerStep = (frameSequencerStep + 1) % 8;
    }

    private void generateAndBufferSample() {
        float leftOut = 0.0f;
        float rightOut = 0.0f;

        if (!masterSoundEnable) {
            // Silêncio
            leftOut = 0.0f;
            rightOut = 0.0f;
        } else {
            // Obter samples dos canais (0-15 cada)
            float ch1 = channel1.getOutputSample();
            float ch2 = channel2.getOutputSample();
            float ch3 = channel3.getOutputSample();
            float ch4 = channel4.getOutputSample();

            // Aplicar panning (NR51)
            if ((nr51_panning & 0x01) != 0) rightOut += ch1;
            if ((nr51_panning & 0x02) != 0) rightOut += ch2;
            if ((nr51_panning & 0x04) != 0) rightOut += ch3;
            if ((nr51_panning & 0x08) != 0) rightOut += ch4;
            
            if ((nr51_panning & 0x10) != 0) leftOut += ch1;
            if ((nr51_panning & 0x20) != 0) leftOut += ch2;
            if ((nr51_panning & 0x40) != 0) leftOut += ch3;
            if ((nr51_panning & 0x80) != 0) leftOut += ch4;

            // Aplicar volume master (NR50)
            float leftVol = ((nr50_master_volume >> 4) & 0x07) / 7.0f;
            float rightVol = (nr50_master_volume & 0x07) / 7.0f;
            
            leftOut *= leftVol;
            rightOut *= rightVol;

            // Normalizar (4 canais * 15 = 60 máximo)
            leftOut /= 60.0f;
            rightOut /= 60.0f;

            // Aplicar high-pass filter (simula capacitores do hardware)
            leftOut = applyHighPass(leftOut, true);
            rightOut = applyHighPass(rightOut, false);
        }

        // Armazenar samples estéreo
        internalSampleBuffer[internalBufferPos * 2] = leftOut;
        internalSampleBuffer[internalBufferPos * 2 + 1] = rightOut;
        
        internalBufferPos++;
        if (internalBufferPos >= INTERNAL_SAMPLE_BUFFER_SIZE) {
            flushInternalBufferToSoundCard();
        }
    }

    private float applyHighPass(float sample, boolean isLeft) {
        // High-pass filter para remover DC bias
        // Simula os capacitores do hardware original do Game Boy
        float filtered;
        if (isLeft) {
            filtered = sample - highpassLeft;
            highpassLeft = sample - filtered * HIGHPASS_CHARGE;
        } else {
            filtered = sample - highpassRight;
            highpassRight = sample - filtered * HIGHPASS_CHARGE;
        }
        return filtered;
    }

    private void flushInternalBufferToSoundCard() {
        if (!javaSoundInitialized || internalBufferPos == 0) return;

        // Converter float para 16-bit PCM
        for (int i = 0; i < internalBufferPos * 2; i++) {
            float sample = internalSampleBuffer[i];
            
            // Clamp para -1.0 a 1.0
            if (sample > 1.0f) sample = 1.0f;
            if (sample < -1.0f) sample = -1.0f;
            
            // Converter para 16-bit
            short pcmSample = (short) (sample * 32767.0f);
            
            // Little-endian
            outputByteBuffer[i * 2] = (byte) (pcmSample & 0xFF);
            outputByteBuffer[i * 2 + 1] = (byte) ((pcmSample >> 8) & 0xFF);
        }

        int bytesToWrite = internalBufferPos * 2 * (SAMPLE_SIZE_BITS / 8);
        sourceDataLine.write(outputByteBuffer, 0, bytesToWrite);
        internalBufferPos = 0;
    }

    // ========================================================================
    // ABSTRACT SOUND CHANNEL BASE CLASS
    // ========================================================================
    
    abstract static class SoundChannel {
        protected final APU apu;
        protected boolean enabled;
        protected int lengthCounter;
        protected boolean lengthEnabled;
        protected int volume;
        protected int initialVolume;
        protected boolean envelopeIncrease;
        protected int envelopePeriod;
        protected int envelopeCounter;
        protected boolean dacEnabled; // NOVO: controle DAC explícito

        public SoundChannel(APU apu) {
            this.apu = apu;
        }

        abstract void reset();
        abstract void resetOnAPUDisable();
        abstract void trigger();
        abstract float getOutputSample();
        abstract void step(int cycles);

        public void clockLength() {
            if (lengthEnabled && lengthCounter > 0) {
                lengthCounter--;
                if (lengthCounter == 0) {
                    enabled = false;
                }
            }
        }

        public void clockEnvelope() {
            if (envelopePeriod == 0) return;
            
            envelopeCounter--;
            if (envelopeCounter <= 0) {
                envelopeCounter = envelopePeriod;
                if (envelopeIncrease && volume < 15) {
                    volume++;
                } else if (!envelopeIncrease && volume > 0) {
                    volume--;
                }
            }
        }

        public boolean isActive() {
            return enabled;
        }

        protected void loadLength(int lengthData, int maxLength) {
            if (lengthData == 0) {
                lengthCounter = maxLength;
            } else {
                lengthCounter = maxLength - lengthData;
            }
        }

        protected void loadEnvelope(byte nrx2) {
            initialVolume = (nrx2 >> 4) & 0x0F;
            envelopeIncrease = (nrx2 & 0x08) != 0;
            envelopePeriod = nrx2 & 0x07;
            volume = initialVolume;
            envelopeCounter = envelopePeriod;
        }

        protected boolean checkDACEnabled(byte nrx2) {
            return (nrx2 & 0xF8) != 0;
        }
    }

    // ========================================================================
    // PULSE CHANNEL (Channel 1 & 2)
    // ========================================================================
    
    static class PulseChannel extends SoundChannel {
        private final boolean hasSweep;
        
        // Frequency
        private int frequencyValue; // 11-bit (0-2047)
        private int frequencyTimer;
        
        // Duty cycle
        private int dutyPattern; // 0-3
        private int dutyStep;    // 0-7
        
        private static final byte[][] DUTY_WAVEFORMS = {
            {0, 0, 0, 0, 0, 0, 0, 1}, // 12.5%
            {1, 0, 0, 0, 0, 0, 0, 1}, // 25%
            {1, 0, 0, 0, 0, 1, 1, 1}, // 50%
            {0, 1, 1, 1, 1, 1, 1, 0}  // 75%
        };
        
        // Sweep (apenas Channel 1)
        private int sweepPeriod;
        private boolean sweepDecrease;
        private int sweepShift;
        private int sweepCounter;
        private int shadowFrequency;
        private boolean sweepEnabled;

        public PulseChannel(APU apu, boolean hasSweep) {
            super(apu);
            this.hasSweep = hasSweep;
            reset();
        }

        @Override
        public void reset() {
            enabled = false;
            dacEnabled = false;
            lengthCounter = 0;
            lengthEnabled = false;
            volume = 0;
            initialVolume = 0;
            envelopeIncrease = false;
            envelopePeriod = 0;
            envelopeCounter = 0;
            
            frequencyValue = 0;
            frequencyTimer = 0;
            dutyPattern = 0;
            dutyStep = 0;
            
            if (hasSweep) {
                sweepPeriod = 0;
                sweepDecrease = false;
                sweepShift = 0;
                sweepCounter = 0;
                shadowFrequency = 0;
                sweepEnabled = false;
            }
        }

        @Override
        void resetOnAPUDisable() {
            reset();
        }

        public void writeNRX0(byte val) { // Sweep (apenas Channel 1)
            if (!hasSweep) return;
            sweepPeriod = (val >> 4) & 0x07;
            sweepDecrease = (val & 0x08) != 0;
            sweepShift = val & 0x07;
        }

        public void writeNRX1(byte val) { // Length/Duty
            loadLength(val & 0x3F, 64);
            dutyPattern = (val >> 6) & 0x03;
        }

        public void writeNRX2(byte val) { // Volume/Envelope
            loadEnvelope(val);
            dacEnabled = checkDACEnabled(val);
            if (!dacEnabled) {
                enabled = false;
            }
        }

        public void writeNRX3(byte val) { // Frequency Low
            frequencyValue = (frequencyValue & 0x0700) | (val & 0xFF);
        }

        public void writeNRX4(byte val) { // Frequency High/Control
            frequencyValue = (frequencyValue & 0x00FF) | ((val & 0x07) << 8);
            lengthEnabled = (val & 0x40) != 0;
            if ((val & 0x80) != 0) {
                trigger();
            }
        }

        @Override
        public void trigger() {
            if (!dacEnabled) {
                enabled = false;
                return;
            }
            
            enabled = true;
            
            if (lengthCounter == 0) {
                loadLength(0, 64);
            }
            
            volume = initialVolume;
            envelopeCounter = envelopePeriod;
            
            // Reset frequency timer: (2048 - freq) * 4
            frequencyTimer = (2048 - frequencyValue) * 4;
            
            if (hasSweep) {
                shadowFrequency = frequencyValue;
                sweepCounter = sweepPeriod;
                sweepEnabled = (sweepPeriod > 0 || sweepShift > 0);
                
                if (sweepShift > 0) {
                    calculateSweepFrequency(true);
                }
            }
        }

        public void clockSweep() {
            if (!hasSweep || !sweepEnabled) return;
            
            if (sweepCounter > 0) {
                sweepCounter--;
            }
            
            if (sweepCounter == 0) {
                sweepCounter = sweepPeriod > 0 ? sweepPeriod : 8;
                
                if (sweepEnabled && sweepPeriod > 0) {
                    int newFreq = calculateSweepFrequency(false);
                    if (newFreq >= 0 && sweepShift > 0) {
                        frequencyValue = newFreq;
                        shadowFrequency = newFreq;
                        frequencyTimer = (2048 - frequencyValue) * 4;
                        
                        // Check overflow again
                        calculateSweepFrequency(true);
                    }
                }
            }
        }

        private int calculateSweepFrequency(boolean checkOnly) {
            int delta = shadowFrequency >> sweepShift;
            int newFreq = sweepDecrease ? shadowFrequency - delta : shadowFrequency + delta;
            
            if (newFreq > 2047) {
                enabled = false;
                return -1;
            }
            
            return newFreq;
        }

        @Override
        public void step(int cycles) {
            if (!enabled) return;
            
            // Frequency timer roda a CPU_CLOCK_SPEED / 4
            frequencyTimer -= cycles;
            while (frequencyTimer <= 0) {
                frequencyTimer += (2048 - frequencyValue) * 4;
                dutyStep = (dutyStep + 1) % 8;
            }
        }

        @Override
        public float getOutputSample() {
            if (!enabled || !dacEnabled) {
                return 7.5f; // DAC off produz valor médio, não zero
            }
            
            int amplitude = DUTY_WAVEFORMS[dutyPattern][dutyStep];
            return amplitude * volume;
        }
    }

    // ========================================================================
    // WAVE CHANNEL (Channel 3)
    // ========================================================================
    
    static class WaveChannel extends SoundChannel {
        private int frequencyValue;
        private int frequencyTimer;
        private int wavePosition; // 0-31
        private int volumeShift;  // 0-3

        public WaveChannel(APU apu) {
            super(apu);
            reset();
        }

        @Override
        public void reset() {
            enabled = false;
            dacEnabled = false;
            lengthCounter = 0;
            lengthEnabled = false;
            frequencyValue = 0;
            frequencyTimer = 0;
            wavePosition = 0;
            volumeShift = 0;
        }

        @Override
        void resetOnAPUDisable() {
            reset();
        }

        public void writeNR30(byte val) { // DAC Enable
            dacEnabled = (val & 0x80) != 0;
            if (!dacEnabled) {
                enabled = false;
            }
        }

        public void writeNR31(byte val) { // Length
            loadLength(val & 0xFF, 256);
        }

        public void writeNR32(byte val) { // Volume
            volumeShift = (val >> 5) & 0x03;
        }

        public void writeNR33(byte val) { // Frequency Low
            frequencyValue = (frequencyValue & 0x0700) | (val & 0xFF);
        }

        public void writeNR34(byte val) { // Frequency High/Control
            frequencyValue = (frequencyValue & 0x00FF) | ((val & 0x07) << 8);
            lengthEnabled = (val & 0x40) != 0;
            if ((val & 0x80) != 0) {
                trigger();
            }
        }

        public void updateWaveRamByte(int offset, byte value) {
            // Wave RAM atualizada em tempo real
        }

        @Override
        public void trigger() {
            if (!dacEnabled) {
                enabled = false;
                return;
            }
            
            enabled = true;
            
            if (lengthCounter == 0) {
                loadLength(0, 256);
            }
            
            wavePosition = 0;
            
            // Frequency timer: (2048 - freq) * 2
            frequencyTimer = (2048 - frequencyValue) * 2;
        }

        @Override
        public void step(int cycles) {
            if (!enabled) return;
            
            // Frequency timer roda a CPU_CLOCK_SPEED / 2
            frequencyTimer -= cycles;
            while (frequencyTimer <= 0) {
                frequencyTimer += (2048 - frequencyValue) * 2;
                wavePosition = (wavePosition + 1) % 32;
            }
        }

        @Override
        public float getOutputSample() {
            if (!enabled || !dacEnabled) {
                return 7.5f;
            }
            
            // Ler 4-bit sample da Wave RAM
            byte waveByte = apu.getWaveRamByte(wavePosition / 2);
            int sample = (wavePosition % 2 == 0) 
                ? (waveByte >> 4) & 0x0F 
                : waveByte & 0x0F;
            
            // Aplicar volume shift
            switch (volumeShift) {
                case 0: return 0.0f;            // Mute
                case 1: return sample;          // 100%
                case 2: return sample >> 1;     // 50%
                case 3: return sample >> 2;     // 25%
                default: return 0.0f;
            }
        }
    }

    // ========================================================================
    // NOISE CHANNEL (Channel 4)
    // ========================================================================
    
    static class NoiseChannel extends SoundChannel {
        private int lfsr;           // Linear Feedback Shift Register
        private boolean lfsrWidth7; // false=15-bit, true=7-bit
        private int clockShift;
        private int divisorCode;
        private int frequencyTimer;
        
        private static final int[] DIVISORS = {8, 16, 32, 48, 64, 80, 96, 112};

        public NoiseChannel(APU apu) {
            super(apu);
            reset();
        }

        @Override
        public void reset() {
            enabled = false;
            dacEnabled = false;
            lengthCounter = 0;
            lengthEnabled = false;
            volume = 0;
            initialVolume = 0;
            envelopeIncrease = false;
            envelopePeriod = 0;
            envelopeCounter = 0;
            
            lfsr = 0x7FFF;
            lfsrWidth7 = false;
            clockShift = 0;
            divisorCode = 0;
            frequencyTimer = 0;
        }

        @Override
        void resetOnAPUDisable() {
            reset();
        }

        public void writeNR41(byte val) { // Length
            loadLength(val & 0x3F, 64);
        }

        public void writeNR42(byte val) { // Volume/Envelope
            loadEnvelope(val);
            dacEnabled = checkDACEnabled(val);
            if (!dacEnabled) {
                enabled = false;
            }
        }

        public void writeNR43(byte val) { // Polynomial Counter
            clockShift = (val >> 4) & 0x0F;
            lfsrWidth7 = (val & 0x08) != 0;
            divisorCode = val & 0x07;
        }

        public void writeNR44(byte val) { // Control
            lengthEnabled = (val & 0x40) != 0;
            if ((val & 0x80) != 0) {
                trigger();
            }
        }

        @Override
        public void trigger() {
            if (!dacEnabled) {
                enabled = false;
                return;
            }
            
            enabled = true;
            
            if (lengthCounter == 0) {
                loadLength(0, 64);
            }
            
            volume = initialVolume;
            envelopeCounter = envelopePeriod;
            
            lfsr = 0x7FFF;
            
            // Frequency timer: divisor << clockShift
            frequencyTimer = DIVISORS[divisorCode] << clockShift;
        }

        @Override
        public void step(int cycles) {
            if (!enabled) return;
            
            frequencyTimer -= cycles;
            while (frequencyTimer <= 0) {
                frequencyTimer += DIVISORS[divisorCode] << clockShift;
                
                // Clock LFSR
                int bit = (lfsr & 1) ^ ((lfsr >> 1) & 1);
                lfsr >>= 1;
                lfsr |= (bit << 14);
                
                if (lfsrWidth7) {
                    lfsr &= ~(1 << 6);
                    lfsr |= (bit << 6);
                }
            }
        }

        @Override
        public float getOutputSample() {
            if (!enabled || !dacEnabled) {
                return 7.5f;
            }
            
            // Bit 0 do LFSR invertido
            int amplitude = (lfsr & 1) == 0 ? 1 : 0;
            return amplitude * volume;
        }
    }
}
