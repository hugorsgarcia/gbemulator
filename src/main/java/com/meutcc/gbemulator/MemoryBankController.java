package com.meutcc.gbemulator;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public interface MemoryBankController {
    int read(int address);
    void write(int address, byte value);
    int readRam(int address);
    void writeRam(int address, byte value);
    void update(int cycles);
    byte[] getRamData(); // For save file persistence
    
    // Save/Load state support
    void saveState(DataOutputStream dos) throws IOException;
    void loadState(DataInputStream dis) throws IOException;
}