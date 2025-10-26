package com.meutcc.gbemulator;


public class Serial {
    
    private int sb = 0x00;  
    private int sc = 0x7E; 
    
    // Estado da transferência
    private boolean transferInProgress = false;
    private int transferCyclesRemaining = 0;
    private int shiftRegister = 0;  // Registrador de deslocamento interno
    private int bitsTransferred = 0;
    
    // Configuração
    private static final int TRANSFER_CYCLES = 4096;  // Ciclos para transferir 8 bits (512 ciclos por bit)
    private static final int TRANSFER_CYCLES_FAST = 256;  // Modo rápido CGB (não implementado ainda)
    
    // Interface para dispositivos conectados (para futuras implementações)
    private SerialDevice connectedDevice = null;
    
    // External clock support
    private boolean externalClockMode = false;
    private int externalClockBitPosition = 0;
    
    /**
     * Reseta o estado do serial.
     */
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
    
    /**
     * Atualiza o estado da transferência serial.
     * @param cycles Número de ciclos de CPU decorridos
     * @return true se a transferência foi completada neste update
     */
    public boolean update(int cycles) {
        if (!transferInProgress) {
            return false;
        }
        
        transferCyclesRemaining -= cycles;
        
        if (transferCyclesRemaining <= 0) {
            // Transferência completa
            completeTransfer();
            return true;
        }
        
        return false;
    }
    
    /**
     * Lê o registrador SB.
     */
    public int readSB() {
        return sb & 0xFF;
    }
    
    /**
     * Escreve no registrador SB.
     */
    public void writeSB(int value) {
        sb = value & 0xFF;
    }
    
    /**
     * Lê o registrador SC.
     */
    public int readSC() {
        // Bits não usados retornam 1
        return (sc & 0x83) | 0x7C;
    }
    
    /**
     * Escreve no registrador SC.
     */
    public void writeSC(int value) {
        sc = value & 0xFF;
        
        // Verificar se uma transferência foi iniciada
        boolean internalClock = (sc & 0x01) != 0;
        boolean startTransfer = (sc & 0x80) != 0;
        
        if (startTransfer && internalClock && !transferInProgress) {
            startTransfer();
        } else if (startTransfer && !internalClock && !transferInProgress) {
            // External clock mode - aguardar pulsos de clock externos
            startExternalClockTransfer();
        }
    }
    
    /**
     * Inicia uma transferência serial.
     */
    private void startTransfer() {
        transferInProgress = true;
        externalClockMode = false;
        shiftRegister = sb;
        bitsTransferred = 0;
        
        // Determinar velocidade (CGB apenas)
        boolean fastMode = (sc & 0x02) != 0;
        transferCyclesRemaining = fastMode ? TRANSFER_CYCLES_FAST : TRANSFER_CYCLES;
        
        System.out.println(String.format("Serial: Transfer started (Internal Clock), SB=0x%02X, SC=0x%02X", sb, sc));
    }
    
    /**
     * Inicia transferência com external clock.
     */
    private void startExternalClockTransfer() {
        transferInProgress = true;
        externalClockMode = true;
        shiftRegister = sb;
        bitsTransferred = 0;
        externalClockBitPosition = 0;
        
        System.out.println(String.format("Serial: Transfer started (External Clock), SB=0x%02X, SC=0x%02X", sb, sc));
        
        // Em external clock mode, o dispositivo externo controla quando bits são transferidos
        // Por padrão, sem dispositivo conectado, completa imediatamente para não travar
        if (connectedDevice == null) {
            System.out.println("Serial: External clock but no device - completing immediately");
            completeTransfer();
        }
    }
    
    /**
     * Completa a transferência serial.
     */
    private void completeTransfer() {
        transferInProgress = false;
        
        // Simular recepção de dados
        if (connectedDevice != null) {
            // Se houver dispositivo conectado, obter dados dele
            sb = connectedDevice.exchangeByte(shiftRegister);
        } else {
            // Sem dispositivo conectado: receber 0xFF (pull-up resistor)
            sb = 0xFF;
        }
        
        // Resetar bit 7 de SC (Transfer Start Flag)
        sc &= ~0x80;
        
        System.out.println(String.format("Serial: Transfer complete, received=0x%02X", sb));
    }
    
    /**
     * Verifica se uma transferência está em progresso.
     */
    public boolean isTransferInProgress() {
        return transferInProgress;
    }
    
    /**
     * Conecta um dispositivo serial (para futuras implementações).
     */
    public void connectDevice(SerialDevice device) {
        this.connectedDevice = device;
        System.out.println("Serial: Device connected - " + device.getClass().getSimpleName());
    }
    
    /**
     * Desconecta o dispositivo serial.
     */
    public void disconnectDevice() {
        this.connectedDevice = null;
        System.out.println("Serial: Device disconnected");
    }
    
    /**
     * Envia um pulso de clock externo (usado em external clock mode).
     * Dispositivos externos chamam este método para avançar a transferência bit a bit.
     * 
     * @return true se a transferência foi completada com este pulso
     */
    public boolean sendExternalClockPulse() {
        if (!transferInProgress || !externalClockMode) {
            return false;
        }
        
        // Avançar um bit
        externalClockBitPosition++;
        
        if (externalClockBitPosition >= 8) {
            // 8 bits transferidos - completar
            completeTransfer();
            return true;
        }
        
        return false;
    }
    
    /**
     * Verifica se está em modo external clock.
     */
    public boolean isExternalClockMode() {
        return externalClockMode;
    }
    
    /**
     * Salva o estado do serial para save states.
     */
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
    
    /**
     * Carrega o estado do serial de save states.
     */
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
    
    /**
     * Interface para dispositivos que podem ser conectados ao cabo serial.
     * Implementações futuras podem incluir:
     * - Outro emulador Game Boy (multiplayer)
     * - Game Boy Printer
     * - Game Boy Camera
     * - Outros periféricos
     */
    public interface SerialDevice {
        /**
         * Troca um byte com o dispositivo.
         * @param dataSent Byte enviado pelo Game Boy
         * @return Byte recebido do dispositivo
         */
        int exchangeByte(int dataSent);
    }
    
    /**
     * Implementação dummy de dispositivo serial (para testes).
     */
    public static class DummyDevice implements SerialDevice {
        @Override
        public int exchangeByte(int dataSent) {
            // Echo: retorna o mesmo byte
            return dataSent;
        }
    }
    
    /**
     * Implementação de dispositivo desconectado (pull-up resistor).
     */
    public static class DisconnectedDevice implements SerialDevice {
        @Override
        public int exchangeByte(int dataSent) {
            // Sem conexão: retorna 0xFF
            return 0xFF;
        }
    }
}
