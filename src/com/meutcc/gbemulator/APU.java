package com.meutcc.gbemulator;

import javax.sound.sampled.*;
import java.util.Arrays;

public class APU {
    // --- Constants ---
    private static final int CPU_CLOCK_SPEED = 4194304; // Hz
    public static final int SAMPLE_RATE = 44100;     // Hz for audio output
    private static final int CHANNELS = 1;             // Mono
    private static final int SAMPLE_SIZE_BITS = 16;    // 16-bit audio
    private static final boolean SIGNED = true;
    private static final boolean BIG_ENDIAN = false;   // Little-endian for 16-bit PCM

    // Buffer for SourceDataLine (number of samples, not bytes)
    private static final int AUDIO_OUTPUT_BUFFER_SAMPLES = 2048;
    // Internal buffer for generated samples before sending to SourceDataLine
    private static final int INTERNAL_SAMPLE_BUFFER_SIZE = 512;


    // Cycles of the CPU clock per one audio sample to be generated
    private static final double CYCLES_PER_OUTPUT_SAMPLE = (double) CPU_CLOCK_SPEED / SAMPLE_RATE;

    // Frame Sequencer ticks at 512 Hz.
    // CPU cycles per Frame Sequencer step: 4194304 / 512 = 8192
    private static final int FRAME_SEQUENCER_CYCLES_PERIOD = CPU_CLOCK_SPEED / 512;

    // --- Audio Output ---
    private SourceDataLine sourceDataLine;
    private boolean javaSoundInitialized = false;
    private final byte[] outputByteBuffer; // Byte buffer for SourceDataLine
    private final short[] internalSampleBuffer; // Buffer for 16-bit samples
    private int internalBufferPos = 0;

    // --- APU State ---
    private boolean masterSoundEnable = false; // NR52 bit 7
    private int apuCycleAccumulator = 0;       // Accumulates CPU cycles for sample generation
    private int frameSequencerCycleCounter = 0; // Accumulates CPU cycles for Frame Sequencer
    private int frameSequencerStep = 0;
    private volatile boolean emulatorSoundGloballyEnabled = true;

    // --- Channels ---
    private final PulseChannel channel1;
    private final PulseChannel channel2;
    private final WaveChannel channel3;
    private final NoiseChannel channel4;

    // --- Control Registers (internal state) ---
    // NR50 (FF24): Channel control / ON-OFF / Volume
    private int nr50_vin_panning_master_vol = 0x77; // SO2 vol (bits 4-6), SO1 vol (bits 0-2)
    // NR51 (FF25): Selection of Sound output terminal
    private int nr51_output_panning = 0xFF; // Each bit pair for Ch4-Ch1 to SO2/SO1
    // NR52 (FF26) is handled by masterSoundEnable and channel.isActive()

    // Raw register values (FF10 - FF26 for channels, FF30 - FF3F for wave RAM)
    // These are written by MMU and read by channel logic
    final byte[] audioRegisters = new byte[0x30]; // Covers up to FF2F for convenience
    final byte[] wavePatternRam = new byte[16];   // FF30 - FF3F

    public APU() {
        outputByteBuffer = new byte[INTERNAL_SAMPLE_BUFFER_SIZE * (SAMPLE_SIZE_BITS / 8) * CHANNELS];
        internalSampleBuffer = new short[INTERNAL_SAMPLE_BUFFER_SIZE * CHANNELS];

        channel1 = new PulseChannel(this, true);  // true for sweep capability
        channel2 = new PulseChannel(this, false);
        channel3 = new WaveChannel(this);
        channel4 = new NoiseChannel(this);

        initializeSoundAPI();
        reset();
    }

    public void setEmulatorSoundGloballyEnabled(boolean enabled) {
        this.emulatorSoundGloballyEnabled = enabled;
        if (!enabled && javaSoundInitialized && sourceDataLine != null) {
            // Opcional: Limpar o buffer de som ou pausar a SourceDataLine
            // sourceDataLine.stop(); // Pausa
            sourceDataLine.flush(); // Limpa dados pendentes
            // sourceDataLine.start(); // Se você parar, precisa reiniciar
            System.out.println("APU processamento global: " + (enabled ? "ON" : "OFF"));
        }
    }

    private void initializeSoundAPI() {
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS, SIGNED, BIG_ENDIAN);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

