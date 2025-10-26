package com.meutcc.gbemulator;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;


public class NetworkLinkCable implements Serial.SerialDevice {
    
    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private volatile boolean connected = false;
    private volatile boolean isServer = false;
    
    private final BlockingQueue<Integer> receiveBuffer = new LinkedBlockingQueue<>();
    private final BlockingQueue<Integer> sendBuffer = new LinkedBlockingQueue<>();
    
    private Thread communicationThread;
    
    private static final int EXCHANGE_TIMEOUT_MS = 100;
    
    private ConnectionListener connectionListener;
    
    public interface ConnectionListener {
        void onConnected(String remoteAddress);
        void onDisconnected(String reason);
        void onError(String error);
    }

    public void startServer(int port) throws IOException {
        if (connected) {
            throw new IllegalStateException("Já conectado");
        }
        
        isServer = true;
        System.out.println("Network Link: Iniciando servidor na porta " + port + "...");
        
        new Thread(() -> {
            try {
                ServerSocket serverSocket = new ServerSocket(port);
                serverSocket.setSoTimeout(60000);
                
                if (connectionListener != null) {
                    connectionListener.onConnected("Aguardando conexão...");
                }
                
                socket = serverSocket.accept();
                setupConnection();
                serverSocket.close();
                
                String remoteAddr = socket.getInetAddress().getHostAddress();
                System.out.println("Network Link: Cliente conectado de " + remoteAddr);
                
                if (connectionListener != null) {
                    connectionListener.onConnected(remoteAddr);
                }
                
            } catch (IOException e) {
                System.err.println("Network Link: Erro ao aceitar conexão - " + e.getMessage());
                if (connectionListener != null) {
                    connectionListener.onError("Falha ao aceitar conexão: " + e.getMessage());
                }
            }
        }, "LinkCable-Server").start();
    }
    

    public void connectToServer(String host, int port) throws IOException {
        if (connected) {
            throw new IllegalStateException("Já conectado");
        }
        
        isServer = false;
        System.out.println("Network Link: Conectando a " + host + ":" + port + "...");
        
        // Thread para conectar sem bloquear
        new Thread(() -> {
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 5000); // Timeout de 5s
                setupConnection();
                
                System.out.println("Network Link: Conectado a " + host + ":" + port);
                
                if (connectionListener != null) {
                    connectionListener.onConnected(host + ":" + port);
                }
                
            } catch (IOException e) {
                System.err.println("Network Link: Erro ao conectar - " + e.getMessage());
                if (connectionListener != null) {
                    connectionListener.onError("Falha ao conectar: " + e.getMessage());
                }
            }
        }, "LinkCable-Client").start();
    }
    

    private void setupConnection() throws IOException {
        socket.setTcpNoDelay(true); 
        socket.setSoTimeout(100); 
        
        input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        
        connected = true;
        
        communicationThread = new Thread(this::communicationLoop, "LinkCable-Communication");
        communicationThread.setDaemon(true);
        communicationThread.start();
    }
    

    private void communicationLoop() {
        System.out.println("Network Link: Thread de comunicação iniciada");
        
        while (connected && !Thread.currentThread().isInterrupted()) {
            try {
                Integer toSend = sendBuffer.poll(10, TimeUnit.MILLISECONDS);
                if (toSend != null) {
                    output.writeByte(toSend);
                    output.flush();
                }
                
                if (input.available() > 0) {
                    int received = input.readUnsignedByte();
                    receiveBuffer.offer(received);
                }
                
            } catch (SocketTimeoutException e) {
            } catch (InterruptedIOException e) {
                break;
            } catch (IOException e) {
                if (connected) {
                    System.err.println("Network Link: Erro de comunicação - " + e.getMessage());
                    disconnect("Erro de comunicação: " + e.getMessage());
                }
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        System.out.println("Network Link: Thread de comunicação encerrada");
    }

    @Override
    public int exchangeByte(int dataSent) {
        if (!connected) {
            return 0xFF; 
        }
        
        try {
            if (!sendBuffer.offer(dataSent, EXCHANGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                System.err.println("Network Link: Timeout ao enviar byte");
                return 0xFF;
            }
            
            Integer received = receiveBuffer.poll(EXCHANGE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            
            if (received == null) {
                return 0xFF;
            }
            
            return received;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0xFF;
        }
    }

    public void disconnect() {
        disconnect("Desconectado pelo usuário");
    }
    
    private void disconnect(String reason) {
        if (!connected) {
            return;
        }
        
        connected = false;
        
        System.out.println("Network Link: Desconectando - " + reason);
        
        if (communicationThread != null) {
            communicationThread.interrupt();
        }
        
        try {
            if (output != null) output.close();
            if (input != null) input.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
        }
        
        sendBuffer.clear();
        receiveBuffer.clear();
        
        if (connectionListener != null) {
            connectionListener.onDisconnected(reason);
        }
    }
    
    public boolean isConnected() {
        return connected;
    }
    

    public boolean isServer() {
        return isServer;
    }
    
    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }
    

    public String getStatistics() {
        if (!connected) {
            return "Desconectado";
        }
        
        String mode = isServer ? "Servidor" : "Cliente";
        String remote = "desconhecido";
        
        try {
            if (socket != null && socket.isConnected()) {
                remote = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
            }
        } catch (Exception e) {
            
        }
        
        return String.format("%s conectado a %s | Buffer: Send=%d, Recv=%d",
            mode, remote, sendBuffer.size(), receiveBuffer.size());
    }
}
