package com.meutcc.gbemulator;

import javax.sound.sampled.*;
import java.util.Arrays;

public class APU {
  
    private static final int CPU_CLOCK_SPEED = 4194304; 
    public static final int SAMPLE_RATE = 48000;        
    private static final int CHANNELS = 2;              
    private static final int SAMPLE_SIZE_BITS = 16;     
    private static final boolean SIGNED = true;
    private static final boolean BIG_ENDIAN = false;
    
    
    private static final int AUDIO_OUTPUT_BUFFER_SAMPLES = 8192;  
    private static final int INTERNAL_SAMPLE_BUFFER_SIZE = 2048;  
    
    private static final int FRAME_SEQUENCER_PERIOD_TCYCLES = 8192;
    private static final long SAMPLE_PHASE_INCREMENT = ((long)SAMPLE_RATE << 16) / CPU_CLOCK_SPEED;
    
    private static final float HIGHPASS_CHARGE = 0.999f;
    
    private static final float LOWPASS_ALPHA = 0.25f;

    
    private SourceDataLine sourceDataLine;
    private boolean javaSoundInitialized = false;
    private final byte[] outputByteBuffer;
    private final float[] internalSampleBuffer;
    private int internalBufferPos = 0;
    
    private float highpassLeft = 0.0f;
    private float highpassRight = 0.0f;
    
    private float lowpassLeft = 0.0f;
    private float lowpassRight = 0.0f;

    
    private boolean masterSoundEnable = false;
    
    
    private long apuTotalCycles = 0;
    
    private int frameSequencerCycleCounter = 0;
    private int frameSequencerStep = 0;
    
    private long samplePhaseAccumulator = 0;
    
    private volatile boolean emulatorSoundGloballyEnabled = true;
    
    private boolean debugTiming = false;
    private long samplesGenerated = 0;
    private long frameSequencerTicks = 0;

   
    private final PulseChannel channel1;
    private final PulseChannel channel2;
    private final WaveChannel channel3;
    private final NoiseChannel channel4;

    private int nr50_master_volume = 0x77;  
    private int nr51_panning = 0xFF;       
    
    
    final byte[] audioRegisters = new byte[0x30];
    final byte[] wavePatternRam = new byte[16];

    public APU() {
        outputByteBuffer = new byte[INTERNAL_SAMPLE_BUFFER_SIZE * (SAMPLE_SIZE_BITS / 8) * CHANNELS];
        internalSampleBuffer = new float[INTERNAL_SAMPLE_BUFFER_SIZE * CHANNELS];

        channel1 = new PulseChannel(this, true);  
        channel2 = new PulseChannel(this, false); 
        channel3 = new WaveChannel(this);
        channel4 = new NoiseChannel(this);

        initializeSoundAPI();
        reset();
    }
    
    public void setEmulatorSoundGloballyEnabled(boolean enabled) {
        this.emulatorSoundGloballyEnabled = enabled;
        if (!enabled && javaSoundInitialized && sourceDataLine != null) {
            sourceDataLine.flush();
            System.out.println("APU: Sound " + (enabled ? "ENABLED" : "DISABLED"));
        }
    }
    
    public void setDebugTiming(boolean enabled) {
        this.debugTiming = enabled;
        if (enabled) {
            System.out.println("APU: Debug timing ENABLED - Statistics will be printed every second");
        } else {
            System.out.println("APU: Debug timing DISABLED");
        }
    }
    
    public String getTimingStats() {
        long expectedSamples = (apuTotalCycles * SAMPLE_RATE / CPU_CLOCK_SPEED);
        long drift = samplesGenerated - expectedSamples;
        double driftPercent = expectedSamples > 0 ? (drift * 100.0 / expectedSamples) : 0;
        
        return String.format(
            "APU Timing Statistics:\n" +
            "  Total T-cycles processed: %d\n" +
            "  Samples generated: %d\n" +
            "  Expected samples: %d\n" +
            "  Sample drift: %d (%.6f%%)\n" +
            "  Frame Sequencer ticks: %d\n" +
            "  Sample rate: %d Hz\n" +
            "  CPU clock: %d Hz\n" +
            "  Phase increment (16.16): 0x%04X (%.6f samples/cycle)",
            apuTotalCycles, samplesGenerated, expectedSamples, 
            drift, Math.abs(driftPercent), frameSequencerTicks,
            SAMPLE_RATE, CPU_CLOCK_SPEED, 
            SAMPLE_PHASE_INCREMENT, (SAMPLE_PHASE_INCREMENT / 65536.0)
        );
    }

