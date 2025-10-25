package com.meutcc.gbemulator;

import javax.sound.sampled.*;
import java.util.Arrays;

public class APU {
  
    private static final int CPU_CLOCK_SPEED = 4194304; // 4.194304 MHz
    public static final int SAMPLE_RATE = 48000;        // 48 kHz (melhorado de 44.1 kHz)
    private static final int CHANNELS = 2;              // Estéreo (melhorado de Mono)
    private static final int SAMPLE_SIZE_BITS = 16;     // 16-bit audio
    private static final boolean SIGNED = true;
    private static final boolean BIG_ENDIAN = false;
    
    
    private static final int AUDIO_OUTPUT_BUFFER_SAMPLES = 32768;  
    private static final int INTERNAL_SAMPLE_BUFFER_SIZE = 8192;  
    
    // Timing - Valores precisos em T-cycles
    // Frame Sequencer: 512 Hz = CPU_CLOCK_SPEED / 512 = 8192 T-cycles por step
    private static final int FRAME_SEQUENCER_PERIOD_TCYCLES = 8192; // Exatamente 8192 T-cycles (512 Hz)
    
    // Phase Accumulator para geração de amostras
    // Usamos um acumulador de fase de 32-bit fixedpoint
    // A parte superior de 16 bits representa a parte inteira (número de amostras)
    // A parte inferior de 16 bits representa a fração
    // 
    // SAMPLE_PHASE_INCREMENT = (SAMPLE_RATE << 16) / CPU_CLOCK_SPEED
    //                        = (48000 << 16) / 4194304
    //                        = 3145728000 / 4194304
    //                        = 750.11... (em fixed-point 16.16)
    //                        = 0x000002EE (aprox. 0.01144 em formato 16.16)
    //
    // Isso significa que a cada T-cycle, incrementamos o phase accumulator em ~0.01144 (em formato 16.16)
    // Quando a parte inteira atinge 1 ou mais, geramos uma amostra e decrementamos
    // Esta técnica garante que não há drift acumulativo ao longo do tempo
    private static final long SAMPLE_PHASE_INCREMENT = ((long)SAMPLE_RATE << 16) / CPU_CLOCK_SPEED;
    
    // High-pass filter constant (para remover DC bias)
    private static final float HIGHPASS_CHARGE = 0.999f;
    
    // Low-pass filter constant (para suavizar o áudio e remover aliasing)
    // Frequência de corte aproximada: ~12 kHz em 48 kHz sample rate
    private static final float LOWPASS_ALPHA = 0.25f;

    
    private SourceDataLine sourceDataLine;
    private boolean javaSoundInitialized = false;
    private final byte[] outputByteBuffer;
    private final float[] internalSampleBuffer;
    private int internalBufferPos = 0;
    
    // High-pass filter state (um para cada canal estéreo)
    private float highpassLeft = 0.0f;
    private float highpassRight = 0.0f;
    
    // Low-pass filter state (um para cada canal estéreo)
    // Usado para suavizar o áudio e reduzir aliasing
    private float lowpassLeft = 0.0f;
    private float lowpassRight = 0.0f;

    
    private boolean masterSoundEnable = false;
    
    // ========================================================================
    // TIMING PRECISO - Contadores em T-cycles
    // ========================================================================
    
    // Contador de T-cycles interno da APU (acumula desde o último reset)
    private long apuTotalCycles = 0;
    
    // Frame Sequencer: contador de T-cycles para o próximo step (512 Hz)
    private int frameSequencerCycleCounter = 0;
    private int frameSequencerStep = 0;
    
    // Phase Accumulator para geração de amostras (técnica robusta e precisa)
    // Usamos fixed-point 16.16: bits superiores = amostras inteiras, bits inferiores = fração
    private long samplePhaseAccumulator = 0;
    
    private volatile boolean emulatorSoundGloballyEnabled = true;
    
    // ========================================================================
    // DEBUGGING E ESTATÍSTICAS
    // ========================================================================
    private boolean debugTiming = false;
    private long samplesGenerated = 0;
    private long frameSequencerTicks = 0;

   
    private final PulseChannel channel1;
    private final PulseChannel channel2;
    private final WaveChannel channel3;
    private final NoiseChannel channel4;

    // ========================================================================
    // CONTROLE DE VOLUME E STATUS
    // ========================================================================
    private int nr50_master_volume = 0x77;  // NR50 (FF24): Master volume & VIN
    private int nr51_panning = 0xFF;        // NR51 (FF25): Sound panning (estéreo)
    // NR52 (FF26) é calculado dinamicamente
    
    
    final byte[] audioRegisters = new byte[0x30];
    final byte[] wavePatternRam = new byte[16];

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
    
    public void setEmulatorSoundGloballyEnabled(boolean enabled) {
        this.emulatorSoundGloballyEnabled = enabled;
        if (!enabled && javaSoundInitialized && sourceDataLine != null) {
            sourceDataLine.flush();
            System.out.println("APU: Sound " + (enabled ? "ENABLED" : "DISABLED"));
        }
    }
    
    /**
     * Habilita/desabilita o modo de debug de timing da APU.
     * Quando habilitado, mostra estatísticas de sincronização a cada segundo.
     * @param enabled true para habilitar debug, false para desabilitar
     */
    public void setDebugTiming(boolean enabled) {
        this.debugTiming = enabled;
        if (enabled) {
            System.out.println("APU: Debug timing ENABLED - Statistics will be printed every second");
        } else {
            System.out.println("APU: Debug timing DISABLED");
        }
    }
    
    /**
     * Retorna estatísticas de timing da APU para debugging.
     * @return String com estatísticas formatadas
     */
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
        
        // Reset dos contadores de timing precisos
        apuTotalCycles = 0;
        frameSequencerCycleCounter = 0;
        frameSequencerStep = 0;
        samplePhaseAccumulator = 0;
        internalBufferPos = 0;
        
        // Reset de estatísticas de debug
        samplesGenerated = 0;
        frameSequencerTicks = 0;
        
        // Reseta high-pass filter
        highpassLeft = 0.0f;
        highpassRight = 0.0f;
        
