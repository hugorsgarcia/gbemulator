package com.meutcc.gbemulator;


public class Serial {
    
    private int sb = 0x00;  
    private int sc = 0x7E; 
    
    private boolean transferInProgress = false;
    private int transferCyclesRemaining = 0;
    private int shiftRegister = 0;  // Registrador de deslocamento interno
    private int bitsTransferred = 0;
    
    private static final int TRANSFER_CYCLES = 4096;  // Ciclos para transferir 8 bits (512 ciclos por bit)
    private static final int TRANSFER_CYCLES_FAST = 256;  // Modo rápido CGB (não implementado ainda)
    
    private SerialDevice connectedDevice = null;
    
    private boolean externalClockMode = false;
    private int externalClockBitPosition = 0;
    public void reset() {
        sb = 0x00;
        sc = 0x7E;
        transferInProgress = false;
        transferCyclesRemaining = 0;
        shiftRegister = 0;
        bitsTransferred = 0;
        externalClockMode = false;
        externalClockBitPosition = 0;
        System.out.println("Serial reset.");
    }
    

    public boolean update(int cycles) {
        if (!transferInProgress) {
            return false;
        }
        
        transferCyclesRemaining -= cycles;
        
        if (transferCyclesRemaining <= 0) {
            completeTransfer();
            return true;
        }
        
        return false;
    }
    
  
    public int readSB() {
        return sb & 0xFF;
    }
    
 
    public void writeSB(int value) {
        sb = value & 0xFF;
    }
    
  
    public int readSC() {
        return (sc & 0x83) | 0x7C;
    }

    public void writeSC(int value) {
        sc = value & 0xFF;
        
        boolean internalClock = (sc & 0x01) != 0;
        boolean startTransfer = (sc & 0x80) != 0;
        
        if (startTransfer && internalClock && !transferInProgress) {
            startTransfer();
        } else if (startTransfer && !internalClock && !transferInProgress) {
            startExternalClockTransfer();
        }
    }
    
  
    private void startTransfer() {
        transferInProgress = true;
        externalClockMode = false;
        shiftRegister = sb;
        bitsTransferred = 0;
        
        boolean fastMode = (sc & 0x02) != 0;
        transferCyclesRemaining = fastMode ? TRANSFER_CYCLES_FAST : TRANSFER_CYCLES;
        
        // Debug logging disabled for testing performance
        // System.out.println(String.format("Serial: Transfer started (Internal Clock), SB=0x%02X, SC=0x%02X", sb, sc));
    }
    
   
    private void startExternalClockTransfer() {
        transferInProgress = true;
        externalClockMode = true;
        shiftRegister = sb;
        bitsTransferred = 0;
        externalClockBitPosition = 0;
        
        System.out.println(String.format("Serial: Transfer started (External Clock), SB=0x%02X, SC=0x%02X", sb, sc));
        
        if (connectedDevice == null) {
            System.out.println("Serial: External clock but no device - completing immediately");
            completeTransfer();
        }
    }
    
  
    private void completeTransfer() {
        transferInProgress = false;
        
        if (connectedDevice != null) {
            sb = connectedDevice.exchangeByte(shiftRegister);
        } else {
            sb = 0xFF;
        }
        
        sc &= ~0x80;
        
    }
    

    public boolean isTransferInProgress() {
        return transferInProgress;
    }
    
  
    public void connectDevice(SerialDevice device) {
        this.connectedDevice = device;
        System.out.println("Serial: Device connected - " + device.getClass().getSimpleName());
    }
  
    public void disconnectDevice() {
        this.connectedDevice = null;
        System.out.println("Serial: Device disconnected");
    }
    
   
    public boolean sendExternalClockPulse() {
        if (!transferInProgress || !externalClockMode) {
            return false;
        }
        
        externalClockBitPosition++;
        
        if (externalClockBitPosition >= 8) {
            completeTransfer();
            return true;
        }
        
        return false;
    }
    

    public boolean isExternalClockMode() {
        return externalClockMode;
    }
 
    public void saveState(java.io.DataOutputStream dos) throws java.io.IOException {
        dos.writeInt(sb);
        dos.writeInt(sc);
        dos.writeBoolean(transferInProgress);
        dos.writeInt(transferCyclesRemaining);
        dos.writeInt(shiftRegister);
        dos.writeInt(bitsTransferred);
        dos.writeBoolean(externalClockMode);
        dos.writeInt(externalClockBitPosition);
    }
    
 
    public void loadState(java.io.DataInputStream dis) throws java.io.IOException {
        sb = dis.readInt();
        sc = dis.readInt();
        transferInProgress = dis.readBoolean();
        transferCyclesRemaining = dis.readInt();
        shiftRegister = dis.readInt();
        bitsTransferred = dis.readInt();
        externalClockMode = dis.readBoolean();
        externalClockBitPosition = dis.readInt();
    }
    

    public interface SerialDevice {
      
        int exchangeByte(int dataSent);
    }
    
 
    public static class DummyDevice implements SerialDevice {
        @Override
        public int exchangeByte(int dataSent) {
            return dataSent;
        }
    }
 
    public static class DisconnectedDevice implements SerialDevice {
        @Override
        public int exchangeByte(int dataSent) {
            return 0xFF;
        }
    }
}