    public void reset() {
        masterSoundEnable = false;
        
        apuTotalCycles = 0;
        frameSequencerCycleCounter = 0;
        frameSequencerStep = 0;
        samplePhaseAccumulator = 0;
        internalBufferPos = 0;
        
        samplesGenerated = 0;
        frameSequencerTicks = 0;
        
        highpassLeft = 0.0f;
        highpassRight = 0.0f;
        
        lowpassLeft = 0.0f;
        lowpassRight = 0.0f;

        Arrays.fill(audioRegisters, (byte) 0);
        Arrays.fill(wavePatternRam, (byte) 0);

        nr50_master_volume = 0x77;
        nr51_panning = 0xFF;

        channel1.reset();
        channel2.reset();
        channel3.reset();
        channel4.reset();

        audioRegisters[0xFF26 - 0xFF10] = (byte) 0x70;
        System.out.println("APU: Reset complete (48kHz Stereo, High-pass + Low-pass filters, Soft clipping)");
    }

    public void update(int cpuCycles) {
        if (!emulatorSoundGloballyEnabled || !javaSoundInitialized) {
            return;
        }

        apuTotalCycles += cpuCycles;

        frameSequencerCycleCounter += cpuCycles;
        while (frameSequencerCycleCounter >= FRAME_SEQUENCER_PERIOD_TCYCLES) {
            frameSequencerCycleCounter -= FRAME_SEQUENCER_PERIOD_TCYCLES;
            if (masterSoundEnable) {
                clockFrameSequencer();
                frameSequencerTicks++;
                
                if (debugTiming && (frameSequencerTicks % 512 == 0)) {
                    System.out.println(String.format(
                        "APU Timing Stats - Total Cycles: %d, Samples Generated: %d, Expected: %d, Drift: %d",
                        apuTotalCycles, samplesGenerated, 
                        (apuTotalCycles * SAMPLE_RATE / CPU_CLOCK_SPEED),
                        samplesGenerated - (apuTotalCycles * SAMPLE_RATE / CPU_CLOCK_SPEED)
                    ));
                }
            }
        }

        if (masterSoundEnable) {
            channel1.step(cpuCycles);
            channel2.step(cpuCycles);
            channel3.step(cpuCycles);
            channel4.step(cpuCycles);
        }

        samplePhaseAccumulator += cpuCycles * SAMPLE_PHASE_INCREMENT;
        
        int samplesToGenerate = (int)(samplePhaseAccumulator >> 16);
        
        samplePhaseAccumulator &= 0xFFFF;
        
        for (int i = 0; i < samplesToGenerate; i++) {
            generateAndBufferSample();
            samplesGenerated++;
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

    public byte readRegister(int address) {
        int regIndex = address - 0xFF10;

        if (address >= 0xFF10 && address <= 0xFF26) {
            switch (address) {
                
                case 0xFF10: return (byte)(audioRegisters[regIndex] | 0x80);
                
                case 0xFF11: return (byte)(audioRegisters[regIndex] | 0x3F);
                
                case 0xFF12: return (byte)(audioRegisters[regIndex] & 0xFF);
                
                case 0xFF13: return (byte)0xFF;
                
                case 0xFF14: return (byte)(audioRegisters[regIndex] | 0xBF);

                
                case 0xFF16: return (byte)(audioRegisters[regIndex] | 0x3F);
                
                case 0xFF17: return (byte)(audioRegisters[regIndex] & 0xFF);
                
                case 0xFF18: return (byte)0xFF;
                
                case 0xFF19: return (byte)(audioRegisters[regIndex] | 0xBF);

                
                case 0xFF1A: return (byte)(audioRegisters[regIndex] | 0x7F);
                
                case 0xFF1B: return (byte)0xFF;
                
                case 0xFF1C: return (byte)(audioRegisters[regIndex] | 0x9F);
                
                case 0xFF1D: return (byte)0xFF;
                
                case 0xFF1E: return (byte)(audioRegisters[regIndex] | 0xBF);

                
                case 0xFF20: return (byte)0xFF;
                
                case 0xFF21: return (byte)(audioRegisters[regIndex] & 0xFF);
                
                case 0xFF22: return (byte)(audioRegisters[regIndex] & 0xFF);
                
                case 0xFF23: return (byte)(audioRegisters[regIndex] | 0xBF);

                
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
                    return (byte)0xFF;
            }
        } else if (address >= 0xFF30 && address <= 0xFF3F) {
            return wavePatternRam[address - 0xFF30];
        }
        
        return (byte) 0xFF;
    }

    public void writeRegister(int address, byte value) {
        if (address == 0xFF26) {
            boolean newMasterEnable = (value & 0x80) != 0;
            
            if (masterSoundEnable && !newMasterEnable) {
                for (int i = 0xFF10; i <= 0xFF25; i++) {
                    audioRegisters[i - 0xFF10] = 0;
                }
                
                nr50_master_volume = 0;
                nr51_panning = 0;
                
                channel1.resetOnAPUDisable();
                channel2.resetOnAPUDisable();
                channel3.resetOnAPUDisable();
                channel4.resetOnAPUDisable();
                
                frameSequencerStep = 0;
                frameSequencerCycleCounter = 0;
                
                System.out.println("APU: Master sound DISABLED - All registers cleared");
            } 
            else if (!masterSoundEnable && newMasterEnable) {
                frameSequencerStep = 0;
                frameSequencerCycleCounter = 0;
                System.out.println("APU: Master sound ENABLED");
            }
            
            masterSoundEnable = newMasterEnable;
            audioRegisters[address - 0xFF10] = (byte)(value & 0x80);
            return;
        }
        
        if (address >= 0xFF30 && address <= 0xFF3F) {
            wavePatternRam[address - 0xFF30] = value;
            return;
        }
        
        if (!masterSoundEnable) {
            if (address == 0xFF11) {
                audioRegisters[address - 0xFF10] = value;
                return;
            } else if (address == 0xFF16) {
                audioRegisters[address - 0xFF10] = value;
                return;
            } else if (address == 0xFF1B) {
                audioRegisters[address - 0xFF10] = value;
                return;
            } else if (address == 0xFF20) {
                audioRegisters[address - 0xFF10] = value;
                return;
            } else {
                return;
            }
        }
        
        if (address >= 0xFF10 && address <= 0xFF2F) {
            audioRegisters[address - 0xFF10] = value;
        }

        switch (address) {
            case 0xFF10: channel1.writeNRX0(value); break;
            case 0xFF11: channel1.writeNRX1(value); break;
            case 0xFF12: channel1.writeNRX2(value); break;
            case 0xFF13: channel1.writeNRX3(value); break;
            case 0xFF14: channel1.writeNRX4(value); break;

            case 0xFF16: channel2.writeNRX1(value); break;
            case 0xFF17: channel2.writeNRX2(value); break;
            case 0xFF18: channel2.writeNRX3(value); break;
            case 0xFF19: channel2.writeNRX4(value); break;

            case 0xFF1A: channel3.writeNR30(value); break;
            case 0xFF1B: channel3.writeNR31(value); break;
            case 0xFF1C: channel3.writeNR32(value); break;
            case 0xFF1D: channel3.writeNR33(value); break;
            case 0xFF1E: channel3.writeNR34(value); break;

            case 0xFF20: channel4.writeNR41(value); break;
            case 0xFF21: channel4.writeNR42(value); break;
            case 0xFF22: channel4.writeNR43(value); break;
            case 0xFF23: channel4.writeNR44(value); break;

            case 0xFF24: 
                nr50_master_volume = value & 0xFF; 
                break;
                
            case 0xFF25: 
                nr51_panning = value & 0xFF; 
                break;

            default:
                break;
        }
    }

    byte getReg(int offset) {
        return audioRegisters[offset];
    }
    
    byte getWaveRamByte(int offset) {
        return wavePatternRam[offset];
    }
    
    boolean isMasterSoundEnabled() {
        return masterSoundEnable;
    }

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
            case 0: 
                channel1.clockLength();
                channel2.clockLength();
                channel3.clockLength();
                channel4.clockLength();
                break;
            case 1: 
                break;
            case 2: 
                channel1.clockLength();
                channel1.clockSweep();
                channel2.clockLength();
                channel3.clockLength();
                channel4.clockLength();
                break;
            case 3: 
                break;
            case 4: 
                channel1.clockLength();
                channel2.clockLength();
                channel3.clockLength();
                channel4.clockLength();
                break;
            case 5: 
                break;
            case 6: 
                channel1.clockLength();
                channel1.clockSweep();
                channel2.clockLength();
                channel3.clockLength();
                channel4.clockLength();
                break;
            case 7: 
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
            leftOut = 0.0f;
            rightOut = 0.0f;
        } else {
            float ch1 = channel1.getOutputSample();
            float ch2 = channel2.getOutputSample();
            float ch3 = channel3.getOutputSample();
            float ch4 = channel4.getOutputSample();

            
            if ((nr51_panning & 0x01) != 0) rightOut += ch1;
            if ((nr51_panning & 0x10) != 0) leftOut += ch1;
            
            if ((nr51_panning & 0x02) != 0) rightOut += ch2;
            if ((nr51_panning & 0x20) != 0) leftOut += ch2;
            
            if ((nr51_panning & 0x04) != 0) rightOut += ch3;
            if ((nr51_panning & 0x40) != 0) leftOut += ch3;
            
            if ((nr51_panning & 0x08) != 0) rightOut += ch4;
            if ((nr51_panning & 0x80) != 0) leftOut += ch4;

           
            int leftVolume = (nr50_master_volume >> 4) & 0x07;
            int rightVolume = nr50_master_volume & 0x07;
            
            float leftVol = (leftVolume + 1) / 8.0f;
            float rightVol = (rightVolume + 1) / 8.0f;
            
            leftOut *= leftVol;
            rightOut *= rightVol;

            
            leftOut = leftOut / 60.0f;
            rightOut = rightOut / 60.0f;

            // Apply filters
            leftOut = applyHighPass(leftOut, true);
            rightOut = applyHighPass(rightOut, false);
            
            leftOut = applyLowPass(leftOut, true);
            rightOut = applyLowPass(rightOut, false);
            
            // Soft clipping
            leftOut = softClip(leftOut);
            rightOut = softClip(rightOut);
        }

        internalSampleBuffer[internalBufferPos * 2] = leftOut;
        internalSampleBuffer[internalBufferPos * 2 + 1] = rightOut;
        
        internalBufferPos++;
        if (internalBufferPos >= INTERNAL_SAMPLE_BUFFER_SIZE) {
            flushInternalBufferToSoundCard();
        }
    }
    