        // Reseta low-pass filter
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

    /**
     * Atualiza a APU com base nos T-cycles da CPU.
     * 
     * Este método implementa sincronização precisa em T-cycles:
     * 
     * 1. Frame Sequencer (512 Hz):
     *    - Executa exatamente a cada 8192 T-cycles (4194304 / 512)
     *    - Controla Length Counter, Sweep e Envelope dos canais
     * 
     * 2. Channel Timers:
     *    - Cada canal atualiza seus timers de frequência em T-cycles
     *    - Pulse: (2048 - freq) * 4 T-cycles por step
     *    - Wave: (2048 - freq) * 2 T-cycles por step
     *    - Noise: divisor << shift T-cycles por step
     * 
     * 3. Sample Generation (Phase Accumulator):
     *    - Usa técnica de phase accumulator para gerar amostras em 48 kHz
     *    - Não há drift acumulativo ao longo do tempo
     *    - Fixed-point 16.16 garante precisão sub-sample
     * 
     * @param cpuCycles Número de T-cycles da CPU desde a última atualização
     */
    public void update(int cpuCycles) {
        if (!emulatorSoundGloballyEnabled || !javaSoundInitialized) {
            return;
        }

        // Incrementa o contador total de ciclos da APU (para debugging/stats)
        apuTotalCycles += cpuCycles;

        // ========================================================================
        // FRAME SEQUENCER - Timing preciso em T-cycles (512 Hz = 8192 T-cycles)
        // ========================================================================
        frameSequencerCycleCounter += cpuCycles;
        while (frameSequencerCycleCounter >= FRAME_SEQUENCER_PERIOD_TCYCLES) {
            frameSequencerCycleCounter -= FRAME_SEQUENCER_PERIOD_TCYCLES;
            if (masterSoundEnable) {
                clockFrameSequencer();
                frameSequencerTicks++;
                
                if (debugTiming && (frameSequencerTicks % 512 == 0)) {
                    // A cada segundo (512 Hz * 1 sec), mostra estatísticas
                    System.out.println(String.format(
                        "APU Timing Stats - Total Cycles: %d, Samples Generated: %d, Expected: %d, Drift: %d",
                        apuTotalCycles, samplesGenerated, 
                        (apuTotalCycles * SAMPLE_RATE / CPU_CLOCK_SPEED),
                        samplesGenerated - (apuTotalCycles * SAMPLE_RATE / CPU_CLOCK_SPEED)
                    ));
                }
            }
        }

        // ========================================================================
        // ATUALIZAÇÃO DOS CANAIS - Cada canal processa seus timers em T-cycles
        // ========================================================================
        if (masterSoundEnable) {
            channel1.step(cpuCycles);
            channel2.step(cpuCycles);
            channel3.step(cpuCycles);
            channel4.step(cpuCycles);
        }

        // ========================================================================
        // GERAÇÃO DE AMOSTRAS - Phase Accumulator (técnica robusta e precisa)
        // ========================================================================
        // Incrementamos o phase accumulator baseado nos T-cycles recebidos
        // SAMPLE_PHASE_INCREMENT é um valor fixed-point 16.16
        // Bits superiores representam o número de amostras a gerar
        samplePhaseAccumulator += cpuCycles * SAMPLE_PHASE_INCREMENT;
        
        // Extraímos a parte inteira (número de amostras a gerar)
        int samplesToGenerate = (int)(samplePhaseAccumulator >> 16);
        
        // Mantemos apenas a parte fracionária no acumulador
        samplePhaseAccumulator &= 0xFFFF;
        
        // Geramos as amostras necessárias
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

    /**
     * Leitura de registradores APU com comportamento preciso baseado no Pan Docs.
     * 
     * Comportamento geral:
     * - Bits não usados retornam 1
     * - Bits write-only retornam 1
     * - Registradores write-only retornam $FF
     * - NR52 retorna o status dos canais dinamicamente
     * 
     * @param address Endereço do registrador ($FF10-$FF3F)
     * @return Valor lido do registrador
     */
    public byte readRegister(int address) {
        int regIndex = address - 0xFF10;

        if (address >= 0xFF10 && address <= 0xFF26) {
            switch (address) {
                // ========== Channel 1 (Pulse with Sweep) ==========
                
                // NR10 - Channel 1 Sweep ($FF10)
                // Bit 7: Unused (returns 1)
                // Bits 6-4: Sweep pace
                // Bit 3: Sweep direction
                // Bits 2-0: Sweep step
                case 0xFF10: return (byte)(audioRegisters[regIndex] | 0x80);
                
                // NR11 - Channel 1 Length timer & Duty cycle ($FF11)
                // Bits 7-6: Wave duty
                // Bits 5-0: Initial length timer (write-only, returns 1)
                case 0xFF11: return (byte)(audioRegisters[regIndex] | 0x3F);
                
                // NR12 - Channel 1 Volume & Envelope ($FF12)
                // Bits 7-4: Initial volume
                // Bit 3: Envelope direction
                // Bits 2-0: Envelope pace
                case 0xFF12: return (byte)(audioRegisters[regIndex] & 0xFF);
                
                // NR13 - Channel 1 Period low ($FF13)
                // Write-only (returns $FF)
                case 0xFF13: return (byte)0xFF;
                
                // NR14 - Channel 1 Period high & Control ($FF14)
                // Bit 7: Trigger (write-only, returns 1)
                // Bit 6: Length enable
                // Bits 5-3: Unused (returns 1)
                // Bits 2-0: Period high (write-only, returns 1)
                case 0xFF14: return (byte)(audioRegisters[regIndex] | 0xBF);

                // ========== Channel 2 (Pulse) ==========
                
                // NR21 - Channel 2 Length timer & Duty cycle ($FF16)
                // Bits 7-6: Wave duty
                // Bits 5-0: Initial length timer (write-only, returns 1)
                case 0xFF16: return (byte)(audioRegisters[regIndex] | 0x3F);
                
                // NR22 - Channel 2 Volume & Envelope ($FF17)
                // Bits 7-4: Initial volume
                // Bit 3: Envelope direction
                // Bits 2-0: Envelope pace
                case 0xFF17: return (byte)(audioRegisters[regIndex] & 0xFF);
                
                // NR23 - Channel 2 Period low ($FF18)
                // Write-only (returns $FF)
                case 0xFF18: return (byte)0xFF;
                
                // NR24 - Channel 2 Period high & Control ($FF19)
                // Bit 7: Trigger (write-only, returns 1)
                // Bit 6: Length enable
                // Bits 5-3: Unused (returns 1)
                // Bits 2-0: Period high (write-only, returns 1)
                case 0xFF19: return (byte)(audioRegisters[regIndex] | 0xBF);

                // ========== Channel 3 (Wave) ==========
                
                // NR30 - Channel 3 DAC enable ($FF1A)
                // Bit 7: DAC power
                // Bits 6-0: Unused (returns 1)
                case 0xFF1A: return (byte)(audioRegisters[regIndex] | 0x7F);
                
                // NR31 - Channel 3 Length timer ($FF1B)
                // Write-only (returns $FF)
                case 0xFF1B: return (byte)0xFF;
                
                // NR32 - Channel 3 Output level ($FF1C)
                // Bit 7: Unused (returns 1)
                // Bits 6-5: Output level
                // Bits 4-0: Unused (returns 1)
                case 0xFF1C: return (byte)(audioRegisters[regIndex] | 0x9F);
                
                // NR33 - Channel 3 Period low ($FF1D)
                // Write-only (returns $FF)
                case 0xFF1D: return (byte)0xFF;
                
                // NR34 - Channel 3 Period high & Control ($FF1E)
                // Bit 7: Trigger (write-only, returns 1)
                // Bit 6: Length enable
                // Bits 5-3: Unused (returns 1)
                // Bits 2-0: Period high (write-only, returns 1)
                case 0xFF1E: return (byte)(audioRegisters[regIndex] | 0xBF);

                // ========== Channel 4 (Noise) ==========
                
                // NR41 - Channel 4 Length timer ($FF20)
                // Write-only (returns $FF)
                case 0xFF20: return (byte)0xFF;
                
                // NR42 - Channel 4 Volume & Envelope ($FF21)
                // Bits 7-4: Initial volume
                // Bit 3: Envelope direction
                // Bits 2-0: Envelope pace
                case 0xFF21: return (byte)(audioRegisters[regIndex] & 0xFF);
                
                // NR43 - Channel 4 Frequency & Randomness ($FF22)
                // Bits 7-4: Clock shift
                // Bit 3: LFSR width
                // Bits 2-0: Clock divider
                case 0xFF22: return (byte)(audioRegisters[regIndex] & 0xFF);
                
                // NR44 - Channel 4 Control ($FF23)
                // Bit 7: Trigger (write-only, returns 1)
                // Bit 6: Length enable
                // Bits 5-0: Unused (returns 1)
                case 0xFF23: return (byte)(audioRegisters[regIndex] | 0xBF);

                // ========== Master Control ==========
                
                // NR50 - Master volume & VIN panning ($FF24)
                // Bit 7: Mix VIN left on/off
                // Bits 6-4: Left volume
                // Bit 3: Mix VIN right on/off
                // Bits 2-0: Right volume
                case 0xFF24: return (byte)nr50_master_volume;
                
                // NR51 - Sound panning ($FF25)
                // Bits 7-4: Channel to left output
                // Bits 3-0: Channel to right output
                case 0xFF25: return (byte)nr51_panning;
                
                // NR52 - Audio master control ($FF26)
                // Bit 7: Audio master enable
                // Bits 6-4: Unused (always return 1)
                // Bit 3: Channel 4 ON flag (read-only)
                // Bit 2: Channel 3 ON flag (read-only)
                // Bit 1: Channel 2 ON flag (read-only)
                // Bit 0: Channel 1 ON flag (read-only)
                case 0xFF26:
                    int status = masterSoundEnable ? 0x80 : 0x00;
                    if (channel1.isActive()) status |= 0x01;
                    if (channel2.isActive()) status |= 0x02;
                    if (channel3.isActive()) status |= 0x04;
                    if (channel4.isActive()) status |= 0x08;
                    status |= 0x70; // Bits 4-6 always 1
                    return (byte) status;

                // Registradores não usados ($FF15, $FF1F, $FF27-$FF2F)
                // Retornam $FF
                default:
                    return (byte)0xFF;
            }
        } else if (address >= 0xFF30 && address <= 0xFF3F) {
            // Wave RAM ($FF30-$FF3F)
            // Pode ser lida mesmo quando o som está desabilitado
            // Mas quando o canal 3 está tocando, o comportamento pode ser diferente
            // (no hardware real, retorna o byte que está sendo acessado pelo canal)
            return wavePatternRam[address - 0xFF30];
        }
        
        return (byte) 0xFF;
    }

    /**
     * Escrita de registradores APU com comportamento preciso baseado no Pan Docs.
     * 
     * Comportamento especial:
     * - Quando NR52 bit 7 = 0 (master disable), apenas NR52 e length timers podem ser escritos
     * - Desabilitar NR52 zera todos os registradores de $FF10-$FF25
     * - Wave RAM pode ser acessada mesmo com som desabilitado
     * 
     * @param address Endereço do registrador ($FF10-$FF3F)
     * @param value Valor a escrever
     */
    public void writeRegister(int address, byte value) {
        // Tratamento especial para NR52 - sempre pode ser escrito
        if (address == 0xFF26) {
            boolean newMasterEnable = (value & 0x80) != 0;
            
            // Transição de ON -> OFF: zera todos os registradores e estados
            if (masterSoundEnable && !newMasterEnable) {
                // Zera todos os registradores de áudio exceto NR52
                for (int i = 0xFF10; i <= 0xFF25; i++) {
                    audioRegisters[i - 0xFF10] = 0;
                }
                
                // Zera os registradores de controle de volume e panning
                nr50_master_volume = 0;
                nr51_panning = 0;
                
                // Reseta completamente os canais
                channel1.resetOnAPUDisable();
                channel2.resetOnAPUDisable();
                channel3.resetOnAPUDisable();
                channel4.resetOnAPUDisable();
                
                // Reseta o frame sequencer
                frameSequencerStep = 0;
                frameSequencerCycleCounter = 0;
                
                System.out.println("APU: Master sound DISABLED - All registers cleared");
            } 
            // Transição de OFF -> ON: reinicia o frame sequencer
            else if (!masterSoundEnable && newMasterEnable) {
                frameSequencerStep = 0;
                frameSequencerCycleCounter = 0;
                System.out.println("APU: Master sound ENABLED");
            }
            
            masterSoundEnable = newMasterEnable;
            // NR52 só armazena o bit 7 (master enable), os outros bits são read-only
            audioRegisters[address - 0xFF10] = (byte)(value & 0x80);
            return;
        }
        
        // Wave RAM pode ser escrita mesmo com som desabilitado
        if (address >= 0xFF30 && address <= 0xFF3F) {
            wavePatternRam[address - 0xFF30] = value;
            return;
        }
        
        // Quando o master sound está desabilitado:
        // - Apenas length timers (NR11, NR21, NR31, NR41) podem ser escritos
        // - Outros registradores são ignorados
        if (!masterSoundEnable) {
            // Length timers podem ser escritos mesmo com som desabilitado
            // (apenas a parte do duty cycle/wave pattern, não o length em si)
            if (address == 0xFF11) {
                audioRegisters[address - 0xFF10] = value;
                // Mas não afeta o canal
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
                // Todos os outros registradores são ignorados
                return;
            }
        }
        
        // Som está habilitado - processa normalmente
        if (address >= 0xFF10 && address <= 0xFF2F) {
            audioRegisters[address - 0xFF10] = value;
        }

        switch (address) {
            // ========== Channel 1 (Pulse with Sweep) ==========
            case 0xFF10: channel1.writeNRX0(value); break;
            case 0xFF11: channel1.writeNRX1(value); break;
            case 0xFF12: channel1.writeNRX2(value); break;
            case 0xFF13: channel1.writeNRX3(value); break;
            case 0xFF14: channel1.writeNRX4(value); break;

            // ========== Channel 2 (Pulse) ==========
            case 0xFF16: channel2.writeNRX1(value); break;
            case 0xFF17: channel2.writeNRX2(value); break;
            case 0xFF18: channel2.writeNRX3(value); break;
            case 0xFF19: channel2.writeNRX4(value); break;

            // ========== Channel 3 (Wave) ==========
            case 0xFF1A: channel3.writeNR30(value); break;
            case 0xFF1B: channel3.writeNR31(value); break;
            case 0xFF1C: channel3.writeNR32(value); break;
            case 0xFF1D: channel3.writeNR33(value); break;
            case 0xFF1E: channel3.writeNR34(value); break;

            // ========== Channel 4 (Noise) ==========
            case 0xFF20: channel4.writeNR41(value); break;
            case 0xFF21: channel4.writeNR42(value); break;
            case 0xFF22: channel4.writeNR43(value); break;
            case 0xFF23: channel4.writeNR44(value); break;

            // ========== Master Control ==========
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
            case 2: 
                channel1.clockLength();
                channel1.clockSweep();
                channel2.clockLength();
                channel3.clockLength();
                channel4.clockLength();
                break;
            case 4: 
                channel1.clockLength();
                channel2.clockLength();
                channel3.clockLength();
                channel4.clockLength();
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

    /**
     * Gera uma amostra de áudio mixando os 4 canais e aplica ao buffer interno.
     * 
     * Processo de mixagem (baseado no hardware real):
     * 
     * 1. Coleta amostras de cada canal (valores 0-15)
     * 2. Aplica panning (NR51):
     *    - Bit 0: Canal 1 para direita
     *    - Bit 1: Canal 2 para direita
     *    - Bit 2: Canal 3 para direita
     *    - Bit 3: Canal 4 para direita
     *    - Bit 4: Canal 1 para esquerda
     *    - Bit 5: Canal 2 para esquerda
     *    - Bit 6: Canal 3 para esquerda
     *    - Bit 7: Canal 4 para esquerda
     * 
     * 3. Aplica volume mestre (NR50):
     *    - Bits 6-4: Volume esquerdo (0-7)
     *    - Bits 2-0: Volume direito (0-7)
     * 
     * 4. Normaliza e aplica filtros
     */
    private void generateAndBufferSample() {
        float leftOut = 0.0f;
        float rightOut = 0.0f;

        if (!masterSoundEnable) {
            // Som desabilitado - saída em silêncio (valor médio do DAC)
            leftOut = 0.0f;
            rightOut = 0.0f;
        } else {
            // ========== 1. COLETA DE AMOSTRAS DOS CANAIS ==========
            // Cada canal retorna um valor de 0.0 a 15.0
            // (ou 7.5 se desabilitado, representando o valor médio do DAC)
            float ch1 = channel1.getOutputSample();
            float ch2 = channel2.getOutputSample();
            float ch3 = channel3.getOutputSample();
            float ch4 = channel4.getOutputSample();

            // ========== 2. PANNING (NR51 - $FF25) ==========
            // Cada canal pode ser roteado independentemente para left/right
            // Bits 0-3: Canais para saída direita
            // Bits 4-7: Canais para saída esquerda
            
            // Canal 1
            if ((nr51_panning & 0x01) != 0) rightOut += ch1;
            if ((nr51_panning & 0x10) != 0) leftOut += ch1;
            
            // Canal 2
            if ((nr51_panning & 0x02) != 0) rightOut += ch2;
            if ((nr51_panning & 0x20) != 0) leftOut += ch2;
            
            // Canal 3
            if ((nr51_panning & 0x04) != 0) rightOut += ch3;
            if ((nr51_panning & 0x40) != 0) leftOut += ch3;
            
            // Canal 4
            if ((nr51_panning & 0x08) != 0) rightOut += ch4;
            if ((nr51_panning & 0x80) != 0) leftOut += ch4;

            // ========== 3. VOLUME MESTRE (NR50 - $FF24) ==========
            // Bits 6-4: SO2 (Left) volume (0-7)
            // Bits 2-0: SO1 (Right) volume (0-7)
            // Volume 7 = 100%, Volume 0 = ~14% (não é completamente silencioso)
            
            int leftVolume = (nr50_master_volume >> 4) & 0x07;
            int rightVolume = nr50_master_volume & 0x07;
            
            // Conversão: volume 0-7 para multiplicador
            // No hardware real, volume 0 ainda deixa passar ~14% do sinal
            // Aqui usamos uma escala linear de (volume+1)/8 para simplicidade
            float leftVol = (leftVolume + 1) / 8.0f;
            float rightVol = (rightVolume + 1) / 8.0f;
            
            leftOut *= leftVol;
            rightOut *= rightVol;

            // ========== 4. NORMALIZAÇÃO ==========
            // Cada canal pode contribuir 0-15, então máximo teórico é 4 * 15 = 60
            // Com volume mestre, máximo real é 60 * (8/8) = 60
            // Normalizamos para o range -1.0 a +1.0
            // 
            // O valor médio do DAC é 7.5, então centramos em torno disso:
            // - Subtraímos 7.5 * número_de_canais_ativos de cada saída
            // - Dividimos por um fator de normalização
            
            // Para simplificar, usamos uma normalização fixa que funciona bem:
            // Dividimos por 60.0 e depois subtraímos o DC offset de cada canal
            float dcOffset = 7.5f * 4.0f; // Offset DC de todos os 4 canais
            
            leftOut = (leftOut - dcOffset * leftVol) / 60.0f;
            rightOut = (rightOut - dcOffset * rightVol) / 60.0f;

            // ========== 5. HIGH-PASS FILTER ==========
            // Remove o DC bias residual
            leftOut = applyHighPass(leftOut, true);
            rightOut = applyHighPass(rightOut, false);
            
            // ========== 6. LOW-PASS FILTER ==========
            // Suaviza o áudio e reduz aliasing para som mais autêntico
            leftOut = applyLowPass(leftOut, true);
            rightOut = applyLowPass(rightOut, false);
            
            // ========== 7. SOFT CLIPPING ==========
            // Aplica soft clipping para evitar distorção severa em picos
            leftOut = softClip(leftOut);
            rightOut = softClip(rightOut);
        }

        // Armazena no buffer interno
        internalSampleBuffer[internalBufferPos * 2] = leftOut;
        internalSampleBuffer[internalBufferPos * 2 + 1] = rightOut;
        
        internalBufferPos++;
        if (internalBufferPos >= INTERNAL_SAMPLE_BUFFER_SIZE) {
            flushInternalBufferToSoundCard();
        }
    }
    
    /**
     * Aplica soft clipping usando tanh para evitar distorção severa.
     * Isso dá um som mais "macio" quando há clipping, similar ao hardware analógico.
     * 
     * @param sample Amostra de entrada
     * @return Amostra com soft clipping aplicado
     */
    private float softClip(float sample) {
        // Soft clipping usando tanh
        // tanh limita naturalmente o sinal entre -1 e 1
        // Multiplicamos por 1.5 antes para dar mais "headroom"
        return (float) Math.tanh(sample * 1.5);
    }

    /**
     * Aplica high-pass filter de 1ª ordem para remover DC bias.
     * Isso evita que offset DC se acumule e cause distorção.
     * 
     * @param sample Amostra de entrada
     * @param isLeft true para canal esquerdo, false para direito
     * @return Amostra filtrada
     */
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
    
    /**
     * Aplica low-pass filter de 1ª ordem para suavizar o áudio.
     * Isso reduz aliasing e dá um som mais autêntico similar ao hardware original.
     * 
     * Fórmula: output = output_prev + alpha * (input - output_prev)
     * onde alpha determina a frequência de corte
     * 
     * @param sample Amostra de entrada
     * @param isLeft true para canal esquerdo, false para direito
     * @return Amostra filtrada
     */
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

    /**
     * Flush do buffer interno para a placa de som.
     * Converte float samples para 16-bit PCM com proteção contra clipping.
     */
    private void flushInternalBufferToSoundCard() {
        if (!javaSoundInitialized || internalBufferPos == 0) return;

        // Converter float para 16-bit PCM
        for (int i = 0; i < internalBufferPos * 2; i++) {
            float sample = internalSampleBuffer[i];
            
            // Hard clipping como última proteção
            // O soft clipping já deve ter lidado com a maioria dos casos
            if (sample > 1.0f) sample = 1.0f;
            if (sample < -1.0f) sample = -1.0f;
            
            // Converter para 16-bit signed PCM
            // Multiplicamos por 32767 (max value para signed 16-bit)
            // e arredondamos para o inteiro mais próximo
            short pcmSample = (short) (sample * 32767.0f);
            
            // Little-endian byte order (padrão do PC)
            outputByteBuffer[i * 2] = (byte) (pcmSample & 0xFF);
            outputByteBuffer[i * 2 + 1] = (byte) ((pcmSample >> 8) & 0xFF);
        }

        int bytesToWrite = internalBufferPos * 2 * (SAMPLE_SIZE_BITS / 8);
        
        // Verifica se há espaço suficiente no buffer antes de escrever
        // Isso evita bloqueios que causam travamentos
        int available = sourceDataLine.available();
        if (available < bytesToWrite) {
            // Se o buffer está muito cheio, não escreve para evitar bloqueio
            // Isso pode acontecer se a emulação está rodando mais rápido que o áudio
            // (é melhor perder algumas amostras do que travar o emulador)
            internalBufferPos = 0;
            return;
        }
        
        // Escreve para a placa de som
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

        /**
         * Clock do Length Counter - executado pelo Frame Sequencer nos steps 0, 2, 4, 6
         * Quando o length counter chega a 0, desabilita o canal
         */
        public void clockLength() {
            if (lengthEnabled && lengthCounter > 0) {
                lengthCounter--;
                if (lengthCounter == 0) {
                    enabled = false;
                }
            }
        }

        /**
         * Clock do Volume Envelope - executado pelo Frame Sequencer no step 7
         * Comportamento preciso:
         * - Se o período é 0, o envelope não funciona
         * - Contador decrementa a cada clock
         * - Quando chega a 0, recarrega com o período e ajusta o volume
         * - Volume não pode exceder 15 ou ir abaixo de 0
         */
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

        /**
         * Carrega o Length Counter com base no valor escrito no registrador
         * @param lengthData Valor escrito (número de passos já contados)
         * @param maxLength Tamanho máximo do contador (64 para Pulse/Noise, 256 para Wave)
         */
        protected void loadLength(int lengthData, int maxLength) {
            lengthCounter = maxLength - lengthData;
        }

        /**
         * Carrega os parâmetros do Volume Envelope do registrador NRx2
         * @param nrx2 Valor do registrador NRx2
         */
        protected void loadEnvelope(byte nrx2) {
            initialVolume = (nrx2 >> 4) & 0x0F;
            envelopeIncrease = (nrx2 & 0x08) != 0;
            envelopePeriod = nrx2 & 0x07;
        }

        /**
         * Verifica se o DAC está habilitado baseado no registrador NRx2
         * DAC está habilitado se os bits superiores (volume inicial) não são todos 0
         * @param nrx2 Valor do registrador NRx2
         * @return true se o DAC está habilitado
         */
        protected boolean checkDACEnabled(byte nrx2) {
            return (nrx2 & 0xF8) != 0;
        }
    }
    /**
     * Pulse Channel (Canal 1 e 2)
     * 
     * Características:
     * - Canal 1 tem Sweep Unit, Canal 2 não tem
     * - Frequência: (2048 - frequency) * 4 T-cycles por step
     * - Duty Cycle: 4 padrões (12.5%, 25%, 50%, 75%)
     * - Volume Envelope: 0-15, com período de 1-7 steps (64 Hz cada)
     * - Length Counter: 0-63 steps (256 Hz)
     * - Sweep (Canal 1): Modifica frequência automaticamente
     */
    static class PulseChannel extends SoundChannel {
        private final boolean hasSweep;
        
        // Frequency Generator
        private int frequencyValue;    // 11-bit (0-2047) - valor da frequência
        private int frequencyTimer;    // Timer em T-cycles
        
        // Duty Cycle Generator
        private int dutyPattern;       // 0-3 (seletor de padrão)
        private int dutyStep;          // 0-7 (posição atual no padrão)
        
        // Padrões de Duty Cycle (8 steps cada)
        private static final byte[][] DUTY_WAVEFORMS = {
            {0, 0, 0, 0, 0, 0, 0, 1}, // 12.5% - _______-
            {1, 0, 0, 0, 0, 0, 0, 1}, // 25%   - -______-
            {1, 0, 0, 0, 0, 1, 1, 1}, // 50%   - -____---
            {0, 1, 1, 1, 1, 1, 1, 0}  // 75%   - _------_ (invertido de 25%)
        };
        
        // Sweep Unit (apenas Canal 1)
        private int sweepPeriod;       // 0-7 (período do sweep em steps de 128 Hz)
        private boolean sweepDecrease; // Direção: false = increase, true = decrease
        private int sweepShift;        // 0-7 (shift amount para cálculo)
        private int sweepCounter;      // Contador interno do sweep
        private int shadowFrequency;   // Cópia shadow da frequência para sweep
        private boolean sweepEnabled;  // Se o sweep está ativo
        private boolean sweepNegateUsed; // Se modo negate já foi usado (importante para comportamento correto)

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

        public void writeNRX0(byte val) { // NR10 (Canal 1 apenas)
            if (!hasSweep) return;
            
            sweepPeriod = (val >> 4) & 0x07;
            boolean newDecrease = (val & 0x08) != 0;
            sweepShift = val & 0x07;
            
            // Comportamento especial: se o sweep estava em modo negate e muda para addition,
            // e negate foi usado, desabilita o canal
            if (sweepNegateUsed && !newDecrease) {
                enabled = false;
            }
            
            sweepDecrease = newDecrease;
        }

        public void writeNRX1(byte val) { // NR11 ou NR21
            loadLength(val & 0x3F, 64);
            dutyPattern = (val >> 6) & 0x03;
        }

        public void writeNRX2(byte val) { // NR12 ou NR22
            loadEnvelope(val);
            dacEnabled = checkDACEnabled(val);
            
            // Se o DAC é desabilitado, o canal é desabilitado imediatamente
            if (!dacEnabled) {
                enabled = false;
            }
        }

        public void writeNRX3(byte val) { // NR13 ou NR23
            frequencyValue = (frequencyValue & 0x0700) | (val & 0xFF);
        }

        public void writeNRX4(byte val) { // NR14 ou NR24
            frequencyValue = (frequencyValue & 0x00FF) | ((val & 0x07) << 8);
            lengthEnabled = (val & 0x40) != 0;
            
            // Trigger bit
            if ((val & 0x80) != 0) {
                trigger();
            }
            
            // Comportamento especial do length: se length está sendo habilitado
            // e o próximo step do frame sequencer NÃO é um step de length,
            // decrementa o length counter extra
            // (Este é um comportamento obscuro do hardware real)
        }

        @Override
        public void trigger() {
            // Se o DAC está desabilitado, o canal não pode ser habilitado
            if (!dacEnabled) {
                return;
            }
            
            enabled = true;
            
            // Se o length counter é 0, recarrega com o valor máximo
            if (lengthCounter == 0) {
                lengthCounter = 64;
                
                // Comportamento especial: se length está habilitado e vamos
                // clock length no próximo step, decrementa um extra
                // (Este é um edge case do hardware)
            }
            
            // Recarrega o frequency timer
            // Período = (2048 - frequency) * 4 T-cycles
            frequencyTimer = (2048 - frequencyValue) * 4;
            
            // Reinicia o envelope
            volume = initialVolume;
            envelopeCounter = envelopePeriod > 0 ? envelopePeriod : 8;
            
            // Sweep (apenas Canal 1)
            if (hasSweep) {
                shadowFrequency = frequencyValue;
                sweepCounter = sweepPeriod > 0 ? sweepPeriod : 8;
                sweepEnabled = (sweepPeriod > 0 || sweepShift > 0);
                sweepNegateUsed = false;
                
                // Faz o cálculo de overflow check imediatamente
                if (sweepShift > 0) {
                    calculateSweepFrequency(true);
                }
            }
        }

        /**
         * Clock do Sweep - executado pelo Frame Sequencer nos steps 2 e 6 (128 Hz)
         * Apenas para o Canal 1
         */
        public void clockSweep() {
            if (!hasSweep || !sweepEnabled) return;
            
            sweepCounter--;
            if (sweepCounter <= 0) {
                // Recarrega o contador (se período é 0, usa 8)
                sweepCounter = sweepPeriod > 0 ? sweepPeriod : 8;
                
                // Aplica o sweep se está habilitado e o período não é 0
                if (sweepEnabled && sweepPeriod > 0) {
                    int newFreq = calculateSweepFrequency(false);
                    
                    if (newFreq <= 2047 && sweepShift > 0) {
                        // Atualiza a frequência
                        frequencyValue = newFreq;
                        shadowFrequency = newFreq;
                        
                        // Faz overflow check novamente
                        calculateSweepFrequency(true);
                    }
                }
            }
        }

        /**
         * Calcula a nova frequência do sweep
         * @param checkOnly Se true, apenas verifica overflow sem atualizar
         * @return Nova frequência (ou -1 se overflow)
         */
        private int calculateSweepFrequency(boolean checkOnly) {
            int delta = shadowFrequency >> sweepShift;
            int newFreq;
            
            if (sweepDecrease) {
                newFreq = shadowFrequency - delta;
                sweepNegateUsed = true;
            } else {
                newFreq = shadowFrequency + delta;
            }
            
            // Overflow check
            if (newFreq > 2047) {
                enabled = false;
                return -1;
            }
            
            return newFreq;
        }

        /**
         * Step do canal - atualiza o frequency timer
         * @param cycles Número de T-cycles desde o último step
         */
        @Override
        public void step(int cycles) {
            if (!enabled) return;
            
            // Decrementa o frequency timer
            frequencyTimer -= cycles;
            
            // Quando o timer chega a 0 ou menos, avança o duty step
            while (frequencyTimer <= 0) {
                frequencyTimer += (2048 - frequencyValue) * 4;
                dutyStep = (dutyStep + 1) % 8;
            }
        }

        @Override
        public float getOutputSample() {
            // Se o canal não está habilitado ou o DAC está desabilitado,
            // retorna o valor médio do DAC (7.5)
            if (!enabled || !dacEnabled) {
                return 7.5f;
            }
            
            // Lê o amplitude do duty cycle atual
            int amplitude = DUTY_WAVEFORMS[dutyPattern][dutyStep];
            
            // Multiplica pelo volume (0-15)
            return amplitude * volume;
        }
    }

    /**
     * Wave Channel (Canal 3)
     * 
     * Características:
     * - 32 samples de 4-bit armazenados na Wave RAM ($FF30-$FF3F)
     * - Frequência: (2048 - frequency) * 2 T-cycles por step
     * - Volume Shift: 0 = mute, 1 = 100%, 2 = 50%, 3 = 25%
     * - Length Counter: 0-255 steps (256 Hz)
     * - DAC habilitado via NR30 bit 7
     * - Comportamento complexo ao escrever na Wave RAM enquanto toca
     */
    static class WaveChannel extends SoundChannel {
        private int frequencyValue;    // 11-bit (0-2047)
        private int frequencyTimer;    // Timer em T-cycles
        private int wavePosition;      // 0-31 (posição atual nos 32 samples)
        private int volumeShift;       // 0-3 (código de shift de volume)
        private int sampleBuffer;      // Buffer do último sample lido

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
            
            // Se o DAC é desabilitado, o canal é desabilitado imediatamente
            if (!dacEnabled) {
                enabled = false;
            }
        }

        public void writeNR31(byte val) { // NR31
            // Length = 256 - val
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
            
            // Trigger bit
            if ((val & 0x80) != 0) {
                trigger();
            }
        }

        @Override
        public void trigger() {
            // Se o DAC está desabilitado, o canal não pode ser habilitado
            if (!dacEnabled) {
                return;
            }
            
            enabled = true;
            
            // Se o length counter é 0, recarrega com o valor máximo
            if (lengthCounter == 0) {
                lengthCounter = 256;
            }
            
            // Reinicia a posição da wave
            wavePosition = 0;
            
            // Recarrega o frequency timer
            // Período = (2048 - frequency) * 2 T-cycles
            frequencyTimer = (2048 - frequencyValue) * 2;
            
            // Lê o primeiro sample imediatamente
            readSampleToBuffer();
        }

        /**
         * Step do canal - atualiza o frequency timer e lê samples da Wave RAM
         * @param cycles Número de T-cycles desde o último step
         */
        @Override
        public void step(int cycles) {
            if (!enabled) return;
            
            // Decrementa o frequency timer
            frequencyTimer -= cycles;
            
            // Quando o timer chega a 0 ou menos, avança para o próximo sample
            while (frequencyTimer <= 0) {
                frequencyTimer += (2048 - frequencyValue) * 2;
                
                // Avança para o próximo sample (0-31)
                wavePosition = (wavePosition + 1) % 32;
                
                // Lê o sample da Wave RAM e armazena no buffer
                // Isso simula o comportamento do hardware que lê da RAM
                readSampleToBuffer();
            }
        }

        /**
         * Lê o sample atual da Wave RAM para o buffer
         * Este método simula o comportamento do hardware real que
         * lê a Wave RAM em cada step do gerador de frequência
         */
        private void readSampleToBuffer() {
            // Cada byte da Wave RAM contém 2 samples de 4-bit
            // Positions 0-1 estão no byte 0, 2-3 no byte 1, etc.
            byte waveByte = apu.getWaveRamByte(wavePosition / 2);
            
            // Extrai o nibble correto (high ou low)
            if ((wavePosition % 2) == 0) {
                // Posição par: usa o nibble alto (bits 4-7)
                sampleBuffer = (waveByte >> 4) & 0x0F;
            } else {
                // Posição ímpar: usa o nibble baixo (bits 0-3)
                sampleBuffer = waveByte & 0x0F;
            }
        }

        @Override
        public float getOutputSample() {
            // Se o canal não está habilitado ou o DAC está desabilitado,
            // retorna o valor médio do DAC (7.5)
            if (!enabled || !dacEnabled) {
                return 7.5f;
            }
            
            // Aplica o volume shift ao sample
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
    /**
     * Noise Channel (Canal 4)
     * 
     * Características:
     * - Gerador de ruído usando LFSR (Linear Feedback Shift Register)
     * - LFSR de 15-bit (modo normal) ou 7-bit (modo width)
     * - Frequência determinada por divisor e clock shift
     * - Volume Envelope: 0-15, com período de 1-7 steps
     * - Length Counter: 0-63 steps (256 Hz)
     * - DAC habilitado via NR42 (bits 3-7)
     */
    static class NoiseChannel extends SoundChannel {
        private int lfsr;              // Linear Feedback Shift Register (15-bit)
        private boolean lfsrWidth7;    // true = 7-bit mode, false = 15-bit mode
        private int clockShift;        // 0-15 (shift do clock)
        private int divisorCode;       // 0-7 (código do divisor)
        private int frequencyTimer;    // Timer em T-cycles
        
        // Tabela de divisores do LFSR
        // divisorCode 0 usa 8 (não 0, caso especial do hardware)
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
            
            lfsr = 0x7FFF;        // LFSR inicia com todos bits em 1
            lfsrWidth7 = false;
            clockShift = 0;
            divisorCode = 0;
            frequencyTimer = 0;
        }

        @Override
        void resetOnAPUDisable() {
            reset();
        }

        public void writeNR41(byte val) { // NR41
            // Length = 64 - (val & 0x3F)
            loadLength(val & 0x3F, 64);
        }

        public void writeNR42(byte val) { // NR42
            loadEnvelope(val);
            dacEnabled = checkDACEnabled(val);
            
            // Se o DAC é desabilitado, o canal é desabilitado imediatamente
            if (!dacEnabled) {
                enabled = false;
            }
        }

        public void writeNR43(byte val) { // NR43
            clockShift = (val >> 4) & 0x0F;
            lfsrWidth7 = (val & 0x08) != 0;
            divisorCode = val & 0x07;
            
            // Recalcula o frequency timer baseado nos novos parâmetros
            // Período = divisor << clockShift
            if (enabled) {
                frequencyTimer = DIVISORS[divisorCode] << clockShift;
            }
        }

        public void writeNR44(byte val) { // NR44
            lengthEnabled = (val & 0x40) != 0;
            
            // Trigger bit
            if ((val & 0x80) != 0) {
                trigger();
            }
        }

        @Override
        public void trigger() {
            // Se o DAC está desabilitado, o canal não pode ser habilitado
            if (!dacEnabled) {
                return;
            }
            
            enabled = true;
            
            // Se o length counter é 0, recarrega com o valor máximo
            if (lengthCounter == 0) {
                lengthCounter = 64;
            }
            
            // Reinicia o envelope
            volume = initialVolume;
            envelopeCounter = envelopePeriod > 0 ? envelopePeriod : 8;
            
            // Reinicia o LFSR com todos bits em 1
            lfsr = 0x7FFF;
            
            // Recarrega o frequency timer
            // Período = divisor << clockShift
            frequencyTimer = DIVISORS[divisorCode] << clockShift;
        }

        /**
         * Step do canal - atualiza o frequency timer e clock o LFSR
         * @param cycles Número de T-cycles desde o último step
         */
        @Override
        public void step(int cycles) {
            if (!enabled) return;
            
            // Decrementa o frequency timer
            frequencyTimer -= cycles;
            
            // Quando o timer chega a 0 ou menos, clock o LFSR
            while (frequencyTimer <= 0) {
                frequencyTimer += DIVISORS[divisorCode] << clockShift;
                
                // Clock do LFSR (Linear Feedback Shift Register)
                clockLFSR();
            }
        }

        /**
         * Clock do LFSR (Linear Feedback Shift Register)
         * 
         * Implementação precisa do LFSR do Game Boy:
         * 1. XOR dos bits 0 e 1
         * 2. Shift right de todo o registrador
         * 3. Coloca o resultado do XOR no bit 14
         * 4. Se modo 7-bit, também coloca o resultado no bit 6
         * 
         * No modo 15-bit: produz sequência pseudo-aleatória longa
         * No modo 7-bit: produz sequência pseudo-aleatória curta (som mais "metálico")
         */
        private void clockLFSR() {
            // Calcula o XOR dos bits 0 e 1 (feedback)
            int bit0 = lfsr & 1;
            int bit1 = (lfsr >> 1) & 1;
            int xorResult = bit0 ^ bit1;
            
            // Shift right de todo o registrador
            lfsr >>= 1;
            
            // Coloca o resultado do XOR no bit 14 (bit mais significativo)
            lfsr &= ~(1 << 14);           // Limpa o bit 14
            lfsr |= (xorResult << 14);     // Define o bit 14 com o resultado do XOR
            
            // Se modo 7-bit (width mode), também coloca o resultado no bit 6
            if (lfsrWidth7) {
                lfsr &= ~(1 << 6);         // Limpa o bit 6
                lfsr |= (xorResult << 6);   // Define o bit 6 com o resultado do XOR
            }
        }

        @Override
        public float getOutputSample() {
            // Se o canal não está habilitado ou o DAC está desabilitado,
            // retorna o valor médio do DAC (7.5)
            if (!enabled || !dacEnabled) {
                return 7.5f;
            }
            
            // O output é determinado pelo bit 0 do LFSR (invertido)
            // Bit 0 = 0 -> amplitude = 1 (high)
            // Bit 0 = 1 -> amplitude = 0 (low)
            int amplitude = (lfsr & 1) == 0 ? 1 : 0;
            
            // Multiplica pelo volume (0-15)
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
