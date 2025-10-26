package com.meutcc.gbemulator;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;

/**
 * Emulador básico do Game Boy Camera.
 * 
 * O Game Boy Camera é um cartucho com câmera integrada que permite
 * tirar fotos em 128x112 pixels (4 tons de cinza).
 * 
 * Esta implementação simplificada:
 * - Carrega imagens de arquivo para simular captura
 * - Converte para formato Game Boy (4 cores, 128x112)
 * - Responde aos comandos do jogo
 * 
 * Nota: Captura de webcam real requereria bibliotecas externas
 * (JavaCV, Webcam-Capture, etc) e não está implementada aqui.
 */
public class CameraEmulator implements Serial.SerialDevice {
    
    // Dimensões da câmera Game Boy
    private static final int CAMERA_WIDTH = 128;
    private static final int CAMERA_HEIGHT = 112;
    
    // Imagem capturada (em escala de cinza)
    private byte[][] capturedImage = new byte[CAMERA_HEIGHT][CAMERA_WIDTH];
    
    // Estado da comunicação
    private enum State {
        IDLE,
        WAITING_COMMAND,
        PROCESSING,
        SENDING_DATA
    }
    
    private State state = State.IDLE;
    private int command = 0;
    private int dataIndex = 0;
    
    // Comandos do Game Boy Camera (valores aproximados)
    private static final int CMD_INIT = 0x01;
    private static final int CMD_CAPTURE = 0x02;
    private static final int CMD_GET_DATA = 0x03;
    private static final int CMD_STATUS = 0x0F;
    
    public CameraEmulator() {
        // Inicializar com imagem padrão (gradiente)
        generateDefaultImage();
        System.out.println("Camera: Inicializada com imagem padrão");
    }
    
    /**
     * Gera uma imagem padrão (gradiente) para testes.
     */
    private void generateDefaultImage() {
        for (int y = 0; y < CAMERA_HEIGHT; y++) {
            for (int x = 0; x < CAMERA_WIDTH; x++) {
                // Gradiente diagonal
                int value = ((x + y) * 255) / (CAMERA_WIDTH + CAMERA_HEIGHT);
                capturedImage[y][x] = (byte) (value & 0xFF);
            }
        }
    }
    
