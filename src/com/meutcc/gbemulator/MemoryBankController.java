package com.meutcc.gbemulator;

public interface MemoryBankController {
    int read(int address);
    void write(int address, byte value);
    int readRam(int address);
    void writeRam(int address, byte value);
    void update(int cycles);
}