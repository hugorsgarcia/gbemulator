package com.meutcc.gbemulator;

/**
 * Interface que define o comportamento básico para todos os
 * Memory Bank Controllers (MBCs).
 */
public interface MemoryBankController {

    /**
     * Lê um byte da ROM do cartucho.
     * @param address O endereço de 16 bits de onde ler.
     * @return O byte lido da ROM mapeada.
     */
    int read(int address);

    /**
     * Escreve um byte em um registrador de controle do MBC.
     * @param address O endereço de 16 bits onde a escrita ocorreu.
     * @param value O valor de 8 bits a ser escrito.
     */
    void write(int address, byte value);

    /**
     * Lê um byte da RAM externa do cartucho, se houver.
     * @param address O endereço de 16 bits de onde ler (relativo ao início da RAM, ex: A000).
     * @return O byte lido da RAM mapeada.
     */
    int readRam(int address);

    /**
     * Escreve um byte na RAM externa do cartucho, se houver.
     * @param address O endereço de 16 bits onde escrever (relativo ao início da RAM, ex: A000).
     * @param value O valor de 8 bits a ser escrito.
     */
    void writeRam(int address, byte value);

    /**
     * Atualiza o estado interno do MBC que pode depender do tempo, como o RTC.
     * @param cycles O número de T-cycles da CPU que se passaram.
     */
    void update(int cycles);
}