    /**
     * Carrega uma imagem de arquivo e converte para formato Game Boy.
     * @param imagePath Caminho para o arquivo de imagem
     */
    public boolean loadImage(String imagePath) {
        try {
            BufferedImage img = ImageIO.read(new File(imagePath));
            
            if (img == null) {
                System.err.println("Camera: Erro ao carregar imagem: " + imagePath);
                return false;
            }
            
            // Redimensionar para 128x112 se necessário
            BufferedImage resized = new BufferedImage(CAMERA_WIDTH, CAMERA_HEIGHT, BufferedImage.TYPE_BYTE_GRAY);
            java.awt.Graphics2D g = resized.createGraphics();
            g.drawImage(img, 0, 0, CAMERA_WIDTH, CAMERA_HEIGHT, null);
            g.dispose();
            
            // Converter para array de bytes (escala de cinza)
            for (int y = 0; y < CAMERA_HEIGHT; y++) {
                for (int x = 0; x < CAMERA_WIDTH; x++) {
                    int rgb = resized.getRGB(x, y);
                    int gray = (rgb >> 16) & 0xFF; // Componente R (imagem já é grayscale)
                    capturedImage[y][x] = (byte) (gray & 0xFF);
                }
            }
            
            System.out.println("Camera: Imagem carregada: " + imagePath);
            return true;
            
        } catch (IOException e) {
            System.err.println("Camera: Erro ao carregar imagem - " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Define uma imagem customizada.
     * @param imageData Array 112x128 de valores 0-255 (escala de cinza)
     */
    public void setImage(byte[][] imageData) {
        if (imageData.length != CAMERA_HEIGHT || imageData[0].length != CAMERA_WIDTH) {
            throw new IllegalArgumentException("Imagem deve ser " + CAMERA_WIDTH + "x" + CAMERA_HEIGHT);
        }
        
        for (int y = 0; y < CAMERA_HEIGHT; y++) {
            System.arraycopy(imageData[y], 0, capturedImage[y], 0, CAMERA_WIDTH);
        }
        
        System.out.println("Camera: Imagem customizada definida");
    }
    
    /**
     * Simula captura de foto (na prática, usa a imagem já carregada).
     */
    public void capture() {
        System.out.println("Camera: Foto capturada (simulada)");
        // Em uma implementação real com webcam, aqui seria feita a captura
        // Por exemplo, usando bibliotecas como:
        // - Webcam-Capture: https://github.com/sarxos/webcam-capture
        // - JavaCV: https://github.com/bytedeco/javacv
    }
    
    /**
     * Converte valor de escala de cinza (0-255) para cor Game Boy (0-3).
     */
    private int grayToGameBoyColor(int gray) {
        // Quantizar 256 tons para 4 tons
        if (gray < 64) return 0;  // Mais escuro
        if (gray < 128) return 1;
        if (gray < 192) return 2;
        return 3; // Mais claro
    }
    
    @Override
    public int exchangeByte(int dataSent) {
        switch (state) {
            case IDLE:
                if (dataSent == CMD_INIT) {
                    System.out.println("Camera: Comando INIT");
                    state = State.IDLE;
                    return 0x00; // ACK
                    
                } else if (dataSent == CMD_CAPTURE) {
                    System.out.println("Camera: Comando CAPTURE");
                    capture();
                    state = State.IDLE;
                    return 0x00; // ACK
                    
                } else if (dataSent == CMD_GET_DATA) {
                    System.out.println("Camera: Comando GET_DATA");
                    state = State.SENDING_DATA;
                    dataIndex = 0;
                    return (CAMERA_WIDTH * CAMERA_HEIGHT) & 0xFF; // Tamanho baixo
                    
                } else if (dataSent == CMD_STATUS) {
                    System.out.println("Camera: Comando STATUS");
                    return 0x00; // Status OK
                    
                } else {
                    return 0xFF; // Comando desconhecido
                }
                
            case SENDING_DATA:
                // Enviar pixels da imagem
                if (dataIndex < CAMERA_WIDTH * CAMERA_HEIGHT) {
                    int y = dataIndex / CAMERA_WIDTH;
                    int x = dataIndex % CAMERA_WIDTH;
                    
                    int gray = capturedImage[y][x] & 0xFF;
                    int gbColor = grayToGameBoyColor(gray);
                    
                    dataIndex++;
                    
                    if (dataIndex >= CAMERA_WIDTH * CAMERA_HEIGHT) {
                        state = State.IDLE;
                        System.out.println("Camera: Transferência de dados completa");
                    }
                    
                    return gbColor;
                } else {
                    state = State.IDLE;
                    return 0xFF;
                }
                
            default:
                state = State.IDLE;
                return 0xFF;
        }
    }
    
    /**
     * Salva a imagem atual em arquivo.
     */
    public boolean saveImage(String outputPath) {
        try {
            BufferedImage img = new BufferedImage(CAMERA_WIDTH, CAMERA_HEIGHT, BufferedImage.TYPE_BYTE_GRAY);
            
            for (int y = 0; y < CAMERA_HEIGHT; y++) {
                for (int x = 0; x < CAMERA_WIDTH; x++) {
                    int gray = capturedImage[y][x] & 0xFF;
                    int rgb = (gray << 16) | (gray << 8) | gray;
                    img.setRGB(x, y, 0xFF000000 | rgb);
                }
            }
            
            ImageIO.write(img, "PNG", new File(outputPath));
            System.out.println("Camera: Imagem salva: " + outputPath);
            return true;
            
        } catch (IOException e) {
            System.err.println("Camera: Erro ao salvar imagem - " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Reseta a câmera.
     */
    public void reset() {
        state = State.IDLE;
        dataIndex = 0;
        generateDefaultImage();
        System.out.println("Camera: Reset");
    }
    
    /**
     * Exemplo de como capturar de webcam (requer biblioteca externa).
     * 
     * Para implementar captura real:
     * 1. Adicionar dependência (Maven/Gradle):
     *    - Webcam-Capture: com.github.sarxos:webcam-capture:0.3.12
     *    - Ou JavaCV: org.bytedeco:javacv-platform:1.5.8
     * 
     * 2. Código exemplo com Webcam-Capture:
     * 
     * import com.github.sarxos.webcam.Webcam;
     * 
     * public void captureFromWebcam() {
     *     Webcam webcam = Webcam.getDefault();
     *     webcam.open();
     *     BufferedImage image = webcam.getImage();
     *     webcam.close();
     *     
     *     // Processar image e converter para capturedImage[][]
     * }
     */
}