    private float softClip(float sample) {
        return (float) Math.tanh(sample * 1.5);
    }

    private float applyHighPass(float sample, boolean isLeft) {
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
    
    private float applyLowPass(float sample, boolean isLeft) {
        float filtered;
        if (isLeft) {
            filtered = lowpassLeft + LOWPASS_ALPHA * (sample - lowpassLeft);
            lowpassLeft = filtered;
        } else {
            filtered = lowpassRight + LOWPASS_ALPHA * (sample - lowpassRight);
            lowpassRight = filtered;
        }
        return filtered;
    }

    private void flushInternalBufferToSoundCard() {
        if (!javaSoundInitialized || internalBufferPos == 0) return;

        for (int i = 0; i < internalBufferPos * 2; i++) {
            float sample = internalSampleBuffer[i];
            
            if (sample > 1.0f) sample = 1.0f;
            if (sample < -1.0f) sample = -1.0f;
            
            short pcmSample = (short) (sample * 32767.0f);
            
            outputByteBuffer[i * 2] = (byte) (pcmSample & 0xFF);
            outputByteBuffer[i * 2 + 1] = (byte) ((pcmSample >> 8) & 0xFF);
        }

        int bytesToWrite = internalBufferPos * 2 * (SAMPLE_SIZE_BITS / 8);
        
        int available = sourceDataLine.available();
        if (available < bytesToWrite) {
            internalBufferPos = 0;
            return;
        }
        
        sourceDataLine.write(outputByteBuffer, 0, bytesToWrite);
        internalBufferPos = 0;
    }
    
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
        protected boolean dacEnabled;

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
            lengthCounter = maxLength - lengthData;
        }