            if (AudioSystem.isLineSupported(info)) {
                sourceDataLine = (SourceDataLine) AudioSystem.getLine(info);
                // Buffer size for SourceDataLine in bytes
                sourceDataLine.open(format, AUDIO_OUTPUT_BUFFER_SAMPLES * (SAMPLE_SIZE_BITS / 8) * CHANNELS);
                sourceDataLine.start();
                javaSoundInitialized = true;
                System.out.println("Java Sound API initialized for APU: " + format);
            } else {
                System.err.println("Java Sound API: SourceDataLine not supported with format: " + format);
            }
        } catch (LineUnavailableException e) {
            System.err.println("Error initializing Java Sound API for APU: " + e.getMessage());
            javaSoundInitialized = false;
        }
    }

    public void reset() {
        masterSoundEnable = false; // Sound is off by default until NR52 is written appropriately
        apuCycleAccumulator = 0;
        frameSequencerCycleCounter = 0;
        frameSequencerStep = 0;
        internalBufferPos = 0;

        // Clear register mirrors (actual game will init them)
        Arrays.fill(audioRegisters, (byte) 0);
        Arrays.fill(wavePatternRam, (byte) 0);

        // Default values for control registers (these are often set by boot ROM or game)
        nr50_vin_panning_master_vol = 0x77; // Max volume, VIN off
        nr51_output_panning = 0xFF;         // All channels to both L/R (for mono, sum)

        // Reset channels
        channel1.reset();
        channel2.reset();
        channel3.reset();
        channel4.reset();

        // NR52 initial state: Bit 7 (master on/off) = 0. Other bits are status.
        // Games typically write 0x80 to NR52 to enable sound, then trigger channels.
        // The read value of NR52 depends on masterSoundEnable and channel active flags.
        audioRegisters[0xFF26 - 0xFF10] = (byte) 0x70; // Bits 4-6 are 1, status bits 0-3 are 0.

        System.out.println("APU reset.");
    }

    public void update(int cpuCycles) {

        if (!this.emulatorSoundGloballyEnabled || !this.javaSoundInitialized) {
            // Se o som do emulador estiver desligado globalmente ou a API de som não estiver inicializada,
            // não processe nada.
            return;
        }

        if (!javaSoundInitialized) return;

        // 1. Frame Sequencer
        frameSequencerCycleCounter += cpuCycles;
        while (frameSequencerCycleCounter >= FRAME_SEQUENCER_CYCLES_PERIOD) {
            frameSequencerCycleCounter -= FRAME_SEQUENCER_CYCLES_PERIOD;
            if (masterSoundEnable) { // Frame sequencer only runs if master sound is on
                clockFrameSequencer();
            }
        }

        // 2. Channel Timers (driven by CPU clock, not frame sequencer directly for sample generation)
        // Each channel's internal timer/phasor advances based on cpuCycles and its frequency.
        // This is implicitly handled within each channel's getOutputSample or a dedicated step method.
        // For simplicity here, we'll assume channels update their internal state for sample generation
        // when getOutputSample() is called. A more accurate model might have channels step per CPU cycle.
        // The provided cpuCycles are for the whole CPU step.
        // We need to generate samples for the duration represented by cpuCycles.

        // 3. Sample Generation
        apuCycleAccumulator += cpuCycles;
        while (apuCycleAccumulator >= CYCLES_PER_OUTPUT_SAMPLE) {
            apuCycleAccumulator -= CYCLES_PER_OUTPUT_SAMPLE;

            // Step each channel's internal frequency timer by one "APU high-frequency tick"
            // This is a simplification. Real channels have timers that run at specific fractions of the CPU clock.
            // For now, we assume their state is ready for sampling.
            // A more accurate model would have each channel.step(1) for each APU cycle,
            // and the getOutputSample would just read the current DAC level.
            // Here, we'll make channels advance their internal state for one output sample period.
            channel1.stepSampleGenerator();
            channel2.stepSampleGenerator();
            channel3.stepSampleGenerator();
            channel4.stepSampleGenerator();

            generateAndBufferSample();
        }
    }

    private void clockFrameSequencer() {
        // Frame Sequencer has 8 steps (0-7), running at 512 Hz.
        // Step 0: Length
        // Step 1: ---
        // Step 2: Length, Sweep
        // Step 3: ---
        // Step 4: Length
        // Step 5: ---
        // Step 6: Length, Sweep
        // Step 7: Volume Envelope
        switch (frameSequencerStep) {
            case 0: // Length
                channel1.clockLength();
                channel2.clockLength();
                channel3.clockLength();
                channel4.clockLength();
                break;
            case 1:
                break;
            case 2: // Length, Sweep
                channel1.clockLength();
                channel1.clockSweep();
                channel2.clockLength();
                channel3.clockLength();
                channel4.clockLength();
                break;
            case 3:
                break;
            case 4: // Length
                channel1.clockLength();
                channel2.clockLength();
                channel3.clockLength();
                channel4.clockLength();
                break;
            case 5:
                break;
            case 6: // Length, Sweep
                channel1.clockLength();
                channel1.clockSweep();
                channel2.clockLength();
                channel3.clockLength();
                channel4.clockLength();
                break;
            case 7: // Volume Envelope
                channel1.clockEnvelope();
                channel2.clockEnvelope();
                // Channel 3 has no envelope
                channel4.clockEnvelope();
                break;
        }
        frameSequencerStep = (frameSequencerStep + 1) % 8;
    }

    private void generateAndBufferSample() {
        if (!masterSoundEnable) {
            internalSampleBuffer[internalBufferPos] = 0; // Silence
        } else {
            // Get raw output from each channel (e.g., 0-15 for pulse/wave, 0/1 for noise)
            int ch1Out = channel1.getOutputSample();
            int ch2Out = channel2.getOutputSample();
            int ch3Out = channel3.getOutputSample();
            int ch4Out = channel4.getOutputSample();

            // Simple mono mix: sum channel outputs.
            // Game Boy has two output terminals (SO1, SO2). NR51 pans channels. NR50 is master volume.
            // For mono, we can sum contributions. Max output of one channel is 15. Max sum could be 60.
            // This needs to be scaled to 16-bit range (e.g., -32768 to 32767).
            // A simple approach: sum and scale.
            // Each channel's output is 0-15. Let's assume they are already volume-adjusted by their envelopes.
            // The DAC converts this to an analog level.
            // A common mixing formula: (ch1+ch2+ch3+ch4)/4 * master_volume_scalar
            // Or, more directly, scale the 0-15 range.
            // Max digital sum: 15*4 = 60.
            // Let's scale this to, say, 1/4 of the 16-bit range to avoid clipping easily.
            // Max 16-bit positive is 32767. So, 32767 / 60 = ~546.
            // Each unit from a channel (0-15) could contribute `value * 500` to the final mix, for example.
            // This is a placeholder for proper mixing and volume scaling.

            float mixedSample = 0;

            // SO1 and SO2 master volumes (0-7)
            int so1_vol = (nr50_vin_panning_master_vol & 0x07);
            int so2_vol = ((nr50_vin_panning_master_vol >> 4) & 0x07);

            // For mono, we can average or sum SO1 and SO2 contributions.
            // Let's just sum all channels selected for output and apply an overall volume.
            // This simplification ignores SO1/SO2 distinction for mono.
            float totalOut = 0;
            if ((nr51_output_panning & 0x01) != 0 || (nr51_output_panning & 0x10) != 0) totalOut += ch1Out; // Ch1 to SO1 or SO2
            if ((nr51_output_panning & 0x02) != 0 || (nr51_output_panning & 0x20) != 0) totalOut += ch2Out; // Ch2 to SO1 or SO2
            if ((nr51_output_panning & 0x04) != 0 || (nr51_output_panning & 0x40) != 0) totalOut += ch3Out; // Ch3 to SO1 or SO2
            if ((nr51_output_panning & 0x08) != 0 || (nr51_output_panning & 0x80) != 0) totalOut += ch4Out; // Ch4 to SO1 or SO2

            // Scale totalOut (max ~60 if all channels max and panned) to 16-bit range.
            // Let's use a master volume scalar. (so1_vol + so2_vol) / 14.0f as a rough master.
            // Max output of a channel is 15. Max sum is 60.
            // Scale 0-15 to roughly 0 - 8000 for each channel.
            // mixedSample = (ch1Out + ch2Out + ch3Out + ch4Out) * 100.0f; // Very rough scaling
            // A channel's output (0-15) should be mapped to an amplitude.
            // E.g., (sample_0_15 / 7.5f - 1.0f) to get -1 to +1 range, then scale by volume.
            // For now, let's assume each channel's getOutputSample() returns a value already somewhat scaled.
            // And the sum is then globally scaled.
            // Max output from a channel is 15. Let's scale this to be a fraction of 32767.
            // If one channel is max (15), maybe it's 15 * 500 = 7500.
            // If all four are max, 4 * 7500 = 30000. This seems reasonable.
            mixedSample = (ch1Out + ch2Out + ch3Out + ch4Out) * 200.0f; // Adjust this factor

            // Clamp to 16-bit range
            if (mixedSample > 32767.0f) mixedSample = 32767.0f;
            if (mixedSample < -32768.0f) mixedSample = -32768.0f;

            internalSampleBuffer[internalBufferPos] = (short) mixedSample;
        }

        internalBufferPos++;
        if (internalBufferPos >= INTERNAL_SAMPLE_BUFFER_SIZE) {
            flushInternalBufferToSoundCard();
        }
    }

    private void flushInternalBufferToSoundCard() {
        if (!javaSoundInitialized || internalBufferPos == 0) return;

        int bytesToWrite = internalBufferPos * (SAMPLE_SIZE_BITS / 8);
        for (int i = 0; i < internalBufferPos; i++) {
            short sample = internalSampleBuffer[i];
            outputByteBuffer[i * 2] = (byte) (sample & 0xFF);          // Little-endian low byte
            outputByteBuffer[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF); // Little-endian high byte
        }

        sourceDataLine.write(outputByteBuffer, 0, bytesToWrite);
        internalBufferPos = 0;
    }

    public byte readRegister(int address) {
        int regIndex = address - 0xFF10;

        if (address >= 0xFF10 && address <= 0xFF26) { // Channel and control registers
            // For write-only registers or specific read behaviors:
            switch (address) {
                // NR10 (FF10) - Channel 1 Sweep: Bits 0-6 readable
                case 0xFF10: return (byte)(audioRegisters[regIndex] | 0x80); // Bit 7 is unused (reads as 1)
                // NR11 (FF11) - Channel 1 Length/Duty: Only bits 7-6 (duty) are R/W by CPU. Length is internal.
                // NR12 (FF12) - Channel 1 Volume/Envelope
                // NR13 (FF13) - Channel 1 Freq Lo (Write-only)
                case 0xFF13: return (byte)0xFF; // Write-only
                // NR14 (FF14) - Channel 1 Freq Hi/Control: Bit 6 (length enable) is R/W. Others write-only or trigger.
                //                 Bit 7 (Trigger) is write-only. Bit 6 (Length enable) is R/W. Bits 0-2 (Freq Hi) are W/O.
                //                 Reads back with unused bits as 1.
                case 0xFF14: return (byte)(audioRegisters[regIndex] | 0xBF); // Bit 6 is R/W, others read as 1 if unused.

                // NR21 (FF16) - Channel 2 Length/Duty
                // NR22 (FF17) - Channel 2 Volume/Envelope
                // NR23 (FF18) - Channel 2 Freq Lo (Write-only)
                case 0xFF18: return (byte)0xFF;
                // NR24 (FF19) - Channel 2 Freq Hi/Control
                case 0xFF19: return (byte)(audioRegisters[regIndex] | 0xBF);

                // NR30 (FF1A) - Channel 3 DAC Enable: Bit 7 R/W. Others unused.
                case 0xFF1A: return (byte)(audioRegisters[regIndex] | 0x7F);
                // NR31 (FF1B) - Channel 3 Length (Write-only)
                case 0xFF1B: return (byte)0xFF;
                // NR32 (FF1C) - Channel 3 Volume: Bits 6-5 R/W. Others unused.
                case 0xFF1C: return (byte)(audioRegisters[regIndex] | 0x9F);
                // NR33 (FF1D) - Channel 3 Freq Lo (Write-only)
                case 0xFF1D: return (byte)0xFF;
                // NR34 (FF1E) - Channel 3 Freq Hi/Control
                case 0xFF1E: return (byte)(audioRegisters[regIndex] | 0xBF);

                // NR41 (FF20) - Channel 4 Length (Write-only)
                case 0xFF20: return (byte)0xFF;
                // NR42 (FF21) - Channel 4 Volume/Envelope
                // NR43 (FF22) - Channel 4 Polynomial Counter
                // NR44 (FF23) - Channel 4 Control
                case 0xFF23: return (byte)(audioRegisters[regIndex] | 0xBF);

                // NR50 (FF24) - Master Volume & VIN Panning
                case 0xFF24: return (byte)nr50_vin_panning_master_vol;
                // NR51 (FF25) - Sound Panning
                case 0xFF25: return (byte)nr51_output_panning;
                // NR52 (FF26) - Sound ON/OFF & Channel Status
                case 0xFF26:
                    int status = masterSoundEnable ? 0x80 : 0x00;
                    if (channel1.isActive()) status |= 0x01;
                    if (channel2.isActive()) status |= 0x02;
                    if (channel3.isActive()) status |= 0x04;
                    if (channel4.isActive()) status |= 0x08;
                    status |= 0x70; // Bits 4-6 always read as 1
                    return (byte) status;

                default:
                    // For registers not specifically handled, return their raw written value
                    // Many APU registers have specific read behaviors (e.g., unused bits read as 1)
                    // This needs to be implemented per register based on Pandocs.
                    // The audioRegisters array stores what was last written.
                    // A simple default:
                    return audioRegisters[regIndex];
            }
        } else if (address >= 0xFF30 && address <= 0xFF3F) { // Wave Pattern RAM
            // Wave RAM is readable only if APU is on and Channel 3 DAC is on (complex condition)
            // Simplification: readable if master sound is on.
            if (masterSoundEnable) {
                return wavePatternRam[address - 0xFF30];
            }
            return (byte) 0xFF; // Or last value if APU off? Pandocs says "mostly $FF".
        }
        // System.err.println("APU: Unhandled read from address: " + String.format("0x%04X", address));
        return (byte) 0xFF;
    }

    public void writeRegister(int address, byte value) {
        // Store raw value for registers that might need it or for debugging
        if (address >= 0xFF10 && address <= 0xFF2F) { // FF27-FF2F are unused but often mirrored
            audioRegisters[address - 0xFF10] = value;
        }

        if (!masterSoundEnable && address != 0xFF26) {
            // Most registers are not writable if APU master is off, except NR52 itself.
            // Length counters for CH1, CH2, CH4 can be written even if APU is off.
            if (address == 0xFF11 || address == 0xFF16 || address == 0xFF20) { // NRx1 (Length)
                // Allow length writes.
            } else {
                return;
            }
        }

        switch (address) {
            // Channel 1
            case 0xFF10: channel1.writeNRX0(value); break; // NR10 Sweep
            case 0xFF11: channel1.writeNRX1(value); break; // NR11 Length/Duty
            case 0xFF12: channel1.writeNRX2(value); break; // NR12 Volume/Envelope
            case 0xFF13: channel1.writeNRX3(value); break; // NR13 Freq Lo
            case 0xFF14: channel1.writeNRX4(value); break; // NR14 Freq Hi/Control (Trigger)

            // Channel 2
            // FF15 is unused
            case 0xFF16: channel2.writeNRX1(value); break; // NR21 Length/Duty
            case 0xFF17: channel2.writeNRX2(value); break; // NR22 Volume/Envelope
            case 0xFF18: channel2.writeNRX3(value); break; // NR23 Freq Lo
            case 0xFF19: channel2.writeNRX4(value); break; // NR24 Freq Hi/Control (Trigger)

            // Channel 3
            case 0xFF1A: channel3.writeNR30(value); break; // NR30 DAC Power
            case 0xFF1B: channel3.writeNR31(value); break; // NR31 Length
            case 0xFF1C: channel3.writeNR32(value); break; // NR32 Volume
            case 0xFF1D: channel3.writeNR33(value); break; // NR33 Freq Lo
            case 0xFF1E: channel3.writeNR34(value); break; // NR34 Freq Hi/Control (Trigger)

            // Channel 4
            // FF1F is unused
            case 0xFF20: channel4.writeNR41(value); break; // NR41 Length
            case 0xFF21: channel4.writeNR42(value); break; // NR42 Volume/Envelope
            case 0xFF22: channel4.writeNR43(value); break; // NR43 Polynomial Counter
            case 0xFF23: channel4.writeNR44(value); break; // NR44 Control (Trigger)

            // Control Registers
            case 0xFF24: nr50_vin_panning_master_vol = value & 0xFF; break; // NR50
            case 0xFF25: nr51_output_panning = value & 0xFF; break;       // NR51
            case 0xFF26: // NR52 Sound ON/OFF
                boolean newMasterEnable = (value & 0x80) != 0;
                if (masterSoundEnable && !newMasterEnable) { // Turning OFF
                    // When APU is turned off, all registers FF10-FF25 are cleared.
                    // Channels are reset. Frame sequencer stops.
                    for (int i = 0xFF10; i <= 0xFF25; i++) {
                        // This clearing behavior is complex. Some emulators re-init channels.
                        // For now, just update the master flag. Channels will check this.
                        // A full reset of channel states might be needed.
                        audioRegisters[i - 0xFF10] = 0;
                    }
                    channel1.resetOnAPUDisable();
                    channel2.resetOnAPUDisable();
                    channel3.resetOnAPUDisable();
                    channel4.resetOnAPUDisable();
                    frameSequencerStep = 0; // Reset frame sequencer position
                    frameSequencerCycleCounter = 0;
                } else if (!masterSoundEnable && newMasterEnable) { // Turning ON
                    // When APU is turned on, frame sequencer is reset to step 0.
                    frameSequencerStep = 0;
                    frameSequencerCycleCounter = 0; // Start fresh for FS timing
                }
                masterSoundEnable = newMasterEnable;
                audioRegisters[address-0xFF10] = value; // Store raw value for NR52
                break;

            // Wave Pattern RAM (0xFF30 - 0xFF3F)
            default:
                if (address >= 0xFF30 && address <= 0xFF3F) {
                    if (masterSoundEnable) { // Wave RAM writable only if APU is on
                        wavePatternRam[address - 0xFF30] = value;
                        channel3.updateWaveRamByte(address - 0xFF30, value);
                    }
                } else {
                    // System.err.println("APU: Unhandled write to address: " + String.format("0x%04X", address) + " value: " + String.format("0x%02X", value));
                }
                break;
        }
    }

    public void close() {
        if (javaSoundInitialized && sourceDataLine != null) {
            flushInternalBufferToSoundCard(); // Flush any remaining samples
            sourceDataLine.drain();
            sourceDataLine.stop();
            sourceDataLine.close();
            javaSoundInitialized = false;
            System.out.println("APU Java Sound resources released.");
        }
    }

    // --- Helper method for channels to access raw register values ---
    byte getReg(int offset) { // offset from 0xFF10
        return audioRegisters[offset];
    }
    byte getWaveRamByte(int offset) { // offset from 0xFF30
        return wavePatternRam[offset];
    }
    boolean isMasterSoundEnabled() {
        return masterSoundEnable;
    }


    // ========================================================================
    // Abstract Channel Base Class (Conceptual)
    // ========================================================================
    abstract static class SoundChannel {
        protected final APU apu;
        protected boolean enabled; // True if triggered and length counter > 0 (or length disabled)
        protected int lengthCounter;
        protected boolean lengthEnabled; // NRx4 bit 6

        // Volume Envelope
        protected int volume;          // Current volume (0-15)
        protected int initialVolume;
        protected boolean envelopeIncrease;
        protected int envelopePeriod;  // Number of envelope steps (1-7), 0 means off
        protected int envelopeCounter; // Counts FS steps for envelope

        public SoundChannel(APU apu) {
            this.apu = apu;
        }

        abstract void reset();
        abstract void resetOnAPUDisable(); // Specific reset when APU master turns off
        abstract void trigger();
        abstract int getOutputSample(); // Returns a value (e.g. 0-15)
        abstract void stepSampleGenerator(); // Advances internal timers for one output sample period

        public void clockLength() {
            if (lengthEnabled && lengthCounter > 0) {
                lengthCounter--;
                if (lengthCounter == 0) {
                    enabled = false; // Channel stops
                }
            }
        }

        public void clockEnvelope() {
            if (envelopePeriod == 0) return; // Envelope disabled

            envelopeCounter--;
            if (envelopeCounter <= 0) {
                envelopeCounter = envelopePeriod;
                if (envelopeIncrease) {
                    if (volume < 15) volume++;
                } else {
                    if (volume > 0) volume--;
                }
            }
        }
        public boolean isActive() { return enabled; }

        protected void loadLength(int lengthData) { // NRx1 bits 0-5 for pulse/noise, NRx1 all bits for wave
            // Max length is 64 for pulse/noise, 256 for wave
            int maxLength = (this instanceof WaveChannel) ? 256 : 64;
            if (lengthData == 0) this.lengthCounter = maxLength; // 0 means max length
            else this.lengthCounter = maxLength - lengthData;
        }

        protected void loadEnvelope(byte nrx2) {
            initialVolume = (nrx2 >> 4) & 0x0F;
            envelopeIncrease = (nrx2 & 0x08) != 0;
            envelopePeriod = nrx2 & 0x07;
            volume = initialVolume; // Volume is reset on trigger
            envelopeCounter = envelopePeriod; // Reset envelope timer
        }

        // DAC enabled check (common for pulse/noise)
        protected boolean isDACOn(byte nrx2_val) {
            return (nrx2_val & 0xF8) != 0; // Initial Volume > 0 OR Envelope direction is Add
        }
    }

    // ========================================================================
    // Pulse Channel (Channel 1 and 2)
    // ========================================================================
    static class PulseChannel extends SoundChannel {
        private final boolean hasSweep; // Channel 1 has sweep

        // Frequency/Period
        private int frequencyValue; // 11-bit value from NRx3, NRx4
        private double periodTimer;    // Counts down at CPU_CLOCK_SPEED / 4 (1MHz for sound gen)
        // Or, simpler: counts down based on output sample rate and desired freq.

        // Duty Cycle
        private int dutyPattern; // 0-3, from NRx1 bits 7-6
        private int dutyStep;    // 0-7, current position in duty waveform

        private static final byte[][] DUTY_WAVEFORMS = {
                {0, 0, 0, 0, 0, 0, 0, 1}, // 12.5% (....,,,#)
                {1, 0, 0, 0, 0, 0, 0, 1}, // 25%   (#...,,,#)
                {1, 0, 0, 0, 0, 1, 1, 1}, // 50%   (#...####)
                {0, 1, 1, 1, 1, 1, 1, 0}  // 75%   (###,,,#.) inverted of 25%
        };

        // Sweep (Channel 1 only)
        private int sweepPeriod;
        private boolean sweepDecrease;
        private int sweepShift;
        private int sweepCounter;
        private int shadowFrequency;
        private boolean sweepEnabledThisTrigger;


        public PulseChannel(APU apu, boolean hasSweep) {
            super(apu);
            this.hasSweep = hasSweep;
            reset();
        }

        @Override
        public void reset() {
            enabled = false;
            lengthCounter = 0;
            lengthEnabled = false;
            volume = 0;
            initialVolume = 0;
            envelopeIncrease = false;
            envelopePeriod = 0;
            envelopeCounter = 0;

            frequencyValue = 0;
            periodTimer = 0.0;
            dutyPattern = 0;
            dutyStep = 0;

            if (hasSweep) {
                sweepPeriod = 0;
                sweepDecrease = false;
                sweepShift = 0;
                sweepCounter = 0;
                shadowFrequency = 0;
                sweepEnabledThisTrigger = false;
            }
        }
        @Override
        void resetOnAPUDisable() {
            reset(); // Full reset for pulse channels
        }


        public void writeNRX0(byte val) { // Sweep (NR10)
            if (!hasSweep) return;
            sweepPeriod = (val >> 4) & 0x07;
            sweepDecrease = (val & 0x08) != 0;
            sweepShift = val & 0x07;
        }
        public void writeNRX1(byte val) { // Length/Duty (NR11, NR21)
            loadLength(val & 0x3F); // bits 0-5 for length
            dutyPattern = (val >> 6) & 0x03;
        }
        public void writeNRX2(byte val) { // Volume/Envelope (NR12, NR22)
            loadEnvelope(val);
            if (!isDACOn(val)) enabled = false; // If DAC is turned off, channel is off
        }
        public void writeNRX3(byte val) { // Freq Lo (NR13, NR23)
            frequencyValue = (frequencyValue & 0x0700) | (val & 0xFF);
        }
        public void writeNRX4(byte val) { // Freq Hi/Control (NR14, NR24)
            frequencyValue = (frequencyValue & 0x00FF) | ((val & 0x07) << 8);
            lengthEnabled = (val & 0x40) != 0;
            if ((val & 0x80) != 0) { // Trigger
                trigger();
            }
        }

        private int dutyStepTimer;
        @Override
        public void trigger() {
            // ... (habilita o canal, reseta volume/envelope)
            enabled = true; // Se DAC estiver ON
            if (!isDACOn(apu.getReg(hasSweep ? 0x02 : 0x07))) { // NRx2
                enabled = false;
                return;
            }

            if (lengthCounter == 0) {
                loadLength(0); // Max length (64)
            }
            volume = initialVolume;
            envelopeCounter = envelopePeriod;

            dutyStep = 0;
            // frequencyValue (11-bit) deve estar atualizado pelos writes a NRx3 e NRx4
            // O timer que avança o dutyStep é clockado a (CPU_CLOCK_SPEED / 8)
            // E o período para um step é (2048 - frequencyValue) desses clocks.
            if (frequencyValue >= 2047) { // Frequência muito alta ou inválida
                dutyStepTimer = Integer.MAX_VALUE; // Efetivamente para o timer
            } else {
                dutyStepTimer = (2048 - frequencyValue);
            }

            if (hasSweep) {
                shadowFrequency = frequencyValue;
                sweepCounter = (sweepPeriod == 0 && sweepShift != 0) ? 8 : sweepPeriod; // Se período 0 mas shift >0, ainda pode varrer na primeira vez
                if(sweepPeriod == 0 && sweepShift == 0) sweepEnabledThisTrigger = false; // Pandocs: sweep timer period is 0, sweep is disabled.
                else sweepEnabledThisTrigger = true;

                if (sweepShift > 0) {
                    calculateNewSweepFrequency(true); // Verifica overflow inicial
                }
            }
        }


// Este método agora é chamado pela APU com base no clock da CPU, não no sample rate
// A APU precisa de uma maneira de passar "ticks do clock da APU" para os canais.
// A estrutura atual de stepSampleGenerator é chamada por amostra de áudio gerada.
// Vamos adaptar: stepSampleGenerator avança o estado INTERNO do canal,
// e getOutputSample apenas lê o estado atual.
// A APU.update() vai ter que simular os ticks do clock interno da APU e chamar um
// método de step interno dos canais.

        // SOLUÇÃO ATUAL: A APU.update chama stepSampleGenerator() por amostra de saída.
// Precisamos simular quantos "ticks do timer do canal" ocorreram nesse intervalo.
// Ticks do timer do canal por segundo = (CPU_CLOCK_SPEED / 8) / (2048 - frequencyValue)
// Ticks do timer do canal por amostra de saída = Ticks por segundo / SAMPLE_RATE
        private double channelTimerTicksPerOutputSample; // Recalcular quando frequencyValue muda


        public void clockSweep() {
            if (!hasSweep || !sweepEnabledThisTrigger || sweepPeriod == 0) return;

            sweepCounter--;
            if (sweepCounter <= 0) {
                sweepCounter = sweepPeriod == 0 ? 8 : sweepPeriod; // Reload sweep timer
                if (sweepEnabledThisTrigger && sweepPeriod > 0) { // Only sweep if period > 0
                    int newFreq = calculateNewSweepFrequency(false);
                    if (newFreq != -1 && sweepShift > 0) { // If new freq is valid and shift is not 0
                        frequencyValue = newFreq;
                        shadowFrequency = newFreq; // Update shadow
                        // Also check overflow again with the new shadow freq
                        calculateNewSweepFrequency(true); // This second check is for the *next* potential update
                    }
                }
            }
        }

        private int calculateNewSweepFrequency(boolean checkOnly) {
            int newFreq = shadowFrequency >> sweepShift;
            if (sweepDecrease) {
                newFreq = shadowFrequency - newFreq;
            } else {
                newFreq = shadowFrequency + newFreq;
            }

            if (newFreq > 2047) { // Overflow
                enabled = false; // Channel disabled
                return -1;
            }
            if (newFreq < 0) newFreq = 0; // Should not happen if shadowFrequency is always positive

            if (!checkOnly) { // Only update shadow if not just checking
                // shadowFrequency = newFreq; // This is done by the caller if valid
            }
            return newFreq;
        }


        // Dentro da classe APU.java, na subclasse PulseChannel

        @Override
        public void stepSampleGenerator() {
            if (!enabled) return;

            // O timer do canal de pulso é decrementado com base nos ciclos da CPU.
            // O clock do timer do canal é (CPU_CLOCK_SPEED / 8) ou ~524kHz.
            // Cada passo do duty cycle dura (2048 - frequencyValue) ticks deste clock.
            // Para simplificar a integração com o loop principal, podemos pensar em termos de
            // um contador que representa o timer do hardware.

            // No seu loop principal da APU, você já tem o CYCLES_PER_OUTPUT_SAMPLE.
            // Vamos usar isso para decrementar nosso timer.
            // Um T-cycle da CPU equivale a um tick do clock principal.
            // O timer de frequência do canal de pulso é decrementado a cada 4 T-cycles.

            // A lógica aqui é complexa de mapear diretamente para um único step por amostra.
            // Uma abordagem mais correta é ter o loop de update da APU passando os ciclos para cada canal.
            // Mas para adaptar sua estrutura atual, vamos fazer uma aproximação:

            // Esta é uma correção crucial. Substitua seu `periodTimer` por esta lógica.
            // 'periodTimer' agora atua como um contador regressivo.
            periodTimer -= 1; // Simplificação: assume um step por amostra gerada. Lógica mais precisa abaixo.

            // Lógica mais precisa:
            // A frequência do gerador de onda é f = 131072 / (2048 - x) Hz
            // O período da onda é T = (2048 - x) / 131072 segundos.
            // O período de uma ÚNICA etapa do duty (são 8 etapas) é T / 8.
            // segundos_por_etapa_duty = (2048 - frequencyValue) / (131072.0 * 8.0)
            // amostras_por_etapa_duty = segundos_por_etapa_duty * SAMPLE_RATE

            if (frequencyValue >= 2047) return; // Canal efetivamente desligado

            double samplesPerDutyStep = ((2048.0 - frequencyValue) / 1048576.0) * SAMPLE_RATE;

            if (samplesPerDutyStep == 0) return; // Evita problemas se a frequência for muito alta

            // Usando um acumulador de fase (melhor abordagem)
            // 'periodTimer' será nosso acumulador de fase agora (mude para double)
            // private double phaseAccumulator = 0.0;

            // A quantidade de "passos de duty" que ocorreram em uma amostra de áudio
            double dutyStepsPerSample = 1.0 / samplesPerDutyStep;

            // `periodTimer` agora é o acumulador de fase. Declare como double na classe.
            periodTimer += dutyStepsPerSample;

            if (periodTimer >= 1.0) {
                int stepsToAdvance = (int)periodTimer;
                dutyStep = (dutyStep + stepsToAdvance) % 8;
                periodTimer -= stepsToAdvance; // ou periodTimer %= 1.0;
            }
        }
// E no trigger, inicializar dutyStepTimer para ticksPerDutyStep:
// dutyStepTimer = (2048 - frequencyValue); // Em trigger()


        @Override
        public int getOutputSample() {
            if (!enabled || !isDACOn(apu.getReg(hasSweep ? 0x02 : 0x07))) {
                return 0;
            }
            // Get current amplitude from duty cycle (0 or 1)
            int amplitude = DUTY_WAVEFORMS[dutyPattern][dutyStep];
            return amplitude * volume; // Scale by current envelope volume
        }
    }

    // ========================================================================
    // Wave Channel (Channel 3)
    // ========================================================================
    static class WaveChannel extends SoundChannel {
        private int frequencyValue;
        private double periodTimer;
        private int waveRamPosition; // 0-31, current sample in wave RAM
        private byte currentSampleBuffer; // Holds two 4-bit samples from wave RAM

        // Volume shift: 0=0%, 1=100%, 2=50%, 3=25%
        private int volumeShiftCode; // 0-3 from NR32 bits 6-5

        public WaveChannel(APU apu) {
            super(apu);
            reset();
        }

        @Override
        public void reset() {
            enabled = false;
            lengthCounter = 0;
            lengthEnabled = false;
            volumeShiftCode = 0; // Muted
            frequencyValue = 0;
            periodTimer = 0.0;
            waveRamPosition = 0;
            currentSampleBuffer = 0;
        }
        @Override
        void resetOnAPUDisable() {
            reset();
            // Wave RAM content is preserved.
        }


        public void writeNR30(byte val) { // DAC Power
            boolean dacOn = (val & 0x80) != 0;
            if (!dacOn) enabled = false; // If DAC turned off, channel is off
            // This register is the only way to enable/disable channel 3's DAC.
        }
        public void writeNR31(byte val) { // Length
            loadLength(val & 0xFF); // Uses all 8 bits for length (max 256)
        }
        public void writeNR32(byte val) { // Volume
            volumeShiftCode = (val >> 5) & 0x03;
        }
        public void writeNR33(byte val) { // Freq Lo
            frequencyValue = (frequencyValue & 0x0700) | (val & 0xFF);
        }
        public void writeNR34(byte val) { // Freq Hi/Control
            frequencyValue = (frequencyValue & 0x00FF) | ((val & 0x07) << 8);
            lengthEnabled = (val & 0x40) != 0;
            if ((val & 0x80) != 0) { // Trigger
                trigger();
            }
        }
        public void updateWaveRamByte(int offset, byte value) {
            // This is called by APU when wave RAM is written.
            // Channel 3 might need to react if it's currently playing from that byte.
            // For simplicity, we assume it reads on demand.
        }

        @Override
        public void trigger() {
            if ((apu.getReg(0x0A) & 0x80) == 0) { // Check NR30 DAC power
                enabled = false; return;
            }
            enabled = true;
            if (lengthCounter == 0) {
                loadLength(0); // Max length (256)
            }
            waveRamPosition = 0; // Restart wave playback from the beginning
            // Period timer reset for frequency
            // Wave timer period is (2048 - frequencyValue) * 2 APU clock cycles (1MHz ticks)
            periodTimer = 0.0; // Placeholder
        }

        @Override
        public void stepSampleGenerator() {
            if (!enabled) return;
            // Similar to pulse channel, advance waveRamPosition based on frequency.
            // Each step of waveRamPosition (0-31) occurs every (2048 - frequencyValue) CPU cycles.
            // (Frequency is 65536 / (2048-x) Hz. Period is (2048-x)/65536 sec.
            // 32 samples in waveform. Time per sample = Period / 32.
            // Samples per output sample = (Time per wave sample) / (Time per output sample)
            // This is a placeholder for correct phase/timer logic.
            if (periodTimer % (int)Math.max(1, 20 * (2048-frequencyValue)/2048.0) == 0) { // Arbitrary slow down
                waveRamPosition = (waveRamPosition + 1) % 32;
            }
        }

        @Override
        public int getOutputSample() {
            if (!enabled || (apu.getReg(0x0A) & 0x80) == 0) { // Check NR30 DAC
                return 0;
            }
            // Read 4-bit sample from Wave RAM
            byte waveByte = apu.getWaveRamByte(waveRamPosition / 2);
            int sample4bit;
            if ((waveRamPosition % 2) == 0) { // High nibble first
                sample4bit = (waveByte >> 4) & 0x0F;
            } else { // Low nibble
                sample4bit = waveByte & 0x0F;
            }

            // Apply volume shift
            switch (volumeShiftCode) {
                case 0: return 0;          // Muted
                case 1: return sample4bit; // 100%
                case 2: return sample4bit >> 1; // 50%
                case 3: return sample4bit >> 2; // 25%
                default: return 0;
            }
        }
    }

    // ========================================================================
    // Noise Channel (Channel 4)
    // ========================================================================
    static class NoiseChannel extends SoundChannel {
        private int lfsr; // Linear Feedback Shift Register (15-bit)
        private boolean lfsrWidthMode; // false for 15-bit, true for 7-bit
        private int clockShift;
        private int clockDividerCode;
        private int periodTimer;

        public NoiseChannel(APU apu) {
            super(apu);
            reset();
        }

        @Override
        public void reset() {
            enabled = false;
            lengthCounter = 0;
            lengthEnabled = false;
            volume = 0;
            initialVolume = 0;
            envelopeIncrease = false;
            envelopePeriod = 0;
            envelopeCounter = 0;

            lfsr = 0x7FFF; // Initial LFSR state (all 1s)
            lfsrWidthMode = false;
            clockShift = 0;
            clockDividerCode = 0;
            periodTimer = 0;
        }
        @Override
        void resetOnAPUDisable() {
            reset();
        }


        public void writeNR41(byte val) { // Length
            loadLength(val & 0x3F);
        }
        public void writeNR42(byte val) { // Volume/Envelope
            loadEnvelope(val);
            if (!isDACOn(val)) enabled = false;
        }
        public void writeNR43(byte val) { // Polynomial Counter
            clockShift = (val >> 4) & 0x0F;
            lfsrWidthMode = (val & 0x08) != 0; // Bit 3: 0=15-bit, 1=7-bit
            clockDividerCode = val & 0x07;
        }
        public void writeNR44(byte val) { // Control (Trigger)
            lengthEnabled = (val & 0x40) != 0;
            if ((val & 0x80) != 0) { // Trigger
                trigger();
            }
        }

        @Override
        public void trigger() {
            if (!isDACOn(apu.getReg(0x11))) { // Check NR42
                enabled = false; return;
            }
            enabled = true;
            if (lengthCounter == 0) {
                loadLength(0); // Max length (64)
            }
            volume = initialVolume;
            envelopeCounter = envelopePeriod;
            lfsr = 0x7FFF; // Reset LFSR to all 1s on trigger

            // Reset period timer for LFSR clock
            // Divisor r: clockDividerCode (0-7). If 0, it's 0.5.
            // Frequency = 262144 Hz / r / (2^s) where s = clockShift + 1
            // Or, 524288 Hz / r / (2^s) if r=0.
            // This is complex. Placeholder for timer.
            int divisor = clockDividerCode == 0 ? 8 : clockDividerCode * 16; // Simplified divisor based on Pandocs table
            periodTimer = divisor << clockShift; // Placeholder
        }

        @Override
        public void stepSampleGenerator() {
            if (!enabled) return;
            // Advance LFSR based on its clock period.
            // This is a placeholder for correct timer logic.
            if (periodTimer % Math.max(1, 10) == 0) { // Arbitrary slow down
                int xor_bit = (lfsr & 1) ^ ((lfsr >> 1) & 1);
                lfsr >>= 1;
                lfsr |= (xor_bit << 14); // Bit 15
                if (lfsrWidthMode) { // 7-bit mode
                    lfsr &= ~(1 << 6); // Clear bit 6
                    lfsr |= (xor_bit << 6); // Set bit 6
                }
            }
        }

        @Override
        public int getOutputSample() {
            if (!enabled || !isDACOn(apu.getReg(0x11))) { // Check NR42
                return 0;
            }
            // Output is based on bit 0 of LFSR (inverted)
            int amplitude = (lfsr & 1) == 0 ? 1 : 0; // If bit 0 is 0, output is 1 (audible)
            return amplitude * volume;
        }
    }
}