        protected void loadEnvelope(byte nrx2) {
            initialVolume = (nrx2 >> 4) & 0x0F;
            envelopeIncrease = (nrx2 & 0x08) != 0;
            envelopePeriod = nrx2 & 0x07;
        }

        protected boolean checkDACEnabled(byte nrx2) {
            return (nrx2 & 0xF8) != 0;
        }
    }
    static class PulseChannel extends SoundChannel {
        private final boolean hasSweep;
        
        private int frequencyValue;   
        private int frequencyTimer;    
        
        private int dutyPattern;      
        private int dutyStep;         
        
        private static final byte[][] DUTY_WAVEFORMS = {
            {0, 0, 0, 0, 0, 0, 0, 1}, 
            {1, 0, 0, 0, 0, 0, 0, 1}, 
            {1, 0, 0, 0, 0, 1, 1, 1}, 
            {0, 1, 1, 1, 1, 1, 1, 0}  
        };
        
        private int sweepPeriod;      
        private boolean sweepDecrease; 
        private int sweepShift;       
        private int sweepCounter;     
        private int shadowFrequency;   
        private boolean sweepEnabled;  
        private boolean sweepNegateUsed; 

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
                sweepNegateUsed = false;
            }
        }

        @Override
        void resetOnAPUDisable() {
            reset();
        }

        public void writeNRX0(byte val) { 
            if (!hasSweep) return;
            
            sweepPeriod = (val >> 4) & 0x07;
            boolean newDecrease = (val & 0x08) != 0;
            sweepShift = val & 0x07;
            
            if (sweepNegateUsed && !newDecrease) {
                enabled = false;
            }
            
            sweepDecrease = newDecrease;
        }

        public void writeNRX1(byte val) { 
            loadLength(val & 0x3F, 64);
            dutyPattern = (val >> 6) & 0x03;
        }

        public void writeNRX2(byte val) { 
            loadEnvelope(val);
            dacEnabled = checkDACEnabled(val);
            
            if (!dacEnabled) {
                enabled = false;
            }
        }

        public void writeNRX3(byte val) { 
            frequencyValue = (frequencyValue & 0x0700) | (val & 0xFF);
        }

        public void writeNRX4(byte val) {
            frequencyValue = (frequencyValue & 0x00FF) | ((val & 0x07) << 8);
            lengthEnabled = (val & 0x40) != 0;
            
            if ((val & 0x80) != 0) {
                trigger();
            }
            
        }

        @Override
        public void trigger() {
            if (!dacEnabled) {
                return;
            }
            
            enabled = true;
            
            if (lengthCounter == 0) {
                lengthCounter = 64;
                
            }
            
            frequencyTimer = (2048 - frequencyValue) * 4;
            
            volume = initialVolume;
            envelopeCounter = envelopePeriod > 0 ? envelopePeriod : 8;
            
            if (hasSweep) {
                shadowFrequency = frequencyValue;
                sweepCounter = sweepPeriod > 0 ? sweepPeriod : 8;
                sweepEnabled = (sweepPeriod > 0 || sweepShift > 0);
                sweepNegateUsed = false;
                
                if (sweepShift > 0) {
                    calculateSweepFrequency(true);
                }
            }
        }

        public void clockSweep() {
            if (!hasSweep || !sweepEnabled) return;
            
            sweepCounter--;
            if (sweepCounter <= 0) {
                sweepCounter = sweepPeriod > 0 ? sweepPeriod : 8;
                
                if (sweepEnabled && sweepPeriod > 0) {
                    int newFreq = calculateSweepFrequency(false);
                    
                    if (newFreq <= 2047 && sweepShift > 0) {
                        frequencyValue = newFreq;
                        shadowFrequency = newFreq;
                        
                        calculateSweepFrequency(true);
                    }
                }
            }
        }

        private int calculateSweepFrequency(boolean checkOnly) {
            int delta = shadowFrequency >> sweepShift;
            int newFreq;
            
            if (sweepDecrease) {
                newFreq = shadowFrequency - delta;
                sweepNegateUsed = true;
            } else {
                newFreq = shadowFrequency + delta;
            }
            
            if (newFreq > 2047) {
                enabled = false;
                return -1;
            }
            
            return newFreq;
        }

        @Override
        public void step(int cycles) {
            if (!enabled) return;
            
            frequencyTimer -= cycles;
            
            
            int maxIterations = 100;
            int iterations = 0;
            
            while (frequencyTimer <= 0 && iterations < maxIterations) {
                frequencyTimer += (2048 - frequencyValue) * 4;
                dutyStep = (dutyStep + 1) % 8;
                iterations++;
            }
            
           
            if (iterations >= maxIterations) {
                frequencyTimer = (2048 - frequencyValue) * 4;
            }
        }

        @Override
        public float getOutputSample() {
            if (!enabled || !dacEnabled) {
                return 0.0f;
            }
            
            int amplitude = DUTY_WAVEFORMS[dutyPattern][dutyStep];
            
            return amplitude * volume;
        }
    }

    static class WaveChannel extends SoundChannel {
        private int frequencyValue;    
        private int frequencyTimer;   
        private int wavePosition;     
        private int volumeShift;       
        private int sampleBuffer;      

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
            sampleBuffer = 0;
        }

        @Override
        void resetOnAPUDisable() {
            reset();
        }

        public void writeNR30(byte val) { // NR30
            dacEnabled = (val & 0x80) != 0;
            
            if (!dacEnabled) {
                enabled = false;
            }
        }

        public void writeNR31(byte val) { // NR31
            loadLength(val & 0xFF, 256);
        }

        public void writeNR32(byte val) { // NR32
            volumeShift = (val >> 5) & 0x03;
        }

        public void writeNR33(byte val) { // NR33
            frequencyValue = (frequencyValue & 0x0700) | (val & 0xFF);
        }

        public void writeNR34(byte val) { // NR34
            frequencyValue = (frequencyValue & 0x00FF) | ((val & 0x07) << 8);
            lengthEnabled = (val & 0x40) != 0;
            
            if ((val & 0x80) != 0) {
                trigger();
            }
        }

        @Override
        public void trigger() {
            if (!dacEnabled) {
                return;
            }
            
            enabled = true;
            
            if (lengthCounter == 0) {
                lengthCounter = 256;
            }
            
            wavePosition = 0;
            
            frequencyTimer = (2048 - frequencyValue) * 2;
            
            readSampleToBuffer();
        }

        @Override
        public void step(int cycles) {
            if (!enabled) return;
            
            frequencyTimer -= cycles;
            
            
            int maxIterations = 100;
            int iterations = 0;
            
            while (frequencyTimer <= 0 && iterations < maxIterations) {
                frequencyTimer += (2048 - frequencyValue) * 2;
                
                wavePosition = (wavePosition + 1) % 32;
                
                readSampleToBuffer();
                iterations++;
            }
            
            
            if (iterations >= maxIterations) {
                frequencyTimer = (2048 - frequencyValue) * 2;
            }
        }

        private void readSampleToBuffer() {
            byte waveByte = apu.getWaveRamByte(wavePosition / 2);
            
            if ((wavePosition % 2) == 0) {
                sampleBuffer = (waveByte >> 4) & 0x0F;
            } else {
                sampleBuffer = waveByte & 0x0F;
            }
        }

        @Override
        public float getOutputSample() {
            if (!enabled || !dacEnabled) {
                return 0.0f;
            }
            
            float sample;
            switch (volumeShift) {
                case 0: // Mute (0%)
                    sample = 0.0f;
                    break;
                case 1: // 100% (sem shift)
                    sample = sampleBuffer;
                    break;
                case 2: // 50% (shift right 1)
                    sample = sampleBuffer >> 1;
                    break;
                case 3: // 25% (shift right 2)
                    sample = sampleBuffer >> 2;
                    break;
                default:
                    sample = 0.0f;
                    break;
            }
            
            return sample;
        }
    }
    static class NoiseChannel extends SoundChannel {
        private int lfsr;              
        private boolean lfsrWidth7;   
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

        public void writeNR41(byte val) { 
            loadLength(val & 0x3F, 64);
        }

        public void writeNR42(byte val) { 
            loadEnvelope(val);
            dacEnabled = checkDACEnabled(val);
            
            if (!dacEnabled) {
                enabled = false;
            }
        }

        public void writeNR43(byte val) { 
            clockShift = (val >> 4) & 0x0F;
            lfsrWidth7 = (val & 0x08) != 0;
            divisorCode = val & 0x07;
            
            if (enabled) {
                frequencyTimer = DIVISORS[divisorCode] << clockShift;
            }
        }

        public void writeNR44(byte val) {
            lengthEnabled = (val & 0x40) != 0;
            
            if ((val & 0x80) != 0) {
                trigger();
            }
        }

        @Override
        public void trigger() {
            if (!dacEnabled) {
                return;
            }
            
            enabled = true;
            
            if (lengthCounter == 0) {
                lengthCounter = 64;
            }
            
            volume = initialVolume;
            envelopeCounter = envelopePeriod > 0 ? envelopePeriod : 8;
            
            lfsr = 0x7FFF;
            
            frequencyTimer = DIVISORS[divisorCode] << clockShift;
        }

        @Override
        public void step(int cycles) {
            if (!enabled) return;
            
            frequencyTimer -= cycles;
            
            
            int maxIterations = 100;
            int iterations = 0;
            
            while (frequencyTimer <= 0 && iterations < maxIterations) {
                frequencyTimer += DIVISORS[divisorCode] << clockShift;
                
                clockLFSR();
                iterations++;
            }
            
            
            if (iterations >= maxIterations) {
                frequencyTimer = DIVISORS[divisorCode] << clockShift;
            }
        }

        private void clockLFSR() {
            int bit0 = lfsr & 1;
            int bit1 = (lfsr >> 1) & 1;
            int xorResult = bit0 ^ bit1;
            
            lfsr >>= 1;
            
            lfsr &= ~(1 << 14);           
            lfsr |= (xorResult << 14);     
            
            if (lfsrWidth7) {
                lfsr &= ~(1 << 6);        
                lfsr |= (xorResult << 6);  
            }
        }

        @Override
        public float getOutputSample() {
            if (!enabled || !dacEnabled) {
                return 0.0f;
            }
            
            int amplitude = (lfsr & 1) == 0 ? 1 : 0;
            
            return amplitude * volume;
        }
    }

   
    public void saveState(java.io.DataOutputStream dos) throws java.io.IOException {
        
        for (int i = 0; i < audioRegisters.length; i++) {
            dos.writeByte(audioRegisters[i]);
        }
        
        for (int i = 0; i < wavePatternRam.length; i++) {
            dos.writeByte(wavePatternRam[i]);
        }
        dos.writeBoolean(masterSoundEnable);
        dos.writeInt(frameSequencerStep);
    }

    public void loadState(java.io.DataInputStream dis) throws java.io.IOException {
        for (int i = 0; i < audioRegisters.length; i++) {
            audioRegisters[i] = dis.readByte();
        }
        for (int i = 0; i < wavePatternRam.length; i++) {
            wavePatternRam[i] = dis.readByte();
        }
        masterSoundEnable = dis.readBoolean();
        frameSequencerStep = dis.readInt();
        
        for (int i = 0; i < audioRegisters.length; i++) {
            writeRegister(0xFF10 + i, audioRegisters[i]);
        }
    }
}
